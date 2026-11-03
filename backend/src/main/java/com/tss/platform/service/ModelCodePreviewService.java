package com.tss.platform.service;

import com.tss.platform.dto.ModelCodeFileDto;
import com.tss.platform.dto.ModelCodePreviewDto;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ModelCodePreviewService {

    private static final int MAX_CODE_FILES = 500;
    private static final int MAX_PREVIEW_BYTES = 1024 * 1024;
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".py", ".ipynb", ".json", ".jsonl", ".yaml", ".yml", ".txt", ".md",
            ".sh", ".bat", ".ps1", ".java", ".js", ".jsx", ".ts", ".tsx",
            ".go", ".rs", ".c", ".cc", ".cpp", ".h", ".hpp", ".cs", ".xml",
            ".toml", ".ini", ".cfg", ".properties", ".sql"
    );

    private final ModelVersionRepository modelVersionRepo;
    private final ModelAssetRepository modelAssetRepo;
    private final MinioService minioService;
    private final AuthContext authContext;

    public ModelCodePreviewService(
            ModelVersionRepository modelVersionRepo,
            ModelAssetRepository modelAssetRepo,
            MinioService minioService,
            AuthContext authContext
    ) {
        this.modelVersionRepo = modelVersionRepo;
        this.modelAssetRepo = modelAssetRepo;
        this.minioService = minioService;
        this.authContext = authContext;
    }

    public List<ModelCodeFileDto> listCodeFiles(String modelVersionId) {
        ModelVersion version = getVersion(modelVersionId);
        ensureZip(version.getStoragePath());
        List<ModelCodeFileDto> files = new ArrayList<>();
        try (InputStream is = minioService.downloadStream(version.getStoragePath());
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(is), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = normalizePath(entry.getName());
                if (!entry.isDirectory() && isSafePath(path) && isCodeFile(path)) {
                    ModelCodeFileDto dto = new ModelCodeFileDto();
                    dto.setPath(path);
                    dto.setFileName(fileNameOf(path));
                    dto.setExtension(extensionOf(path));
                    dto.setSizeBytes(entry.getSize() >= 0 ? entry.getSize() : null);
                    files.add(dto);
                    if (files.size() >= MAX_CODE_FILES) {
                        break;
                    }
                }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取模型代码文件失败: " + e.getMessage());
        }
        files.sort(Comparator.comparing(ModelCodeFileDto::getPath));
        return files;
    }

    public ModelCodePreviewDto previewCode(String modelVersionId, String path) {
        ModelVersion version = getVersion(modelVersionId);
        ensureZip(version.getStoragePath());
        String targetPath = normalizePath(path);
        if (!isSafePath(targetPath) || !isCodeFile(targetPath)) {
            throw new IllegalArgumentException("仅支持预览模型包内的代码或文本文件");
        }

        try (InputStream is = minioService.downloadStream(version.getStoragePath());
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(is), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryPath = normalizePath(entry.getName());
                if (!entry.isDirectory() && targetPath.equals(entryPath)) {
                    byte[] bytes = readLimited(zip);
                    ModelCodePreviewDto dto = new ModelCodePreviewDto();
                    dto.setPath(entryPath);
                    dto.setFileName(fileNameOf(entryPath));
                    dto.setContent(decodeUtf8(bytes));
                    dto.setSizeBytes(entry.getSize() >= 0 ? entry.getSize() : (long) bytes.length);
                    return dto;
                }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取模型代码内容失败: " + e.getMessage());
        }
        throw new IllegalArgumentException("代码文件不存在: " + targetPath);
    }

    private ModelVersion getVersion(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("模型版本 ID 不能为空");
        }
        ModelVersion version = modelVersionRepo.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("模型不存在"));
        authContext.requireOwnerAccess(effectiveOwner(version), "model not found or no permission");
        return version;
    }

    private Integer effectiveOwner(ModelVersion version) {
        if (version.getOwnerUserId() != null) {
            return version.getOwnerUserId();
        }
        return modelAssetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .map(ModelAsset::getOwnerUserId)
                .orElse(null);
    }

    private void ensureZip(String storagePath) {
        if (storagePath == null || !storagePath.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("模型代码预览仅支持 zip 模型包");
        }
    }

    private byte[] readLimited(InputStream inputStream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            total += len;
            if (total > MAX_PREVIEW_BYTES) {
                throw new IllegalArgumentException("代码文件超过 1MB，暂不支持在线预览");
            }
            out.write(buffer, 0, len);
        }
        return out.toByteArray();
    }

    private String decodeUtf8(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("代码文件不是有效的 UTF-8 文本", exception);
        }
    }

    private boolean isCodeFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return CODE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private boolean isSafePath(String path) {
        return ZipPathValidator.isSafeEntryPath(path);
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private String fileNameOf(String path) {
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private String extensionOf(String path) {
        String fileName = fileNameOf(path);
        int index = fileName.lastIndexOf('.');
        return index >= 0 ? fileName.substring(index).toLowerCase(Locale.ROOT) : "";
    }
}
