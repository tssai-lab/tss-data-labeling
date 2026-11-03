package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.tss.platform.controller.v2.V2BusinessException;
import org.apache.commons.csv.CSVFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class DatasetWorkspaceTextFilePolicy {

    public static final int MAX_INLINE_BYTES = 1024 * 1024;

    private static final Set<String> EXTENSIONS = Set.of(
            ".txt", ".json", ".jsonl", ".xml", ".csv", ".yaml", ".yml"
    );
    private static final Map<String, String> DEFAULT_CONTENT_TYPES = Map.of(
            ".txt", "text/plain",
            ".json", "application/json",
            ".jsonl", "application/x-ndjson",
            ".xml", "application/xml",
            ".csv", "text/csv",
            ".yaml", "application/yaml",
            ".yml", "application/yaml"
    );
    private static final Map<String, Set<String>> CONTENT_TYPES = Map.of(
            ".txt", Set.of("text/plain"),
            ".json", Set.of("application/json"),
            ".jsonl", Set.of("application/x-ndjson", "application/json"),
            ".xml", Set.of("application/xml", "text/xml"),
            ".csv", Set.of("text/csv"),
            ".yaml", Set.of("application/yaml", "text/yaml", "application/x-yaml"),
            ".yml", Set.of("application/yaml", "text/yaml", "application/x-yaml")
    );

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public DatasetWorkspaceTextFilePolicy(ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public ValidatedText validate(
            String content,
            String fileName,
            String format,
            String contentType
    ) {
        String safeName = requireFileName(fileName);
        String extension = extension(safeName);
        if (!EXTENSIONS.contains(extension)) {
            throw new V2BusinessException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "INLINE_TEXT_FORMAT_UNSUPPORTED",
                    "在线文本编辑仅支持 txt/json/jsonl/xml/csv/yaml/yml"
            );
        }
        if (content == null) {
            throw badRequest("content 不能为空");
        }
        if (content.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(content)) {
            throw badRequest("content 必须是无 NUL 的有效 UTF-8 文本");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_INLINE_BYTES) {
            throw new V2BusinessException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "INLINE_TEXT_TOO_LARGE",
                    "在线文本内容超过 1 MiB，请改用分片上传"
            );
        }

        String resolvedFormat = normalizeFormat(format, extension);
        String resolvedContentType = normalizeContentType(contentType, extension);
        validateSyntax(content, extension);
        return new ValidatedText(
                bytes,
                safeName,
                resolvedFormat,
                resolvedContentType,
                sha256(bytes)
        );
    }

    public Descriptor validateDescriptor(
            String fileName,
            String format,
            String contentType
    ) {
        String safeName = requireFileName(fileName);
        String extension = extension(safeName);
        if (extension.isEmpty()) {
            throw badRequest("fileName 必须包含扩展名");
        }
        String safeFormat = requireText(format, "format 不能为空", 32);
        if (!compatibleFormat(extension, safeFormat)) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RESOURCE_DESCRIPTOR_INVALID",
                    "format 与文件扩展名不一致"
            );
        }
        String safeContentType = requireText(
                contentType,
                "contentType 不能为空",
                128
        ).toLowerCase(Locale.ROOT);
        return new Descriptor(safeName, safeFormat, safeContentType);
    }

    public static String requireFileName(String value) {
        String fileName = requireText(value, "fileName 不能为空", 255);
        if (fileName.contains("/")
                || fileName.contains("\\")
                || fileName.contains("\0")
                || ".".equals(fileName)
                || "..".equals(fileName)) {
            throw badRequest("fileName 只能是安全文件名，不能包含路径");
        }
        return fileName;
    }

    public static String extension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index).toLowerCase(Locale.ROOT);
    }

    private String normalizeFormat(String format, String extension) {
        String value = format == null || format.isBlank()
                ? extension.substring(1)
                : requireText(format, "format 不能为空", 32);
        if (!compatibleFormat(extension, value)) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RESOURCE_DESCRIPTOR_INVALID",
                    "format 与文件扩展名不一致"
            );
        }
        return value;
    }

    private String normalizeContentType(String contentType, String extension) {
        String value = contentType == null || contentType.isBlank()
                ? DEFAULT_CONTENT_TYPES.get(extension)
                : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES.get(extension).contains(value)) {
            throw new V2BusinessException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "RESOURCE_CONTENT_TYPE_INVALID",
                    "contentType 与文件扩展名不一致"
            );
        }
        return value;
    }

    private void validateSyntax(String content, String extension) {
        try {
            switch (extension) {
                case ".json" -> jsonMapper.readTree(content);
                case ".jsonl" -> {
                    int lineNumber = 0;
                    for (String line : content.split("\\R", -1)) {
                        lineNumber += 1;
                        if (!line.isBlank()) {
                            jsonMapper.readTree(line);
                        }
                    }
                }
                case ".xml" -> parseXml(content);
                case ".csv" -> {
                    try (var parser = CSVFormat.DEFAULT.parse(new StringReader(content))) {
                        parser.forEach(record -> {
                            // Force the complete input to be parsed.
                        });
                    }
                }
                case ".yaml", ".yml" -> yamlMapper.readTree(content);
                default -> {
                    // Plain text has no additional grammar.
                }
            }
        } catch (Exception exception) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INLINE_TEXT_SYNTAX_INVALID",
                    "文本文件基础语法校验失败"
            );
        }
    }

    private static Document parseXml(String content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false
        );
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false
        );
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(
                new InputSource(new StringReader(content))
        );
    }

    private static boolean compatibleFormat(String extension, String format) {
        String value = format.trim().toLowerCase(Locale.ROOT);
        return switch (extension) {
            case ".txt" -> Set.of("txt", "text", "yolo").contains(value);
            case ".json" -> Set.of("json", "coco", "labelme").contains(value);
            case ".jsonl" -> Set.of("jsonl", "ndjson").contains(value);
            case ".xml" -> Set.of("xml", "voc").contains(value);
            case ".csv" -> "csv".equals(value);
            case ".yaml", ".yml" ->
                    Set.of("yaml", "yml", "yolo").contains(value);
            default -> value.equals(extension.substring(1));
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(
            String value,
            String message,
            int maxLength
    ) {
        if (value == null || value.isBlank()) {
            throw badRequest(message);
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw badRequest("字段长度超过限制");
        }
        return normalized;
    }

    private static V2BusinessException badRequest(String message) {
        return new V2BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                message
        );
    }

    public record ValidatedText(
            byte[] bytes,
            String fileName,
            String format,
            String contentType,
            String sha256
    ) {
    }

    public record Descriptor(
            String fileName,
            String format,
            String contentType
    ) {
    }
}
