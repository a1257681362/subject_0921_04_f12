package com.evops.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LoginSessionMapper extends BaseMapper<LoginSession> {
}
