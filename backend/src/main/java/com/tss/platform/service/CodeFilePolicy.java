package com.tss.platform.service;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Shared extension, preview and raw-content hashing policy for code files.
 */
@Component
public final class CodeFilePolicy {

    public static final long EDITABLE_LIMIT_BYTES = 1_048_576L;

    private static final Map<String, FileType> SUPPORTED_TYPES = Map.of(
            ".py", new FileType("python", "text/x-python"),
            ".json", new FileType("json", "application/json"),
            ".jsonl", new FileType("json", "application/x-ndjson"),
            ".yaml", new FileType("yaml", "application/yaml"),
            ".yml", new FileType("yaml", "application/yaml"),
            ".md", new FileType("markdown", "text/markdown"),
            ".txt", new FileType("plaintext", "text/plain")
    );

    public CodeFileDescriptor describe(String normalizedPath, byte[] rawBytes) {
        Objects.requireNonNull(rawBytes, "rawBytes");
        FileType fileType = requireSupportedType(normalizedPath);
        String extension = extensionOf(normalizedPath);
        boolean validUtf8 = isValidUtf8(rawBytes);
        boolean withinEditableLimit = rawBytes.length <= EDITABLE_LIMIT_BYTES;
        boolean editable = validUtf8 && withinEditableLimit;
        String reasonCode = !validUtf8
                ? "INVALID_UTF8"
                : withinEditableLimit ? null : "FILE_TOO_LARGE";
        int slash = normalizedPath.lastIndexOf('/');
        String name = slash < 0 ? normalizedPath : normalizedPath.substring(slash + 1);
        return new CodeFileDescriptor(
                normalizedPath,
                name,
                "FILE",
                extension,
                fileType.languageId(),
                fileType.contentType(),
                rawBytes.length,
                editable,
                editable,
                true,
                reasonCode,
                sha256(rawBytes)
        );
    }

    /**
     * Describes metadata that already passed strict archive/edit validation.
     * This method deliberately does not read or decode source bytes.
     */
    public CodeFileDescriptor describeTrustedMetadata(
            String normalizedPath,
            long sizeBytes,
            String contentHash
    ) {
        if (sizeBytes < 0) {
            throw validation("INVALID_SIZE", "Code file size is invalid");
        }
        FileType fileType = requireSupportedType(normalizedPath);
        String extension = extensionOf(normalizedPath);
        boolean editable = sizeBytes <= EDITABLE_LIMIT_BYTES;
        int slash = normalizedPath.lastIndexOf('/');
        String name = slash < 0 ? normalizedPath : normalizedPath.substring(slash + 1);
        return new CodeFileDescriptor(
                normalizedPath,
                name,
                "FILE",
                extension,
                fileType.languageId(),
                fileType.contentType(),
                sizeBytes,
                editable,
                editable,
                true,
                editable ? null : "FILE_TOO_LARGE",
                contentHash
        );
    }

    public String decodeEditable(byte[] rawBytes) {
        Objects.requireNonNull(rawBytes, "rawBytes");
        if (rawBytes.length > EDITABLE_LIMIT_BYTES) {
            throw validation("FILE_TOO_LARGE", "Code file exceeds the online editing limit");
        }
        try {
            return strictDecoder().decode(ByteBuffer.wrap(rawBytes)).toString();
        } catch (CharacterCodingException e) {
            throw new CodeValidationException("INVALID_UTF8", "Code file is not valid UTF-8", e);
        }
    }

    public void validateSupportedPath(String normalizedPath) {
        requireSupportedType(normalizedPath);
    }

    public String sha256(byte[] rawBytes) {
        Objects.requireNonNull(rawBytes, "rawBytes");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    void validateUtf8(byte[] rawBytes) {
        if (!isValidUtf8(rawBytes)) {
            throw validation("INVALID_UTF8", "Code file is not valid UTF-8");
        }
    }

    String extension(String normalizedPath) {
        return extensionOf(normalizedPath);
    }

    private FileType requireSupportedType(String normalizedPath) {
        String extension = extensionOf(normalizedPath);
        FileType fileType = SUPPORTED_TYPES.get(extension);
        if (fileType == null) {
            throw validation("UNSUPPORTED_EXTENSION", "Code file type is not supported");
        }
        return fileType;
    }

    private static String extensionOf(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return "";
        }
        int slash = normalizedPath.lastIndexOf('/');
        String name = slash < 0 ? normalizedPath : normalizedPath.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static boolean isValidUtf8(byte[] rawBytes) {
        Objects.requireNonNull(rawBytes, "rawBytes");
        var decoder = strictDecoder();
        ByteBuffer input = ByteBuffer.wrap(rawBytes);
        CharBuffer output = CharBuffer.allocate(8192);
        while (true) {
            CoderResult result = decoder.decode(input, output, true);
            if (result.isError()) {
                return false;
            }
            if (result.isUnderflow()) {
                break;
            }
            output.clear();
        }
        output.clear();
        while (true) {
            CoderResult result = decoder.flush(output);
            if (result.isError()) {
                return false;
            }
            if (result.isUnderflow()) {
                return true;
            }
            output.clear();
        }
    }

    private static java.nio.charset.CharsetDecoder strictDecoder() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private static CodeValidationException validation(String reasonCode, String message) {
        return new CodeValidationException(reasonCode, message);
    }

    private record FileType(String languageId, String contentType) {
    }
}
