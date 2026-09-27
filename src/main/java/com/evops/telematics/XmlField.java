package com.evops.telematics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * XML 报文字段映射注解：贴在报文类的字段上，声明对应的子元素名与是否必填。
 * 新人看字段即知报文结构，无需翻拼接代码。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface XmlField {

    /** 子元素名（与平台规范一致，如 orderNo） */
    String name();

    /** 必填：报文缺该元素即校验错误（"缺少必填元素 <x>"） */
    boolean required() default false;
}
