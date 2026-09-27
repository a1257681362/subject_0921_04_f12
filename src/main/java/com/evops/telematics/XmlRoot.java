package com.evops.telematics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * XML 报文根元素注解：贴在报文类上，声明根元素名（与平台规范一致）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface XmlRoot {

    /** 根元素名，如 handoverNotice / deviceReport */
    String value();
}
