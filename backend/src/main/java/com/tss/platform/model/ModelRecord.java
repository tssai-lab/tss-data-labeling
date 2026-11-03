package com.tss.platform.model;

import lombok.Data;

import java.time.Instant;

@Data
public class ModelRecord {
    private String id;
    private String name;
    private String version;
    private String type;
    private String remark;
    private String storagePath;
    private String createdAt;

    public static ModelRecord of(String id, String name, String version, String type,
                                 String remark, String storagePath) {
        ModelRecord r = new ModelRecord();
        r.setId(id);
        r.setName(name);
        r.setVersion(version);
        r.setType(type);
        r.setRemark(remark);
        r.setStoragePath(storagePath);
        r.setCreatedAt(Instant.now().toString());
        return r;
    }
}
