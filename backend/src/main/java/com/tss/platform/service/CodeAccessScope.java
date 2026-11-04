package com.tss.platform.service;

/**
 * Explicit access scope for code-asset facades.
 *
 * <p>OWNER always means the exact logged-in owner, even when that user also
 * has an administrator role. ADMIN is reserved for the dedicated
 * {@code /api/v2/admin/**} management surface.</p>
 */
public enum CodeAccessScope {
    OWNER,
    ADMIN
}
