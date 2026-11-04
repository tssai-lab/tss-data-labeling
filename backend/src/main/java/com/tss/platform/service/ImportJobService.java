package com.tss.platform.service;

import com.tss.platform.service.ImportFailureMapper.ImportFailure;
import static com.tss.platform.service.ImportFailureMapper.importFailure;
import static com.tss.platform.service.ImportFailureMapper.truncateError;
import static com.tss.platform.service.ImportFailureMapper.toJson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.asset.spec.ArtifactSpecIds;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.entity.ImportJobSampleFailure;
import com.tss.platform.model.DatasetTaskType;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.model.manifest.ManifestAnnotation;
import com.tss.platform.model.manifest.ManifestData;
import com.tss.platform.model.manifest.ManifestImportPlan;
import com.tss.platform.model.manifest.ManifestSample;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.repository.ImportJobSampleFailureRepository;
import io.minio.StatObjectResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ImportJobService {

    private static final String JOB_PENDING = "PENDING";
    private static final String JOB_RUNNING = "RUNNING";
    private static final String JOB_SUCCESS = "SUCCESS";
    private static final String JOB_FAILED = "FAILED";
    private static final String JOB_PARTIAL = "PARTIAL";
    private static final String JOB_SUPERSEDED = "SUPERSEDED";
    private static final String FAILURE_FAILED = "FAILED";
    private static final String FAILURE_RETRYING = "RETRYING";
    private static final String VERSION_DRAFT = "DRAFT";
    private static final String VERSION_READY = "READY";
    private static final String PACKAGE_PARTIAL = "PARTIAL";
    private static final String PACKAGE_ROLE_APPEND = "APPEND";
    private static final String GROUPING_MANIFEST = "MANIFEST";
    private static final String GROUPING_AUTO_DIRECTORY = "AUTO_DIRECTORY";

    private final ImportJobRepository jobRepo;
    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final DatasetPackageRepository packageRepo;
    private final DatasetVersionPackageRepository versionPackageRepo;
    private final DatasetUploadSessionRepository sessionRepo;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final ImportJobSampleFailureRepository failureRepo;
    private final MinioService minioService;
    private final ZipCentralDirectoryReader zipReader;
    private final ManifestZipReader manifestReader;
    private final ManifestParser manifestParser;
    private final AutoDirectoryManifestBuilder autoDirectoryManifestBuilder;
    private final SingleModalImportPlanBuilder singleModalImportPlanBuilder;
    private final DatasetWorkspaceAuditService auditService;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate statusTransaction;
    private final TransactionTemplate sampleTransaction;
    private final Map<String, String> activeExecutors = new ConcurrentHashMap<>();

    @Autowired
    public ImportJobService(
            ImportJobRepository jobRepo,
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            DatasetPackageRepository packageRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetUploadSessionRepository sessionRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            ImportJobSampleFailureRepository failureRepo,
            MinioService minioService,
            ZipCentralDirectoryReader zipReader,
            ManifestZipReader manifestReader,
            ManifestParser manifestParser,
            AutoDirectoryManifestBuilder autoDirectoryManifestBuilder,
            SingleModalImportPlanBuilder singleModalImportPlanBuilder,
            PlatformTransactionManager transactionManager,
            DatasetWorkspaceAuditService auditService
    ) {
        this.jobRepo = jobRepo;
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.packageRepo = packageRepo;
        this.versionPackageRepo = versionPackageRepo;
        this.sessionRepo = sessionRepo;
        this.sampleRepo = sampleRepo;
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
        this.failureRepo = failureRepo;
        this.minioService = minioService;
        this.zipReader = zipReader;
        this.manifestReader = manifestReader;
        this.manifestParser = manifestParser;
        this.autoDirectoryManifestBuilder = autoDirectoryManifestBuilder;
        this.singleModalImportPlanBuilder = singleModalImportPlanBuilder;
        this.auditService = auditService;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.statusTransaction = new TransactionTemplate(transactionManager);
        this.statusTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.sampleTransaction = new TransactionTemplate(transactionManager);
        this.sampleTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    ImportJobService(
            ImportJobRepository jobRepo,
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            DatasetPackageRepository packageRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetUploadSessionRepository sessionRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            ImportJobSampleFailureRepository failureRepo,
            MinioService minioService,
            ZipCentralDirectoryReader zipReader,
            ManifestZipReader manifestReader,
            ManifestParser manifestParser,
            AutoDirectoryManifestBuilder autoDirectoryManifestBuilder,
            SingleModalImportPlanBuilder singleModalImportPlanBuilder,
            PlatformTransactionManager transactionManager
    ) {
        this(
                jobRepo,
                versionRepo,
                assetRepo,
                packageRepo,
                versionPackageRepo,
                sessionRepo,
                sampleRepo,
                dataRepo,
                annotationRepo,
                failureRepo,
                minioService,
                zipReader,
                manifestReader,
                manifestParser,
                autoDirectoryManifestBuilder,
                singleModalImportPlanBuilder,
                transactionManager,
                null
        );
    }

    ImportJobService(
            ImportJobRepository jobRepo,
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            DatasetPackageRepository packageRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetUploadSessionRepository sessionRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            MinioService minioService,
            ZipCentralDirectoryReader zipReader,
            ManifestZipReader manifestReader,
            ManifestParser manifestParser,
            AutoDirectoryManifestBuilder autoDirectoryManifestBuilder,
            SingleModalImportPlanBuilder singleModalImportPlanBuilder,
            PlatformTransactionManager transactionManager
    ) {
        this(
                jobRepo,
                versionRepo,
                assetRepo,
                packageRepo,
                versionPackageRepo,
                sessionRepo,
                sampleRepo,
                dataRepo,
                annotationRepo,
                null,
                minioService,
                zipReader,
                manifestReader,
                manifestParser,
                autoDirectoryManifestBuilder,
                singleModalImportPlanBuilder,
                transactionManager,
                null
        );
    }

    public void execute(String importJobId) {
        if (importJobId == null || importJobId.isBlank()) {
            throw new IllegalArgumentException("importJobId cannot be blank");
        }
        String executorId = "executor-" + UUID.randomUUID().toString().replace("-", "");
        Boolean claimed = statusTransaction.execute(status ->
                jobRepo.claimPending(importJobId, JOB_PENDING, JOB_RUNNING, executorId, Instant.now()) == 1
        );
        if (!Boolean.TRUE.equals(claimed)) {
            throw new IllegalArgumentException("import job is not claimable: " + importJobId);
        }
        activeExecutors.put(importJobId, executorId);

        List<ImportJobSampleFailure> retryFailures = List.of();
        try {
            retryFailures = retryFailures(importJobId);
            ImportContext context = loadContext(importJobId, executorId);
            StatObjectResponse stat = minioService.stat(context.objectName());
            long objectSize = stat.size();
            List<ZipEntryInfo> entries = zipReader.read(context.objectName(), objectSize);
            ManifestImportPlan plan = buildPlan(context, entries, objectSize);
            if (!retryFailures.isEmpty()) {
                plan = selectRetrySamples(plan, retryFailures);
            }
            List<ImportJobSampleFailure> effectiveRetryFailures = retryFailures;
            ManifestImportPlan effectivePlan = plan;
            writeTransaction.executeWithoutResult(
                    status -> persistPlan(
                            context,
                            effectivePlan,
                            executorId,
                            effectiveRetryFailures
                    )
            );
        } catch (Exception exception) {
            log.error(
                    "Import job failed: importJobId={}, executorId={}",
                    importJobId,
                    executorId,
                    exception
            );
            ImportFailure failure = importFailure(exception);
            List<ImportJobSampleFailure> failedRetryRows = retryFailures;
            statusTransaction.executeWithoutResult(
                    status -> {
                        if (!failedRetryRows.isEmpty()) {
                            markPartialAfterRetryFailure(importJobId, executorId, failure);
                        } else {
                            markFailed(importJobId, executorId, failure);
                        }
                    }
            );
        } finally {
            activeExecutors.remove(importJobId, executorId);
        }
    }

    void heartbeatActiveJobs() {
        Instant now = Instant.now();
        for (Map.Entry<String, String> lease : activeExecutors.entrySet()) {
            Integer updated = statusTransaction.execute(status ->
                    jobRepo.updateHeartbeatIfOwned(
                            lease.getKey(),
                            lease.getValue(),
                            JOB_RUNNING,
                            now
                    )
            );
            if (updated == null || updated != 1) {
                activeExecutors.remove(lease.getKey(), lease.getValue());
            }
        }
    }

    private ImportContext loadContext(String importJobId, String executorId) {
        ImportJob job = jobRepo.findById(importJobId)
                .orElseThrow(() -> new IllegalArgumentException("import job not found: " + importJobId));
        if (!JOB_RUNNING.equals(job.getStatus()) || !executorId.equals(job.getExecutorId())) {
            throw new IllegalArgumentException(
                    "import job lease was lost: " + importJobId
            );
        }
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(job.getDatasetVersionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset version not found: " + job.getDatasetVersionId()
                ));
        if (!VERSION_DRAFT.equals(version.getStatus())) {
            throw new IllegalArgumentException(
                    "dataset version must be DRAFT: " + version.getId() + ", status=" + version.getStatus()
            );
        }
        DatasetUploadSession session = sessionRepo.findByImportJobId(importJobId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "upload session not found for import job: " + importJobId
                ));
        if (!version.getId().equals(session.getVersionId())) {
            throw new IllegalArgumentException("upload session version does not match import job");
        }
        String taskType = DatasetTaskType.normalize(session.getType());
        String sampleGrouping = session.getSampleGrouping();
        boolean strictManifest = Boolean.TRUE.equals(session.getStrictManifest());
        boolean multimodal = "MULTIMODAL".equals(taskType);
        if (multimodal
                && !GROUPING_MANIFEST.equals(sampleGrouping)
                && !GROUPING_AUTO_DIRECTORY.equals(sampleGrouping)) {
            throw new IllegalArgumentException(
                    "upload session sampleGrouping must be MANIFEST or AUTO_DIRECTORY"
            );
        }
        if (!multimodal && sampleGrouping != null) {
            throw new IllegalArgumentException(
                    "single-modal import cannot use sampleGrouping"
            );
        }
        if (strictManifest && (!multimodal || !GROUPING_MANIFEST.equals(sampleGrouping))) {
            throw new IllegalArgumentException(
                    "strictManifest requires MULTIMODAL MANIFEST import"
            );
        }

        String objectName;
        String manifestPath = session.getManifestPath();
        String packageManifestPath = null;
        String packageId = job.getPackageId();
        String packageRole = null;
        if (packageId == null || packageId.isBlank()) {
            objectName = version.getStoragePath();
            if (objectName == null || objectName.isBlank()) {
                throw new IllegalArgumentException(
                        "dataset version storagePath is blank: " + version.getId()
                );
            }
            packageId = null;
        } else {
            DatasetPackage datasetPackage = packageRepo.findByIdAndDeletedFalse(packageId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "dataset package not found: " + job.getPackageId()
                    ));
            if (!version.getAssetId().equals(datasetPackage.getDatasetAssetId())) {
                throw new IllegalArgumentException("dataset package does not belong to version asset");
            }
            DatasetVersionPackage relation = versionPackageRepo
                    .findByDatasetVersionIdAndPackageId(
                            version.getId(),
                            datasetPackage.getId()
                    )
                    .orElseThrow(() -> new IllegalArgumentException(
                            "dataset package is not linked to version"
                    ));
            packageRole = relation.getPackageRole();
            objectName = datasetPackage.getStoragePath();
            if (objectName == null || objectName.isBlank()) {
                throw new IllegalArgumentException(
                        "dataset package storagePath is blank: " + datasetPackage.getId()
                );
            }
            if (GROUPING_MANIFEST.equals(sampleGrouping)
                    && datasetPackage.getManifestPath() != null
                    && !datasetPackage.getManifestPath().isBlank()) {
                manifestPath = datasetPackage.getManifestPath();
            }
            packageManifestPath = datasetPackage.getManifestPath();
        }

        if (multimodal && GROUPING_MANIFEST.equals(sampleGrouping)) {
            manifestPath = DatasetUploadService.normalizeManifestPath(
                    GROUPING_MANIFEST,
                    manifestPath
            );
        } else if (multimodal) {
            if ((manifestPath != null && !manifestPath.isBlank())
                    || (packageManifestPath != null && !packageManifestPath.isBlank())) {
                throw new IllegalArgumentException(
                        "AUTO_DIRECTORY import cannot use manifestPath"
                );
            }
            manifestPath = null;
        } else {
            if ((manifestPath != null && !manifestPath.isBlank())
                    || (packageManifestPath != null && !packageManifestPath.isBlank())) {
                throw new IllegalArgumentException(
                        "single-modal import cannot use manifestPath"
                );
            }
            manifestPath = null;
        }

        return new ImportContext(
                importJobId,
                version.getId(),
                version.getAssetId(),
                taskType,
                packageId,
                packageRole,
                objectName,
                sampleGrouping,
                manifestPath,
                strictManifest
        );
    }

    private ManifestImportPlan buildPlan(
            ImportContext context,
            List<ZipEntryInfo> entries,
            long objectSize
    ) throws Exception {
        int generatedStart = generatedSampleIndexStart(context);
        if (!"MULTIMODAL".equals(context.taskType())) {
            return singleModalImportPlanBuilder.build(
                    context.taskType(),
                    entries,
                    generatedStart
            );
        }
        if (GROUPING_AUTO_DIRECTORY.equals(context.sampleGrouping())) {
            return autoDirectoryManifestBuilder.build(entries, generatedStart);
        }
        String manifestJson = manifestReader.readManifest(
                context.objectName(),
                objectSize,
                context.manifestPath()
        );
        return manifestParser.parse(
                manifestJson,
                entries,
                context.manifestPath(),
                generatedStart,
                context.strictManifest()
        );
    }

    private int generatedSampleIndexStart(ImportContext context) {
        if (!context.appendPackage()) {
            return 0;
        }
        Integer maxSampleIndex =
                sampleRepo.findMaxSampleIndexByDatasetVersionIdAndDeletedFalse(
                        context.versionId()
                );
        return (maxSampleIndex == null ? -1 : maxSampleIndex) + 1;
    }

    private void persistPlan(
            ImportContext context,
            ManifestImportPlan plan,
            String executorId,
            List<ImportJobSampleFailure> retryFailures
    ) {
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalseForUpdate(context.assetId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset asset not found: " + context.assetId()
                ));
        DatasetVersion version = versionRepo
                .findByIdAndDeletedFalseForUpdate(context.versionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "dataset version not found: " + context.versionId()
                ));
        if (!VERSION_DRAFT.equals(version.getStatus())) {
            throw new IllegalArgumentException(
                    "dataset version must remain DRAFT during import: " + version.getId()
            );
        }
        if (!context.appendPackage()) {
            requireCompatibleArtifactSpec(
                    version.getAssetId(),
                    verifiedMultimodalSpecId(context)
            );
        }

        boolean incrementalRetry = retryFailures != null && !retryFailures.isEmpty();
        if (context.appendPackage() && !incrementalRetry) {
            validateAppendConflicts(context.versionId(), plan);
        }

        Map<String, ImportJobSampleFailure> retryByExternalId =
                failuresByExternalId(retryFailures);
        int importedBefore = importedSamplesBeforeRetry(context.importJobId(), incrementalRetry);
        int totalSamples = totalSamplesForSettlement(
                context.importJobId(),
                incrementalRetry,
                plan.totalSamples()
        );
        int importedNow = 0;
        int failedNow = 0;
        int dataCount = 0;
        int annotationCount = 0;
        LinkedHashSet<String> retriedExternalIds = new LinkedHashSet<>();

        for (ManifestSample manifestSample : plan.samples()) {
            ImportJobSampleFailure retryFailure =
                    retryByExternalId.get(manifestSample.externalId());
            retriedExternalIds.add(manifestSample.externalId());
            try {
                if (incrementalRetry) {
                    validateRetrySampleConflicts(context.versionId(), manifestSample);
                }
                SamplePersistResult result = persistSample(context, version, manifestSample);
                importedNow += result.samples();
                dataCount += result.dataItems();
                annotationCount += result.annotations();
                if (retryFailure != null && failureRepo != null) {
                    failureRepo.markResolved(retryFailure.getId(), Instant.now());
                }
            } catch (Exception exception) {
                failedNow += 1;
                ImportFailure failure = importFailure(exception);
                recordSampleFailure(context, manifestSample, retryFailure, failure);
            }
        }

        for (ImportJobSampleFailure retryFailure : retryByExternalId.values()) {
            if (!retriedExternalIds.contains(retryFailure.getExternalId())) {
                failedNow += 1;
                recordSampleFailure(
                        context,
                        retryFailure,
                        new ImportFailure(
                                "RETRY_SAMPLE_NOT_FOUND",
                                "失败样本在原始上传内容中不存在",
                                null
                        )
                );
            }
        }

        int importedTotal = importedBefore + importedNow;
        if (failedNow == 0) {
            completeSuccessfulImport(
                    context,
                    version,
                    asset,
                    executorId,
                    totalSamples,
                    dataCount,
                    annotationCount
            );
            return;
        }
        if (importedTotal > 0) {
            completePartialImport(
                    context,
                    version,
                    asset,
                    executorId,
                    totalSamples,
                    importedTotal,
                    failedNow
            );
            return;
        }
        markFailed(
                context.importJobId(),
                executorId,
                new ImportFailure(
                        "IMPORT_FAILED",
                        "数据导入失败，请检查上传内容后重试",
                        partialDetailsJson(totalSamples, importedTotal, failedNow)
                )
        );
    }

    private List<ImportJobSampleFailure> retryFailures(String importJobId) {
        if (failureRepo == null) {
            return List.of();
        }
        return failureRepo.findByImportJobIdAndStatusOrderBySampleIndexAsc(
                importJobId,
                FAILURE_RETRYING
        );
    }

    private ManifestImportPlan selectRetrySamples(
            ManifestImportPlan plan,
            List<ImportJobSampleFailure> retryFailures
    ) {
        Map<String, ManifestSample> samplesByExternalId = new LinkedHashMap<>();
        for (ManifestSample sample : plan.samples()) {
            samplesByExternalId.put(sample.externalId(), sample);
        }
        List<ManifestSample> selected = new ArrayList<>();
        int dataCount = 0;
        int annotationCount = 0;
        for (ImportJobSampleFailure failure : retryFailures) {
            ManifestSample source = samplesByExternalId.get(failure.getExternalId());
            if (source == null) {
                continue;
            }
            ManifestSample retrySample = new ManifestSample(
                    source.externalId(),
                    failure.getSampleIndex(),
                    source.tags(),
                    source.metadata(),
                    source.data(),
                    source.annotations()
            );
            selected.add(retrySample);
            dataCount += retrySample.data().size();
            annotationCount += retrySample.annotations().size();
        }
        return new ManifestImportPlan(
                plan.version(),
                selected,
                selected.size(),
                dataCount,
                annotationCount,
                plan.warnings()
        );
    }

    private Map<String, ImportJobSampleFailure> failuresByExternalId(
            List<ImportJobSampleFailure> retryFailures
    ) {
        Map<String, ImportJobSampleFailure> failures = new LinkedHashMap<>();
        if (retryFailures == null) {
            return failures;
        }
        for (ImportJobSampleFailure failure : retryFailures) {
            failures.put(failure.getExternalId(), failure);
        }
        return failures;
    }

    private int importedSamplesBeforeRetry(String importJobId, boolean incrementalRetry) {
        if (!incrementalRetry) {
            return 0;
        }
        return jobRepo.findById(importJobId)
                .map(ImportJob::getImportedSamples)
                .orElse(0);
    }

    private int totalSamplesForSettlement(
            String importJobId,
            boolean incrementalRetry,
            int planTotalSamples
    ) {
        if (!incrementalRetry) {
            return planTotalSamples;
        }
        return jobRepo.findById(importJobId)
                .map(ImportJob::getTotalSamples)
                .orElse(planTotalSamples);
    }

    private SamplePersistResult persistSample(
            ImportContext context,
            DatasetVersion version,
            ManifestSample manifestSample
    ) {
        return sampleTransaction.execute(status -> {
            Instant now = Instant.now();
            DatasetSample sample =
                    ImportSampleMapper.toSample(version, context.packageId(), manifestSample, now);
            Map<String, DatasetSampleData> dataByPath = new LinkedHashMap<>();
            List<DatasetSampleData> dataItems =
                    new ArrayList<>(manifestSample.data().size());
            List<DatasetAnnotation> annotations =
                    new ArrayList<>(manifestSample.annotations().size());

            for (ManifestData manifestData : manifestSample.data()) {
                DatasetSampleData data = ImportSampleMapper.toSampleData(
                        version,
                        sample,
                        context.packageId(),
                        manifestData,
                        now
                );
                dataItems.add(data);
                dataByPath.put(manifestData.path(), data);
            }
            for (ManifestAnnotation manifestAnnotation : manifestSample.annotations()) {
                DatasetSampleData referencedData = manifestAnnotation.refDataPath() == null
                        ? null
                        : dataByPath.get(manifestAnnotation.refDataPath());
                if (manifestAnnotation.refDataPath() != null && referencedData == null) {
                    throw new IllegalArgumentException(
                            "refDataPath not found while persisting sample "
                                    + manifestSample.externalId() + ": "
                                    + manifestAnnotation.refDataPath()
                    );
                }
                annotations.add(ImportSampleMapper.toAnnotation(
                        version,
                        sample,
                        referencedData,
                        context.packageId(),
                        manifestAnnotation,
                        now
                ));
            }

            sampleRepo.saveAllAndFlush(List.of(sample));
            dataRepo.saveAllAndFlush(dataItems);
            annotationRepo.saveAllAndFlush(annotations);
            return new SamplePersistResult(1, dataItems.size(), annotations.size());
        });
    }

    private void completeSuccessfulImport(
            ImportContext context,
            DatasetVersion version,
            DatasetAsset asset,
            String executorId,
            int totalSamples,
            int totalDataCount,
            int totalAnnotationCount
    ) {
        Instant now = Instant.now();
        version.setFileCount(countPersistedFiles(version.getId()));
        int completed = jobRepo.completeSuccessIfOwned(
                context.importJobId(),
                executorId,
                JOB_RUNNING,
                totalSamples,
                now,
                now
        );
        if (completed != 1) {
            throw new IllegalStateException("import job lease was lost before SUCCESS");
        }

        if (context.packageId() != null) {
            setPackageStatus(context.packageId(), VERSION_READY);
        }
        if (context.appendPackage()) {
            incrementWorkspaceRevision(version, now);
            versionRepo.saveAndFlush(version);
            supersedeOlderFailedAppendImports(context, now);
        } else {
            applyVerifiedMultimodalSpec(context, version);
            version.setStatus(VERSION_READY);
            version.setPublishedAt(now);
            versionRepo.saveAndFlush(version);

            if (shouldUpdateCurrentVersion(asset, version)) {
                asset.setCurrentVersionId(version.getId());
                asset.setUpdatedAt(now);
                assetRepo.saveAndFlush(asset);
            }
        }
        if (auditService != null) {
            auditService.recordImportSucceeded(
                    asset,
                    version,
                    jobRepo.findById(context.importJobId())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "import job not found: " + context.importJobId()
                            )),
                    context.appendPackage(),
                    totalSamples,
                    totalDataCount,
                    totalAnnotationCount
            );
        }
    }

    private void applyVerifiedMultimodalSpec(ImportContext context, DatasetVersion version) {
        String artifactSpecId = verifiedMultimodalSpecId(context);
        if (artifactSpecId == null) {
            return;
        }
        version.setArtifactSpecId(artifactSpecId);
        DatasetUploadSession session = sessionRepo.findByImportJobId(context.importJobId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "upload session not found for import job: " + context.importJobId()
                ));
        session.setArtifactSpecId(artifactSpecId);
        sessionRepo.saveAndFlush(session);
    }

    private String verifiedMultimodalSpecId(ImportContext context) {
        if (!"MULTIMODAL".equals(context.taskType())) {
            return null;
        }
        return GROUPING_MANIFEST.equals(context.sampleGrouping())
                ? ArtifactSpecIds.DATASET_MULTIMODAL_MANIFEST
                : ArtifactSpecIds.DATASET_MULTIMODAL_DIRECTORY;
    }

    private void requireCompatibleArtifactSpec(String assetId, String artifactSpecId) {
        if (artifactSpecId == null) {
            return;
        }
        versionRepo
                .findTopByAssetIdAndArtifactSpecIdIsNotNullAndDeletedFalseOrderByVersionNoDesc(
                        assetId
                )
                .ifPresent(existing -> {
                    if (!artifactSpecId.equals(existing.getArtifactSpecId())) {
                        throw new IllegalArgumentException(
                                "dataset artifact specification does not match existing asset versions"
                        );
                    }
                });
    }

    private void completePartialImport(
            ImportContext context,
            DatasetVersion version,
            DatasetAsset asset,
            String executorId,
            int totalSamples,
            int importedSamples,
            int failedSamples
    ) {
        Instant now = Instant.now();
        version.setStatus(VERSION_DRAFT);
        version.setPublishedAt(null);
        version.setFileCount(countPersistedFiles(version.getId()));
        if (context.appendPackage()) {
            incrementWorkspaceRevision(version, now);
        }
        versionRepo.saveAndFlush(version);
        int progress = progress(importedSamples, totalSamples);
        int updated = jobRepo.markPartialIfOwned(
                context.importJobId(),
                executorId,
                JOB_RUNNING,
                progress,
                totalSamples,
                importedSamples,
                "部分样本导入失败，可增量重试",
                "PARTIAL_IMPORT_FAILED",
                partialDetailsJson(totalSamples, importedSamples, failedSamples),
                now
        );
        if (updated != 1) {
            throw new IllegalStateException("import job lease was lost before PARTIAL");
        }
        if (context.packageId() != null) {
            setPackageStatus(context.packageId(), PACKAGE_PARTIAL);
        }
        if (auditService != null) {
            auditService.recordImportPartial(
                    asset,
                    version,
                    jobRepo.findById(context.importJobId())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "import job not found: " + context.importJobId()
                            )),
                    context.packageRole(),
                    importedSamples,
                    failedSamples
            );
        }
    }

    private void recordSampleFailure(
            ImportContext context,
            ManifestSample sample,
            ImportJobSampleFailure existing,
            ImportFailure failure
    ) {
        if (failureRepo == null) {
            return;
        }
        ImportJobSampleFailure row = existing == null
                ? new ImportJobSampleFailure()
                : existing;
        Instant now = Instant.now();
        if (row.getId() == null) {
            row.setId(ImportSampleMapper.id("ijsf"));
            row.setImportJobId(context.importJobId());
            row.setDatasetVersionId(context.versionId());
            row.setPackageId(context.packageId());
            row.setExternalId(sample.externalId());
            row.setSampleIndex(sample.sampleIndex());
            row.setAttemptCount(1);
            row.setFirstFailedAt(now);
            row.setCreatedAt(now);
        }
        row.setStatus(FAILURE_FAILED);
        row.setErrorCode(failure.code());
        row.setErrorMessage(truncateError(failure.message()));
        row.setErrorDetailsJson(failure.detailsJson());
        row.setLastFailedAt(now);
        row.setResolvedAt(null);
        row.setUpdatedAt(now);
        failureRepo.saveAndFlush(row);
    }

    private void recordSampleFailure(
            ImportContext context,
            ImportJobSampleFailure row,
            ImportFailure failure
    ) {
        if (failureRepo == null) {
            return;
        }
        Instant now = Instant.now();
        row.setStatus(FAILURE_FAILED);
        row.setErrorCode(failure.code());
        row.setErrorMessage(truncateError(failure.message()));
        row.setErrorDetailsJson(failure.detailsJson());
        row.setLastFailedAt(now);
        row.setResolvedAt(null);
        row.setUpdatedAt(now);
        failureRepo.saveAndFlush(row);
    }

    private void validateRetrySampleConflicts(
            String versionId,
            ManifestSample sample
    ) {
        List<DatasetSample> externalConflicts =
                sampleRepo.findByDatasetVersionIdAndDeletedFalseAndExternalIdIn(
                        versionId,
                        List.of(sample.externalId())
                );
        if (!externalConflicts.isEmpty()) {
            throw duplicateSampleFailure(
                    "external_id",
                    sample.externalId(),
                    null,
                    "external_id already exists in workspace"
            );
        }
        List<DatasetSample> indexConflicts =
                sampleRepo.findByDatasetVersionIdAndDeletedFalseAndSampleIndexIn(
                        versionId,
                        List.of(sample.sampleIndex())
                );
        if (!indexConflicts.isEmpty()) {
            throw duplicateSampleFailure(
                    "sample_index",
                    sample.externalId(),
                    sample.sampleIndex(),
                    "sample_index already exists in workspace"
            );
        }
    }

    private void markPartialAfterRetryFailure(
            String importJobId,
            String executorId,
            ImportFailure failure
    ) {
        ImportJob job = jobRepo.findById(importJobId).orElse(null);
        if (job == null) {
            return;
        }
        TerminalWorkspaceContext workspaceContext =
                lockTerminalWorkspace(job);
        if (failureRepo != null) {
            failureRepo.markStatusByImportJobId(
                    importJobId,
                    FAILURE_RETRYING,
                    FAILURE_FAILED,
                    Instant.now()
            );
        }
        if (job.getImportedSamples() == null || job.getImportedSamples() <= 0) {
            markFailed(importJobId, executorId, failure);
            return;
        }
        int failedSamples = failureRepo == null
                ? 0
                : Math.toIntExact(failureRepo.countByImportJobIdAndStatus(
                        importJobId,
                        FAILURE_FAILED
                ));
        int totalSamples = job.getTotalSamples() == null
                ? job.getImportedSamples() + failedSamples
                : job.getTotalSamples();
        int updated = jobRepo.markPartialIfOwned(
                importJobId,
                executorId,
                JOB_RUNNING,
                progress(job.getImportedSamples(), totalSamples),
                totalSamples,
                job.getImportedSamples(),
                truncateError(failure.message()),
                failure.code(),
                failure.detailsJson(),
                Instant.now()
        );
        if (updated == 1
                && workspaceContext != null
                && PACKAGE_ROLE_APPEND.equals(workspaceContext.packageRole())
                && VERSION_DRAFT.equals(workspaceContext.version().getStatus())) {
            incrementWorkspaceRevision(workspaceContext.version(), Instant.now());
            versionRepo.saveAndFlush(workspaceContext.version());
        }
    }

    private void setPackageStatus(String packageId, String status) {
        packageRepo.findByIdAndDeletedFalse(packageId)
                .ifPresent(datasetPackage -> {
                    datasetPackage.setStatus(status);
                    packageRepo.saveAndFlush(datasetPackage);
                });
    }

    private static int progress(int importedSamples, int totalSamples) {
        if (totalSamples <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(99, (int) Math.floor(importedSamples * 100.0 / totalSamples)));
    }

    private static String partialDetailsJson(
            int totalSamples,
            int importedSamples,
            int failedSamples
    ) {
        return toJson(Map.of(
                "totalSamples", totalSamples,
                "importedSamples", importedSamples,
                "failedSamples", failedSamples,
                "retryMode", "INCREMENTAL"
        ));
    }

    private record SamplePersistResult(
            int samples,
            int dataItems,
            int annotations
    ) {
    }

    private void supersedeOlderFailedAppendImports(ImportContext context, Instant now) {
        List<ImportJob> jobs = jobRepo.findByDatasetVersionId(context.versionId());
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        for (ImportJob job : jobs) {
            if (job == null
                    || context.importJobId().equals(job.getId())
                    || !JOB_FAILED.equals(job.getStatus())
                    || job.getPackageId() == null
                    || job.getPackageId().isBlank()) {
                continue;
            }
            DatasetVersionPackage relation = versionPackageRepo
                    .findByDatasetVersionIdAndPackageId(
                            context.versionId(),
                            job.getPackageId()
                    )
                    .orElse(null);
            if (relation == null
                    || !PACKAGE_ROLE_APPEND.equals(relation.getPackageRole())
                    || hasPersistedSamples(context.versionId(), job.getPackageId())) {
                continue;
            }
            DatasetPackage datasetPackage = packageRepo
                    .findByIdAndDeletedFalse(job.getPackageId())
                    .orElse(null);
            if (datasetPackage == null || !JOB_FAILED.equals(datasetPackage.getStatus())) {
                continue;
            }
            job.setStatus(JOB_SUPERSEDED);
            job.setErrorCode(null);
            job.setErrorMessage(null);
            job.setErrorDetailsJson(null);
            job.setUpdatedAt(now);
            if (job.getFinishedAt() == null) {
                job.setFinishedAt(now);
            }
            jobRepo.saveAndFlush(job);

            datasetPackage.setStatus(JOB_SUPERSEDED);
            packageRepo.saveAndFlush(datasetPackage);
        }
    }

    private boolean hasPersistedSamples(String versionId, String packageId) {
        return sampleRepo.countByDatasetVersionIdAndCreatedByPackageIdAndDeletedFalse(
                versionId,
                packageId
        ) > 0;
    }

    private long countPersistedFiles(String versionId) {
        return dataRepo.countByDatasetVersionId(versionId)
                + annotationRepo.countByDatasetVersionId(versionId);
    }

    private void validateAppendConflicts(
            String versionId,
            ManifestImportPlan plan
    ) {
        Collection<String> externalIds = plan.samples().stream()
                .map(ManifestSample::externalId)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        List<DatasetSample> externalConflicts =
                sampleRepo.findByDatasetVersionIdAndDeletedFalseAndExternalIdIn(
                        versionId,
                        externalIds
                );
        if (!externalConflicts.isEmpty()) {
            throw duplicateSampleFailure(
                    "external_id",
                    externalConflicts.get(0).getExternalId(),
                    null,
                    "external_id already exists in workspace"
            );
        }

        Collection<Integer> sampleIndexes = plan.samples().stream()
                .map(ManifestSample::sampleIndex)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        List<DatasetSample> indexConflicts =
                sampleRepo.findByDatasetVersionIdAndDeletedFalseAndSampleIndexIn(
                        versionId,
                        sampleIndexes
                );
        if (!indexConflicts.isEmpty()) {
            int sampleIndex = indexConflicts.get(0).getSampleIndex();
            String externalId = plan.samples().stream()
                    .filter(sample -> sample.sampleIndex() == sampleIndex)
                    .map(ManifestSample::externalId)
                    .findFirst()
                    .orElse(null);
            throw duplicateSampleFailure(
                    "sample_index",
                    externalId,
                    sampleIndex,
                    "sample_index already exists in workspace"
            );
        }
    }

    private static ManifestValidationException duplicateSampleFailure(
            String field,
            String externalId,
            Integer sampleIndex,
            String reason
    ) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        if (externalId != null && !externalId.isBlank()) {
            details.put("externalId", externalId);
        }
        if (sampleIndex != null) {
            details.put("sampleIndex", sampleIndex);
        }
        details.put("reason", reason);
        return new ManifestValidationException(
                "DUPLICATE_SAMPLE",
                "上传内容包含已存在的样本",
                Map.copyOf(details)
        );
    }

    private boolean shouldUpdateCurrentVersion(DatasetAsset asset, DatasetVersion candidate) {
        String currentVersionId = asset.getCurrentVersionId();
        if (currentVersionId == null || currentVersionId.isBlank()) {
            return true;
        }
        DatasetVersion current = versionRepo.findByIdAndDeletedFalse(currentVersionId).orElse(null);
        if (current == null || current.getVersionNo() == null || candidate.getVersionNo() == null) {
            return false;
        }
        return current.getVersionNo() < candidate.getVersionNo();
    }

    private void markFailed(
            String importJobId,
            String executorId,
            ImportFailure failure
    ) {
        ImportJob snapshotJob = jobRepo.findById(importJobId).orElse(null);
        if (snapshotJob == null) {
            return;
        }
        TerminalWorkspaceContext workspaceContext =
                lockTerminalWorkspace(snapshotJob);
        Instant now = Instant.now();
        int failed = jobRepo.markFailedIfOwned(
                importJobId,
                executorId,
                JOB_RUNNING,
                truncateError(failure.message()),
                failure.code(),
                failure.detailsJson(),
                now
        );
        if (failed != 1) {
            return;
        }
        ImportJob job = jobRepo.findById(importJobId).orElse(null);
        if (job == null) {
            return;
        }
        DatasetAsset asset = workspaceContext == null
                ? null
                : workspaceContext.asset();
        DatasetVersion version = workspaceContext == null
                ? null
                : workspaceContext.version();
        String packageRole = workspaceContext == null
                ? null
                : workspaceContext.packageRole();
        if (version != null) {
            version.setStatus(VERSION_DRAFT);
            version.setPublishedAt(null);
            if (PACKAGE_ROLE_APPEND.equals(packageRole)) {
                incrementWorkspaceRevision(version, now);
            }
            versionRepo.saveAndFlush(version);
        }
        if (job.getPackageId() != null) {
            packageRepo.findByIdAndDeletedFalse(job.getPackageId())
                    .ifPresent(datasetPackage -> {
                        datasetPackage.setStatus(JOB_FAILED);
                        packageRepo.saveAndFlush(datasetPackage);
                    });
        }
        if (auditService != null && asset != null && version != null) {
            auditService.recordImportFailed(
                    asset,
                    version,
                    job,
                    packageRole,
                    failure.code()
            );
        }
    }

    private TerminalWorkspaceContext lockTerminalWorkspace(ImportJob job) {
        if (job == null || job.getDatasetVersionId() == null) {
            return null;
        }
        DatasetVersion snapshot = versionRepo
                .findByIdAndDeletedFalse(job.getDatasetVersionId())
                .orElse(null);
        if (snapshot == null) {
            return null;
        }
        DatasetAsset asset = assetRepo
                .findByIdAndDeletedFalseForUpdate(snapshot.getAssetId())
                .orElse(null);
        DatasetVersion version = versionRepo
                .findByIdAndDeletedFalseForUpdate(snapshot.getId())
                .orElse(null);
        if (asset == null || version == null) {
            return null;
        }
        DatasetVersionPackage relation = job.getPackageId() == null
                ? null
                : versionPackageRepo
                        .findByDatasetVersionIdAndPackageId(
                                job.getDatasetVersionId(),
                                job.getPackageId()
                        )
                        .orElse(null);
        return new TerminalWorkspaceContext(
                asset,
                version,
                relation == null ? null : relation.getPackageRole()
        );
    }

    private static void incrementWorkspaceRevision(
            DatasetVersion version,
            Instant now
    ) {
        long current = version.getWorkspaceRevision() == null
                ? 0L
                : version.getWorkspaceRevision();
        version.setWorkspaceRevision(current + 1L);
        version.setUpdatedAt(now);
    }

    private record TerminalWorkspaceContext(
            DatasetAsset asset,
            DatasetVersion version,
            String packageRole
    ) {
    }





    private record ImportContext(
            String importJobId,
            String versionId,
            String assetId,
            String taskType,
            String packageId,
            String packageRole,
            String objectName,
            String sampleGrouping,
            String manifestPath,
            boolean strictManifest
    ) {
        private boolean appendPackage() {
            return PACKAGE_ROLE_APPEND.equals(packageRole);
        }
    }

}
