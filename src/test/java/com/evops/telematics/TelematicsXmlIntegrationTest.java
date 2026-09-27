package com.evops.telematics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 车载定位平台 XML 报文往来（telematics-xml-1）集成测试：内存 H2，归档目录指向临时目录。
 *
 * 覆盖：
 * - 出站：注解映射生成报文、空值与缺省区分、特殊字符转义、按订单号归档落盘；
 * - 重发：走原报文（逐字节一致、哈希一致、resend_of 链到原件）；
 * - 入站：合法报文落库、同内容幂等、解析失败进异常清单、校验不过错误带元素名；
 * - 回归：网点上一期租车单往返校验无丢失；
 * - 编解码器：往返转换无丢失（含 & < > 回车 换行 制表符 中文 emoji）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:telematics-xml-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.recon.enabled=false",
        "evops.telematics.export-dir=${java.io.tmpdir}/evops-telematics-xml-test/export",
        "evops.telematics.xml-dir=${java.io.tmpdir}/evops-telematics-xml-test/xml"
})
class TelematicsXmlIntegrationTest {

    private static final String XML_DIR =
            System.getProperty("java.io.tmpdir") + "/evops-telematics-xml-test/xml";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Operator", "dispatcher-li")
                .header("X-Request-No", reqNo())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode data(MvcResult result) throws Exception {
        // 用字节数组解析：响应无 charset 时 getContentAsString() 会按 ISO-8859-1 解码，中文变乱码
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
    }

    private JsonNode createOrder(String plate, String branchCode, String bizTime) throws Exception {
        MvcResult vehicle = mockMvc.perform(write(post("/api/vehicles"))
                        .content("{\"plateNo\":\"" + plate + "\",\"model\":\"Model-A\"}"))
                .andExpect(status().isOk()).andReturn();
        long vehicleId = data(vehicle).get("id").asLong();
        String body = "{\"vehicleId\":" + vehicleId + ",\"customerName\":\"张三\""
                + (branchCode != null ? ",\"branchCode\":\"" + branchCode + "\"" : "") + "}";
        MockHttpServletRequestBuilder builder = write(post("/api/orders")).content(body);
        if (bizTime != null) {
            builder = builder.header("X-Biz-Time", bizTime);
        }
        MvcResult created = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return data(created);
    }

    private void pickup(long orderId, String bizTime, int fuel, String photos, String remarkJson)
            throws Exception {
        String body = "{\"fuelReading\":" + fuel + ",\"damagePhotos\":\"" + photos + "\""
                + (remarkJson != null ? ",\"remark\":" + remarkJson : "") + "}";
        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"))
                        .header("X-Biz-Time", bizTime).content(body))
                .andExpect(status().isOk());
    }

    // =====================================================================
    // 出站：生成报文并按订单号归档
    // =====================================================================

    @Test
    void exportArchivesByOrderNoAndDistinguishesNullFromEmpty() throws Exception {
        String bizDate = "2026-09-20";
        // 交接 1：备注含 & < > 引号 回车 换行 制表符 中文 -> 验证转义与往返无丢失
        String nastyRemark = "前杠有划痕 & 掉漆 <已拍> \"确认\"\r\n第二行\t缩进";
        String nastyJson = "\"前杠有划痕 & 掉漆 <已拍> \\\"确认\\\"\\r\\n第二行\\t缩进\"";
        JsonNode order1 = createOrder("京X" + reqNo().substring(0, 6), null, null);
        pickup(order1.get("id").asLong(), bizDate + "T09:15:00", 80,
                "oss://p/a1.jpg,oss://p/a2.jpg", nastyJson);
        // 交接 2：remark 传空串 -> 空元素 <remark/>（与缺省区分）
        JsonNode order2 = createOrder("京X" + reqNo().substring(0, 6), null, null);
        pickup(order2.get("id").asLong(), bizDate + "T10:20:00", 50, "oss://p/b.jpg", "\"\"");
        // 交接 3：remark 不传 -> null -> 元素缺省
        JsonNode order3 = createOrder("京X" + reqNo().substring(0, 6), null, null);
        pickup(order3.get("id").asLong(), bizDate + "T11:25:00", 60, "oss://p/c.jpg", null);

        MvcResult exported = mockMvc.perform(write(post("/api/telematics/xml/exports"))
                        .param("bizDate", bizDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andReturn();
        JsonNode records = data(exported);

        JsonNode msg1 = null;
        JsonNode msg2 = null;
        JsonNode msg3 = null;
        for (JsonNode record : records) {
            assertEquals("OUT", record.get("direction").asText());
            assertEquals("HANDOVER_NOTICE", record.get("msgType").asText());
            assertEquals("SENT", record.get("status").asText());
            String orderNo = record.get("orderNo").asText();
            // 按订单号归档：文件落在 <xmlDir>/<orderNo>/ 下且真实存在
            String filePath = record.get("filePath").asText();
            assertTrue(filePath.contains(orderNo), "归档路径应含订单号目录：" + filePath);
            assertTrue(Paths.get(filePath).startsWith(Paths.get(XML_DIR, orderNo)));
            assertTrue(Files.exists(Paths.get(filePath)), "归档文件应真实存在：" + filePath);
            // 落盘内容与库内原报文一致
            assertEquals(record.get("content").asText(),
                    new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8));
            if (orderNo.equals(order1.get("orderNo").asText())) {
                msg1 = record;
            } else if (orderNo.equals(order2.get("orderNo").asText())) {
                msg2 = record;
            } else if (orderNo.equals(order3.get("orderNo").asText())) {
                msg3 = record;
            }
        }
        assertTrue(msg1 != null && msg2 != null && msg3 != null, "三条报文应分别对应三张订单");

        // 报文 1：注解映射字段齐全 + 特殊字符转义
        String xml1 = msg1.get("content").asText();
        assertTrue(xml1.startsWith(XmlCodec.DECLARATION + "<handoverNotice>"), "应有声明与根元素：" + xml1);
        assertTrue(xml1.contains("<orderNo>" + order1.get("orderNo").asText() + "</orderNo>"));
        assertTrue(xml1.contains("<plateNo>京X"), "应含车牌元素：" + xml1);
        assertTrue(xml1.contains("<handoverType>PICKUP</handoverType>"));
        assertTrue(xml1.contains("<handoverTime>2026-09-20 09:15:00</handoverTime>"));
        assertTrue(xml1.contains("<fuelReading>80</fuelReading>"));
        assertTrue(xml1.contains("<damagePhotos>oss://p/a1.jpg,oss://p/a2.jpg</damagePhotos>"));
        assertTrue(xml1.contains("前杠有划痕 &amp; 掉漆 &lt;已拍&gt; \"确认\"&#13;\n第二行\t缩进"),
                "& < > 应转义、回车应写为 &#13;、引号不转义：" + xml1);

        // 往返转换无丢失：解析回报逐字段一致，再序列化逐字节一致（规范形）
        HandoverNoticeMessage back = XmlCodec.parse(xml1, HandoverNoticeMessage.class);
        assertEquals(order1.get("orderNo").asText(), back.getOrderNo());
        assertEquals("PICKUP", back.getHandoverType());
        assertEquals(80, back.getFuelReading());
        assertEquals(nastyRemark, back.getRemark());
        assertEquals(xml1, XmlCodec.serialize(back));

        // 报文 2：空串备注 -> 空元素 <remark/>，解析回空串（与缺省区分）
        String xml2 = msg2.get("content").asText();
        assertTrue(xml2.contains("<remark/>"), "空串备注应为空元素：" + xml2);
        assertEquals("", XmlCodec.parse(xml2, HandoverNoticeMessage.class).getRemark());

        // 报文 3：null 备注 -> 元素缺省，解析回 null
        String xml3 = msg3.get("content").asText();
        assertFalse(xml3.contains("remark"), "null 备注应元素缺省：" + xml3);
        assertEquals(null, XmlCodec.parse(xml3, HandoverNoticeMessage.class).getRemark());

        // 归档列表可按订单号查
        mockMvc.perform(get("/api/telematics/xml/messages")
                        .param("direction", "OUT")
                        .param("orderNo", order1.get("orderNo").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].content").value(xml1));
    }

    // =====================================================================
    // 重发：走原报文
    // =====================================================================

    @Test
    void resendReusesOriginalMessageBytes() throws Exception {
        String bizDate = "2026-09-21";
        JsonNode order = createOrder("京R" + reqNo().substring(0, 6), null, null);
        pickup(order.get("id").asLong(), bizDate + "T08:00:00", 70, "oss://p/r.jpg", "\"原件备注\"");
        MvcResult exported = mockMvc.perform(write(post("/api/telematics/xml/exports"))
                        .param("bizDate", bizDate))
                .andExpect(status().isOk()).andReturn();
        JsonNode original = data(exported).get(0);
        long originalId = original.get("id").asLong();

        // 重发：内容逐字节沿用原件（哈希一致即凭证），resend_of 指向原件
        MvcResult resent = mockMvc.perform(write(post(
                        "/api/telematics/xml/messages/" + originalId + "/resend")))
                .andExpect(status().isOk()).andReturn();
        JsonNode resend = data(resent);
        assertNotEquals(originalId, resend.get("id").asLong());
        assertEquals(original.get("content").asText(), resend.get("content").asText(),
                "重发应走原报文（逐字节一致）");
        assertEquals(original.get("contentHash").asText(), resend.get("contentHash").asText());
        assertEquals(originalId, resend.get("resendOf").asLong());
        assertNotEquals(original.get("filePath").asText(), resend.get("filePath").asText());
        // 两次归档文件字节一致
        assertEquals(new String(Files.readAllBytes(Paths.get(original.get("filePath").asText())),
                        StandardCharsets.UTF_8),
                new String(Files.readAllBytes(Paths.get(resend.get("filePath").asText())),
                        StandardCharsets.UTF_8));

        // 重发的重发：仍指向最初原件
        MvcResult resent2 = mockMvc.perform(write(post(
                        "/api/telematics/xml/messages/" + resend.get("id").asLong() + "/resend")))
                .andExpect(status().isOk()).andReturn();
        assertEquals(originalId, data(resent2).get("resendOf").asLong());

        // 同一订单号下归档可查：原件 + 两次重发
        mockMvc.perform(get("/api/telematics/xml/messages")
                        .param("orderNo", order.get("orderNo").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        // 入站报文不可重发
        MvcResult inbound = receive("<deviceReport><plateNo>京R" + reqNo().substring(0, 6)
                + "</plateNo><deviceNo>DEV-R1</deviceNo><deviceStatus>ONLINE</deviceStatus>"
                + "<reportTime>2026-09-21 09:00:00</reportTime></deviceReport>");
        long inboundId = data(inbound).get("id").asLong();
        mockMvc.perform(write(post("/api/telematics/xml/messages/" + inboundId + "/resend")))
                .andExpect(status().isConflict());
    }

    // =====================================================================
    // 入站：接收平台回执报文
    // =====================================================================

    private MvcResult receive(String xml) throws Exception {
        return mockMvc.perform(post("/api/telematics/xml/imports")
                        .header("X-Operator", "dispatcher-li")
                        .header("X-Request-No", reqNo())
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void receiveValidReportUpsertsDeviceAndSameContentIsIdempotent() throws Exception {
        String tag = reqNo().substring(0, 6);
        // 美化缩排 + 空元素备注：解析应容忍元素间空白，<remark/> 得空串
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<deviceReport>\n"
                + "  <plateNo>京N" + tag + "</plateNo>\n"
                + "  <deviceNo>DEV-" + tag + "</deviceNo>\n"
                + "  <deviceStatus>ONLINE</deviceStatus>\n"
                + "  <reportTime>2026-09-25 08:30:00</reportTime>\n"
                + "  <remark/>\n"
                + "</deviceReport>";

        MvcResult first = receive(xml);
        JsonNode firstData = data(first);
        assertEquals("IN", firstData.get("direction").asText());
        assertEquals("RECEIVED", firstData.get("status").asText());
        assertFalse(firstData.get("duplicate").asBoolean());

        // 回执落库：空串备注存空串（非 null），来源挂 XML 报文 id
        mockMvc.perform(get("/api/telematics/device-reports").param("plateNo", "京N" + tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].deviceStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data[0].remark").value(""))
                .andExpect(jsonPath("$.data[0].xmlMessageId").value(firstData.get("id").asLong()));

        // 同内容重复接收（换请求号）：幂等返回首次结果，回执行数不变
        MvcResult second = receive(xml);
        JsonNode secondData = data(second);
        assertTrue(secondData.get("duplicate").asBoolean());
        assertEquals(firstData.get("id").asLong(), secondData.get("id").asLong());
        mockMvc.perform(get("/api/telematics/device-reports").param("plateNo", "京N" + tag))
                .andExpect(jsonPath("$.data.length()").value(1));

        // 同设备新报文：按最新覆盖，来源换挂新报文
        String xml2 = "<deviceReport><plateNo>京N" + tag + "</plateNo>"
                + "<deviceNo>DEV-" + tag + "</deviceNo><deviceStatus>OFFLINE</deviceStatus>"
                + "<reportTime>2026-09-25 10:00:00</reportTime><remark>已恢复</remark></deviceReport>";
        MvcResult third = receive(xml2);
        mockMvc.perform(get("/api/telematics/device-reports").param("plateNo", "京N" + tag))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].deviceStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.data[0].remark").value("已恢复"))
                .andExpect(jsonPath("$.data[0].xmlMessageId").value(data(third).get("id").asLong()));
    }

    @Test
    void malformedXmlGoesToExceptionList() throws Exception {
        String tag = reqNo().substring(0, 6);
        // 结构非法（元素未闭合）
        MvcResult broken = receive("<deviceReport><plateNo>京B" + tag + "</plateNo>");
        JsonNode brokenData = data(broken);
        assertEquals("FAILED", brokenData.get("status").asText());
        assertTrue(brokenData.get("errorDetail").asText().contains("XML 结构非法"),
                "应说明结构非法：" + brokenData.get("errorDetail").asText());

        // 根元素不符
        MvcResult wrongRoot = receive("<foo/>");
        assertTrue(data(wrongRoot).get("errorDetail").asText().contains("根元素应为 <deviceReport>"));

        // 未识别的元素
        MvcResult unknown = receive("<deviceReport><plateNo>京B" + tag + "</plateNo>"
                + "<deviceNo>DEV-B</deviceNo><deviceStatus>ONLINE</deviceStatus>"
                + "<reportTime>2026-09-25 08:30:00</reportTime><gps>x</gps></deviceReport>");
        assertTrue(data(unknown).get("errorDetail").asText().contains("未识别的元素 <gps>"));

        // 异常清单可查（含失败原因），业务行零写入
        MvcResult exceptions = mockMvc.perform(get("/api/telematics/xml/exceptions"))
                .andExpect(status().isOk()).andReturn();
        boolean found = false;
        for (JsonNode item : data(exceptions)) {
            if (item.get("id").asLong() == brokenData.get("id").asLong()) {
                found = true;
                assertEquals("FAILED", item.get("status").asText());
                assertTrue(item.get("errorDetail").asText().contains("XML 结构非法"));
            }
        }
        assertTrue(found, "解析失败的报文应进异常清单");
        mockMvc.perform(get("/api/telematics/device-reports").param("plateNo", "京B" + tag))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void invalidReportFailsWithElementLevelErrors() throws Exception {
        String tag = reqNo().substring(0, 6);
        // 结构合法但业务校验不过：车牌超长 + 状态非法（逐条列明）
        String longPlate = "京V" + tag + "123456789012345678901234567890";   // > 32
        MvcResult result = receive("<deviceReport><plateNo>" + longPlate + "</plateNo>"
                + "<deviceNo>DEV-V</deviceNo><deviceStatus>FLYING</deviceStatus>"
                + "<reportTime>2026-09-25 08:30:00</reportTime></deviceReport>");
        JsonNode record = data(result);
        assertEquals("FAILED", record.get("status").asText());
        String detail = record.get("errorDetail").asText();
        assertTrue(detail.contains("元素 <plateNo> 超长"), "应指明 plateNo 超长：" + detail);
        assertTrue(detail.contains("元素 <deviceStatus> 非法：FLYING"), "应指明状态非法：" + detail);

        // 必填元素缺失
        MvcResult missing = receive("<deviceReport><plateNo>京M" + tag + "</plateNo>"
                + "<deviceStatus>ONLINE</deviceStatus>"
                + "<reportTime>2026-09-25 08:30:00</reportTime></deviceReport>");
        assertTrue(data(missing).get("errorDetail").asText().contains("缺少必填元素 <deviceNo>"));

        // 时间格式非法
        MvcResult badTime = receive("<deviceReport><plateNo>京M" + tag + "</plateNo>"
                + "<deviceNo>DEV-M</deviceNo><deviceStatus>ONLINE</deviceStatus>"
                + "<reportTime>25/09/2026 08:32</reportTime></deviceReport>");
        assertTrue(data(badTime).get("errorDetail").asText()
                .contains("元素 <reportTime> 格式应为 yyyy-MM-dd HH:mm:ss"));

        // 全部进异常清单，业务行零写入
        mockMvc.perform(get("/api/telematics/device-reports").param("plateNo", "京M" + tag))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // =====================================================================
    // 上线前回归：网点上一期租车单往返无损
    // =====================================================================

    @Test
    void regressionCheckOverBranchOrdersProvesLosslessRoundTrip() throws Exception {
        String branch = "BR" + reqNo().substring(0, 4);
        // 网点上一期（2026-08）租车单：A 交车+还车、B 仅交车（备注含特殊字符）、C 无交接
        JsonNode orderA = createOrder("京G" + reqNo().substring(0, 6), branch, "2026-08-10T09:00:00");
        pickup(orderA.get("id").asLong(), "2026-08-10T10:00:00", 90, "oss://p/g1.jpg",
                "\"交车 & 检查 <正常>\"");
        mockMvc.perform(write(post("/api/orders/" + orderA.get("id").asLong() + "/return"))
                        .header("X-Biz-Time", "2026-08-12T18:00:00")
                        .content("{\"fuelReading\":35,\"damagePhotos\":\"oss://p/g2.jpg\","
                                + "\"settleAmount\":120.50,\"remark\":\"还车备注\\r\\n次行\"}"))
                .andExpect(status().isOk());
        JsonNode orderB = createOrder("京G" + reqNo().substring(0, 6), branch, "2026-08-15T09:00:00");
        pickup(orderB.get("id").asLong(), "2026-08-15T10:00:00", 60, "oss://p/g3.jpg", null);
        createOrder("京G" + reqNo().substring(0, 6), branch, "2026-08-20T09:00:00");   // C：无交接
        // 干扰项：别的网点同期、同网点别的期，均不应计入
        JsonNode other = createOrder("京G" + reqNo().substring(0, 6), "BRX" + reqNo().substring(0, 3),
                "2026-08-10T09:00:00");
        pickup(other.get("id").asLong(), "2026-08-10T10:00:00", 80, "oss://p/x.jpg", null);
        createOrder("京G" + reqNo().substring(0, 6), branch, "2026-09-01T09:00:00");

        MvcResult report = mockMvc.perform(get("/api/telematics/xml/regression-check")
                        .param("branchCode", branch).param("period", "2026-08"))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = data(report);
        assertEquals("2026-08", data.get("period").asText());
        assertEquals(3, data.get("totalOrders").asInt(), "只算该网点该期租车单");
        assertEquals(2, data.get("ordersWithHandover").asInt());
        assertEquals(3, data.get("checkedMessages").asInt(), "A 两条交接 + B 一条");
        assertEquals(3, data.get("passed").asInt(), "往返转换应无丢失");
        assertEquals(0, data.get("failed").asInt());
        assertEquals(0, data.get("failures").size());

        // 缺省 period 取上一月：在该月造一单，验证缺省口径
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        String branch2 = "BR" + reqNo().substring(0, 4);
        JsonNode orderD = createOrder("京G" + reqNo().substring(0, 6), branch2,
                lastMonth.atDay(10).toString() + "T09:00:00");
        pickup(orderD.get("id").asLong(), lastMonth.atDay(10).toString() + "T10:00:00",
                55, "oss://p/g4.jpg", "\"\"");
        MvcResult defaulted = mockMvc.perform(get("/api/telematics/xml/regression-check")
                        .param("branchCode", branch2))
                .andExpect(status().isOk()).andReturn();
        assertEquals(lastMonth.toString(), data(defaulted).get("period").asText());
        assertEquals(1, data(defaulted).get("totalOrders").asInt());
        assertEquals(1, data(defaulted).get("passed").asInt());
        assertEquals(0, data(defaulted).get("failed").asInt());
    }

    // =====================================================================
    // 编解码器：往返转换无丢失 / 严格校验
    // =====================================================================

    @Test
    void codecRoundTripIsLosslessAndValidationIsStrict() {
        // 全字符谱往返：& < > 引号 回车 换行 制表符 中文 emoji
        HandoverNoticeMessage message = new HandoverNoticeMessage();
        message.setOrderNo("RO20260920001");
        message.setPlateNo("京A12345");
        message.setHandoverType("PICKUP");
        message.setHandoverTime(java.time.LocalDateTime.of(2026, 9, 20, 9, 15, 0));
        message.setFuelReading(80);
        message.setDamagePhotos("oss://p/a.jpg");
        message.setRemark("a&b<c>d\"e'f\rg\nh\ti京🚗");
        String xml = XmlCodec.serialize(message);
        HandoverNoticeMessage back = XmlCodec.parse(xml, HandoverNoticeMessage.class);
        assertEquals(message.getRemark(), back.getRemark(), "往返转换应无丢失");
        assertEquals(xml, XmlCodec.serialize(back), "规范形再序列化应逐字节一致");

        // 空串 <-> 空元素；null <-> 缺省
        message.setRemark("");
        assertEquals("", XmlCodec.parse(XmlCodec.serialize(message), HandoverNoticeMessage.class)
                .getRemark());
        message.setRemark(null);
        String noRemark = XmlCodec.serialize(message);
        assertFalse(noRemark.contains("remark"));
        assertEquals(null, XmlCodec.parse(noRemark, HandoverNoticeMessage.class).getRemark());

        // 出站即拦截必填缺失
        HandoverNoticeMessage incomplete = new HandoverNoticeMessage();
        try {
            XmlCodec.serialize(incomplete);
            fail("必填字段为 null 应拦截");
        } catch (XmlFormatException ex) {
            assertTrue(ex.getMessage().contains("缺少必填元素 <orderNo>"), ex.getMessage());
        }

        // 入站严格：元素重复 / 叶子嵌套 / 根内文本 / DOCTYPE（XXE）
        assertCodecError("<deviceReport><plateNo>a</plateNo><plateNo>b</plateNo></deviceReport>",
                "元素 <plateNo> 重复出现");
        assertCodecError("<deviceReport><plateNo><x/></plateNo></deviceReport>",
                "元素 <plateNo> 应为文本内容，不允许嵌套元素");
        assertCodecError("<deviceReport>文本</deviceReport>", "内不允许直接出现文本");
        assertCodecError("<?xml version=\"1.0\"?><!DOCTYPE deviceReport [<!ENTITY x \"boom\">]>"
                + "<deviceReport><plateNo>&x;</plateNo></deviceReport>", "结构非法");
        assertCodecError("<deviceReport><fuelReading>abc</fuelReading></deviceReport>",
                "未识别的元素");   // deviceReport 无 fuelReading 元素
    }

    private static void assertCodecError(String xml, String expectedMessagePart) {
        try {
            XmlCodec.parse(xml, DeviceReportMessage.class);
            fail("应拒绝：" + xml);
        } catch (XmlFormatException ex) {
            assertTrue(ex.getMessage().contains(expectedMessagePart),
                    "错误消息应含 [" + expectedMessagePart + "]，实际：" + ex.getMessage());
        }
    }
}
