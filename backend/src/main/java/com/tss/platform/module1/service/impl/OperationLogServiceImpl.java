package com.tss.platform.module1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tss.platform.module1.dto.LogItemVO;
import com.tss.platform.module1.dto.LogListQueryDTO;
import com.tss.platform.module1.dto.OperationLogQueryDTO;
import com.tss.platform.module1.entity.OperationLog;
import com.tss.platform.module1.entity.User;
import com.tss.platform.module1.mapper.OperationLogMapper;
import com.tss.platform.module1.mapper.UserMapper;
import com.tss.platform.module1.service.OperationLogService;
import com.tss.platform.service.SystemConfigService;
import com.tss.platform.module1.util.OperationLogConverter;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog> implements OperationLogService {

    @Resource
    private UserMapper userMapper;

    @Resource
    private SystemConfigService systemConfigService;

    @Override
    public boolean recordLog(OperationLog log) {
        log.setOperationTime(java.time.LocalDateTime.now());
        boolean ok = save(log);
        if (ok) {
            try {
                systemConfigService.trimUserExcessLogs(log.getUserId());
            } catch (Exception ignored) {
                // 裁剪失败不影响主流程写日志
            }
        }
        return ok;
    }

    @Override
    public IPage<OperationLog> queryLogs(OperationLogQueryDTO queryDTO) {
        int pageNum = queryDTO.getPage() == null || queryDTO.getPage() < 1 ? 1 : queryDTO.getPage();
        int pageSize = queryDTO.getSize() == null || queryDTO.getSize() < 1 ? 10 : queryDTO.getSize();
        Page<OperationLog> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();

        if (queryDTO.getForceOperatorUserIds() != null) {
            if (queryDTO.getForceOperatorUserIds().isEmpty()) {
                wrapper.eq(OperationLog::getUserId, -1);
            } else {
                wrapper.in(OperationLog::getUserId, queryDTO.getForceOperatorUserIds());
            }
        } else if (queryDTO.getUserId() != null) {
            wrapper.eq(OperationLog::getUserId, queryDTO.getUserId());
        }
        if (StringUtils.hasText(queryDTO.getUsername())) {
            wrapper.like(OperationLog::getUserName, queryDTO.getUsername().trim());
        }
        applyOperationTypeFilter(wrapper, queryDTO.getOperationType());
        if (StringUtils.hasText(queryDTO.getOperationObj())) {
            wrapper.eq(OperationLog::getOperationObj, queryDTO.getOperationObj().trim());
        }
        applyStatusFilter(wrapper, queryDTO.getStatus());
        if (StringUtils.hasText(queryDTO.getIpAddress())) {
            wrapper.like(OperationLog::getIpAddress, queryDTO.getIpAddress().trim());
        }
        if (StringUtils.hasText(queryDTO.getRemarksKeyword())) {
            wrapper.like(OperationLog::getRemarks, queryDTO.getRemarksKeyword().trim());
        }
        if (queryDTO.getStartTime() != null) {
            wrapper.ge(OperationLog::getOperationTime, queryDTO.getStartTime());
        }
        if (queryDTO.getEndTime() != null) {
            wrapper.le(OperationLog::getOperationTime, queryDTO.getEndTime());
        }

        wrapper.orderByDesc(OperationLog::getOperationTime);
        return page(page, wrapper);
    }

    @Override
    public IPage<LogItemVO> queryLogPage(LogListQueryDTO query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 10 : query.getPageSize();

        Page<OperationLog> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<OperationLog> wrapper = buildLogListWrapper(query);
        if (wrapper == null) {
            Page<LogItemVO> empty = new Page<>(pageNum, pageSize, 0);
            empty.setRecords(Collections.emptyList());
            return empty;
        }

        IPage<OperationLog> raw = page(page, wrapper);
        boolean hideIp = Boolean.TRUE.equals(query.getHideIp());
        Page<LogItemVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(log -> {
            LogItemVO vo = OperationLogConverter.toVo(log);
            if (hideIp) {
                vo.setIp(null);
            }
            return vo;
        }).toList());
        return result;
    }

    private LambdaQueryWrapper<OperationLog> buildLogListWrapper(LogListQueryDTO query) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();

        if (query.getForceUserId() != null) {
            wrapper.eq(OperationLog::getUserId, query.getForceUserId());
        } else if (StringUtils.hasText(query.getCurrentUsername())) {
            wrapper.eq(OperationLog::getUserName, query.getCurrentUsername().trim());
        } else if (StringUtils.hasText(query.getUsername())) {
            wrapper.like(OperationLog::getUserName, query.getUsername().trim());
        }

        if (Boolean.TRUE.equals(query.getForceNormalUsersOnly())
                || "normal_admin".equals(query.getCurrentUserRole())) {
            List<Integer> normalUserIds = listNormalUserIds();
            if (normalUserIds.isEmpty()) {
                return null;
            }
            wrapper.in(OperationLog::getUserId, normalUserIds);
        }

        // 个人中心查本人记录时不按 logType 拆分，避免登录等系统类记录被漏掉
        boolean personal = query.getForceUserId() != null || StringUtils.hasText(query.getCurrentUsername());
        if (!personal) {
            if ("system".equals(query.getLogType())) {
                wrapper.in(OperationLog::getOperationType, "5", "6");
            } else if ("operation".equals(query.getLogType())) {
                wrapper.notIn(OperationLog::getOperationType, "5", "6");
            }
        }

        List<String> typeCodes = OperationLogConverter.resolveOperationTypeCodes(query.getOperateType());
        if (typeCodes != null) {
            wrapper.in(OperationLog::getOperationType, typeCodes);
        }

        if ("success".equalsIgnoreCase(query.getResult()) || "SUCCESS".equalsIgnoreCase(query.getResult())) {
            wrapper.eq(OperationLog::getStatus, "SUCCESS");
        } else if ("failed".equalsIgnoreCase(query.getResult())
                || "fail".equalsIgnoreCase(query.getResult())
                || "FAIL".equalsIgnoreCase(query.getResult())) {
            wrapper.eq(OperationLog::getStatus, "FAIL");
        }

        if (StringUtils.hasText(query.getIp()) && !Boolean.TRUE.equals(query.getHideIp())) {
            wrapper.like(OperationLog::getIpAddress, query.getIp().trim());
        }
        if (StringUtils.hasText(query.getContent())) {
            wrapper.like(OperationLog::getRemarks, query.getContent().trim());
        }
        if (query.getOperateTimeStart() != null) {
            wrapper.ge(OperationLog::getOperationTime, query.getOperateTimeStart());
        }
        if (query.getOperateTimeEnd() != null) {
            wrapper.le(OperationLog::getOperationTime, query.getOperateTimeEnd());
        }

        wrapper.orderByDesc(OperationLog::getOperationTime);
        return wrapper;
    }

    public List<Integer> listNormalUserIds() {
        return userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getRoleId, 3)
                        .isNull(User::getDeletedAt)
        ).stream().map(User::getId).toList();
    }

    private void applyOperationTypeFilter(LambdaQueryWrapper<OperationLog> wrapper, String operationType) {
        if (!StringUtils.hasText(operationType)) {
            return;
        }
        String raw = operationType.trim();
        List<String> codes = OperationLogConverter.resolveOperationTypeCodes(raw);
        if (codes != null) {
            wrapper.in(OperationLog::getOperationType, codes);
            return;
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if ("LOGIN".equals(upper)) {
            wrapper.in(OperationLog::getOperationType, "5", "6");
            return;
        }
        if ("LOGOUT".equals(upper)) {
            wrapper.eq(OperationLog::getOperationType, "6");
            return;
        }
        wrapper.eq(OperationLog::getOperationType, raw);
    }

    private void applyStatusFilter(LambdaQueryWrapper<OperationLog> wrapper, String status) {
        if (!StringUtils.hasText(status)) {
            return;
        }
        String s = status.trim().toUpperCase(Locale.ROOT);
        if ("SUCCESS".equals(s) || "OK".equals(s)) {
            wrapper.eq(OperationLog::getStatus, "SUCCESS");
        } else if ("FAIL".equals(s) || "FAILED".equals(s) || "ERROR".equals(s)) {
            wrapper.eq(OperationLog::getStatus, "FAIL");
        } else {
            wrapper.eq(OperationLog::getStatus, status.trim());
        }
    }
}
