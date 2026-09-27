package com.evops.telematics;

/**
 * XML 报文结构/映射异常：结构非法、元素缺失、类型转换失败等。
 * 消息必须指明元素名与原因，调用方原样透出即为明确错误。
 */
public class XmlFormatException extends RuntimeException {
    public XmlFormatException(String message) {
        super(message);
    }
}
