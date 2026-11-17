package com.tss.labeling.ls;

import com.tss.labeling.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class SourceExportWorker {
    private final JdbcTemplate db;private final SourceExportService service;private final boolean enabled;
    public SourceExportWorker(JdbcTemplate db,SourceExportService service,@Value("${labeling.exports.worker-enabled:true}")boolean enabled){
        this.db=db;this.service=service;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${labeling.exports.poll-delay-ms:1000}")
    public void poll(){if(enabled)runOnce();}
    public void runOnce(){
        var ids=db.query("SELECT id FROM source_export_job WHERE status='QUEUED' ORDER BY created_at,id LIMIT 4",(rs,n)->rs.getObject(1,UUID.class));
        for(UUID id:ids)try{service.generate(id);}catch(Exception exc){
            service.fail(id,exc instanceof BusinessException business?business.code():"EXPORT_FAILED");
        }
    }
}
