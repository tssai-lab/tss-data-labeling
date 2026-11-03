package com.tss.platform.module1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tss.platform.module1.common.AuditActionType;
import com.tss.platform.module1.dto.LogItemVO;
import com.tss.platform.module1.dto.LogListQueryDTO;
import com.tss.platform.module1.entity.AuditRecord;
import com.tss.platform.module1.entity.User;
import com.tss.platform.module1.mapper.AuditRecordMapper;
import com.tss.platform.module1.mapper.UserMapper;
import com.tss.platform.module1.service.AuditRecordQueryService;
import com.tss.platform.module1.util.AuditRecordConverter;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@Service
public class AuditRecordQueryServiceImpl implements AuditRecordQueryService {

    private static final int MAX_PAGE_SIZE = 10_000;

    @Resource
    private AuditRecordMapper auditRecordMapper;

    @Resource
    private UserMapper userMapper;

    @Override
    public IPage<LogItemVO> queryLogPage(LogListQueryDTO query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int requestedSize = query.getPageSize() == null || query.getPageSize() < 1 ? 10 : query.getPageSize();
        int pageSize = Math.min(requestedSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<AuditRecord> wrapper = buildWrapper(query);
        if (wrapper == null) {
            Page<LogItemVO> empty = new Page<>(pageNum, pageSize, 0);
            empty.setRecords(Collections.emptyList());
            return empty;
        }

        IPage<AuditRecord> raw = auditRecordMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        boolean hideIp = Boolean.TRUE.equals(query.getHideIp());
        Page<LogItemVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(record -> {
            LogItemVO vo = AuditRecordConverter.toVo(record);
            if (hideIp) {
                vo.setIp(null);
            }
            return vo;
        }).toList());
        return result;
    }

    private LambdaQueryWrapper<AuditRecord> buildWrapper(LogListQueryDTO query) {
        LambdaQueryWrapper<AuditRecord> wrapper = new LambdaQueryWrapper<>();

        if (query.getForceUserId() != null) {
            wrapper.eq(AuditRecord::getUserId, query.getForceUserId());
        } else if (StringUtils.hasText(query.getUsername())) {
            wrapper.like(AuditRecord::getUsername, query.getUsername().trim());
        }

        if (Boolean.TRUE.equals(query.getForceNormalUsersOnly())) {
            List<Integer> normalUserIds = listNormalUserIds();
            if (normalUserIds.isEmpty()) {
                return null;
            }
            wrapper.in(AuditRecord::getUserId, normalUserIds);
        }

        // 个人中心始终展示本人的全部六类记录，沿用旧日志页面语义。
        boolean personal = query.getForceUserId() != null;
        if (!personal) {
            if ("system".equalsIgnoreCase(query.getLogType())) {
                wrapper.eq(AuditRecord::getActionType, AuditActionType.LOGIN.name());
            } else if ("operation".equalsIgnoreCase(query.getLogType())) {
                wrapper.ne(AuditRecord::getActionType, AuditActionType.LOGIN.name());
            }
        }

        List<String> actions = AuditRecordConverter.resolveActionTypes(query.getOperateType());
        if (actions != null) {
            wrapper.in(AuditRecord::getActionType, actions);
        }

        if ("success".equalsIgnoreCase(query.getResult()) || "SUCCESS".equalsIgnoreCase(query.getResult())) {
            wrapper.eq(AuditRecord::getResult, "SUCCESS");
        } else if ("failed".equalsIgnoreCase(query.getResult())
                || "fail".equalsIgnoreCase(query.getResult())
                || "FAILED".equalsIgnoreCase(query.getResult())) {
            wrapper.eq(AuditRecord::getResult, "FAILED");
        }

        if (StringUtils.hasText(query.getIp()) && !Boolean.TRUE.equals(query.getHideIp())) {
            wrapper.like(AuditRecord::getIpAddress, query.getIp().trim());
        }
        if (StringUtils.hasText(query.getContent())) {
            String keyword = query.getContent().trim();
            wrapper.and(nested -> nested
                    .like(AuditRecord::getDetail, keyword)
                    .or().like(AuditRecord::getFailReason, keyword)
                    .or().like(AuditRecord::getObjectId, keyword)
                    .or().like(AuditRecord::getObjectType, keyword));
        }
        if (query.getOperateTimeStart() != null) {
            wrapper.ge(AuditRecord::getCreatedAt, query.getOperateTimeStart());
        }
        if (query.getOperateTimeEnd() != null) {
            wrapper.le(AuditRecord::getCreatedAt, query.getOperateTimeEnd());
        }

        wrapper.orderByDesc(AuditRecord::getCreatedAt).orderByDesc(AuditRecord::getId);
        return wrapper;
    }

    private List<Integer> listNormalUserIds() {
        return userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getRoleId, 3)
                        .isNull(User::getDeletedAt)
        ).stream().map(User::getId).toList();
    }
}
