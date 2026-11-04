package com.tss.platform.dto.resource;

import lombok.Data;

@Data
public class AddServerRequest {
    private String serverIp;
    private String hostname;
    private ServerSpecs specs;
}
