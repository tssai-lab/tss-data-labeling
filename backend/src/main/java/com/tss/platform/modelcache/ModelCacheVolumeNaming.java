package com.tss.platform.modelcache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ModelCacheVolumeNaming {

    private static final String PREFIX = "tss-model-cache-";
    private static final int MAX_NAME_LENGTH = 63;
    private static final int HASH_LENGTH = 10;
    private static final Pattern INVALID = Pattern.compile("[^a-z0-9-]");

    private ModelCacheVolumeNaming() {
    }

    public static String claimNameForNode(String nodeName) {
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("model cache requires an assigned Kubernetes node");
        }
        String normalized = nodeName.trim().toLowerCase(Locale.ROOT);
        String suffix = trimHyphens(
                INVALID.matcher(normalized).replaceAll("-").replaceAll("-+", "-")
        );
        if (suffix.isEmpty()) {
            throw new IllegalArgumentException("Kubernetes node name cannot produce a cache volume name");
        }
        int maximumSuffixLength = MAX_NAME_LENGTH - PREFIX.length();
        if (!suffix.equals(normalized) || suffix.length() > maximumSuffixLength) {
            String digest = sha256(normalized).substring(0, HASH_LENGTH);
            int headLength = maximumSuffixLength - HASH_LENGTH - 1;
            suffix = trimHyphens(suffix.substring(0, Math.min(suffix.length(), headLength)))
                    + "-" + digest;
        }
        return PREFIX + suffix;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String trimHyphens(String value) {
        String result = value;
        while (result.startsWith("-")) {
            result = result.substring(1);
        }
        while (result.endsWith("-")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
