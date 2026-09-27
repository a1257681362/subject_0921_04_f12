package com.evops.branch;

import com.evops.branch.dto.BranchCreateRequest;
import com.evops.branch.dto.MergeRequest;
import com.evops.branch.dto.MoveRequest;
import com.evops.branch.dto.SortRequest;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 网点树主流程（闭包表）。
 *
 * 规则：
 * - 建点/子树迁移/区域合并/排序配置的多表写入共用一个事务，中途失败全部回退；
 * - 子树迁移原子完成：防成环校验 -> 删子树外部旧链路（内部链路保留）->
 *   接新祖先链 -> 改 parent_id（乐观锁）-> 写 MOVE 事件；
 * - 区域合并原子完成：源子树全部网点的业务归属（车辆台账、租车单）改派到目标网点
 *   -> 源子树整体挂到目标节点下（与迁移同一套闭包步骤）-> 写 MERGE 事件。
 *   归属改派与树迁移必须同事务：只搬树不改派时，被并网点名下车辆/订单仍挂在源编码上，
 *   而周转率/对账等报表一律按 branch_code 扁平汇总（不按闭包层级上卷），
 *   源编码停用后这块数据会从区域汇总里整块消失（数据其实还在）；
 * - 闭包表为派生索引数据，不随行业务四要素，变更留痕在 t_branch_event；
 * - 网点全量留痕，不提供物理删除；
 * - 调度查询读路径（children/subtree/path）过灰度闸 BranchGrayGate。
 */
@Service
public class BranchService {

    private final BranchMapper branchMapper;
    private final BranchClosureMapper closureMapper;
    private final BranchEventMapper eventMapper;
    private final BranchOwnershipMapper ownershipMapper;
    private final BranchGrayGate grayGate;

    public BranchService(BranchMapper branchMapper, BranchClosureMapper closureMapper,
                         BranchEventMapper eventMapper, BranchOwnershipMapper ownershipMapper,
                         BranchGrayGate grayGate) {
        this.branchMapper = branchMapper;
        this.closureMapper = closureMapper;
        this.eventMapper = eventMapper;
        this.ownershipMapper = ownershipMapper;
        this.grayGate = grayGate;
    }

    /** 建点：节点 + 自闭环 + 继承父节点祖先链 + CREATE 事件，同一事务。 */
    @Transactional(rollbackFor = Exception.class)
    public Branch create(BranchCreateRequest request) {
        long parentId = request.getParentId() != null ? request.getParentId() : Branch.ROOT_PARENT_ID;
        if (parentId != Branch.ROOT_PARENT_ID && branchMapper.selectById(parentId) == null) {
            throw new BizException("父节点不存在：" + parentId);
        }

        Branch branch = new Branch();
        branch.setBranchCode(request.getBranchCode().trim());
        branch.setName(request.getName().trim());
        branch.setParentId(parentId);
        branch.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        branchMapper.insert(branch);   // branch_code 库层唯一约束兜底重复提交

        closureMapper.insertSelfLink(branch.getId());
        if (parentId != Branch.ROOT_PARENT_ID) {
            closureMapper.insertAncestorLinks(branch.getId(), parentId);
        }

        recordEvent(branch.getId(), EventType.CREATE,
                "新建网点：" + branch.getName() + "（编码 " + branch.getBranchCode()
                        + "），父节点：" + parentId + "，同级排序：" + branch.getSortOrder());
        return branch;
    }

    /**
     * 子树整体迁移（原子）：节点带着子孙整体搬到新父节点下（0 = 迁为根节点）。
     * 旧外部链路删干净、子树内部链路保留、新祖先链重建，全部在同一事务，
     * 中途失败整体回滚，不留半截链路。
     */
    @Transactional(rollbackFor = Exception.class)
    public Branch move(Long nodeId, MoveRequest request) {
        Long newParentId = request.getNewParentId();
        Branch node = requireBranch(nodeId);
        if (newParentId.equals(nodeId)) {
            throw new BizException("不能把节点挂到自身下：" + nodeId);
        }
        if (newParentId.equals(node.getParentId())) {
            throw new BizException("节点已在目标父节点下，无需迁移：" + nodeId);
        }
        if (newParentId != Branch.ROOT_PARENT_ID) {
            if (branchMapper.selectById(newParentId) == null) {
                throw new BizException("目标父节点不存在：" + newParentId);
            }
            // 防成环：目标父节点不能落在被迁子树内
            if (closureMapper.countLink(nodeId, newParentId) > 0) {
                throw new BizException("目标父节点位于被迁子树内，迁移会成环，已拒绝：" + newParentId);
            }
        }

        long oldParentId = node.getParentId();
        // 1. 旧链路清理干净：断开子树与旧外部祖先的全部链路（子树内部链路保留）
        closureMapper.deleteExternalLinks(nodeId);
        // 2. 接新链路：新父祖先链 × 子树（迁为根节点则无需新链路）
        if (newParentId != Branch.ROOT_PARENT_ID) {
            closureMapper.insertLinksUnder(nodeId, newParentId);
        }
        // 3. 改父指针（乐观锁兜底并发搬家）
        node.setParentId(newParentId);
        int updated = branchMapper.updateById(node);
        if (updated == 0) {
            throw new BizException("节点已被并发修改，请刷新后重试：" + nodeId);
        }

        recordEvent(nodeId, EventType.MOVE,
                "子树迁移：父节点 " + oldParentId + " -> " + newParentId
                        + reasonSuffix(request.getReason()));
        return branchMapper.selectById(nodeId);
    }

    /**
     * 区域合并（原子）：源网点整棵子树的业务归属改派到目标网点，源子树挂到目标节点下。
     * 一个事务内：
     * 1. 校验：目标存在；禁止合并到自身；禁止目标落在源子树内（防成环）；
     * 2. 归属改派：源子树全部网点编码名下的车辆台账、租车单 -> 目标网点编码
     *    （未分派为空的不属于任何网点，不动；历史快照——交接照片网点快照、
     *    对账流水、周转率已关账快照——不改写）；
     * 3. 树迁移：与 move 同一套闭包步骤，删旧外部链路、接目标祖先链、改 parent_id（乐观锁）；
     * 4. 写 MERGE 事件（含改派行数，审计可按数核对）。
     * 中途任何失败整体回滚：归属不留半截改派，树上不留半截链路。
     */
    @Transactional(rollbackFor = Exception.class)
    public Branch merge(Long sourceId, MergeRequest request) {
        Long targetId = request.getTargetId();
        Branch source = requireBranch(sourceId);
        Branch target = requireBranch(targetId);
        if (sourceId.equals(targetId)) {
            throw new BizException("不能把网点合并到自身：" + sourceId);
        }
        // 防成环：目标落在源子树内时，"子树挂到目标下"会把祖先挂进自己的后代
        if (closureMapper.countLink(sourceId, targetId) > 0) {
            throw new BizException("目标网点位于被合并网点的子树内，合并会成环，已拒绝：" + targetId);
        }

        // 1. 归属改派（先改派后搬树，同事务同生共死）
        List<String> subtreeCodes = branchMapper.selectSubtreeCodes(sourceId);
        String operator = RequestContext.operator();
        String requestNo = RequestContext.requestNo();
        int vehicles = ownershipMapper.reassignVehicles(subtreeCodes, target.getBranchCode(), operator, requestNo);
        int orders = ownershipMapper.reassignRentalOrders(subtreeCodes, target.getBranchCode(), operator, requestNo);

        // 2. 树迁移：删旧外部链路（含源已在目标下的既有链路）-> 接目标祖先链 -> 改父指针
        long oldParentId = source.getParentId();
        closureMapper.deleteExternalLinks(sourceId);
        closureMapper.insertLinksUnder(sourceId, targetId);
        source.setParentId(targetId);
        int updated = branchMapper.updateById(source);
        if (updated == 0) {
            throw new BizException("节点已被并发修改，请刷新后重试：" + sourceId);
        }

        recordEvent(sourceId, EventType.MERGE,
                "区域合并：整棵子树并入 " + target.getBranchCode() + "（节点 " + targetId + "），父节点 "
                        + oldParentId + " -> " + targetId
                        + "，改派车辆 " + vehicles + " 台、租车单 " + orders + " 笔"
                        + reasonSuffix(request.getReason()));
        return branchMapper.selectById(sourceId);
    }

    /** 同级排序配置：sort_order 小的在前，同值按 id。 */
    @Transactional(rollbackFor = Exception.class)
    public Branch updateSort(Long nodeId, SortRequest request) {
        Branch node = requireBranch(nodeId);
        int oldSort = node.getSortOrder();
        node.setSortOrder(request.getSortOrder());
        int updated = branchMapper.updateById(node);
        if (updated == 0) {
            throw new BizException("节点已被并发修改，请刷新后重试：" + nodeId);
        }
        recordEvent(nodeId, EventType.SORT, "同级排序：" + oldSort + " -> " + request.getSortOrder());
        return branchMapper.selectById(nodeId);
    }

    /** 节点详情（运维诊断，不过灰度闸）。 */
    public Branch detail(Long nodeId) {
        return requireBranch(nodeId);
    }

    /** 直接子节点（灰度闸内），按同级排序配置返回。 */
    public List<BranchNode> children(Long nodeId) {
        requireBranch(nodeId);
        grayGate.assertOpen(nodeId);
        return branchMapper.selectChildren(nodeId);
    }

    /** 任意层级后代（灰度闸内），可选最大深度；单索引等值扫描，性能与树深无关。 */
    public List<BranchNode> subtree(Long nodeId, Integer maxDepth) {
        requireBranch(nodeId);
        grayGate.assertOpen(nodeId);
        if (maxDepth != null && maxDepth < 1) {
            throw new BizException("maxDepth 必须 >= 1");
        }
        return branchMapper.selectSubtree(nodeId, maxDepth);
    }

    /** 到根路径（灰度闸内），根在前、不含自身。 */
    public List<BranchNode> path(Long nodeId) {
        requireBranch(nodeId);
        grayGate.assertOpen(nodeId);
        return branchMapper.selectAncestors(nodeId);
    }

    // ---- 内部辅助 ----

    private Branch requireBranch(Long nodeId) {
        Branch branch = branchMapper.selectById(nodeId);
        if (branch == null) {
            throw new BizException("网点不存在：" + nodeId);
        }
        return branch;
    }

    private void recordEvent(Long branchId, String eventType, String detail) {
        BranchEvent event = new BranchEvent();
        event.setBranchId(branchId);
        event.setEventType(eventType);
        event.setDetail(detail);
        eventMapper.insert(event);
    }

    /** 事件留痕的原因后缀（可空，空则不带）。 */
    private static String reasonSuffix(String reason) {
        return reason != null && !reason.trim().isEmpty() ? "，原因：" + reason.trim() : "";
    }

    /** 事件类型常量 */
    public static final class EventType {
        public static final String CREATE = "CREATE";
        public static final String MOVE = "MOVE";
        public static final String SORT = "SORT";
        public static final String MERGE = "MERGE";

        private EventType() {}
    }
}
