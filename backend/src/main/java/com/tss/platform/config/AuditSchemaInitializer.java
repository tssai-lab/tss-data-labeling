package com.tss.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 幂等初始化 audit_records 表（CREATE IF NOT EXISTS）。
 * 本地 demo 未统一使用 Flyway 历史时，用此方式保证迁移可重复执行。
 */
@Component
public class AuditSchemaInitializer implements ApplicationRunner {

    private static final Logger SYSTEM_LOG = LoggerFactory.getLogger("SYSTEM_LOG");

    private final DataSource dataSource;

    public AuditSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.setContinueOnError(false);
            populator.setSeparator(";");
            populator.addScript(new ClassPathResource("db/audit/create_audit_records.sql"));
            populator.execute(dataSource);
            SYSTEM_LOG.info("audit_records 表结构已校验/初始化");
        } catch (Exception e) {
            SYSTEM_LOG.error("audit_records 表初始化失败: {}", e.getMessage(), e);
        }
    }
}
