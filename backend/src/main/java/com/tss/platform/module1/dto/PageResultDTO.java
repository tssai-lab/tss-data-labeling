package com.tss.platform.module1.dto;

import lombok.Data;

import java.util.List;

@Data
public class PageResultDTO<T> {
    private List<T> records;
    private Long total;
    private Integer page;
    private Integer size;

    public PageResultDTO(List<T> records, Long total, Integer page, Integer size) {
        this.records = records;
        this.total = total;
        this.page = page;
        this.size = size;
    }
}
