# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；启动时自动完成两处库结构初始化：
   - **Flyway 迁移**（`src/main/resources/db/migration/`）：租车单相关七表（车辆台账/租车单/交接/事件流水/流转历史/姓名索引/幂等请求）脚本化版本管理；
   - **`src/main/resources/schema.sql`**（`spring.sql.init`）：其余各表幂等初始化。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

租车表结构的迁移脚本规范、不可改/可重跑/留档/失败定位约定与配置项默认值，见 `docs/schema/README.md`。
工作区 `docs/schema/evops.sql` 是 sql.init 脚本的交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## 租车业务（rental-1）

### 已落地的三块

1. **车辆台账** `t_vehicle`（车牌唯一）
2. **租车单全量记录** `t_rental_order`（订单号唯一约束；全量留痕，禁止物理删除，已结算/已归档只许冲销）
3. **订单号 + 业务时间组合检索** `GET /api/orders?orderNo=&bizTimeFrom=&bizTimeTo=`

### 主流程状态机

```
下单 CREATED --交车--> RENTING --还车结算--> SETTLED --归档--> ARCHIVED
SETTLED / ARCHIVED --冲销--> REVERSED
```

- 交车 `POST /api/orders/{id}/pickup`、还车 `POST /api/orders/{id}/return`：必须登记油量表读数与车损照片。
- 每个业务动作的多表写入（订单/交接/事件/车辆状态）共用一个事务，中途失败全部回退。
- 全程留痕：事件流水 `t_rental_order_event`（CREATE/PICKUP/RETURN/ARCHIVE/REVERSE）。

### 写入约定

- 写请求（POST/PUT/DELETE）必须携带请求头：
  - `X-Operator` 操作人
  - `X-Request-No` 请求号（入口幂等键；重复提交 409 拦截，库层唯一约束兜底）
  - `X-Biz-Time` 业务时间（可选，`yyyy-MM-dd'T'HH:mm:ss`，缺省取服务端当前时间）
- 每条写入都带：操作人、业务时间、请求号、版本号（乐观锁自增）。
- 幂等记录 `t_idempotent_request`：PROCESSING/DONE/FAILED 三态；业务失败可同请求号重试。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/vehicles | 车辆入账 |
| GET | /api/vehicles, /api/vehicles/{id} | 车辆查询 |
| POST | /api/orders | 下单（orderNo 可传可省，省略自动生成） |
| POST | /api/orders/{id}/pickup | 交车（油量+车损照片必填） |
| POST | /api/orders/{id}/return | 还车并结算（油量+车损照片必填） |
| POST | /api/orders/{id}/archive | 归档 |
| POST | /api/orders/{id}/reverse | 冲销（仅 SETTLED/ARCHIVED） |
| GET | /api/orders | 订单号+业务时间组合检索（分页） |
| GET | /api/orders/{id} | 详情：订单+交接+事件流水 |
| DELETE | /api/orders/{id} | 一律拒绝（禁止物理删除） |

### 测试

`mvn test`：9 个集成用例覆盖主流程、重复提交拦截、订单号唯一兜底、多表写入事务回滚、删除/冲销规则、组合检索、入参校验。

## 客户姓名拼音检索（rental-name-search-1）

背景：总部稽核要求客户姓名记不全也能检索。为租车单客户姓名建拼音索引，基于 pinyin4j 生成全拼与首字母，读路径经库表灰度开关控制，可在线即时回退。

### 检索能力

`GET /api/orders` 新增可选参数 `customerName`，与原订单号/状态/业务时间条件可任意组合：

- **全拼**：`zhangsanfeng`、`zhangsan`（记不全，前缀/片段可查）；
- **首字母**：`zsf`、`zs`；
- **原文直查**：中文 `张三`、外文 `john` 同样可查（索引冗余规范化原文）；
- **容错**：大小写不敏感；全角/半角经 NFKC 归一（`ＺＳＦ`=`zsf`）；自动压缩空格与标点；外文字母、数字原样保留（`张a三1` → 全拼 `zhangasan1`、首字母 `zas1`）；
- **多音字**取拼音库常用首读，全拼与首字母用同一读音，结果稳定；
- **相关度排序**：原文精确 > 全拼精确 > 首字母精确 > 原文前缀 > 全拼前缀 > 首字母前缀 > 原文包含 > 全拼包含 > 首字母包含；同分再按业务时间、id 倒序。

### 索引同步

- 派生索引表 `t_rental_order_name_index`，与订单一一对应（`order_id` 即主键）；
- **下单**：订单与索引在同一业务事务内写入，失败一起回滚；
- **改名**：`PUT /api/orders/{id}/customer`（携带写请求三件套）在同一事务内更新订单姓名、重建该单索引、留 `RENAME` 事件，中途失败全部回滚；
- **全量重建/自愈**：`POST /api/orders/name-index/rebuild` 以订单为准逐单重算覆盖；应用启动后自动跑一次自愈（失败不阻断启动）。

### 灰度开关与十分钟回退

复用库表灰度规则 `t_gray_rule`，新增场景 `CUSTOMER_NAME_SEARCH`（全局开关，`branch_code` 固定 `*`）：

- 默认无规则即**关闭**，读路径行为与升级前完全一致（`customerName` 入参被忽略）；
- 上线：`POST /api/gray/rules {"scene":"CUSTOMER_NAME_SEARCH","branchCode":"*","status":"ON"}`，运行时生效、无需重启；
- **回退**：值守发现异常，置 `"status":"OFF"`（或删除该规则）即立刻退回旧检索链路，无需重启、无需重新部署，满足上线当天十分钟内回退；
- 上线前先对存量执行一次 `POST /api/orders/name-index/rebuild`（应用启动也会自愈一次），确认索引齐全后再开开关。

### 接口速览（新增）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/orders?customerName= | 姓名原文/全拼/首字母检索（灰度开启时生效），相关度排序，可与既有条件组合 |
| PUT | /api/orders/{id}/customer | 客户改名，同事务同步拼音索引并留痕 |
| POST | /api/orders/name-index/rebuild | 拼音索引全量重建（存量初始化/兜底自愈） |

### 测试

`mvn test`：新增 7 个集成用例覆盖全拼/首字母及片段检索、大小写与全半角/空格标点容错、中文与中英数混合直查、相关度排序、改名同事务同步与留痕、灰度 OFF 回退、删索引后全量重建恢复。

## 网点树（branch-1）

背景：网点调度反馈旧处理方式忙时漏单、区域层级调整频繁。本期交付闭包表网点树；调度单可靠化另起迭代。

### 已落地的四块

1. **网点节点** `t_branch`（branch_code 唯一；parent_id=0 为根，允许森林；全量留痕禁止物理删除）
2. **闭包表** `t_branch_closure`（祖先-子孙全量链路含 depth=0 自闭环；任意层级查询均为单索引等值扫描，性能与树深无关）
3. **变更留痕** `t_branch_event`（CREATE/MOVE/SORT/MERGE，试点观察期审计依据）
4. **灰度规则** `t_gray_rule`（scene + branch_code 唯一，`'*'` 表示全量）

### 子树迁移（原子）

`POST /api/branches/{id}/move`：节点带着子孙整体搬到新父节点下（newParentId=0 迁为根）。一个事务内：

1. 校验：目标父节点存在；禁止挂到自身或自己的子孙下（防成环）；禁止原地搬家；
2. 删旧链路：断开子树与旧外部祖先的全部链路（子树内部链路保留）；
3. 接新链路：新父祖先链 × 子树，深度重算；
4. 改 parent_id（乐观锁兜底并发搬家）；
5. 写 MOVE 事件。

中途任何失败整体回滚，不留半截链路。

### 区域合并（原子）

`POST /api/branches/{id}/merge`：把源网点整棵子树并入目标网点。一个事务内：

1. 校验：目标存在；禁止合并到自身；禁止目标落在源子树内（防成环）；
2. **归属改派**：源子树全部网点编码名下的车辆台账、租车单改派到目标网点编码（未分派为空的不动；交接照片网点快照、对账流水、周转率已关账快照等历史留痕不改写）；
3. 树迁移：与 move 同一套闭包步骤，源子树整体挂到目标节点下；
4. 写 MERGE 事件（含改派车辆/租车单行数，审计可按数核对）。

归属改派与树迁移必须同事务：只搬树不改派时，被并网点名下车辆/订单仍挂在源编码上，而周转率/对账等报表一律按 `branch_code` 扁平汇总（不按闭包层级上卷），源编码停用后这块数据会从区域汇总里整块消失（数据其实还在）。中途任何失败整体回滚，归属不留半截改派、树上不留半截链路。

### 同级排序

`sort_order` 配置列，小的在前、同值按 id；`PUT /api/branches/{id}/sort` 调整并留痕。

### 灰度（先试点再推全）

调度查询读路径（children/subtree/path）过灰度闸：全量规则 ON 直接放行；否则节点落在任一生效试点网点的子树内（含试点自身，经闭包表判定）放行，其余 409。运维写路径（建点/迁移/排序）不过闸。

- 试点：`POST /api/gray/rules {"branchCode":"试点网点编码","status":"ON"}`
- 观察一周，审计 `t_branch_event`；
- 推全：`POST /api/gray/rules {"branchCode":"*","status":"ON"}`，运行时生效、无需重启。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/branches | 新建网点（branchCode/name 必填，parentId 缺省 0=根，sortOrder 缺省 0） |
| POST | /api/branches/{id}/move | 子树整体迁移（原子；0=迁为根；防成环） |
| POST | /api/branches/{id}/merge | 区域合并（原子；整棵子树归属改派目标网点+树迁移同事务） |
| PUT | /api/branches/{id}/sort | 同级排序配置 |
| GET | /api/branches/{id} | 节点详情（运维诊断，不过灰度闸） |
| GET | /api/branches/{id}/children | 直接子节点，按 sort_order 排序（灰度闸） |
| GET | /api/branches/{id}/subtree?maxDepth= | 任意层级后代（灰度闸） |
| GET | /api/branches/{id}/path | 到根路径，根在前（灰度闸） |
| DELETE | /api/branches/{id} | 一律拒绝（禁止物理删除） |
| POST | /api/gray/rules | 灰度规则新增/开关（同 scene+branchCode 更新状态） |
| GET | /api/gray/rules | 灰度规则列表 |

### 测试

`mvn test`：新增 9 个集成用例覆盖闭包链路正确性、同级排序、子树迁移与旧链路清理、迁为根、防成环/原地搬家拦截、迁移中途失败整体回滚、重复提交拦截、灰度试点到推全、删除拒绝与编码唯一兜底、乐观锁并发拦截。区域合并（branch-2）新增 3 个回归用例：合并后整棵子树车辆/租车单归属改派且周转率汇总口径一块不少、合并中途失败归属与链路整体回滚、自身/目标不存在/成环/缺参拦截。

## 网点对账（recon-1）

背景：网点新换了负责人，第一件事把资金账理清楚。把对账做成周期任务：凌晨自动拉双方流水勾稽，金额或状态不一致的进差异清单，调度员手工核销必须填原因，日结后出对账单；白天不敢动的配置（匹配容差）落变更单排队，夜间批处理统一生效，次日早晨凭作业运行记录核对结果。

### 数据模型

| 表 | 说明 |
| --- | --- |
| `t_recon_txn_internal` | 内部流水（本方账面）：按网点+业务日从租车单 RETURN/REVERSE 事件抽取，ref_no 即订单号 |
| `t_recon_txn_external` | 外部流水（渠道账面）：对账文件导入，external_ref_no 对齐订单号，同键重复导入覆盖 |
| `t_recon_batch` | 对账批次：一网点一业务日一次勾稽一行（RUNNING/DONE/FAILED），可重跑 |
| `t_recon_diff` | 差异清单：金额超差/状态不一致/单边账；OPEN/RESOLVED，核销原因必填 |
| `t_recon_statement` | 对账单：日结后一网点一业务日一份，GENERATED 可刷新，CONFIRMED 冻结 |
| `t_recon_config` | 当前生效配置（单行）：匹配容差 `match_tolerance`（含边界，不能为负） |
| `t_recon_config_change` | 配置变更单：PENDING 白天排队，夜间 APPLIED / REJECTED |
| `t_recon_job_run` | 周期作业运行记录：CONFIG_APPLY / DAILY_RECON 每次触发留痕 |

租车单新增可空 `branch_code`（归属网点，下单可选传，既有流程不受影响），对账按网点抽取内部流水。

### 勾稽规则

- **配对键**：业务参考号 + 业务大类（结算 SETTLE / 冲销退款 REVERSE）；外部类型归一（REFUND/REVERSE 归冲销类，其余归结算类）。
- **金额**：`|内部-外部| <= 匹配容差` 视为一致（容差含边界，默认 0，即必须分毫不差）。
- **状态**：渠道状态原文按大类归一（结算认 SUCCESS/SETTLED/PAID，冲销认 SUCCESS/REFUNDED/REFUND/REVERSED），认不出即状态不一致。
- **单边账**：只有一方有账 → INTERNAL_ONLY / EXTERNAL_ONLY。

### 重跑口径（同网点同业务日可安全重跑）

- 本次仍存在的 OPEN 差异原地刷新（最新批次与金额快照）；
- 本次已消失且**未经人工核销**的差异自动消解（resolve_type=AUTO）；
- **人工核销(MANUAL)结果永不翻案**；
- 批次头独立事务先落 RUNNING，勾稽主体失败整体回滚后批次以独立事务置 FAILED，不留半截账。

### 核销即时清零

调度员 `POST /api/recon/diffs/{id}/resolve` 核销，`reason` 必填（入参校验 + 服务层二次兜底）；提交事务后该差异立即从 OPEN 清单消失；若对账单已生成（未确认），对账单口径同步刷新。

### 日结对账单

凌晨 03:00 作业对前一业务日全部活跃网点（当天有结算/冲销事件或导入过外部流水的网点）逐点勾稽并出对账单；单网点失败不影响其余网点，失败点在作业摘要列明，白天修复后可手工重跑。对账单未确认可随重跑刷新；次日早晨核对无误 `confirm` 后冻结，冻结日拒绝再勾稽重跑。

### 配置夜间批处理

白天调整容差只登记 PENDING 变更单，生效值不变；02:00 批处理逐条校验：合法则改写生效配置置 APPLIED，非法（如负数）置 REJECTED 留驳回原因，单条失败不影响其余。应急可手工 `POST /api/recon/jobs/config-apply` 立即执行。

周期时点可在 `application.yml` 调整（`evops.recon.config-apply-cron` / `daily-recon-cron`），`evops.recon.enabled=false` 为应急总开关（不影响手工触发接口）。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/recon/external-txns/import | 导入渠道对账文件（外部流水），同键覆盖 |
| GET | /api/recon/internal-txns, /external-txns | 双方流水查询（网点+业务日） |
| POST | /api/recon/run | 手工触发对账（补跑/重跑） |
| GET | /api/recon/batches | 对账批次列表 |
| GET | /api/recon/diffs?status=OPEN | 差异清单（OPEN 待核销 / RESOLVED 已清零） |
| POST | /api/recon/diffs/{id}/resolve | 手工核销（**原因必填**），核销即时清零 |
| POST | /api/recon/statements | 日结生成对账单（未确认可刷新，已确认拒绝） |
| GET | /api/recon/statements, /statements/{id} | 对账单查询 |
| POST | /api/recon/statements/{id}/confirm | 早晨核对后确认冻结 |
| GET | /api/recon/config | 当前生效匹配容差 |
| POST | /api/recon/config-changes | 提交配置变更（白天只排队 PENDING） |
| GET | /api/recon/config-changes | 变更单列表（PENDING/APPLIED/REJECTED） |
| POST | /api/recon/jobs/config-apply | 手工触发夜间配置批处理（应急） |
| POST | /api/recon/jobs/daily-recon?bizDate= | 手工触发日结对账（应急） |
| GET | /api/recon/job-runs?jobType= | 周期作业运行记录（次日早晨核对） |

所有写请求同样遵循三件套（`X-Operator` / `X-Request-No` / 可选 `X-Biz-Time`）与入口幂等。

### 测试

`mvn test`：新增 8 个集成用例覆盖全匹配出账、金额超差进差异与核销原因必填/即时清零、渠道状态更正后重跑自动消解、双边单边账、容差变更白天排队夜间生效后重跑消解、人工核销不翻案与对账单确认冻结、日结作业自动覆盖活跃网点出对账单、文件内重复流水拦截。

## 车载定位平台文件往来（telematics-1）

背景：租车业务与车载定位平台交换文件——我方导出交/还车交接数据（平台据此启停定位跟踪），平台回传设备回执由我方导入。旧方式出错要很久才能发现，本期统一为规范 CSV 往来：列与编码按对方规范、特殊字符转义、空值与空串区分、导出按日期归档、导入解析失败带行号、重复导入幂等。改动不动交车主链路（导出只读交接数据）。

### CSV 规范（与对方对齐的口径）

- **字符集**：UTF-8（`evops.telematics.charset` 可配，对方若要求 GBK 改配置即可，启动即校验字符集可用）；
- **行尾** CRLF，首行表头，列序固定；
- **转义**：字段含 `,` `"` 回车 换行 时双引号包围，字段内 `"` 双写；
- **空值与空串区分**：null = 空字段（两分隔符间无内容），空串 = `""`；
- **时间格式**：`yyyy-MM-dd HH:mm:ss`。

### 导出（我方 → 平台）

`POST /api/telematics/exports?bizDate=`（缺省当天）：抽取该业务日交接记录（按交接业务时间）联车辆表生成 CSV：

- 列（定序）：`order_no, plate_no, handover_type, handover_time, fuel_reading, damage_photos, remark`；
- 文件落盘 `data/telematics/export/yyyyMMdd/handovers_yyyyMMdd_HHmmss_xxxx.csv`（按业务日分目录归档），`t_telematics_export_file` 留归档记录（业务日/文件名/路径/行数/字符集）；
- 一业务日可重复导出（补发），每次生成新文件并留痕；落库失败时尽力清理已写文件，不留无记录孤儿文件。

### 导入（平台 → 我方）

`POST /api/telematics/imports`（multipart 上传）：

- 列（定序）：`plate_no, device_no, device_status, report_time, remark`；`device_status` 仅认 `ONLINE/OFFLINE`；
- **全或无**：任一行非法（列数不符/必填缺失/状态非法/时间格式错/文件内重复键）整单置 FAILED，**错误明细逐条带行号**（结构错误如引号未闭合按记录起始行报），业务行零写入；导入记录留痕可查明细；
- **重复导入幂等（三层）**：① 入口 `X-Request-No` 幂等（既有拦截器）；② 文件内容 SHA-256 唯一——同一文件重复导入直接返回首次结果（`duplicate=true`），不重复写业务行；③ 业务键 `(plate_no, device_no)` 唯一——不同文件重复上报同一设备按最新内容覆盖；
- **模板可下载**：`GET /api/telematics/imports/template`（表头 + 示例行，与解析口径一致）。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/telematics/exports?bizDate= | 导出指定业务日交接数据并归档（缺省当天，可重复导出补发） |
| GET | /api/telematics/exports?bizDate= | 导出归档记录列表 |
| GET | /api/telematics/exports/{id}/download | 下载归档的导出文件 |
| GET | /api/telematics/imports/template | 下载导入模板（表头+示例行） |
| POST | /api/telematics/imports | 上传设备回执文件导入（全或无，失败带行号，重复导入幂等） |
| GET | /api/telematics/imports, /imports/{id} | 导入记录列表/详情（含失败行号明细） |
| GET | /api/telematics/device-reports?plateNo= | 设备回执查询 |

写请求同样遵循三件套（`X-Operator` / `X-Request-No` / 可选 `X-Biz-Time`）与入口幂等。

### 测试

`mvn test`：新增 6 个集成用例覆盖导出转义与空值/空串区分、CRLF 与 UTF-8、按日期归档落盘、模板下载、导入成功与同文件重复导入幂等、同设备不同文件覆盖、失败行号明细与全或无（合法行也不落库）、表头不符与引号未闭合报错。回归聚焦交车主链路：既有 RentalFlowIntegrationTest 9 用例不动、全量测试通过。

## 车载定位平台 XML 报文往来（telematics-xml-1）

背景：平台侧接口升级为 XML 报文收发。调度员带的新人上手慢、老办法（手拼报文）教不动——本期字段映射全部走注解：报文类上 `@XmlRoot` 声明根元素、字段上 `@XmlField(name=..., required=...)` 声明子元素，**看字段即知报文结构**，新人不用翻拼接代码。空值与缺省区分、出站按订单号归档、重发走原报文、入站解析失败进异常清单、上线前可用网点上一期租车单跑回归验证。CSV 文件往来保留并行，互不影响。

### XML 规范（与平台对齐的口径）

- **字符集**：固定 UTF-8（报文声明即 UTF-8；`evops.telematics.charset` 仅作用于 CSV 文件往来）；根元素 `handoverNotice`（出站交接通知）/ `deviceReport`（入站设备回执），子元素与 CSV 列同口径驼峰命名；
- **空值与缺省区分**：字段为 null 元素整个缺省，空串写为空元素 `<remark/>`；解析时空元素得空串、缺省保持 null；
- **往返转换无丢失**：序列化为规范形（元素按字段声明序、`&` `<` `>` 转义、回车写为 `&#13;`——XML 解析器会把裸回车归一为换行，转义后往返才不丢字符），故 `解析(序列化(x)) == x` 且再序列化逐字节一致；
- **严格解析**：根元素不符、未识别元素、元素重复、叶子元素嵌套、必填缺失、类型不符，均报错且**消息带元素名与原因**；禁 DOCTYPE/外部实体（防 XXE）；
- **时间格式**：`yyyy-MM-dd HH:mm:ss`（与 CSV 一致）。

### 出站（我方 → 平台）：按订单号归档，重发走原报文

`POST /api/telematics/xml/exports?bizDate=`（缺省当天）：该业务日每条交接生成一条报文：

- 文件落盘 `data/telematics/xml/<订单号>/handover_yyyyMMdd_HHmmss_xxxx.xml`（**按订单号分目录归档**），`t_telematics_xml_message` 留归档记录（方向/类型/订单号/原报文/内容哈希/路径）；
- 必填字段（订单号/车牌/交接类型/交接时间）缺失或含 XML 非法字符时整批失败并指明订单与元素，事务回滚、已写文件清理；
- **重发走原报文**：`POST /api/telematics/xml/messages/{id}/resend` 逐字节沿用归档原件（content 列），不重新生成——新记录与原报文内容哈希一致即凭证，`resend_of` 指向最初原件（重发的重发也指向原件）；仅出站报文可重发。

### 入站（平台 → 我方）：解析失败进异常清单

`POST /api/telematics/xml/imports`（请求体即 XML 原文，`Content-Type: application/xml`）：

- 解析失败（结构非法/根元素不符/未识别元素/必填缺失/类型不符）或业务校验不过（车牌/设备号超长、状态非 ONLINE/OFFLINE、备注超长）一律置 FAILED **进异常清单**（`GET /api/telematics/xml/exceptions`），错误明细带元素名与原因，业务行零写入；
- **同内容幂等**：报文内容 SHA-256（`dedup_key` 库层唯一兜底）——同一报文重复接收直接返回首次结果（`duplicate=true`）；业务键 `(plate_no, device_no)` 唯一，不同报文重复上报同一设备按最新覆盖；
- 回执来源可溯：`t_telematics_device_report` 新增 `xml_message_id`（与 CSV 的 `import_file_id` 二选一，CHECK 约束保证必有来源）。

### 上线前回归：网点上一期租车单往返无损

`GET /api/telematics/xml/regression-check?branchCode=&period=`（period=yyyy-MM，缺省上一月）：用该网点该期租车单的交接数据逐条跑"生成报文 → 解析回报 → 逐字段比对"，**只读不落库**，返回订单数/有交接订单数/校验报文数/通过数/失败数及失败明细（订单号+交接 id+哪一字段丢了）。上线前跑一轮，failed 为 0 即证明往返转换无丢失。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/telematics/xml/exports?bizDate= | 生成指定业务日交接报文并按订单号归档（缺省当天） |
| POST | /api/telematics/xml/messages/{id}/resend | 重发（走原报文，仅出站报文） |
| POST | /api/telematics/xml/imports | 接收平台设备回执报文（失败进异常清单，同内容幂等） |
| GET | /api/telematics/xml/messages?direction=&orderNo= | 报文归档查询 |
| GET | /api/telematics/xml/messages/{id} | 报文详情（含原报文） |
| GET | /api/telematics/xml/exceptions | 异常清单（入站解析/校验失败） |
| GET | /api/telematics/xml/regression-check?branchCode=&period= | 回归验证（往返无损，只读） |

写请求同样遵循三件套（`X-Operator` / `X-Request-No` / 可选 `X-Biz-Time`）与入口幂等。

### 测试

`mvn test`：新增 7 个集成用例覆盖注解映射与特殊字符转义、空值/缺省区分、按订单号归档落盘（文件与库内原报文一致）、重发逐字节一致且 resend_of 链到原件、入站合法落库与同内容幂等、结构非法/根元素不符/未识别元素进异常清单、校验不过错误带元素名且业务行零写入、网点上一期租车单回归往返无损（含缺省 period 取上一月）、编解码器全字符谱往返与严格校验（元素重复/嵌套/DOCTYPE 拦截）。

## 交车生命周期状态机（rental-fsm-1）

背景：调度员带人到现场处理过几次，越级操作、误点回退、两人同时操作同一单，每次都手忙脚乱。本期把交车生命周期管住：**状态机配置驱动，合法路径集中定义，越级与回退按规则拒绝，流转历史完整可追溯；并行流转只有一个成功；新增状态不改代码。**

### 合法路径集中配置（不再散落在代码 if 里）

`application.yml` 的 `evops.rental.fsm.transitions` 是合法路径全集，每条 = 动作 + 起始状态 + 目标状态：

```
CREATED --PICKUP交车--> RENTING --RETURN还车结算--> SETTLED --ARCHIVE归档--> ARCHIVED
SETTLED / ARCHIVED --REVERSE冲销(forward:false)--> REVERSED
```

- 启动即校验配置（action/from/to 必填、同动作同状态不许配两个目标、至少一条规则），配错快速失败；
- **越级**（CREATED 直接 ARCHIVE）、**回退**（RENTING 再 PICKUP、REVERSED 再 ARCHIVE）、**未知动作**都因“不在合法路径内”由同一出口拒绝（409，提示该动作允许的起始状态）；
- `forward: false` 仅标注显式允许的逆向动作（冲销），配置之外的逆向一律拒绝。

### 并行流转只有一个成功（乐观锁 CAS）

状态推进统一经引擎执行 `UPDATE t_rental_order SET status=?, version=version+1 WHERE id=? AND version=?`：

- 现场重复点交车、调度与网点同时操作同一单，影响行数=1 的事务成功，败者影响行数=0 抛“并发冲突”，**整个业务事务回滚**（交接/事件/流转历史都不落半截）；
- 订单原有 `version` 乐观锁继续作为并发兜底，无需新增锁机制。

### 流转历史完整可追溯（查什么都能对上）

新增结构化流转历史 `t_rental_order_transition`，每次成功流转在业务事务内落一行：动作、起止状态、流转前后版本、关联交接记录 id（交车/还车可直接对到油量与车损照片）、备注/原因、操作人、业务时间、请求号。

- `GET /api/orders/{id}/transitions` 按发生顺序返回完整状态链；订单详情内嵌同一链；
- 与既有事件流水 `t_rental_order_event` 分工：事件记业务动作叙述（含改名等非状态动作），流转历史只记状态机变化、字段结构化；
- 交接记录、事件、流转历史三者靠 order_id / handover_id / request_no 互相对得上。

### 新增状态不改代码

- 库层不再用 CHECK 枚举锁死订单状态（既有库启动时自动 `DROP CONSTRAINT` + 列扩容到 VARCHAR(32)，幂等），新状态落库不需要改表；
- 新增状态/动作只在 `evops.rental.fsm.transitions` 加规则（如 `RENTING --INSPECT--> INSPECTING --INSPECT_DONE--> RENTING`）；
- 纯状态动作经通用接口 `POST /api/orders/{id}/transitions {"action":"...","remark":"..."}` 直接承载；交车/还车因强制登记油量与车损照片，仍走专属接口（通用接口明确拒绝）。

### 接口速览（新增）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/orders/state-machine | 合法路径视图（初始状态、全部规则、正向/逆向标注、动作与状态中文名） |
| GET | /api/orders/{id}/transitions | 状态流转历史（按顺序，可追溯到交接/操作人/请求号/版本） |
| POST | /api/orders/{id}/transitions | 通用状态流转（动作取配置；PICKUP/RETURN 请走专属接口） |

既有 pickup/return/archive/reverse 接口不变，内部全部改走状态机引擎，越级/回退返回 409。

### 测试

`mvn test`：新增 8 个集成用例覆盖合法路径流转链（首尾相接/版本连续/交接关联/冲销原因）、越级拒绝不留痕、回退与终态拒绝、未知动作拒绝、交车不可走通用接口、并行 CAS 只有一个成功且败者回滚无半截历史、配置新增状态（HELD/INSPECTING）不改代码经通用接口流转、状态机视图。全量 47 个用例通过（含既有 9 个主流程用例零改动回归）。

## 交车照片标识（photo-1）

背景：上回抽查被点名——交车照片没加标识就对外了。本期把照片处理彻底理顺：**照片对外前必须带标识**（网点标识 + 订单号水印），处理放后台不卡上传，处理耗时可见，竖拍照片按 EXIF 方向摆正。

### 处理链（上传与处理解耦）

1. **上传** `POST /api/orders/{id}/photos`（multipart）：校验订单存在、文件可解码后，原图落盘 `data/photos/{orderId}/orig_<hash>.<ext>` 并登记 `t_handover_photo`（PENDING）即返回——**不卡上传**；
2. **后台处理**（独立线程池 `evops.photo.executor`，队列满由调用线程兜底不丢任务）：读原图 → 按 EXIF 方向摆正 → Thumbnailator 缩放 280px 预览 → 叠加 30% 透明度水印（右下角，网点标识 + 订单号）→ 落盘 `preview_<hash>.jpg` → 回填状态与耗时；
3. **对外出口唯一**：`GET /api/photos/{id}/preview` 只提供 280px 水印预览图；原图仅内部留存，不提供下载。

### 口径与边界（对照去年同期租车单极端值设防）

本工作区无去年同期生产数据（`data/` 为空库），边界按 schema 上限与成像领域极端设防，集成测试逐一覆盖：

- **超长订单号**：`order_no` 上限 40 位顶满 → 水印字号逐号缩小（最小 8px）至不超出图宽 90%，仍能出图；
- **未分派网点**：`branch_code` 可空 → 水印以 `NO-BRANCH` 占位（取 ASCII 网点编码而非中文名，避免服务器缺中文字体出方块）；
- **竖拍照片**：EXIF 方向 1-8 全映射（手机竖拍多为 6/8），摆正后再缩放加水印；无 EXIF（如 PNG）不旋转；
- **小图不放大**：不足 280px 的原图按原尺寸出预览（Thumbnailator `size()` 对小图默认放大，已显式规避）；
- **坏图**：上传即校验可解码直接拒（409）；落盘后损坏的处理置 FAILED，失败原因与耗时落库，可 `POST /api/photos/{id}/retry` 重试（次数累计）；
- **重复上传**：同一订单同一文件（SHA-256）幂等返回首次记录（`duplicate=true`），库层 `(order_id, file_hash)` 唯一约束兜底并发；
- **单文件上限 20MB**（`spring.servlet.multipart.max-file-size`）；
- **停机自愈**：启动时遗留 PENDING/PROCESSING 记录自动重投处理，失败不阻断启动。

### 耗时可见

每张照片的处理耗时（毫秒）落 `t_handover_photo.process_cost_ms`（完成/失败均回填），列表/详情接口可见，处理完成有 INFO 日志（含 EXIF 方向、是否摆正、耗时）。

### 接口速览（新增）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/orders/{id}/photos | 上传交车照片（multipart `file`），登记 PENDING 即返回，后台异步处理 |
| GET | /api/orders/{id}/photos | 订单照片列表（状态/EXIF 方向/是否摆正/耗时/失败原因） |
| GET | /api/photos/{id} | 照片处理详情 |
| GET | /api/photos/{id}/preview | 下载对外预览图（280px、已摆正、30% 水印）；未完成 409 |
| POST | /api/photos/{id}/retry | FAILED 重试（retry_count 累计） |

写请求同样遵循三件套（`X-Operator` / `X-Request-No` / 可选 `X-Biz-Time`）与入口幂等。交车/还车主链路（pickup/return 的 `damagePhotos` 地址登记）不变，本模块独立成链。

### 测试

`mvn test`：新增 9 个集成用例覆盖上传即返回 PENDING 与异步置 DONE、耗时落库可见、EXIF 方向 6/8 摆正（色块位置断言）、无 EXIF 不旋转、小图不放大、水印痕迹校验、40 位超长订单号+空网点、非图片拒绝、坏图 FAILED 与重试计数、重复上传幂等、预览图未完成 409 与完成后下载一致。全量 56 个用例通过（既有 47 个用例零改动回归）。

## 登录态多端会话（auth-1）

背景：登录这块之前一直靠人工盯，旺季前必须系统化。本期落地：**JWT 携带调度员身份与角色；访问令牌短期有效、过期走刷新接口换新（前端无感）；刷新令牌一次性轮换；修改密码后全部端强制下线；登出后旧令牌立即失效。**

### 令牌模型

- **访问令牌**：标准 JWT（JWS, alg=HS256），零额外依赖（Jackson + JDK `javax.crypto.Mac`）。claims：`uid/username/roles`（身份与角色）、`tv`（tokenVersion，改密自增）、`sid`（所属会话）、`jti`、`type=ACCESS`、`iat/exp`。默认 30 分钟有效（`evops.auth.jwt.access-token-ttl-seconds`）。
- **刷新令牌**：32 字节随机串（非 JWT），**库内只存 SHA-256 哈希**（`t_login_session.refresh_token_hash` 唯一），明文只在登录/刷新响应中出现一次。会话有效期默认 7 天（`evops.auth.refresh.ttl-days`）。
- **会话即端**：每次登录产生一行 `t_login_session`（`client_type` = WEB/APP/...），多端并存互不顶掉；轮换复用同一会话行，`rotate_count` 累计。

### 一次性轮换与重放侦测

`POST /api/auth/refresh` 用刷新令牌换新一对令牌：

1. 命中**当前**哈希 → CAS 原子轮换（`UPDATE ... WHERE refresh_token_hash=旧 AND status='ACTIVE'`），并发/重放只有一方影响行数为 1；旧哈希挪到 `prev_refresh_token_hash`；
2. 命中**上一个**哈希 → 旧令牌再现 = 疑似被盗，**整段会话立即吊销**（吊销走独立事务，即便随后抛 401 也已落库），返回 401 `REFRESH_REPLAY`；
3. 都不命中 → 401 `REFRESH_INVALID`。

**前端契约**：同一刷新令牌必须串行使用（单飞锁）。收到 401 `TOKEN_EXPIRED` 后调刷新接口换新并重试原请求；刷新返回 401 则跳登录。多标签页同时刷新同一令牌会触发重放吊销——这是安全特性，请用单飞锁规避。

### 登出立即失效 / 改密全端下线

- 访问令牌绑定 `sid`，过滤器每请求校验会话状态：`POST /api/auth/logout` 吊销本端会话后，**该会话已签发的访问令牌下一次请求即被 401 拒绝**（`SESSION_REVOKED`），不等自然过期；
- `POST /api/auth/password` 校验原密码后：密码摘要更新（PBKDF2WithHmacSHA256，每账号独立随机盐）、`token_version + 1`、**该账号全部 ACTIVE 会话吊销**——WEB/APP 各端访问令牌因 tv 失配立即 401（`TOKEN_VERSION_CHANGED`），刷新令牌也全部失效，所有端强制重新登录。

### 账号与角色

- 账号表 `t_sys_user`：角色 `DISPATCHER`（调度员）/`MANAGER`（主管）/`ADMIN`（管理员），状态 ACTIVE/DISABLED；
- 首次启动库中无账号时，按 `evops.auth.bootstrap` 创建引导管理员（默认 admin/admin123，**生产务必立即改密或建专属账号**）；
- 管理员接口 `POST /api/system/users`（创建账号）、`GET /api/system/users`（列表，不回传密码摘要），仅 ADMIN；
- 角色控制：方法/类上 `@RequireRole`，未登录 401、角色不足 403；
- `GET /api/auth/me` 返回当前身份与角色。

### 接入与兼容

- 认证由 `JwtAuthFilter` 负责（/api/**），Shiro 链对 /api/** 一律 anon 不再介入；匿名接口：`/api/health`、`/api/auth/login`、`/api/auth/refresh`；
- 登录/刷新不走 `X-Operator`/`X-Request-No` 三件套（登录前没有操作人）；其余登录态写接口（登出/改密）也豁免入口幂等；
- 生产必须覆盖 `evops.auth.jwt.secret`（≥32 字节，建议环境变量 `EVOPS_AUTH_JWT_SECRET` 注入），使用开发默认值启动会打 WARN；
- `evops.auth.enabled=false` 为应急总开关（本地/排障）；测试默认经 `src/test/resources/application-test.yml` 关闭鉴权，既有 56 个用例零改动回归，登录态用例显式 `evops.auth.enabled=true`。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/auth/login | 登录（username/password/clientType=WEB\|APP），下发访问+刷新令牌 |
| POST | /api/auth/refresh | 刷新令牌换新（一次性轮换，重放吊销会话） |
| POST | /api/auth/logout | 登出（需登录），本端会话吊销、旧令牌立即失效 |
| POST | /api/auth/password | 修改密码（需登录），成功后全部端强制下线 |
| GET | /api/auth/me | 当前登录态（身份+角色+会话） |
| POST | /api/system/users | 创建账号（仅 ADMIN） |
| GET | /api/system/users | 账号列表（仅 ADMIN） |

### 测试

`mvn test`：新增 8 个集成用例覆盖登录签发双令牌、错误密码/无令牌/伪造令牌 401、角色 403、刷新一次性轮换与重放吊销、并发刷新恰好一方成功（CAS）、登出后访问/刷新令牌立即失效、改密后 WEB/APP 两端强制下线（tokenVersion+会话吊销）、访问令牌过期 401 TOKEN_EXPIRED 后无感刷新重试成功。

## 周转率统计（turnover-1）

背景：周转率列入本期重点，**数字要经得起审计**。本期落地：每日每网点快照（当天可重算）、凌晨关账冻结、关账后只能追加冲正单（原因必填、append-only）、原始计数落库可独立复算、作业逐次留痕。

### 审计口径（原始计数均可在业务表复算）

- **在役车辆数** = `t_vehicle` 中 IDLE+RENTING（DISABLED 不计分母，单列披露）；
- **完成订单数** = 事件流水 `t_rental_order_event` 中 biz_time 落在业务日的 RETURN（还车结算）行数——状态机保证一笔还车恰有一行；
- **周转率** = 完成订单数 / 在役车辆数（DECIMAL(10,4)，HALF_UP，分母 0 为 null）；
- **出租率** = 快照时点 RENTING / 在役车辆数；
- 车辆/订单未分派网点（branch_code 为空）并入哨兵网点 `NO-BRANCH` 出快照；车辆新增可空 `branch_code` 归属网点（`POST /api/vehicles` 可传，存量为 null 不受影响）。

### 快照生命周期

```
当天 OPEN（可重算刷新，recompute_count 累计）
  --凌晨 04:00 作业（或手工 close）--> CLOSED（冻结，拒绝改写）
CLOSED 后发现数据问题 --只能追加--> 冲正单（t_turnover_correction，原因必填）
```

- **重算**：`POST /api/turnover/run?bizDate=`（缺省当天），OPEN 快照原地刷新；已关账业务日 409 拒绝；
- **关账**：凌晨 04:00 作业（`evops.turnover.daily-close-cron`）对前一业务日重算并置 CLOSED；已 CLOSED 网点幂等跳过；手工 `POST /api/turnover/close?bizDate=` 应急；
- **冲正**：`POST /api/turnover/corrections`（仅 CLOSED 业务日；`completedDelta`/`totalVehiclesDelta` 可正可负，不能全 0；原因必填）。**基线行永不改写**，查询返回 基线 + 每笔冲正 + 冲正合计 + 最终值（最终比率由最终计数复算，不直接相加比率）；
- **留痕**：`t_turnover_job_run` 记录每次重算/关账（含被拒的关账后重算，FAILED + 原因）。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/turnover/daily?branchCode=&from=&to= | 审计视图：快照基线+冲正+最终值（登录即可） |
| POST | /api/turnover/run?bizDate= | 手工重算（MANAGER/ADMIN；已关账 409） |
| POST | /api/turnover/close?bizDate= | 手工关账冻结（MANAGER/ADMIN；缺省前一业务日） |
| POST | /api/turnover/corrections | 关账后冲正（MANAGER/ADMIN；原因必填，append-only） |
| GET | /api/turnover/job-runs?jobType=&bizDate= | 作业运行记录（凌晨关账/手工重算留痕） |

写请求遵循三件套（X-Operator/X-Request-No）与入口幂等；作业内以 SYS_TURNOVER 身份留痕。`evops.turnover.enabled=false` 为应急总开关（不影响手工触发）。

### 测试

`mvn test`：新增 2 个集成用例（全链路造数：车辆/订单/交还车 → 统计 → 重算 → 关账 → 冲正）覆盖计数口径与比率复算（含 NO-BRANCH 归集、零订单网点、在租车辆）、当天重算刷新与重算计数、关账冻结拒绝改写、关账后重算 409、未关账冲正 409、原因必填 400、冲正后基线不变+最终值正确、作业留痕（SUCCESS/FAILED）、未登录 401 与调度员 403。

## 车辆档期（schedule-1）

背景：前任未交接，车辆档期此前没有系统管，现场靠口头/记忆，撞单、占着位不确认、取消不放档期频发。本期把车辆档期统一收归系统：**预约冲突按区间重叠（含前后缓冲）判定；占位需在锁位时限内确认，取消或超时未确认自动释放；冲突时推荐最近空档。本期不含审批流，档期只负责时间占用本身。**

### 占用与冲突口径（半开区间 + 前后缓冲）

- 占用按半开区间 `[startTime, endTime)` 比较：前一单结束时刻 = 下一单开始时刻（即紧邻）本身不冲突；
- 每段占用前后各留缓冲（默认 **12 分钟**，`evops.schedule.buffer-minutes`）：把既有占用扩成禁占区间 `[start-12, end+12)`，新区间 `[s,e)` 满足 `s < end+12 && e > start-12` 即重叠冲突；
- **间隔恰为 12 分钟允许，小于 12 分钟（如 11 分钟）拒绝**——给现场留出/入车准备时间；
- 判定只统计在占状态 `HELD/CONFIRMED`，`CANCELLED/EXPIRED` 行保留留痕但不再挡单。

### 占位生命周期

```
占位 HELD（held_deadline = 占位时刻 + 25 分钟）
  ├─ 锁位期内 confirm --> CONFIRMED
  ├─ 任何在占阶段 cancel --> CANCELLED（释放档期，原因留痕）
  └─ 超过 held_deadline 未确认 --> EXPIRED（自动释放）
```

- **锁位 25 分钟**（`evops.schedule.lock-ttl-minutes`）：`HELD` 到期未确认由周期作业默认**每分钟**扫描自动置 `EXPIRED`（`evops.schedule.release-cron`）；
- 读取路径（再占位、可用性校验）在本车事务内还做了**懒释放**——即便扫描有延迟，超时占位也不会挡住新预约；
- 确认时发现已超时：先在独立事务落 `EXPIRED` 提交，再 409 拒绝确认（释放不会随拒绝回滚）；已释放占位不可确认，需重新占位；
- 确认/取消对已处理状态幂等；全量留痕，不做物理删除。

### 冲突推荐最近空档

占位/可用性校验冲突时，响应 `code=SCHEDULE_CONFLICT`（HTTP 409），`data` 里回传保持原时长、从请求开始时刻向后找到的最早可约区间（合并禁占区间后顺延，最远搜 14 天，超范围不回传）；也可单独调 `GET /api/schedules/next-slot`。

### 并发安全

同一辆车的占位在单事务内先对 `t_vehicle` 行加 `FOR UPDATE` 行锁，再懒释放→判冲突→写入。两个并发占位串行化判定：影响到的那一个成功，后到者看到前者占用后 409 冲突并拿到同样的空档建议，不会出现双占。H2 锁超时已调到 5 秒（生产可按库另行配置）。

### 接口速览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/schedules | 占位（[start,end)；落 HELD，deadline=now+25min；冲突 409 带空档建议） |
| POST | /api/schedules/{id}/confirm | 锁位期内确认占位（超时/已释放 409；确认幂等） |
| POST | /api/schedules/{id}/cancel | 取消释放（HELD/CONFIRMED→CANCELLED，原因可选留痕；取消幂等） |
| GET | /api/schedules?vehicleId=&from=&to=&includeAll= | 某车时间窗内档期（默认仅 HELD/CONFIRMED） |
| GET | /api/schedules/{id} | 档期记录详情 |
| GET | /api/schedules/availability?vehicleId=&start=&end= | 可用性校验（冲突带最近空档） |
| GET | /api/schedules/next-slot?vehicleId=&start=&end= | 单独推荐最近空档 |
| POST | /api/schedules/release-overdue | 手工触发超时释放（应急/补跑） |

写请求遵循三件套（X-Operator/X-Request-No/可选 X-Biz-Time）与入口幂等；周期作业以 SYS_SCHEDULE 身份留痕。`evops.schedule.enabled=false` 为应急总开关（只停周期扫描，不影响手工释放）。

### 配置项

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| evops.schedule.buffer-minutes | 12 | 每段占用前后缓冲分钟数，间隔小于此值冲突，恰为此值允许 |
| evops.schedule.lock-ttl-minutes | 25 | 占位锁位时限，到期未确认自动释放 |
| evops.schedule.release-cron | `0 * * * * ?` | 超时占位扫描周期（每分钟） |
| evops.schedule.enabled | true | 周期扫描应急开关（不影响手工接口） |

### 测试

`mvn test`：新增 12 个集成用例覆盖占位成功与 25 分钟锁位截止、结束早于开始拒绝、完全重叠 409 带最近空档（11:12 起、时长不变）、缓冲边界（12 分钟允许/11 分钟拒绝，前后两侧）、可用性视图、确认成功与确认幂等、超时确认 409 且占位 EXPIRED、取消释放后同窗可重约、周期释放超时占位与二次扫描幂等、未扫描时新占位懒释放、同一辆车并发占位恰一个成功+败者拿冲突、车辆不存在/停用拦截。
