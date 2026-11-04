package com.tss.platform.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Issues short-lived tickets for browser-native downloads.
 *
 * <p>The ticket only transports the existing Sa-Token value to one exact GET
 * download URL. It may be resolved repeatedly until its fixed expiration time
 * because browser download managers can probe or split one download into
 * multiple requests. The original controller and service still perform all
 * asset ownership and role checks for every request.</p>
 */
@Service
public class NativeDownloadTicketService {

    public static final String QUERY_PARAMETER = "downloadTicket";
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_LIVE_TICKETS = 10_000;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, StoredTicket> tickets = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final LongSupplier nowMillis;

    @Autowired
    public NativeDownloadTicketService(
            @Value("${download.ticket.ttl-seconds:60}") long ttlSeconds
    ) {
        this(ttlSeconds, System::currentTimeMillis);
    }

    NativeDownloadTicketService(long ttlSeconds, LongSupplier nowMillis) {
        if (ttlSeconds < 5 || ttlSeconds > 300) {
            throw new IllegalArgumentException("download ticket ttl must be between 5 and 300 seconds");
        }
        this.ttlMillis = Math.multiplyExact(ttlSeconds, 1_000L);
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
    }

    public IssuedTicket issue(String target, String saTokenValue) {
        if (saTokenValue == null || saTokenValue.isBlank()) {
            throw new IllegalArgumentException("login token is required");
        }
        DownloadTarget downloadTarget = DownloadTarget.parse(target);
        long now = nowMillis.getAsLong();
        removeExpired(now);
        if (tickets.size() >= MAX_LIVE_TICKETS) {
            throw new IllegalStateException("too many pending download tickets");
        }

        String ticket;
        StoredTicket stored;
        do {
            ticket = randomTicket();
            stored = new StoredTicket(downloadTarget, saTokenValue, now + ttlMillis);
        } while (tickets.putIfAbsent(ticket, stored) != null);

        String separator = target.contains("?") ? "&" : "?";
        return new IssuedTicket(
                target + separator + QUERY_PARAMETER + "=" + ticket,
                Instant.ofEpochMilli(stored.expiresAtMillis())
        );
    }

    /**
     * Resolves a ticket to the original Sa-Token value without extending its
     * fixed lifetime. A target mismatch does not invalidate the legitimate
     * download, while the exact target binding prevents cross-file reuse.
     */
    public String resolve(String ticket, HttpServletRequest request) {
        if (ticket == null || ticket.isBlank()) {
            throw new IllegalArgumentException("download ticket is required");
        }
        StoredTicket stored = tickets.get(ticket);
        if (stored == null) {
            throw new IllegalArgumentException("download ticket is invalid or expired");
        }
        if (stored.expiresAtMillis() <= nowMillis.getAsLong()) {
            tickets.remove(ticket, stored);
            throw new IllegalArgumentException("download ticket is invalid or expired");
        }
        DownloadTarget actual = DownloadTarget.fromRequest(request);
        if (!stored.target().equals(actual)) {
            throw new IllegalArgumentException("download ticket target mismatch");
        }
        return stored.saTokenValue();
    }

    private String randomTicket() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void removeExpired(long now) {
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    public record IssuedTicket(String downloadUrl, Instant expiresAt) {
    }

    private record StoredTicket(DownloadTarget target, String saTokenValue, long expiresAtMillis) {
    }

    private record DownloadTarget(String path, Map<String, List<String>> query) {

        private static DownloadTarget parse(String target) {
            if (target == null || target.isBlank() || containsControl(target)) {
                throw new IllegalArgumentException("download target is required");
            }
            URI uri;
            try {
                uri = URI.create(target);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("download target is invalid", exception);
            }
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("download target must be a same-origin path");
            }
            String path = uri.getPath();
            validatePath(path);
            Map<String, List<String>> query = parseRawQuery(uri.getRawQuery());
            if (query.containsKey(QUERY_PARAMETER)) {
                throw new IllegalArgumentException("download target cannot contain a ticket");
            }
            return new DownloadTarget(path, query);
        }

        private static DownloadTarget fromRequest(HttpServletRequest request) {
            String path = request.getRequestURI();
            validatePath(path);
            Map<String, List<String>> query = new TreeMap<>();
            request.getParameterMap().forEach((key, values) -> {
                if (!QUERY_PARAMETER.equals(key)) {
                    List<String> normalized = new ArrayList<>(Arrays.asList(values));
                    Collections.sort(normalized);
                    query.put(key, List.copyOf(normalized));
                }
            });
            return new DownloadTarget(path, Map.copyOf(query));
        }

        private static void validatePath(String path) {
            if (path == null
                    || !path.startsWith("/api/")
                    || path.contains("\\")
                    || path.contains("//")
                    || path.contains("/../")
                    || path.endsWith("/..")
                    || containsControl(path)) {
                throw new IllegalArgumentException("download target path is invalid");
            }
            boolean attachmentEndpoint = path.endsWith("/download");
            boolean trainingTemplate = path.matches("/api/admin/training-plans/templates/[^/]+");
            if (!attachmentEndpoint && !trainingTemplate) {
                throw new IllegalArgumentException("target is not a supported download endpoint");
            }
        }

        private static Map<String, List<String>> parseRawQuery(String rawQuery) {
            if (rawQuery == null || rawQuery.isBlank()) {
                return Map.of();
            }
            Map<String, List<String>> parsed = new TreeMap<>();
            for (String pair : rawQuery.split("&", -1)) {
                int separator = pair.indexOf('=');
                String rawKey = separator < 0 ? pair : pair.substring(0, separator);
                String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
                String key = decode(rawKey);
                String value = decode(rawValue);
                parsed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            }
            parsed.replaceAll((ignored, values) -> {
                Collections.sort(values);
                return List.copyOf(values);
            });
            return Map.copyOf(parsed);
        }

        private static String decode(String value) {
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("download target query is invalid", exception);
            }
        }

        private static boolean containsControl(String value) {
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) {
                    return true;
                }
            }
            return false;
        }
    }
}
