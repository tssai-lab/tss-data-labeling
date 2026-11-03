package com.tss.platform.service;

import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

@Service
public class SampleFileService {

    private static final long MAX_PREVIEW_BYTES = 100L * 1024 * 1024;
    private static final String DATA_NOT_FOUND = "sample file not found or no permission";
    private static final String ANNOTATION_NOT_FOUND =
            "annotation file not found or no permission";
    private static final Set<String> SAFE_OTHER_CONTENT_TYPES = Set.of(
            "text/plain",
            "text/csv",
            "application/json",
            "application/xml",
            "text/xml",
            "application/yaml",
            "text/yaml",
            "application/x-yaml",
            "application/x-ndjson"
    );

    private final DatasetSampleDataRepository dataRepo;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final DatasetPackageRepository packageRepo;
    private final DatasetVersionPackageRepository versionPackageRepo;
    private final MinioService minioService;
    private final AuthContext authContext;

    public SampleFileService(
            DatasetSampleDataRepository dataRepo,
            DatasetSampleRepository sampleRepo,
            DatasetAnnotationRepository annotationRepo,
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            DatasetPackageRepository packageRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            MinioService minioService,
            AuthContext authContext
    ) {
        this.dataRepo = dataRepo;
        this.sampleRepo = sampleRepo;
        this.annotationRepo = annotationRepo;
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.packageRepo = packageRepo;
        this.versionPackageRepo = versionPackageRepo;
        this.minioService = minioService;
        this.authContext = authContext;
    }

    @Transactional(readOnly = true)
    public SampleFileStream openDataPreview(String dataId) {
        return openDataPreview(dataId, null);
    }

    @Transactional(readOnly = true)
    public SampleFileStream openDataPreview(String dataId, String rangeHeader) {
        DatasetSampleData data = requireData(dataId);
        DatasetVersion version = requireReadyVersionForSample(
                data.getSampleId(),
                data.getDatasetVersionId(),
                DATA_NOT_FOUND
        );
        SampleFileSource source = resolveSampleFileSource(
                version,
                data.getPackageId(),
                DATA_NOT_FOUND
        );
        if (isVideo(data)) {
            return openVideoPreview(source, data, rangeHeader);
        }
        validatePreviewType(data);
        Long sizeBytes = effectiveSize(data.getUncompressedSize(), data.getSizeBytes());
        if (sizeBytes == null) {
            throw new SampleFileException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "ZIP entry size is unavailable"
            );
        }
        if (sizeBytes > MAX_PREVIEW_BYTES) {
            throw new SampleFileException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "preview file exceeds 100MB limit"
            );
        }
        return openResourceStream(
                source,
                data.getZipDataOffset(),
                data.getCompressedSize(),
                data.getUncompressedSize(),
                data.getSizeBytes(),
                data.getCompressionMethod(),
                data.getFileName(),
                data.getContentType()
        );
    }

    @Transactional(readOnly = true)
    public SampleFileStream openDataDownload(String dataId) {
        DatasetSampleData data = requireData(dataId);
        DatasetVersion version = requireReadyVersionForSample(
                data.getSampleId(),
                data.getDatasetVersionId(),
                DATA_NOT_FOUND
        );
        SampleFileSource source = resolveSampleFileSource(
                version,
                data.getPackageId(),
                DATA_NOT_FOUND
        );
        return openResourceStream(
                source,
                data.getZipDataOffset(),
                data.getCompressedSize(),
                data.getUncompressedSize(),
                data.getSizeBytes(),
                data.getCompressionMethod(),
                data.getFileName(),
                data.getContentType()
        );
    }

    @Transactional(readOnly = true)
    public SampleFileStream openAnnotationDownload(String annotationId) {
        DatasetAnnotation annotation = requireAnnotation(annotationId);
        DatasetVersion version = requireReadyVersionForSample(
                annotation.getSampleId(),
                annotation.getDatasetVersionId(),
                ANNOTATION_NOT_FOUND
        );
        SampleFileSource source = resolveSampleFileSource(
                version,
                annotation.getPackageId(),
                ANNOTATION_NOT_FOUND
        );
        return openResourceStream(
                source,
                annotation.getZipDataOffset(),
                annotation.getCompressedSize(),
                annotation.getUncompressedSize(),
                annotation.getSizeBytes(),
                annotation.getCompressionMethod(),
                annotation.getFileName(),
                annotation.getContentType()
        );
    }

    @Transactional(readOnly = true)
    public SampleFileStream openWorkspaceDataPreview(
            String workspaceId,
            String sampleId,
            String dataId,
            String rangeHeader
    ) {
        DatasetSampleData data = requireWorkspaceData(
                workspaceId,
                sampleId,
                dataId
        );
        DatasetVersion workspace = requireDraftVersionForSample(
                sampleId,
                workspaceId,
                DATA_NOT_FOUND
        );
        SampleFileSource source = resolveSampleFileSource(
                workspace,
                data.getPackageId(),
                DATA_NOT_FOUND
        );
        if (isVideo(data)) {
            return openVideoPreview(source, data, rangeHeader);
        }
        validatePreviewType(data);
        Long sizeBytes = effectiveSize(data.getUncompressedSize(), data.getSizeBytes());
        requirePreviewSize(sizeBytes);
        return openResourceStream(
                source,
                data.getZipDataOffset(),
                data.getCompressedSize(),
                data.getUncompressedSize(),
                data.getSizeBytes(),
                data.getCompressionMethod(),
                data.getFileName(),
                data.getContentType()
        );
    }

    @Transactional(readOnly = true)
    public SampleFileStream openWorkspaceDataDownload(
            String workspaceId,
            String sampleId,
            String dataId
    ) {
        DatasetSampleData data = requireWorkspaceData(
                workspaceId,
                sampleId,
                dataId
        );
        DatasetVersion workspace = requireDraftVersionForSample(
                sampleId,
                workspaceId,
                DATA_NOT_FOUND
        );
        return openResourceStream(
                resolveSampleFileSource(
                        workspace,
                        data.getPackageId(),
                        DATA_NOT_FOUND
                ),
                data.getZipDataOffset(),
                data.getCompressedSize(),
                data.getUncompressedSize(),
                data.getSizeBytes(),
                data.getCompressionMethod(),
                data.getFileName(),
                data.getContentType()
        );
    }

    @Transactional(readOnly = true)
    public SampleFileStream openWorkspaceAnnotationPreview(
            String workspaceId,
            String sampleId,
            String annotationId
    ) {
        DatasetAnnotation annotation = requireWorkspaceAnnotation(
                workspaceId,
                sampleId,
                annotationId
        );
        DatasetVersion workspace = requireDraftVersionForSample(
                sampleId,
                workspaceId,
                ANNOTATION_NOT_FOUND
        );
        if (!isSafeOtherContentType(annotation.getContentType())) {
            throw new SampleFileException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "annotation content type is not supported for preview"
            );
        }
        Long sizeBytes = effectiveSize(
                annotation.getUncompressedSize(),
                annotation.getSizeBytes()
        );
        requirePreviewSize(sizeBytes);
        return openResourceStream(
                resolveSampleFileSource(
                        workspace,
                        annotation.getPackageId(),
                        ANNOTATION_NOT_FOUND
                ),
                annotation.getZipDataOffset(),
                annotation.getCompressedSize(),
                annotation.getUncompressedSize(),
                annotation.getSizeBytes(),
                annotation.getCompressionMethod(),
                annotation.getFileName(),
                annotation.getContentType()
        );
    }

    @Transactional(readOnly = true)
    public SampleFileStream openWorkspaceAnnotationDownload(
            String workspaceId,
            String sampleId,
            String annotationId
    ) {
        DatasetAnnotation annotation = requireWorkspaceAnnotation(
                workspaceId,
                sampleId,
                annotationId
        );
        DatasetVersion workspace = requireDraftVersionForSample(
                sampleId,
                workspaceId,
                ANNOTATION_NOT_FOUND
        );
        return openResourceStream(
                resolveSampleFileSource(
                        workspace,
                        annotation.getPackageId(),
                        ANNOTATION_NOT_FOUND
                ),
                annotation.getZipDataOffset(),
                annotation.getCompressedSize(),
                annotation.getUncompressedSize(),
                annotation.getSizeBytes(),
                annotation.getCompressionMethod(),
                annotation.getFileName(),
                annotation.getContentType()
        );
    }

    private DatasetSampleData requireData(String dataId) {
        if (dataId == null || dataId.isBlank()) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, DATA_NOT_FOUND);
        }
        DatasetSampleData data = dataRepo.findById(dataId)
                .orElseThrow(() -> new SampleFileException(
                        HttpStatus.NOT_FOUND,
                        DATA_NOT_FOUND
                ));
        if (Boolean.TRUE.equals(data.getDeleted())) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, DATA_NOT_FOUND);
        }
        return data;
    }

    private DatasetAnnotation requireAnnotation(String annotationId) {
        if (annotationId == null || annotationId.isBlank()) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, ANNOTATION_NOT_FOUND);
        }
        DatasetAnnotation annotation = annotationRepo.findById(annotationId)
                .orElseThrow(() ->
                        new SampleFileException(HttpStatus.NOT_FOUND, ANNOTATION_NOT_FOUND)
                );
        if (Boolean.TRUE.equals(annotation.getDeleted())) {
            throw new SampleFileException(
                    HttpStatus.NOT_FOUND,
                    ANNOTATION_NOT_FOUND
            );
        }
        return annotation;
    }

    private DatasetSampleData requireWorkspaceData(
            String workspaceId,
            String sampleId,
            String dataId
    ) {
        DatasetSampleData data = dataRepo
                .findByIdAndDatasetVersionId(dataId, workspaceId)
                .orElseThrow(() -> new SampleFileException(
                        HttpStatus.NOT_FOUND,
                        DATA_NOT_FOUND
                ));
        if (!sampleId.equals(data.getSampleId())
                || Boolean.TRUE.equals(data.getDeleted())) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, DATA_NOT_FOUND);
        }
        return data;
    }

    private DatasetAnnotation requireWorkspaceAnnotation(
            String workspaceId,
            String sampleId,
            String annotationId
    ) {
        DatasetAnnotation annotation = annotationRepo
                .findByIdAndDatasetVersionId(annotationId, workspaceId)
                .orElseThrow(() -> new SampleFileException(
                        HttpStatus.NOT_FOUND,
                        ANNOTATION_NOT_FOUND
                ));
        if (!sampleId.equals(annotation.getSampleId())
                || Boolean.TRUE.equals(annotation.getDeleted())) {
            throw new SampleFileException(
                    HttpStatus.NOT_FOUND,
                    ANNOTATION_NOT_FOUND
            );
        }
        return annotation;
    }

    private DatasetVersion requireReadyVersionForSample(
            String sampleId,
            String versionId,
            String errorMessage
    ) {
        if (sampleId == null || sampleId.isBlank()) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, errorMessage);
        }
        DatasetSample sample = sampleRepo.findByIdAndDeletedFalse(sampleId)
                .orElseThrow(() ->
                        new SampleFileException(HttpStatus.NOT_FOUND, errorMessage)
                );
        if (sample.getDatasetVersionId() == null
                || !sample.getDatasetVersionId().equals(versionId)) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, errorMessage);
        }
        return requireReadyVersion(sample.getDatasetVersionId(), errorMessage);
    }

    private DatasetVersion requireReadyVersion(String versionId, String errorMessage) {
        if (versionId == null || versionId.isBlank()) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, errorMessage);
        }
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(versionId)
                .orElseThrow(() -> new SampleFileException(HttpStatus.NOT_FOUND, errorMessage));
        if (!"READY".equals(version.getStatus())) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, errorMessage);
        }
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> new SampleFileException(HttpStatus.NOT_FOUND, errorMessage));
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, errorMessage);
        }
        return version;
    }

    private DatasetVersion requireDraftVersionForSample(
            String sampleId,
            String versionId,
            String errorMessage
    ) {
        if (sampleId == null || sampleId.isBlank()
                || versionId == null || versionId.isBlank()) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, errorMessage);
        }
        DatasetSample sample = sampleRepo
                .findByIdAndDatasetVersionId(sampleId, versionId)
                .orElseThrow(() -> new SampleFileException(
                        HttpStatus.NOT_FOUND,
                        errorMessage
                ));
        if (Boolean.TRUE.equals(sample.getDeleted())) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, errorMessage);
        }
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(versionId)
                .orElseThrow(() -> new SampleFileException(
                        HttpStatus.NOT_FOUND,
                        errorMessage
                ));
        if (!"DRAFT".equals(version.getStatus())) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, errorMessage);
        }
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> new SampleFileException(
                        HttpStatus.NOT_FOUND,
                        errorMessage
                ));
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, errorMessage);
        }
        return version;
    }

    private SampleFileSource resolveSampleFileSource(
            DatasetVersion version,
            String packageId,
            String notFoundMessage
    ) {
        if (packageId == null || packageId.isBlank()) {
            if (version.getStoragePath() == null || version.getStoragePath().isBlank()) {
                throw new SampleFileException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "sample file storage is unavailable"
                );
            }
            return new SampleFileSource(version.getStoragePath(), "ZIP");
        }

        DatasetPackage datasetPackage = packageRepo.findByIdAndDeletedFalse(packageId)
                .orElseThrow(() -> new SampleFileException(
                        HttpStatus.NOT_FOUND,
                        notFoundMessage
                ));
        if (!version.getAssetId().equals(datasetPackage.getDatasetAssetId())
                || !"READY".equals(datasetPackage.getStatus())
                || !versionPackageRepo.existsByDatasetVersionIdAndPackageId(
                        version.getId(),
                        datasetPackage.getId()
                )) {
            throw new SampleFileException(HttpStatus.NOT_FOUND, notFoundMessage);
        }
        if (datasetPackage.getStoragePath() == null
                || datasetPackage.getStoragePath().isBlank()) {
            throw new SampleFileException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "sample file storage is unavailable"
            );
        }
        return new SampleFileSource(
                datasetPackage.getStoragePath(),
                datasetPackage.getStorageKind()
        );
    }

    private SampleFileStream openResourceStream(
            SampleFileSource source,
            Long zipDataOffset,
            Long compressedSize,
            Long uncompressedSize,
            Long sizeBytes,
            String compressionMethod,
            String fileName,
            String contentType
    ) {
        if ("RAW".equalsIgnoreCase(source.storageKind())) {
            return openRawStream(
                    source.objectName(),
                    effectiveSize(uncompressedSize, sizeBytes),
                    fileName,
                    contentType
            );
        }
        validateIndex(zipDataOffset, compressedSize, compressionMethod);
        String method = compressionMethod.toUpperCase(Locale.ROOT);
        if (!"STORED".equals(method) && !"DEFLATED".equals(method)) {
            throw new SampleFileException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "unsupported ZIP compression method"
            );
        }

        InputStream compressed;
        try {
            compressed = compressedSize == 0
                    ? InputStream.nullInputStream()
                    : minioService.downloadRange(
                            source.objectName(),
                            zipDataOffset,
                            compressedSize
                    );
        } catch (Exception exception) {
            throw new SampleFileException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "sample file stream could not be opened",
                    exception
            );
        }

        InputStream result = "DEFLATED".equals(method)
                ? rawDeflateStream(compressed)
                : compressed;
        return new SampleFileStream(
                result,
                fileName,
                contentType,
                effectiveSize(uncompressedSize, sizeBytes)
        );
    }

    private SampleFileStream openRawStream(
            String objectName,
            Long sizeBytes,
            String fileName,
            String contentType
    ) {
        InputStream inputStream;
        try {
            inputStream = sizeBytes != null && sizeBytes == 0
                    ? InputStream.nullInputStream()
                    : minioService.downloadStream(objectName);
        } catch (Exception exception) {
            throw new SampleFileException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "sample file stream could not be opened",
                    exception
            );
        }
        return new SampleFileStream(
                inputStream,
                fileName,
                contentType,
                sizeBytes
        );
    }

    private SampleFileStream openVideoPreview(
            SampleFileSource source,
            DatasetSampleData data,
            String rangeHeader
    ) {
        if ("RAW".equalsIgnoreCase(source.storageKind())) {
            return openRawVideoPreview(source.objectName(), data, rangeHeader);
        }
        String compressionMethod = data.getCompressionMethod();
        if (compressionMethod == null || compressionMethod.isBlank()) {
            throw unsupportedVideoCompression();
        }
        String method = compressionMethod.toUpperCase(Locale.ROOT);
        if ("DEFLATED".equals(method)) {
            throw new SampleFileException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "DEFLATED video preview is not supported; use download"
            );
        }
        if (!"STORED".equals(method)) {
            throw unsupportedVideoCompression();
        }

        Long zipDataOffset = data.getZipDataOffset();
        Long compressedSize = data.getCompressedSize();
        Long uncompressedSize = data.getUncompressedSize();
        if (zipDataOffset == null
                || zipDataOffset < 0
                || compressedSize == null
                || compressedSize < 0
                || uncompressedSize == null
                || uncompressedSize < 0) {
            throw new SampleFileException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "VIDEO ZIP entry index is incomplete"
            );
        }
        if (!compressedSize.equals(uncompressedSize)) {
            throw new SampleFileException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "STORED video compressed and uncompressed sizes differ"
            );
        }

        ResolvedRange range = resolveRange(rangeHeader, uncompressedSize);
        InputStream inputStream;
        if (range.length() == 0) {
            inputStream = InputStream.nullInputStream();
        } else {
            long actualOffset;
            try {
                actualOffset = Math.addExact(zipDataOffset, range.start());
            } catch (ArithmeticException exception) {
                throw new SampleFileException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "VIDEO ZIP entry offset is invalid"
                );
            }
            inputStream = openRange(
                    source.objectName(),
                    actualOffset,
                    range.length()
            );
        }

        return new SampleFileStream(
                inputStream,
                data.getFileName(),
                videoContentType(data.getContentType()),
                range.length(),
                true,
                range.partial(),
                range.start(),
                range.end(),
                range.total()
        );
    }

    private SampleFileStream openRawVideoPreview(
            String objectName,
            DatasetSampleData data,
            String rangeHeader
    ) {
        Long size = data.getSizeBytes();
        if (size == null || size < 0) {
            throw new SampleFileException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RAW video size is unavailable"
            );
        }
        ResolvedRange range = resolveRange(rangeHeader, size);
        InputStream inputStream = range.length() == 0
                ? InputStream.nullInputStream()
                : openRange(objectName, range.start(), range.length());
        return new SampleFileStream(
                inputStream,
                data.getFileName(),
                videoContentType(data.getContentType()),
                range.length(),
                true,
                range.partial(),
                range.start(),
                range.end(),
                range.total()
        );
    }

    private InputStream openRange(String objectName, long offset, long length) {
        try {
            return minioService.downloadRange(objectName, offset, length);
        } catch (Exception exception) {
            throw new SampleFileException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "sample file stream could not be opened",
                    exception
            );
        }
    }

    private static ResolvedRange resolveRange(String rangeHeader, long total) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return total == 0
                    ? new ResolvedRange(0, -1, total, false)
                    : new ResolvedRange(0, total - 1, total, false);
        }
        if (total == 0) {
            throw rangeNotSatisfiable(total);
        }

        String value = rangeHeader.trim();
        if (!value.regionMatches(true, 0, "bytes=", 0, 6)) {
            throw rangeNotSatisfiable(total);
        }
        String specification = value.substring(6).trim();
        if (specification.isEmpty() || specification.indexOf(',') >= 0) {
            throw rangeNotSatisfiable(total);
        }

        int dash = specification.indexOf('-');
        if (dash < 0 || dash != specification.lastIndexOf('-')) {
            throw rangeNotSatisfiable(total);
        }
        String startText = specification.substring(0, dash).trim();
        String endText = specification.substring(dash + 1).trim();
        if (startText.isEmpty() && endText.isEmpty()) {
            throw rangeNotSatisfiable(total);
        }

        if (startText.isEmpty()) {
            long suffixLength = parseRangeNumber(endText, total);
            if (suffixLength <= 0) {
                throw rangeNotSatisfiable(total);
            }
            long start = suffixLength >= total ? 0 : total - suffixLength;
            return new ResolvedRange(start, total - 1, total, true);
        }

        long start = parseRangeNumber(startText, total);
        if (start >= total) {
            throw rangeNotSatisfiable(total);
        }
        long end;
        if (endText.isEmpty()) {
            end = total - 1;
        } else {
            end = parseRangeNumber(endText, total);
            if (end < start || end >= total) {
                throw rangeNotSatisfiable(total);
            }
        }
        return new ResolvedRange(start, end, total, true);
    }

    private record SampleFileSource(String objectName, String storageKind) {
    }

    private static long parseRangeNumber(String value, long total) {
        if (value.isEmpty()) {
            throw rangeNotSatisfiable(total);
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                throw rangeNotSatisfiable(total);
            }
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw rangeNotSatisfiable(total);
        }
    }

    private static SampleFileException rangeNotSatisfiable(long total) {
        return new SampleFileException(
                HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                "requested range is not satisfiable",
                total
        );
    }

    private static SampleFileException unsupportedVideoCompression() {
        return new SampleFileException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "video preview requires a STORED ZIP entry"
        );
    }

    private static boolean isVideo(DatasetSampleData data) {
        return data.getDataType() != null
                && "VIDEO".equalsIgnoreCase(data.getDataType());
    }

    private static String videoContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "video/mp4" : contentType;
    }

    private static void validateIndex(
            Long zipDataOffset,
            Long compressedSize,
            String compressionMethod
    ) {
        if (zipDataOffset == null
                || zipDataOffset < 0
                || compressedSize == null
                || compressedSize < 0
                || compressionMethod == null
                || compressionMethod.isBlank()) {
            throw new SampleFileException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "ZIP entry index is incomplete"
            );
        }
    }

    private static void validatePreviewType(DatasetSampleData data) {
        String dataType = data.getDataType() == null
                ? ""
                : data.getDataType().toUpperCase(Locale.ROOT);
        if ("AUDIO".equals(dataType)) {
            throw new SampleFileException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "audio preview is not implemented"
            );
        }
        if ("IMAGE".equals(dataType)
                || "TEXT".equals(dataType)
                || "POINT_CLOUD".equals(dataType)) {
            return;
        }
        if ("OTHER".equals(dataType) && isSafeOtherContentType(data.getContentType())) {
            return;
        }
        throw new SampleFileException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "sample data type is not supported for preview"
        );
    }

    private static boolean isSafeOtherContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return SAFE_OTHER_CONTENT_TYPES.contains(normalized);
    }

    private static Long effectiveSize(Long uncompressedSize, Long sizeBytes) {
        if (uncompressedSize != null && uncompressedSize >= 0) {
            return uncompressedSize;
        }
        return sizeBytes != null && sizeBytes >= 0 ? sizeBytes : null;
    }

    private static void requirePreviewSize(Long sizeBytes) {
        if (sizeBytes == null) {
            throw new SampleFileException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "file size is unavailable"
            );
        }
        if (sizeBytes > MAX_PREVIEW_BYTES) {
            throw new SampleFileException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "preview file exceeds 100MB limit"
            );
        }
    }

    private static InputStream rawDeflateStream(InputStream compressed) {
        Inflater inflater = new Inflater(true);
        return new InflaterInputStream(compressed, inflater) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    super.close();
                } finally {
                    inflater.end();
                }
            }
        };
    }

    private record ResolvedRange(long start, long end, long total, boolean partial) {
        private long length() {
            return end < start ? 0 : end - start + 1;
        }
    }

    public record SampleFileStream(
            InputStream inputStream,
            String fileName,
            String contentType,
            Long sizeBytes,
            boolean rangeSupported,
            boolean partial,
            Long rangeStart,
            Long rangeEnd,
            Long totalSize
    ) {
        public SampleFileStream(
                InputStream inputStream,
                String fileName,
                String contentType,
                Long sizeBytes
        ) {
            this(
                    inputStream,
                    fileName,
                    contentType,
                    sizeBytes,
                    false,
                    false,
                    null,
                    null,
                    sizeBytes
            );
        }
    }
}
