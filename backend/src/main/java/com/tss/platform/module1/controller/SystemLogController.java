package com.tss.platform.module1.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.tss.platform.module1.common.Result;
import com.tss.platform.module1.dto.LogItemVO;
import com.tss.platform.module1.dto.LogListQueryDTO;
import com.tss.platform.module1.service.AuditRecordQueryService;
import com.tss.platform.module1.util.LogAccessScope;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/log")
public class SystemLogController {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_LIST_PAGE_SIZE = 200;
    private static final int MAX_EXPORT_PAGE_SIZE = 10_000;

    @Resource
    private AuditRecordQueryService auditRecordQueryService;

    @GetMapping("/list")
    public Result<Map<String, Object>> getLogList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String operateType,
            @RequestParam(required = false) List<String> operateTime,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String logType,
            @RequestParam(required = false) String currentUserRole,
            @RequestParam(required = false) String currentUsername,
            @RequestParam(required = false) String content) {

        LogAccessScope scope = resolveScope();
        Result<Map<String, Object>> authError = checkLogAccess(scope, currentUsername);
        if (authError != null) {
            return authError;
        }

        LogListQueryDTO query = new LogListQueryDTO();
        query.setPageNum(normalizePageNum(pageNum));
        query.setPageSize(normalizePageSize(pageSize, MAX_LIST_PAGE_SIZE));
        query.setUsername(username);
        query.setOperateType(operateType);
        query.setIp(ip);
        query.setResult(result);
        query.setLogType(logType);
        query.setContent(content);
        Result<Map<String, Object>> timeError = parseOperateTime(operateTime, query);
        if (timeError != null) {
            return timeError;
        }
        applyScope(query, scope, currentUsername);

        IPage<LogItemVO> pageResult = auditRecordQueryService.queryLogPage(query);

        Map<String, Object> data = new HashMap<>();
        data.put("list", pageResult.getRecords());
        data.put("total", pageResult.getTotal());
        data.put("pageNum", query.getPageNum());
        data.put("pageSize", query.getPageSize());

        return Result.success(data, "查询成功");
    }

    @GetMapping("/export")
    public Result<Map<String, Object>> exportLogs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10000") Integer pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String operateType,
            @RequestParam(required = false) List<String> operateTime,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String logType,
            @RequestParam(required = false) String currentUserRole,
            @RequestParam(required = false) String currentUsername,
            @RequestParam(required = false) String content) {

        LogAccessScope scope = resolveScope();
        if (scope != LogAccessScope.ALL) {
            return Result.noAuth("无权限导出日志");
        }

        LogListQueryDTO query = new LogListQueryDTO();
        query.setPageNum(normalizePageNum(pageNum));
        query.setPageSize(normalizePageSize(pageSize, MAX_EXPORT_PAGE_SIZE));
        query.setUsername(username);
        query.setOperateType(operateType);
        query.setIp(ip);
        query.setResult(result);
        query.setLogType(logType);
        query.setContent(content);
        Result<Map<String, Object>> timeError = parseOperateTime(operateTime, query);
        if (timeError != null) {
            return timeError;
        }

        IPage<LogItemVO> pageResult = auditRecordQueryService.queryLogPage(query);
        Map<String, Object> data = new HashMap<>();
        data.put("list", pageResult.getRecords());
        data.put("total", pageResult.getTotal());
        return Result.success(data, "导出成功（共 " + pageResult.getTotal() + " 条）");
    }

    @GetMapping("/types")
    public Result<List<Map<String, String>>> getLogTypes() {
        List<Map<String, String>> types = List.of(
                Map.of("key", "UPLOAD", "label", "上传"),
                Map.of("key", "DELETE", "label", "删除"),
                Map.of("key", "TRAIN", "label", "训练"),
                Map.of("key", "INFERENCE", "label", "推理"),
                Map.of("key", "LOGIN", "label", "登录/退出"),
                Map.of("key", "PERMISSION_CHANGE", "label", "权限变更")
        );
        return Result.success(types, "查询成功");
    }

    @GetMapping("/objects")
    public Result<List<Map<String, String>>> getLogObjects() {
        List<Map<String, String>> objects = List.of(
                Map.of("key", "USER", "label", "用户"),
                Map.of("key", "MODEL", "label", "模型"),
                Map.of("key", "DATASET", "label", "数据集"),
                Map.of("key", "TRAINING_CODE", "label", "训练代码"),
                Map.of("key", "TRAINING_PLAN", "label", "训练方案"),
                Map.of("key", "INFERENCE_SCRIPT", "label", "推理脚本"),
                Map.of("key", "TRAIN_TASK", "label", "训练任务"),
                Map.of("key", "INFERENCE_TASK", "label", "推理任务")
        );
        return Result.success(objects, "查询成功");
    }

    private Result<Map<String, Object>> checkLogAccess(LogAccessScope scope, String currentUsername) {
        if (scope == LogAccessScope.SELF_ONLY) {
            if (currentUsername != null && !currentUsername.isBlank()) {
                String loginUser = getLoginUsername();
                if (loginUser == null || !loginUser.equals(currentUsername.trim())) {
                    return Result.noAuth("仅可查看本人操作记录");
                }
            }
            return null;
        }
        if (currentUsername != null && !currentUsername.isBlank()) {
            String loginUser = getLoginUsername();
            if (loginUser == null || !loginUser.equals(currentUsername.trim())) {
                return Result.noAuth("仅可查看本人操作记录");
            }
        }
        return null;
    }

    private void applyScope(LogListQueryDTO query, LogAccessScope scope, String currentUsername) {
        query.setCurrentUserRole(null);

        if (scope == LogAccessScope.SELF_ONLY
                || (currentUsername != null && !currentUsername.isBlank())) {
            query.setForceUserId(StpUtil.getLoginIdAsInt());
            query.setCurrentUsername(null);
            query.setUsername(null);
            query.setForceNormalUsersOnly(false);
            query.setHideIp(!isSuperAdmin());
            return;
        }

        boolean normalOnly = scope == LogAccessScope.NORMAL_USERS_ONLY;
        query.setForceNormalUsersOnly(normalOnly);
        query.setHideIp(!isSuperAdmin());
        if (!isSuperAdmin()) {
            query.setIp(null);
        }
    }

    private Result<Map<String, Object>> parseOperateTime(List<String> operateTime, LogListQueryDTO query) {
        if (operateTime == null || operateTime.size() < 2) {
            return null;
        }
        try {
            String start = operateTime.get(0);
            String end = operateTime.get(1);
            if (start != null && !start.isBlank()) {
                query.setOperateTimeStart(LocalDateTime.parse(start, TIME_FORMATTER));
            }
            if (end != null && !end.isBlank()) {
                query.setOperateTimeEnd(LocalDateTime.parse(end, TIME_FORMATTER));
            }
            if (query.getOperateTimeStart() != null && query.getOperateTimeEnd() != null
                    && query.getOperateTimeStart().isAfter(query.getOperateTimeEnd())) {
                return Result.fail("开始时间不能晚于结束时间");
            }
            return null;
        } catch (RuntimeException exception) {
            return Result.fail("时间格式错误，应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizePageSize(Integer pageSize, int max) {
        int normalized = pageSize == null || pageSize < 1 ? 10 : pageSize;
        return Math.min(normalized, max);
    }

    private LogAccessScope resolveScope() {
        Integer roleId = (Integer) StpUtil.getTokenSession().get("roleId");
        if (roleId != null && roleId == 1) {
            return LogAccessScope.ALL;
        }
        if (roleId != null && roleId == 2) {
            return LogAccessScope.NORMAL_USERS_ONLY;
        }
        return LogAccessScope.SELF_ONLY;
    }

    private boolean isSuperAdmin() {
        Integer roleId = (Integer) StpUtil.getTokenSession().get("roleId");
        return roleId != null && roleId == 1;
    }

    private String getLoginUsername() {
        Object username = StpUtil.getTokenSession().get("username");
        return username != null ? username.toString() : null;
    }
}
