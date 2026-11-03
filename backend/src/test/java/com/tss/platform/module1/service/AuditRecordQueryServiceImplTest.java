package com.tss.platform.module1.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tss.platform.module1.dto.LogItemVO;
import com.tss.platform.module1.dto.LogListQueryDTO;
import com.tss.platform.module1.entity.AuditRecord;
import com.tss.platform.module1.mapper.AuditRecordMapper;
import com.tss.platform.module1.mapper.UserMapper;
import com.tss.platform.module1.service.impl.AuditRecordQueryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditRecordQueryServiceImplTest {

    private AuditRecordMapper mapper;
    private UserMapper userMapper;
    private AuditRecordQueryServiceImpl service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(AuditRecordMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, AuditRecord.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AuditRecordMapper.class);
        userMapper = mock(UserMapper.class);
        service = new AuditRecordQueryServiceImpl();
        ReflectionTestUtils.setField(service, "auditRecordMapper", mapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void appliesServerScopeFiltersAndHidesIp() {
        AuditRecord record = record();
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<AuditRecord> page = invocation.getArgument(0);
            page.setRecords(List.of(record));
            page.setTotal(1);
            return page;
        });
        LogListQueryDTO query = new LogListQueryDTO();
        query.setForceUserId(7);
        query.setOperateType("UPLOAD");
        query.setResult("failed");
        query.setContent("MODEL_UPLOAD");
        query.setOperateTimeStart(LocalDateTime.of(2026, 8, 1, 0, 0));
        query.setOperateTimeEnd(LocalDateTime.of(2026, 8, 31, 23, 59));
        query.setHideIp(true);

        Page<LogItemVO> result = (Page<LogItemVO>) service.queryLogPage(query);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).singleElement().satisfies(row -> {
            assertThat(row.getUsername()).isEqualTo("alice");
            assertThat(row.getIp()).isNull();
            assertThat(row.getOperateType()).isEqualTo("UPLOAD");
        });
        ArgumentCaptor<LambdaQueryWrapper<AuditRecord>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), wrapperCaptor.capture());
        LambdaQueryWrapper<AuditRecord> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("user_id", "action_type", "result", "created_at", "ORDER BY");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(7, "UPLOAD", "FAILED", "%MODEL_UPLOAD%");
    }

    @Test
    void normalAdministratorGetsEmptyPageWhenNoNormalUsersExist() {
        when(userMapper.selectList(any())).thenReturn(List.of());
        LogListQueryDTO query = new LogListQueryDTO();
        query.setForceNormalUsersOnly(true);
        query.setPageNum(2);
        query.setPageSize(20);

        var result = service.queryLogPage(query);

        assertThat(result.getTotal()).isZero();
        assertThat(result.getCurrent()).isEqualTo(2);
        assertThat(result.getRecords()).isEmpty();
        verify(mapper, never()).selectPage(any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void capsPageSizeForDefenceInDepth() {
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LogListQueryDTO query = new LogListQueryDTO();
        query.setPageSize(Integer.MAX_VALUE);

        service.queryLogPage(query);

        ArgumentCaptor<Page<AuditRecord>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10_000);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void personalQueryKeepsAllActionTypesEvenWhenLegacyLogTypeIsPresent() {
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LogListQueryDTO query = new LogListQueryDTO();
        query.setForceUserId(7);
        query.setLogType("operation");

        service.queryLogPage(query);

        ArgumentCaptor<LambdaQueryWrapper<AuditRecord>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
                .contains("user_id")
                .doesNotContain("action_type");
    }

    private static AuditRecord record() {
        AuditRecord record = new AuditRecord();
        record.setId(1L);
        record.setUserId(7);
        record.setUsername("alice");
        record.setActionType("UPLOAD");
        record.setObjectType("MODEL");
        record.setObjectId("model-1");
        record.setResult("FAILED");
        record.setFailReason("bad file");
        record.setDetail("MODEL_UPLOAD");
        record.setIpAddress("10.0.0.7");
        record.setCreatedAt(LocalDateTime.of(2026, 8, 27, 1, 2, 3));
        return record;
    }
}
