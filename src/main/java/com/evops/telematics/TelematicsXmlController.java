package com.evops.telematics;

import com.evops.common.ApiResponse;
import com.evops.common.BizException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 车载定位平台 XML 报文接口。写请求统一携带 X-Operator / X-Request-No（/X-Biz-Time）三件套，过幂等拦截。
 */
@RestController
@RequestMapping("/api/telematics/xml")
public class TelematicsXmlController {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final TelematicsXmlService xmlService;

    public TelematicsXmlController(TelematicsXmlService xmlService) {
        this.xmlService = xmlService;
    }

    // ---- 出站（我方 -> 平台） ----

    /** 为指定业务日（缺省当天）的交接记录生成 XML 报文，按订单号归档；同日可重复导出（补发）。 */
    @PostMapping("/exports")
    public ApiResponse<List<TelematicsXmlMessage>> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        LocalDate target = bizDate != null ? bizDate : LocalDate.now();
        return ApiResponse.ok(xmlService.exportNotices(target));
    }

    /** 重发：走原报文（逐字节沿用归档原件，不重新生成），仅出站报文可重发。 */
    @PostMapping("/messages/{id}/resend")
    public ApiResponse<TelematicsXmlMessage> resend(@PathVariable Long id) {
        return ApiResponse.ok(xmlService.resend(id));
    }

    // ---- 入站（平台 -> 我方） ----

    /**
     * 接收平台设备回执报文（请求体即 XML 原文）。解析失败/校验不过置 FAILED
     * 进异常清单（错误带元素名）；同内容重复接收幂等（duplicate=true）。
     */
    @PostMapping(value = "/imports",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE})
    public ApiResponse<TelematicsXmlMessage> receive(@RequestBody String xml) {
        return ApiResponse.ok(xmlService.receiveReport(xml));
    }

    // ---- 归档与异常清单 ----

    /** 报文归档查询：可按方向（OUT/IN）、订单号组合过滤。 */
    @GetMapping("/messages")
    public ApiResponse<List<TelematicsXmlMessage>> listMessages(
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String orderNo) {
        return ApiResponse.ok(xmlService.listMessages(direction, orderNo));
    }

    /** 报文详情（含原报文 content）。 */
    @GetMapping("/messages/{id}")
    public ApiResponse<TelematicsXmlMessage> getMessage(@PathVariable Long id) {
        return ApiResponse.ok(xmlService.requireMessage(id));
    }

    /** 异常清单：入站解析/校验失败的报文（含失败原因）。 */
    @GetMapping("/exceptions")
    public ApiResponse<List<TelematicsXmlMessage>> listExceptions() {
        return ApiResponse.ok(xmlService.listExceptions());
    }

    // ---- 上线前回归 ----

    /**
     * 回归验证：用网点指定一期（period=yyyy-MM，缺省上一月）租车单的交接数据
     * 跑"生成 -> 解析 -> 逐字段比对"，验证往返转换无丢失。只读不落库。
     */
    @GetMapping("/regression-check")
    public ApiResponse<XmlRegressionReport> regressionCheck(
            @RequestParam String branchCode,
            @RequestParam(required = false) String period) {
        YearMonth target = null;
        if (period != null && !period.trim().isEmpty()) {
            try {
                target = YearMonth.parse(period.trim(), PERIOD_FMT);
            } catch (DateTimeParseException ex) {
                throw new BizException("period 格式应为 yyyy-MM：" + period);
            }
        }
        return ApiResponse.ok(xmlService.regressionCheck(branchCode, target));
    }
}
