package com.evops.turnover;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 周转率原始计数聚合：全部基于库表现算，快照行只是结果的落库留痕。
 * 审计可用同样口径复算：分母为车辆台账当前在役车辆，分子为订单事件流水
 * （biz_time 落在业务日的 RETURN=完成 / CREATE=下单），按订单归属网点归集。
 * 列别名加双引号以保留驼峰原样（H2 不加引号会折叠为大写，影响 Map 取值）。
 */
@Mapper
public interface TurnoverStatsMapper {

    /** 各网点车辆状态计数（未分派/空网点并入 NO-BRANCH）。 */
    @Select("SELECT COALESCE(NULLIF(TRIM(branch_code), ''), 'NO-BRANCH') AS \"branchCode\", "
            + "SUM(CASE WHEN status = 'IDLE' THEN 1 ELSE 0 END) AS \"idleCount\", "
            + "SUM(CASE WHEN status = 'RENTING' THEN 1 ELSE 0 END) AS \"rentingCount\", "
            + "SUM(CASE WHEN status = 'DISABLED' THEN 1 ELSE 0 END) AS \"disabledCount\" "
            + "FROM t_vehicle GROUP BY COALESCE(NULLIF(TRIM(branch_code), ''), 'NO-BRANCH')")
    List<Map<String, Object>> vehicleCountsByBranch();

    /**
     * 指定业务日各网点完成（RETURN 还车结算）/下单（CREATE）事件数。
     * 一笔还车在事件流水中恰有一行 RETURN（状态机保证不重复），按事件业务时间归属业务日。
     */
    @Select("SELECT COALESCE(NULLIF(TRIM(o.branch_code), ''), 'NO-BRANCH') AS \"branchCode\", "
            + "SUM(CASE WHEN e.event_type = 'RETURN' THEN 1 ELSE 0 END) AS \"completedCount\", "
            + "SUM(CASE WHEN e.event_type = 'CREATE' THEN 1 ELSE 0 END) AS \"createdCount\" "
            + "FROM t_rental_order_event e "
            + "JOIN t_rental_order o ON o.id = e.order_id "
            + "WHERE CAST(e.biz_time AS DATE) = #{bizDate} "
            + "AND e.event_type IN ('RETURN', 'CREATE') "
            + "GROUP BY COALESCE(NULLIF(TRIM(o.branch_code), ''), 'NO-BRANCH')")
    List<Map<String, Object>> eventCountsByBranch(@Param("bizDate") LocalDate bizDate);
}
