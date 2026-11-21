package com.tss.labeling.health;

import com.tss.labeling.ls.LsSourceAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadinessControllerTest {
    @Test void requiresBothDatabaseAndShippedSource() {
        var db=mock(JdbcTemplate.class); var source=mock(LsSourceAdapter.class);
        var readiness=new ReadinessController(db,source);
        assertEquals(200,readiness.ready().getStatusCode().value());
        when(source.inspect(anyString())).thenThrow(new IllegalStateException("private runtime path"));
        var unavailable=readiness.ready();
        assertEquals(503,unavailable.getStatusCode().value());
        assertEquals("{status=DOWN}",unavailable.getBody().toString());
        reset(source);
        when(db.queryForObject("SELECT 1",Integer.class)).thenThrow(new IllegalStateException("private database detail"));
        assertEquals(503,readiness.ready().getStatusCode().value());
        verifyNoInteractions(source);
    }
}
