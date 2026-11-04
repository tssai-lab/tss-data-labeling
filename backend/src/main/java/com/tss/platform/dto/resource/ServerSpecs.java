package com.tss.platform.dto.resource;

import lombok.Data;

@Data
public class ServerSpecs {
    private String cpu;
    private String memory;
    private String gpu;
    private String os;
}
