package com.tss.platform.dto;

import lombok.Data;

import java.util.List;

@Data
public class DatasetContentPreviewDto {
    private String path;
    private String fileName;
    private String extension;
    private String contentType;
    private String content;
    private List<String> columns;
    private List<List<String>> rows;
    private Integer page;
    private Integer pageSize;
    private Boolean pageable;
    private Integer total;
    private Integer totalPages;
    private Boolean truncated;
    private String message;
}
