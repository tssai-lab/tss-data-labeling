package com.tss.platform.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Accepts only deterministic PyPI requirement lines. Direct URLs, VCS links,
 * local paths and pip options would make an automatic image build non-repeatable.
 */
@Component
public class PythonRequirementsValidator {

    private static final int MAX_LINES = 256;
    private static final int MAX_LINE_LENGTH = 512;
    private static final Pattern REQUIREMENT = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9_.-]*(?:\\[[A-Za-z0-9_.-]+(?:,[A-Za-z0-9_.-]+)*])?"
                    + "(?:\s*(?:==|!=|<=|>=|<|>|~=)\s*[A-Za-z0-9][A-Za-z0-9_.+!*:-]*)?$"
    );

    public DependencyManifest parse(byte[] requirementsBytes) {
        if (requirementsBytes == null || requirementsBytes.length == 0) {
            return DependencyManifest.empty();
        }
        String text = new String(requirementsBytes, StandardCharsets.UTF_8);
        if (text.indexOf('\0') >= 0) {
            throw invalid("requirements.txt contains a NUL character");
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (lines.length > MAX_LINES) {
            throw invalid("requirements.txt has too many lines");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : lines) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() > MAX_LINE_LENGTH || line.startsWith("-")
                    || line.contains("@") || line.contains("://")
                    || line.contains("/") || line.contains("\\")) {
                throw invalid("requirements.txt only accepts PyPI package constraints");
            }
            if (!REQUIREMENT.matcher(line).matches()) {
                throw invalid("requirements.txt contains an invalid package constraint: " + safePackageName(line));
            }
            unique.add(normalize(line));
        }
        List<String> requirements = unique.stream().sorted(Comparator.naturalOrder()).toList();
        String canonical = String.join("\n", requirements);
        return new DependencyManifest(requirements, sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private String stripComment(String raw) {
        int index = raw.indexOf('#');
        return index >= 0 ? raw.substring(0, index) : raw;
    }

    private String normalize(String line) {
        return line.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String safePackageName(String line) {
        String value = line.replaceAll("[^A-Za-z0-9_.-]", "");
        return value.isBlank() ? "<invalid>" : value.substring(0, Math.min(80, value.length()));
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private CodeValidationException invalid(String message) {
        return new CodeValidationException("REQUIREMENTS_INVALID", message);
    }

    public record DependencyManifest(List<String> requirements, String sha256) {
        public static DependencyManifest empty() {
            return new DependencyManifest(List.of(), null);
        }

        public boolean hasDependencies() {
            return requirements != null && !requirements.isEmpty();
        }
    }
}
