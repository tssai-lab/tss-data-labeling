package com.tss.platform.module1.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tss.platform.module1.dto.LogItemVO;
import com.tss.platform.module1.dto.LogListQueryDTO;
import com.tss.platform.module1.dto.OperationLogQueryDTO;
import com.tss.platform.module1.entity.OperationLog;

public interface OperationLogService extends IService<OperationLog> {
    boolean recordLog(OperationLog log);
    IPage<OperationLog> queryLogs(OperationLogQueryDTO queryDTO);
    IPage<LogItemVO> queryLogPage(LogListQueryDTO queryDTO);

    /** 普通用户（role_id=3）ID 列表，供普管日志范围裁剪 */
    java.util.List<Integer> listNormalUserIds();
}
