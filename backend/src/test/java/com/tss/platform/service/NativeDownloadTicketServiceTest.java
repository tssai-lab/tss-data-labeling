package com.tss.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeDownloadTicketServiceTest {

    @Test
    void exactDownloadCanReuseTicketWithinItsFixedLifetime() {
        AtomicLong now = new AtomicLong(1_000L);
        NativeDownloadTicketService service = new NativeDownloadTicketService(60, now::get);
        NativeDownloadTicketService.IssuedTicket issued = service.issue(
                "/api/files/download?objectName=users%2F7%2Fresult.zip",
                "sa-token-value"
        );

        String ticket = ticketFrom(issued.downloadUrl());
        MockHttpServletRequest request = request(
                "/api/files/download",
                "objectName", "users/7/result.zip",
                NativeDownloadTicketService.QUERY_PARAMETER, ticket
        );
        assertEquals("sa-token-value", service.resolve(ticket, request));
        now.set(30_000L);
        assertEquals("sa-token-value", service.resolve(ticket, request));
        now.set(61_000L);
        assertThrows(IllegalArgumentException.class, () -> service.resolve(ticket, request));
    }

    @Test
    void ticketExpiresAndWrongTargetDoesNotBreakItsLegitimateDownload() {
        AtomicLong now = new AtomicLong(1_000L);
        NativeDownloadTicketService service = new NativeDownloadTicketService(60, now::get);
        String expired = ticketFrom(service.issue(
                "/api/dataset-versions/dataset-v1/download", "token-a"
        ).downloadUrl());
        now.set(61_000L);
        assertThrows(IllegalArgumentException.class, () -> service.resolve(
                expired,
                request("/api/dataset-versions/dataset-v1/download",
                        NativeDownloadTicketService.QUERY_PARAMETER, expired)
        ));

        now.set(70_000L);
        String wrongTarget = ticketFrom(service.issue(
                "/api/v2/model-versions/model-v1/download", "token-b"
        ).downloadUrl());
        MockHttpServletRequest wrongRequest = request(
                "/api/v2/model-versions/model-v2/download",
                NativeDownloadTicketService.QUERY_PARAMETER, wrongTarget
        );
        assertThrows(IllegalArgumentException.class, () -> service.resolve(wrongTarget, wrongRequest));
        assertEquals("token-b", service.resolve(
                wrongTarget,
                request("/api/v2/model-versions/model-v1/download",
                        NativeDownloadTicketService.QUERY_PARAMETER, wrongTarget)
        ));
    }

    @Test
    void rejectsExternalNonDownloadAndNestedTicketTargets() {
        NativeDownloadTicketService service = new NativeDownloadTicketService(60, System::currentTimeMillis);
        List<String> invalidTargets = List.of(
                "https://example.com/api/files/download",
                "//example.com/api/files/download",
                "/api/files/health",
                "/api/files/../files/download",
                "/api/files/download?downloadTicket=already-present"
        );
        for (String target : invalidTargets) {
            assertThrows(IllegalArgumentException.class, () -> service.issue(target, "token"), target);
        }
    }

    @Test
    void downloadManagerProbeAndParallelRequestsCanAllResolveWithinTtl() throws Exception {
        NativeDownloadTicketService service = new NativeDownloadTicketService(60, System::currentTimeMillis);
        String ticket = ticketFrom(service.issue(
                "/api/admin/training-plans/templates/cv-cpu-v2", "token"
        ).downloadUrl());
        int consumers = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(consumers);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < consumers; index++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        service.resolve(ticket, request(
                                "/api/admin/training-plans/templates/cv-cpu-v2",
                                NativeDownloadTicketService.QUERY_PARAMETER, ticket
                        ));
                        successes.incrementAndGet();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(consumers, successes.get());
    }

    @Test
    void acceptsQueryParametersIndependentOfTheirOrder() {
        NativeDownloadTicketService service = new NativeDownloadTicketService(60, System::currentTimeMillis);
        NativeDownloadTicketService.IssuedTicket issued = service.issue(
                "/api/v2/code-versions/v1/files/download?path=src%2Fmain.py&mode=raw",
                "token"
        );
        String ticket = ticketFrom(issued.downloadUrl());
        MockHttpServletRequest request = request(
                "/api/v2/code-versions/v1/files/download",
                "mode", "raw",
                NativeDownloadTicketService.QUERY_PARAMETER, ticket,
                "path", "src/main.py"
        );
        assertEquals("token", service.resolve(ticket, request));
        assertTrue(issued.expiresAt().toEpochMilli() > System.currentTimeMillis());
    }

    private static String ticketFrom(String downloadUrl) {
        String query = URI.create(downloadUrl).getRawQuery();
        for (String pair : query.split("&")) {
            if (pair.startsWith(NativeDownloadTicketService.QUERY_PARAMETER + "=")) {
                return pair.substring(pair.indexOf('=') + 1);
            }
        }
        throw new AssertionError("ticket missing from URL");
    }

    private static MockHttpServletRequest request(String path, String... parameters) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        for (int index = 0; index < parameters.length; index += 2) {
            request.addParameter(parameters[index], parameters[index + 1]);
        }
        return request;
    }
}
