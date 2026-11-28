package com.tss.labeling.imports;

import java.util.Locale;

/** Trusted image formats shared by imports, local references, previews and exports. */
public enum ImageFormats {
    PNG("png", "image/png", ".png", false),
    JPEG("JPEG", "image/jpeg", ".jpg", false),
    TIFF("TIFF", "image/tiff", ".tiff", true),
    WEBP("WebP", "image/webp", ".webp", true),
    BMP("bmp", "image/bmp", ".bmp", true);

    private final String readerName;
    private final String mediaType;
    private final String extension;
    private final boolean preview;

    ImageFormats(String readerName, String mediaType, String extension, boolean preview) {
        this.readerName = readerName;
        this.mediaType = mediaType;
        this.extension = extension;
        this.preview = preview;
    }

    public String readerName() { return readerName; }
    public String mediaType() { return mediaType; }
    public String extension() { return extension; }
    public boolean requiresPreview() { return preview; }

    public static ImageFormats fromPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return JPEG;
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) return TIFF;
        if (lower.endsWith(".webp")) return WEBP;
        if (lower.endsWith(".bmp")) return BMP;
        return null;
    }

    public static ImageFormats fromMediaType(String mediaType) {
        for (var format : values()) if (format.mediaType.equals(mediaType)) return format;
        return null;
    }

    public static boolean isReference(String path) {
        return path.toLowerCase(Locale.ROOT).matches(".*[.](png|jpe?g|tiff?|webp|bmp)(#page-[1-9][0-9]*)?");
    }
}
