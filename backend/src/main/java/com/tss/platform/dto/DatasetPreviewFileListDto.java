package com.tss.platform.dto;

import lombok.Data;

import java.util.List;

@Data
public class DatasetPreviewFileListDto {
    private String datasetVersionId;
    private String type;
    private String fileName;
    private Boolean sourceArchive;
    private Integer page;
    private Integer pageSize;
    private Integer total;
    private List<DatasetPreviewFileDto> files;
}
