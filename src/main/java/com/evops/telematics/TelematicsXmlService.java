package com.evops.telematics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizException;
import com.evops.rental.RentalHandover;
import com.evops.rental.RentalHandoverMapper;
import com.evops.rental.RentalOrder;
import com.evops.rental.RentalOrderMapper;
import com.evops.vehicle.Vehicle;
import com.evops.vehicle.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 车载定位平台 XML 报文往来。
 *
 * 出站（我方 -> 平台）：按业务日抽取交接记录，一交接生成一条交接通知报文，
 * 按订单号分目录归档落盘并留归档记录。重发走原报文：逐字节沿用归档原件
 * （content 列），不重新生成，新记录 resend_of 指向最初原件。
 *
 * 入站（平台 -> 我方）：设备回执报文。解析失败/校验不过置 FAILED 进异常清单
 * （error_detail 带元素名与原因），业务行零写入；同内容报文重复接收幂等
 * （duplicate=true 返回首次结果，dedup_key 库层唯一兜底）。
 *
 * 上线前回归：regressionCheck 用网点上一期租车单的交接数据跑
 * "生成 -> 解析 -> 逐字段比对"，验证往返转换无丢失；只读不落库。
 */
@Service
public class TelematicsXmlService {

    private static final DateTimeFormatter FILE_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final Set<String> DEVICE_STATUSES = new HashSet<>(Arrays.asList("ONLINE", "OFFLINE"));

    private final TelematicsProperties properties;
    private final TelematicsXmlMessageMapper messageMapper;
    private final TelematicsDeviceReportMapper deviceMapper;
    private final RentalHandoverMapper handoverMapper;
    private final RentalOrderMapper orderMapper;
    private final VehicleMapper vehicleMapper;

    public TelematicsXmlService(TelematicsProperties properties,
                                TelematicsXmlMessageMapper messageMapper,
                                TelematicsDeviceReportMapper deviceMapper,
                                RentalHandoverMapper handoverMapper,
                                RentalOrderMapper orderMapper,
                                VehicleMapper vehicleMapper) {
        this.properties = properties;
        this.messageMapper = messageMapper;
        this.deviceMapper = deviceMapper;
        this.handoverMapper = handoverMapper;
        this.orderMapper = orderMapper;
        this.vehicleMapper = vehicleMapper;
    }

    // =====================================================================
    // 出站：生成报文并按订单号归档
    // =====================================================================

    /**
     * 为指定业务日的交接记录生成 XML 报文（一交接一条），按订单号分目录归档。
     * 落盘与归档记录同一事务；中途失败清理已写文件，不留无记录孤儿文件。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<TelematicsXmlMessage> exportNotices(LocalDate bizDate) {
        LocalDateTime from = bizDate.atStartOfDay();
        LocalDateTime to = bizDate.plusDays(1).atStartOfDay();
        List<RentalHandover> handovers = handoverMapper.selectList(
                new LambdaQueryWrapper<RentalHandover>()
                        .ge(RentalHandover::getBizTime, from)
                        .lt(RentalHandover::getBizTime, to)
                        .orderByAsc(RentalHandover::getBizTime)
                        .orderByAsc(RentalHandover::getId));

        List<TelematicsXmlMessage> records = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        try {
            for (RentalHandover handover : handovers) {
                RentalOrder order = orderMapper.selectById(handover.getOrderId());
                Vehicle vehicle = order != null ? vehicleMapper.selectById(order.getVehicleId()) : null;
                HandoverNoticeMessage message = buildNotice(order, vehicle, handover);

                String xml;
                try {
                    xml = XmlCodec.serialize(message);
                } catch (XmlFormatException ex) {
                    throw new BizException("订单 " + message.getOrderNo() + " 报文生成失败：" + ex.getMessage());
                }

                TelematicsXmlMessage record = new TelematicsXmlMessage();
                record.setDirection(TelematicsXmlMessage.DIRECTION_OUT);
                record.setMsgType(TelematicsXmlMessage.TYPE_HANDOVER_NOTICE);
                record.setOrderNo(message.getOrderNo());
                record.setContent(xml);
                record.setContentHash(sha256(xml.getBytes(StandardCharsets.UTF_8)));
                record.setStatus(TelematicsXmlMessage.STATUS_SENT);
                record.setFilePath(writeArchive(message.getOrderNo(), "handover", xml).toString());
                written.add(Paths.get(record.getFilePath()));
                messageMapper.insert(record);
                records.add(record);
            }
        } catch (RuntimeException ex) {
            // 事务回滚：清理本次已写文件，避免无记录孤儿文件
            for (Path path : written) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 尽力清理，清理失败由运维按归档目录巡检
                }
            }
            throw ex;
        }
        return records;
    }

    /**
     * 重发：走原报文——逐字节沿用归档原件内容，不重新生成。
     * 新记录与原报文内容哈希一致即为凭证，resend_of 指向最初原件。
     */
    @Transactional(rollbackFor = Exception.class)
    public TelematicsXmlMessage resend(Long id) {
        TelematicsXmlMessage original = requireMessage(id);
        if (!TelematicsXmlMessage.DIRECTION_OUT.equals(original.getDirection())) {
            throw new BizException("仅出站报文可重发：" + id);
        }
        String xml = original.getContent();

        TelematicsXmlMessage record = new TelematicsXmlMessage();
        record.setDirection(TelematicsXmlMessage.DIRECTION_OUT);
        record.setMsgType(original.getMsgType());
        record.setOrderNo(original.getOrderNo());
        record.setContent(xml);
        record.setContentHash(sha256(xml.getBytes(StandardCharsets.UTF_8)));
        record.setStatus(TelematicsXmlMessage.STATUS_SENT);
        record.setResendOf(original.getResendOf() != null ? original.getResendOf() : original.getId());
        record.setFilePath(writeArchive(original.getOrderNo(), "resend", xml).toString());
        try {
            messageMapper.insert(record);
        } catch (RuntimeException ex) {
            try {
                Files.deleteIfExists(Paths.get(record.getFilePath()));
            } catch (IOException ignored) {
                // 尽力清理
            }
            throw ex;
        }
        return record;
    }

    // =====================================================================
    // 入站：接收平台回执报文
    // =====================================================================

    /**
     * 接收设备回执报文。同内容重复接收幂等（duplicate=true 返回首次结果）；
     * 解析失败/校验不过置 FAILED 进异常清单（错误带元素名），业务行零写入。
     */
    @Transactional(rollbackFor = Exception.class)
    public TelematicsXmlMessage receiveReport(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            throw new BizException("报文为空");
        }
        String hash = sha256(xml.getBytes(StandardCharsets.UTF_8));
        TelematicsXmlMessage existing = messageMapper.selectOne(
                new LambdaQueryWrapper<TelematicsXmlMessage>()
                        .eq(TelematicsXmlMessage::getDedupKey, "IN:" + hash));
        if (existing != null) {
            existing.setDuplicate(true);
            return existing;
        }

        TelematicsXmlMessage record = new TelematicsXmlMessage();
        record.setDirection(TelematicsXmlMessage.DIRECTION_IN);
        record.setMsgType(TelematicsXmlMessage.TYPE_DEVICE_REPORT);
        record.setContent(xml);
        record.setContentHash(hash);
        record.setDedupKey("IN:" + hash);

        DeviceReportMessage message;
        try {
            message = XmlCodec.parse(xml, DeviceReportMessage.class);
        } catch (XmlFormatException ex) {
            // 解析失败进异常清单
            record.setStatus(TelematicsXmlMessage.STATUS_FAILED);
            record.setErrorDetail(ex.getMessage());
            messageMapper.insert(record);
            return record;
        }

        List<String> errors = validate(message);
        if (!errors.isEmpty()) {
            // 校验不过：明确错误（元素名 + 原因），业务行零写入
            record.setStatus(TelematicsXmlMessage.STATUS_FAILED);
            record.setErrorDetail(String.join("\n", errors));
            messageMapper.insert(record);
            return record;
        }

        record.setStatus(TelematicsXmlMessage.STATUS_RECEIVED);
        messageMapper.insert(record);
        upsertDevice(record, message);
        return record;
    }

    /** 业务校验（与 CSV 导入同口径）：合法返回空列表，否则逐条错误（元素名 + 原因）。 */
    private List<String> validate(DeviceReportMessage message) {
        List<String> errors = new ArrayList<>();
        String plateNo = trim(message.getPlateNo());
        String deviceNo = trim(message.getDeviceNo());
        String deviceStatus = trim(message.getDeviceStatus());
        String remark = message.getRemark();

        if (plateNo == null || plateNo.isEmpty()) {
            errors.add("元素 <plateNo> 不能为空");
        } else if (plateNo.length() > 32) {
            errors.add("元素 <plateNo> 超长（最大 32）");
        }
        if (deviceNo == null || deviceNo.isEmpty()) {
            errors.add("元素 <deviceNo> 不能为空");
        } else if (deviceNo.length() > 64) {
            errors.add("元素 <deviceNo> 超长（最大 64）");
        }
        if (deviceStatus == null || !DEVICE_STATUSES.contains(deviceStatus)) {
            errors.add("元素 <deviceStatus> 非法：" + deviceStatus + "（仅支持 ONLINE/OFFLINE）");
        }
        if (remark != null && remark.length() > 500) {
            errors.add("元素 <remark> 超长（最大 500）");
        }
        if (errors.isEmpty()) {
            // 校验通过：按修剪后的值落库（与 CSV 导入口径一致）
            message.setPlateNo(plateNo);
            message.setDeviceNo(deviceNo);
            message.setDeviceStatus(deviceStatus);
        }
        return errors;
    }

    /** 业务键 (plate_no, device_no) 幂等：已存在按最新内容覆盖。 */
    private void upsertDevice(TelematicsXmlMessage record, DeviceReportMessage message) {
        TelematicsDeviceReport existing = deviceMapper.selectOne(
                new LambdaQueryWrapper<TelematicsDeviceReport>()
                        .eq(TelematicsDeviceReport::getPlateNo, message.getPlateNo())
                        .eq(TelematicsDeviceReport::getDeviceNo, message.getDeviceNo()));
        if (existing == null) {
            TelematicsDeviceReport report = new TelematicsDeviceReport();
            report.setPlateNo(message.getPlateNo());
            report.setDeviceNo(message.getDeviceNo());
            report.setDeviceStatus(message.getDeviceStatus());
            report.setReportTime(message.getReportTime());
            report.setRemark(message.getRemark());
            report.setXmlMessageId(record.getId());
            deviceMapper.insert(report);
        } else {
            existing.setDeviceStatus(message.getDeviceStatus());
            existing.setReportTime(message.getReportTime());
            existing.setRemark(message.getRemark());
            existing.setXmlMessageId(record.getId());
            deviceMapper.updateById(existing);
        }
    }

    // =====================================================================
    // 查询
    // =====================================================================

    public List<TelematicsXmlMessage> listMessages(String direction, String orderNo) {
        String dir = trim(direction);
        String order = trim(orderNo);
        return messageMapper.selectList(new LambdaQueryWrapper<TelematicsXmlMessage>()
                .eq(dir != null && !dir.isEmpty(), TelematicsXmlMessage::getDirection, dir)
                .eq(order != null && !order.isEmpty(), TelematicsXmlMessage::getOrderNo, order)
                .orderByDesc(TelematicsXmlMessage::getId));
    }

    public TelematicsXmlMessage requireMessage(Long id) {
        TelematicsXmlMessage record = messageMapper.selectById(id);
        if (record == null) {
            throw new BizException("报文不存在：" + id);
        }
        return record;
    }

    /** 异常清单：入站解析/校验失败的报文。 */
    public List<TelematicsXmlMessage> listExceptions() {
        return messageMapper.selectList(new LambdaQueryWrapper<TelematicsXmlMessage>()
                .eq(TelematicsXmlMessage::getDirection, TelematicsXmlMessage.DIRECTION_IN)
                .eq(TelematicsXmlMessage::getStatus, TelematicsXmlMessage.STATUS_FAILED)
                .orderByDesc(TelematicsXmlMessage::getId));
    }

    // =====================================================================
    // 上线前回归：网点上一期租车单往返无损校验（只读）
    // =====================================================================

    /**
     * 用网点指定一期（缺省上一月）租车单的交接数据跑回归：逐交接生成报文、
     * 解析回报、逐字段比对，验证往返转换无丢失。只读校验，不落库、不写文件。
     */
    public XmlRegressionReport regressionCheck(String branchCode, YearMonth period) {
        String branch = trim(branchCode);
        if (branch == null || branch.isEmpty()) {
            throw new BizException("branchCode 不能为空");
        }
        YearMonth target = period != null ? period : YearMonth.now().minusMonths(1);
        LocalDateTime from = target.atDay(1).atStartOfDay();
        LocalDateTime to = target.plusMonths(1).atDay(1).atStartOfDay();

        List<RentalOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<RentalOrder>()
                .eq(RentalOrder::getBranchCode, branch)
                .ge(RentalOrder::getBizTime, from)
                .lt(RentalOrder::getBizTime, to)
                .orderByAsc(RentalOrder::getId));

        XmlRegressionReport report = new XmlRegressionReport();
        report.setBranchCode(branch);
        report.setPeriod(target.toString());
        report.setTotalOrders(orders.size());

        for (RentalOrder order : orders) {
            List<RentalHandover> handovers = handoverMapper.selectList(
                    new LambdaQueryWrapper<RentalHandover>()
                            .eq(RentalHandover::getOrderId, order.getId())
                            .orderByAsc(RentalHandover::getId));
            if (handovers.isEmpty()) {
                continue;
            }
            report.setOrdersWithHandover(report.getOrdersWithHandover() + 1);
            Vehicle vehicle = vehicleMapper.selectById(order.getVehicleId());
            for (RentalHandover handover : handovers) {
                report.setCheckedMessages(report.getCheckedMessages() + 1);
                String failure = roundTripOnce(buildNotice(order, vehicle, handover));
                if (failure == null) {
                    report.setPassed(report.getPassed() + 1);
                } else {
                    report.setFailed(report.getFailed() + 1);
                    report.getFailures().add(new XmlRegressionReport.Failure(
                            order.getOrderNo(), handover.getId(), failure));
                }
            }
        }
        return report;
    }

    /** 单条往返校验：生成 -> 解析 -> 逐字段比对。无丢失返回 null，否则返回原因。 */
    private String roundTripOnce(HandoverNoticeMessage message) {
        String xml;
        try {
            xml = XmlCodec.serialize(message);
        } catch (XmlFormatException ex) {
            return "报文生成失败：" + ex.getMessage();
        }
        HandoverNoticeMessage back;
        try {
            back = XmlCodec.parse(xml, HandoverNoticeMessage.class);
        } catch (XmlFormatException ex) {
            return "报文回读失败：" + ex.getMessage();
        }
        for (Field field : HandoverNoticeMessage.class.getDeclaredFields()) {
            XmlField mapping = field.getAnnotation(XmlField.class);
            if (mapping == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object expected = field.get(message);
                Object actual = field.get(back);
                if (!Objects.equals(expected, actual)) {
                    return "元素 <" + mapping.name() + "> 往返不一致：发出 " + expected + " / 回读 " + actual;
                }
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("字段不可读：" + field.getName(), ex);
            }
        }
        return null;
    }

    // ---- 内部辅助 ----

    /** 由交接数据装配出站报文（导出与回归校验同一装配口径）。 */
    private HandoverNoticeMessage buildNotice(RentalOrder order, Vehicle vehicle, RentalHandover handover) {
        HandoverNoticeMessage message = new HandoverNoticeMessage();
        message.setOrderNo(order != null ? order.getOrderNo() : null);
        message.setPlateNo(vehicle != null ? vehicle.getPlateNo() : null);
        message.setHandoverType(handover.getType());
        message.setHandoverTime(handover.getBizTime());
        message.setFuelReading(handover.getFuelReading());
        message.setDamagePhotos(handover.getDamagePhotos());
        message.setRemark(handover.getRemark());
        return message;
    }

    /** 报文归档落盘：<xmlDir>/<订单号>/<前缀>_<时间戳>_<随机4位>.xml，返回路径。 */
    private Path writeArchive(String orderNo, String prefix, String xml) {
        String dirName = orderNo == null ? "unknown" : orderNo.replaceAll("[^A-Za-z0-9._-]", "_");
        String fileName = prefix + "_" + LocalDateTime.now().format(FILE_TIME_FMT)
                + "_" + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)) + ".xml";
        Path path = Paths.get(properties.getXmlDir(), dirName, fileName);
        try {
            Files.createDirectories(path.getParent());
            // XML 报文固定 UTF-8（报文声明即 UTF-8）；evops.telematics.charset 仅作用于 CSV 往来
            Files.write(path, xml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BizException("报文归档写入失败：" + ex.getMessage());
        }
        return path;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
