package com.tss.platform.repository;

import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.show_sql=false",
        "logging.level.org.hibernate.SQL=OFF"
})
class DatasetSampleRepositoryTest {

    @Autowired
    private DatasetSampleRepository sampleRepo;

    @Autowired
    private DatasetSampleDataRepository dataRepo;

    @Autowired
    private DatasetAnnotationRepository annotationRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createTables() {
        jdbcTemplate.execute("drop table if exists dataset_annotation");
        jdbcTemplate.execute("drop table if exists dataset_sample_data");
        jdbcTemplate.execute("drop table if exists dataset_sample");
        jdbcTemplate.execute("""
                create table dataset_sample (
                    id varchar(64) primary key,
                    dataset_version_id varchar(64) not null,
                    created_by_package_id varchar(64),
                    external_id varchar(255) not null,
                    sample_index integer not null,
                    tags varchar(4000),
                    metadata varchar(4000),
                    owner_user_id integer,
                    created_at timestamp with time zone,
                    updated_at timestamp with time zone,
                    deleted boolean not null,
                    deleted_at timestamp with time zone
                )
                """);
        jdbcTemplate.execute("""
                create table dataset_sample_data (
                    id varchar(64) primary key,
                    sample_id varchar(64) not null,
                    dataset_version_id varchar(64) not null,
                    package_id varchar(64),
                    data_type varchar(32) not null,
                    sensor varchar(64),
                    channel varchar(32),
                    seq integer not null,
                    format varchar(32),
                    original_path varchar(1024) not null,
                    file_name varchar(255),
                    size_bytes bigint,
                    checksum varchar(128),
                    content_type varchar(128),
                    zip_entry_offset bigint,
                    zip_data_offset bigint,
                    compressed_size bigint,
                    uncompressed_size bigint,
                    compression_method varchar(16),
                    crc32 bigint,
                    metadata varchar(4000),
                    created_at timestamp with time zone,
                    updated_at timestamp with time zone,
                    deleted boolean not null,
                    deleted_at timestamp with time zone
                )
                """);
        jdbcTemplate.execute("""
                create table dataset_annotation (
                    id varchar(64) primary key,
                    sample_id varchar(64) not null,
                    sample_data_id varchar(64),
                    dataset_version_id varchar(64) not null,
                    package_id varchar(64),
                    annotation_type varchar(64) not null,
                    format varchar(32) not null,
                    original_path varchar(1024) not null,
                    file_name varchar(255),
                    size_bytes bigint,
                    checksum varchar(128),
                    zip_entry_offset bigint,
                    zip_data_offset bigint,
                    compressed_size bigint,
                    uncompressed_size bigint,
                    compression_method varchar(16),
                    crc32 bigint,
                    content_type varchar(128),
                    metadata varchar(4000),
                    created_at timestamp with time zone,
                    updated_at timestamp with time zone,
                    deleted boolean not null,
                    deleted_at timestamp with time zone
                )
                """);
    }

    @Test
    void findsExternalIdAcrossRequestedVersionsOnlyAndHonorsStableSort() {
        sampleRepo.save(sample("sample-v2", "version-2", "scene-1", 1, false));
        sampleRepo.save(sample("sample-v1", "version-1", "scene-1", 2, false));
        sampleRepo.save(sample("sample-other-external", "version-1", "scene-2", 1, false));
        sampleRepo.save(sample("sample-other-version", "version-3", "scene-1", 1, false));
        sampleRepo.save(sample("sample-deleted", "version-2", "scene-1", 3, true));
        sampleRepo.flush();

        Page<DatasetSample> result =
                sampleRepo.findByDatasetVersionIdInAndExternalIdAndDeletedFalse(
                        List.of("version-2", "version-1"),
                        "scene-1",
                        PageRequest.of(
                                0,
                                10,
                                Sort.by(
                                        Sort.Order.asc("datasetVersionId"),
                                        Sort.Order.asc("sampleIndex"),
                                        Sort.Order.asc("createdAt"),
                                        Sort.Order.asc("id")
                                )
                        )
                );

        assertEquals(2, result.getTotalElements());
        assertEquals(List.of("sample-v1", "sample-v2"),
                result.getContent().stream().map(DatasetSample::getId).toList());
    }

    @Test
    void loadsDataAndAnnotationsForPageSampleIdsInStableOrder() {
        dataRepo.save(data("data-2", "sample-2", "version-2", 2));
        dataRepo.save(data("data-1", "sample-1", "version-1", 1));
        dataRepo.save(data("data-hidden", "sample-3", "version-3", 1));
        annotationRepo.save(annotation("annotation-2", "sample-2", "data-2", "version-2"));
        annotationRepo.save(annotation("annotation-1", "sample-1", "data-1", "version-1"));
        annotationRepo.save(annotation("annotation-hidden", "sample-3", "data-hidden", "version-3"));
        dataRepo.flush();
        annotationRepo.flush();

        List<DatasetSampleData> data =
                dataRepo.findBySampleIdInOrderBySampleIdAscSeqAscIdAsc(
                        List.of("sample-2", "sample-1")
                );
        List<DatasetAnnotation> annotations =
                annotationRepo.findBySampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
                        List.of("sample-2", "sample-1")
                );

        assertEquals(List.of("data-1", "data-2"),
                data.stream().map(DatasetSampleData::getId).toList());
        assertEquals(List.of("annotation-1", "annotation-2"),
                annotations.stream().map(DatasetAnnotation::getId).toList());
    }

    private DatasetSample sample(
            String id,
            String datasetVersionId,
            String externalId,
            Integer sampleIndex,
            boolean deleted
    ) {
        DatasetSample sample = new DatasetSample();
        sample.setId(id);
        sample.setDatasetVersionId(datasetVersionId);
        sample.setExternalId(externalId);
        sample.setSampleIndex(sampleIndex);
        sample.setCreatedAt(Instant.parse("2026-07-03T00:00:00Z"));
        sample.setDeleted(deleted);
        return sample;
    }

    private DatasetSampleData data(
            String id,
            String sampleId,
            String datasetVersionId,
            Integer seq
    ) {
        DatasetSampleData data = new DatasetSampleData();
        data.setId(id);
        data.setSampleId(sampleId);
        data.setDatasetVersionId(datasetVersionId);
        data.setDataType("VIDEO");
        data.setSeq(seq);
        data.setOriginalPath("private/" + id + ".mp4");
        data.setCreatedAt(Instant.parse("2026-07-03T00:00:00Z"));
        return data;
    }

    private DatasetAnnotation annotation(
            String id,
            String sampleId,
            String sampleDataId,
            String datasetVersionId
    ) {
        DatasetAnnotation annotation = new DatasetAnnotation();
        annotation.setId(id);
        annotation.setSampleId(sampleId);
        annotation.setSampleDataId(sampleDataId);
        annotation.setDatasetVersionId(datasetVersionId);
        annotation.setAnnotationType("TRACK");
        annotation.setFormat("json");
        annotation.setOriginalPath("private/" + id + ".json");
        annotation.setCreatedAt(Instant.parse("2026-07-03T00:00:00Z"));
        return annotation;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {
            DatasetSample.class,
            DatasetSampleData.class,
            DatasetAnnotation.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            DatasetSampleRepository.class,
            DatasetSampleDataRepository.class,
            DatasetAnnotationRepository.class
    })
    static class RepositoryTestConfig {
    }
}
