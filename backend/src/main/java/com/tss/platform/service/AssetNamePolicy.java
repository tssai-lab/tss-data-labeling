package com.tss.platform.service;

import org.hibernate.exception.ConstraintViolationException;

import java.util.Locale;
import java.util.Set;

public final class AssetNamePolicy {

    public static final int MAX_NAME_LENGTH = 255;

    private static final Set<String> NAME_CONSTRAINTS = Set.of(
            "uk_model_asset_owner_normalized_name",
            "uk_dataset_asset_owner_normalized_name",
            "uk_code_asset_owner_normalized_name",
            "ck_model_asset_name_not_blank",
            "ck_dataset_asset_name_not_blank",
            "ck_code_asset_name_not_blank"
    );

    private AssetNamePolicy() {
    }

    public static String normalizeRequired(String value) {
        if (value == null) {
            throw new AssetNameValidationException("asset name cannot be empty");
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new AssetNameValidationException("asset name cannot be empty");
        }
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new AssetNameValidationException(
                    "asset name length cannot exceed " + MAX_NAME_LENGTH
            );
        }
        return normalized;
    }

    public static boolean isNameConstraintViolation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraint
                    && constraint.getConstraintName() != null
                    && NAME_CONSTRAINTS.contains(
                    constraint.getConstraintName().toLowerCase(Locale.ROOT)
            )) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (NAME_CONSTRAINTS.stream().anyMatch(normalized::contains)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
