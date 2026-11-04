package com.tss.labeling.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Component
public class IdentityClient {

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public IdentityClient(
            RestClient.Builder builder,
            @Value("${labeling.identity.base-url}") String baseUrl
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public IdentityUser currentUser(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }

        Map<String, Object> response;
        try {
            response = restClient.get()
                    .uri("/api/user/current-user")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(RESPONSE_TYPE);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401) {
                throw new ResponseStatusException(UNAUTHORIZED);
            }
            if (exception.getStatusCode().value() == 403) {
                throw new ResponseStatusException(FORBIDDEN);
            }
            throw new ResponseStatusException(SERVICE_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE);
        }

        if (response == null || !(response.get("data") instanceof Map<?, ?> userData)) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE);
        }
        int code = response.get("code") instanceof Number number
                ? number.intValue()
                : -1;
        if (code == 401) {
            throw new ResponseStatusException(UNAUTHORIZED);
        }
        if (code == 403) {
            throw new ResponseStatusException(FORBIDDEN);
        }
        if (code != 200) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE);
        }

        Object id = userData.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE);
        }
        Object status = userData.get("status");
        if (Boolean.FALSE.equals(status)) {
            throw new ResponseStatusException(FORBIDDEN);
        }
        Object username = userData.get("username");
        String displayName = username == null ? "用户" : String.valueOf(username).trim();
        if (displayName.isBlank()) {
            displayName = "用户";
        }
        return new IdentityUser(String.valueOf(id), displayName);
    }
}
