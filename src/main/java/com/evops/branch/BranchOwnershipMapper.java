package com.evops.branch;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 区域合并的业务归属改派：把源子树全部网点编码名下的车辆台账、租车单
 * 改派到目标网点编码。只在 BranchService.merge 的合并事务内调用。
 *
 * 口径：
 * - 只改 branch_code 等于源子树编码的行；未分派（空）不属于任何网点，不动；
 * - 历史快照不改写（交接照片网点快照、对账流水、周转率已关账快照）；
 * - 业务四要素随行刷新（操作人/请求号取合并请求上下文），版本号 +1，
 *   biz_time 保持首次写入值不变（与审计填充约定一致）。
 */
@Mapper
public interface BranchOwnershipMapper {

    /** 车辆台账归属改派，返回改派行数（写入合并事件留痕，供审计核对）。 */
    @Update("<script>"
            + "UPDATE t_vehicle SET branch_code = #{targetCode},"
            + " operator = #{operator}, request_no = #{requestNo},"
            + " update_time = CURRENT_TIMESTAMP, version = version + 1"
            + " WHERE branch_code IN"
            + " <foreach collection='sourceCodes' item='c' open='(' separator=',' close=')'>#{c}</foreach>"
            + "</script>")
    int reassignVehicles(@Param("sourceCodes") List<String> sourceCodes,
                         @Param("targetCode") String targetCode,
                         @Param("operator") String operator,
                         @Param("requestNo") String requestNo);

    /** 租车单归属改派，返回改派行数（写入合并事件留痕，供审计核对）。 */
    @Update("<script>"
            + "UPDATE t_rental_order SET branch_code = #{targetCode},"
            + " operator = #{operator}, request_no = #{requestNo},"
            + " update_time = CURRENT_TIMESTAMP, version = version + 1"
            + " WHERE branch_code IN"
            + " <foreach collection='sourceCodes' item='c' open='(' separator=',' close=')'>#{c}</foreach>"
            + "</script>")
    int reassignRentalOrders(@Param("sourceCodes") List<String> sourceCodes,
                             @Param("targetCode") String targetCode,
                             @Param("operator") String operator,
                             @Param("requestNo") String requestNo);
}
