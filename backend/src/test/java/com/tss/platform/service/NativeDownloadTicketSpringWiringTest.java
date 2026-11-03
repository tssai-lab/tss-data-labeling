package com.tss.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class NativeDownloadTicketSpringWiringTest {

    @Test
    void productionConstructorCanBeCreatedBySpringWithConfiguredTtl() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "download.ticket.ttl-seconds=60"
            );
            context.register(NativeDownloadTicketService.class);
            context.refresh();

            assertNotNull(context.getBean(NativeDownloadTicketService.class));
        }
    }
}
