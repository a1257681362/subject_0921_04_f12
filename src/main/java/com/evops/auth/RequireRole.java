package com.evops.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口角色要求：标注于 Controller 方法/类上，命中其一即放行；
 * 未登录 401，已登录但角色不足 403。不标注则只要求登录。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许的角色（OR 关系） */
    String[] value();
}
