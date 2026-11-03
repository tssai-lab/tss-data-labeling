package com.tss.platform.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public final class KubernetesQuantityParser {

    private static final Map<String, BigDecimal> MEMORY_MULTIPLIERS = memoryMultipliers();

    private KubernetesQuantityParser() {
    }

    public static double cpuCores(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String value = raw.trim();
        BigDecimal multiplier = BigDecimal.ONE;
        String number = value;
        if (value.endsWith("n")) {
            multiplier = new BigDecimal("0.000000001");
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("u") || value.endsWith("µ")) {
            multiplier = new BigDecimal("0.000001");
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = new BigDecimal("0.001");
            number = value.substring(0, value.length() - 1);
        }
        BigDecimal parsed = nonNegative(number, raw).multiply(multiplier);
        double result = parsed.doubleValue();
        if (!Double.isFinite(result)) {
            throw invalid(raw);
        }
        return result;
    }

    public static long memoryBytes(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String value = raw.trim();
        String suffix = "";
        for (String candidate : MEMORY_MULTIPLIERS.keySet()) {
            if (!candidate.isEmpty() && value.endsWith(candidate)) {
                suffix = candidate;
                break;
            }
        }
        String number = suffix.isEmpty() ? value : value.substring(0, value.length() - suffix.length());
        BigDecimal bytes = nonNegative(number, raw).multiply(MEMORY_MULTIPLIERS.get(suffix));
        try {
            return bytes.longValueExact();
        } catch (ArithmeticException exception) {
            throw invalid(raw);
        }
    }

    private static BigDecimal nonNegative(String value, String raw) {
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.signum() < 0) {
                throw invalid(raw);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(raw);
        }
    }

    private static IllegalArgumentException invalid(String raw) {
        return new IllegalArgumentException("invalid Kubernetes quantity: " + raw);
    }

    private static Map<String, BigDecimal> memoryMultipliers() {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("Ei", BigDecimal.valueOf(1024).pow(6));
        values.put("Pi", BigDecimal.valueOf(1024).pow(5));
        values.put("Ti", BigDecimal.valueOf(1024).pow(4));
        values.put("Gi", BigDecimal.valueOf(1024).pow(3));
        values.put("Mi", BigDecimal.valueOf(1024).pow(2));
        values.put("Ki", BigDecimal.valueOf(1024));
        values.put("E", BigDecimal.TEN.pow(18));
        values.put("P", BigDecimal.TEN.pow(15));
        values.put("T", BigDecimal.TEN.pow(12));
        values.put("G", BigDecimal.TEN.pow(9));
        values.put("M", BigDecimal.TEN.pow(6));
        values.put("k", BigDecimal.TEN.pow(3));
        values.put("", BigDecimal.ONE);
        return values;
    }
}
