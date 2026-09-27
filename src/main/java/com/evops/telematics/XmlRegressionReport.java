package com.evops.telematics;

import java.util.ArrayList;
import java.util.List;

/**
 * XML 上线前回归验证报告：用网点上一期租车单的交接数据跑
 * "生成报文 -> 解析回报 -> 逐字段比对"，证明往返转换无丢失。
 * 只读校验，不落库。
 */
public class XmlRegressionReport {

    private String branchCode;

    /** 验证期（yyyy-MM，缺省上一月） */
    private String period;

    /** 该网点该期租车单总数 */
    private int totalOrders;

    /** 其中有交接记录（可生成报文）的订单数 */
    private int ordersWithHandover;

    /** 参与往返校验的报文条数（一交接一条） */
    private int checkedMessages;

    /** 往返无丢失条数 */
    private int passed;

    /** 往返有丢失/异常条数 */
    private int failed;

    /** 失败明细：订单号 + 交接 id + 原因（哪一字段丢了） */
    private List<Failure> failures = new ArrayList<>();

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public int getTotalOrders() { return totalOrders; }
    public void setTotalOrders(int totalOrders) { this.totalOrders = totalOrders; }
    public int getOrdersWithHandover() { return ordersWithHandover; }
    public void setOrdersWithHandover(int ordersWithHandover) { this.ordersWithHandover = ordersWithHandover; }
    public int getCheckedMessages() { return checkedMessages; }
    public void setCheckedMessages(int checkedMessages) { this.checkedMessages = checkedMessages; }
    public int getPassed() { return passed; }
    public void setPassed(int passed) { this.passed = passed; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public List<Failure> getFailures() { return failures; }
    public void setFailures(List<Failure> failures) { this.failures = failures; }

    public static final class Failure {
        private String orderNo;
        private Long handoverId;
        private String detail;

        public Failure(String orderNo, Long handoverId, String detail) {
            this.orderNo = orderNo;
            this.handoverId = handoverId;
            this.detail = detail;
        }

        public String getOrderNo() { return orderNo; }
        public Long getHandoverId() { return handoverId; }
        public String getDetail() { return detail; }
    }
}
