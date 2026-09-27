package com.evops.vehicle;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VehicleMapper extends BaseMapper<Vehicle> {

    /**
     * 档期占位并发控制：在事务内对车辆行加排他行锁（SELECT ... FOR UPDATE）。
     * 同一辆车的并发放置串行化判定，保证两个并发占位不会同时通过区间重叠检查。
     */
    @Select("SELECT * FROM t_vehicle WHERE id = #{id} FOR UPDATE")
    Vehicle selectForUpdate(@Param("id") Long id);
}
