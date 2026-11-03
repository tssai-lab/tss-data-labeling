package com.tss.platform.module1.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.tss.platform.module1.dto.LogItemVO;
import com.tss.platform.module1.dto.LogListQueryDTO;

/**
 * 合同六类用户行为日志的统一只读查询入口。
 */
public interface AuditRecordQueryService {

    IPage<LogItemVO> queryLogPage(LogListQueryDTO query);
}
