package com.tss.platform.module1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tss.platform.module1.entity.AuditRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditRecordMapper extends BaseMapper<AuditRecord> {
}
