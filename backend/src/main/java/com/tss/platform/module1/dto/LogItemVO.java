package com.tss.platform.module1.dto;

import lombok.Data;

@Data
public class LogItemVO {
    private Long id;
    private String username;
    private String operateType;
    private String operateTime;
    private String ip;
    private String content;
    private String result;
    private String logType;
}
