package com.tss.platform.module1.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.common.AuditResult;
import com.tss.platform.module1.dto.AuditRecordCommand;
import com.tss.platform.module1.entity.AuditRecord;
import com.tss.platform.module1.mapper.AuditRecordMapper;
import com.tss.platform.module1.service.AuditRecordService;
import com.tss.platform.module1.util.AuditDetailSanitizer;
import com.tss.platform.module1.util.ClientIpUtil;
import com.tss.platform.module1.util.DesensitizationUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

@Service
public class AuditRecordServiceImpl implements AuditRecordService {

    private static final Logger SYSTEM_LOG = LoggerFactory.getLogger("SYSTEM_LOG");

    @Resource
    private AuditRecordMapper auditRecordMapper;

    @Override
    public void record(AuditRecordCommand command) {
        if (command == null || command.getActionType() == null || command.getResult() == null) {
            SYSTEM_LOG.error("审计记录写入失败: 缺少必要字段 actionType/result");
            return;
        }
        try {
            AuditRecord record = new AuditRecord();
            fillOperator(record, command);
            record.setActionType(command.getActionType().name());
            record.setObjectType(command.getObjectType() != null
                    ? command.getObjectType().name()
                    : AuditObjectType.UNKNOWN.name());
            record.setObjectId(trimTo(command.getObjectId(), 128));
            record.setResult(command.getResult().name());
            record.setFailReason(AuditDetailSanitizer.sanitizeFailReason(command.getFailReason()));
            HttpServletRequest request = currentRequest();
            String ipAddress = command.getIpAddress() != null
                    ? command.getIpAddress()
                    : ClientIpUtil.resolve(request);
            String requestId = command.getRequestId() != null
                    ? command.getRequestId()
                    : requestId(request);
            record.setIpAddress(trimTo(ipAddress, 64));
            record.setRequestId(trimTo(requestId, 64));
            record.setDetail(AuditDetailSanitizer.sanitize(command.getDetail()));
            record.setCreatedAt(LocalDateTime.now());

            if (record.getUsername() == null || record.getUsername().isBlank()) {
                record.setUsername("unknown");
            }

            int rows = auditRecordMapper.insert(record);
            if (rows <= 0) {
                SYSTEM_LOG.error("审计记录写入失败: insert 返回 0, action={}, result={}",
                        record.getActionType(), record.getResult());
            }
        } catch (Exception e) {
            // 审计失败不得影响业务，但必须留下明确错误日志
            SYSTEM_LOG.error("审计记录写入异常: action={}, error={}",
                    command.getActionType(), e.getMessage(), e);
        }
    }

    private void fillOperator(AuditRecord record, AuditRecordCommand command) {
        try {
            if (StpUtil.isLogin()) {
                record.setUserId(StpUtil.getLoginIdAsInt());
                Object username = StpUtil.getTokenSession().get("username");
                Object roleId = StpUtil.getTokenSession().get("roleId");
                record.setUsername(username != null ? username.toString() : "user#" + record.getUserId());
                record.setOperatorRole(roleId != null ? mapRole(roleId) : null);
                return;
            }
        } catch (Exception ignored) {
            // 未登录或会话异常，走 fallback
        }

        // 登录失败等场景：允许使用脱敏后的 fallback 账号，userId 可为 null
        record.setUserId(command.getFallbackUserId());
        String fallback = command.getFallbackUsername();
        if (fallback != null && !fallback.isBlank()) {
            if (command.getActionType() == AuditActionType.LOGIN
                    && command.getResult() == AuditResult.FAILED) {
                record.setUsername(DesensitizationUtil.maskUsername(fallback));
            } else {
                record.setUsername(fallback);
            }
        } else {
            record.setUsername("anonymous");
        }
    }

    private static String mapRole(Object roleId) {
        try {
            int id = Integer.parseInt(roleId.toString());
            return switch (id) {
                case 1 -> "超管";
                case 2 -> "普通管理员";
                case 3 -> "普通用户";
                default -> "role#" + id;
            };
        } catch (Exception e) {
            return String.valueOf(roleId);
        }
    }

    private static String trimTo(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private static String requestId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getHeader("X-Correlation-ID");
        }
        return requestId;
    }
}
