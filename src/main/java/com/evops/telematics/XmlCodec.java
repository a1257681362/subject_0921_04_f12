package com.evops.telematics;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 注解式 XML 报文编解码（与车载定位平台报文往来规范一致）：
 * <ul>
 *   <li>字段映射靠注解：类上 {@link XmlRoot} 声明根元素，字段上 {@link XmlField} 声明子元素，
 *       看字段即知报文结构，新人不用翻拼接代码；</li>
 *   <li>空值与缺省可区分：字段为 null 序列化时整个元素省略（缺省），空串序列化为 {@code <x/>}，
 *       解析时空元素得空串、缺省元素保持 null —— 往返转换无丢失；</li>
 *   <li>序列化为规范形：元素按字段声明顺序、无缩排、&amp;/&lt;/&gt; 转义、回车写为 &amp;#13;
 *       （XML 解析器会把裸回车归一为换行，转义后往返才不丢字符），故
 *       parse(serialize(x)) == x 且 serialize(parse(serialize(x))) == serialize(x)；</li>
 *   <li>解析严格：根元素不符、未识别元素、元素重复、叶子元素嵌套、必填缺失、类型不符，
 *       均抛 {@link XmlFormatException} 且消息指明元素名与原因；</li>
 *   <li>安全：禁 DOCTYPE/外部实体（防 XXE）。</li>
 * </ul>
 * 支持的字段类型：String、Integer、LocalDateTime（yyyy-MM-dd HH:mm:ss，与 CSV 往来口径一致）。
 * 新增类型在此扩展，未支持类型在序列化/解析时即报编程错误。
 */
public final class XmlCodec {

    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** XML 声明（规范形的一部分，归档与重发均以此为准） */
    public static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private XmlCodec() {}

    // =====================================================================
    // 序列化（对象 -> 报文）
    // =====================================================================

    /** 序列化报文对象为规范形 XML 字符串。字段值含 XML 1.0 非法字符时抛 XmlFormatException。 */
    public static <T> String serialize(T bean) {
        Class<?> type = bean.getClass();
        StringBuilder sb = new StringBuilder(DECLARATION);
        String root = rootName(type);
        sb.append('<').append(root).append('>');
        for (Field field : mappedFields(type)) {
            XmlField mapping = field.getAnnotation(XmlField.class);
            Object value;
            try {
                field.setAccessible(true);
                value = field.get(bean);
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("字段不可读：" + field.getName(), ex);
            }
            if (value == null) {
                // 缺省：null 不产出元素；但必填字段为 null 说明数据不全，出站即拦截
                if (mapping.required()) {
                    throw new XmlFormatException("缺少必填元素 <" + mapping.name() + ">（字段值为空）");
                }
                continue;
            }
            String text = toText(value, field);
            sb.append('<').append(mapping.name());
            if (text.isEmpty()) {
                sb.append("/>");   // 空串 -> 空元素（与缺省区分）
            } else {
                sb.append('>').append(escape(text, mapping.name()))
                        .append("</").append(mapping.name()).append('>');
            }
        }
        sb.append("</").append(root).append('>');
        return sb.toString();
    }

    private static String toText(Object value, Field field) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Integer) {
            return value.toString();
        }
        if (value instanceof LocalDateTime) {
            return TIME_FMT.format((LocalDateTime) value);
        }
        throw new IllegalStateException("字段 " + field.getName() + " 类型暂不支持：" + value.getClass().getName());
    }

    /** 文本转义：& < > 必转；回车转为 &#13; 防止解析器归一为换行导致往返丢字符。 */
    private static String escape(String text, String elementName) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '\r': sb.append("&#13;"); break;
                default:
                    if (c < 0x20 && c != '\t' && c != '\n') {
                        throw new XmlFormatException("元素 <" + elementName + "> 含 XML 1.0 不支持的字符"
                                + "（0x" + Integer.toHexString(c) + "）");
                    }
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    // =====================================================================
    // 解析（报文 -> 对象）
    // =====================================================================

    /** 解析报文为对象。结构非法/元素缺失/类型不符抛 XmlFormatException（消息带元素名）。 */
    public static <T> T parse(String xml, Class<T> type) {
        if (xml == null || xml.trim().isEmpty()) {
            throw new XmlFormatException("报文为空");
        }
        Element root = parseDocument(xml);
        String expectedRoot = rootName(type);
        if (!expectedRoot.equals(root.getTagName())) {
            throw new XmlFormatException("根元素应为 <" + expectedRoot + ">，实际为 <" + root.getTagName() + ">");
        }

        Map<String, Field> byElement = new HashMap<>();
        List<Field> fields = mappedFields(type);
        for (Field field : fields) {
            byElement.put(field.getAnnotation(XmlField.class).name(), field);
        }

        T bean = newInstance(type);
        Set<String> seen = new HashSet<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.TEXT_NODE) {
                if (!node.getTextContent().trim().isEmpty()) {
                    throw new XmlFormatException("根元素 <" + expectedRoot + "> 内不允许直接出现文本："
                            + abbreviate(node.getTextContent().trim()));
                }
                continue;   // 元素间空白（美化缩排）忽略
            }
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;   // 注释/处理指令忽略
            }
            Element element = (Element) node;
            String name = element.getTagName();
            Field field = byElement.get(name);
            if (field == null) {
                throw new XmlFormatException("未识别的元素 <" + name + ">");
            }
            if (!seen.add(name)) {
                throw new XmlFormatException("元素 <" + name + "> 重复出现");
            }
            if (element.getElementsByTagName("*").getLength() > 0) {
                throw new XmlFormatException("元素 <" + name + "> 应为文本内容，不允许嵌套元素");
            }
            setValue(bean, field, name, element.getTextContent());
        }

        for (Field field : fields) {
            XmlField mapping = field.getAnnotation(XmlField.class);
            if (mapping.required() && !seen.contains(mapping.name())) {
                throw new XmlFormatException("缺少必填元素 <" + mapping.name() + ">");
            }
        }
        return bean;
    }

    /** DOM 解析（禁 DOCTYPE/外部实体）。结构非法时把 SAX 行列号带入错误消息。 */
    private static Element parseDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            return doc.getDocumentElement();
        } catch (SAXParseException ex) {
            throw new XmlFormatException("XML 结构非法（第 " + ex.getLineNumber() + " 行第 "
                    + ex.getColumnNumber() + " 列）：" + ex.getMessage());
        } catch (XmlFormatException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new XmlFormatException("XML 结构非法：" + ex.getMessage());
        }
    }

    private static void setValue(Object bean, Field field, String element, String text) {
        try {
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == String.class) {
                field.set(bean, text);   // 原文保留（空元素即空串），是否修剪由业务校验决定
            } else if (type == Integer.class) {
                String trimmed = text.trim();
                if (trimmed.isEmpty()) {
                    throw new XmlFormatException("元素 <" + element + "> 应为整数，实际为空");
                }
                try {
                    field.set(bean, Integer.valueOf(trimmed));
                } catch (NumberFormatException ex) {
                    throw new XmlFormatException("元素 <" + element + "> 应为整数：" + trimmed);
                }
            } else if (type == LocalDateTime.class) {
                String trimmed = text.trim();
                try {
                    field.set(bean, LocalDateTime.parse(trimmed, TIME_FMT));
                } catch (DateTimeParseException ex) {
                    throw new XmlFormatException("元素 <" + element + "> 格式应为 yyyy-MM-dd HH:mm:ss："
                            + (trimmed.isEmpty() ? "（空）" : trimmed));
                }
            } else {
                throw new IllegalStateException("字段 " + field.getName() + " 类型暂不支持：" + type.getName());
            }
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("字段不可写：" + field.getName(), ex);
        }
    }

    // ---- 内部辅助 ----

    private static String rootName(Class<?> type) {
        XmlRoot root = type.getAnnotation(XmlRoot.class);
        if (root == null) {
            throw new IllegalStateException(type.getSimpleName() + " 缺少 @XmlRoot 注解");
        }
        return root.value();
    }

    /** 映射字段（声明顺序）：仅取带 @XmlField 的实例字段。 */
    private static List<Field> mappedFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.getAnnotation(XmlField.class) != null) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            java.lang.reflect.Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(type.getSimpleName() + " 缺少无参构造器", ex);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(type.getSimpleName() + " 实例化失败", ex);
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 20 ? text : text.substring(0, 20) + "...";
    }
}
