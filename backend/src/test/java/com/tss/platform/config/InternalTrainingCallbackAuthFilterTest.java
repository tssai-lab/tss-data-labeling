package com.tss.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalTrainingCallbackAuthFilterTest {

    @Test
    void skipsNonInternalTrainingPaths() throws ServletException, IOException {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("secret-token");
        InternalTrainingCallbackAuthFilter filter = new InternalTrainingCallbackAuthFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/task/list");
        request.addHeader("X-Internal-Token", "secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, chain(invoked));

        assertTrue(invoked.get());
    }

    @Test
    void rejectsInternalTrainingPathWhenTokenInvalid() throws ServletException, IOException {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("secret-token");
        InternalTrainingCallbackAuthFilter filter = new InternalTrainingCallbackAuthFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/internal/training/callback"
        );
        request.addHeader("X-Internal-Token", "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, chain(invoked));

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsValidInternalTrainingCallbackTokenWhenSaTokenUnavailable() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("secret-token");

        assertTrue(properties.matchesInternalCallbackToken("secret-token"));
    }

    @Test
    void rejectsBlankConfiguredToken() {
        TrainingKubernetesProperties properties = new TrainingKubernetesProperties();
        properties.setInternalCallbackToken("   ");

        assertFalse(properties.matchesInternalCallbackToken("secret-token"));
    }

    private static FilterChain chain(AtomicBoolean invoked) {
        return (request, response) -> invoked.set(true);
    }
}
