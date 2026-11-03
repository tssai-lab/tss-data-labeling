package com.tss.platform.training.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class TrainingPlanContent {

    private TrainingPlanContent() {
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    public static byte[] utf8Bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
