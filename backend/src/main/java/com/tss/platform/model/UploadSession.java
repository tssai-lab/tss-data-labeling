package com.tss.platform.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Data
public class UploadSession {
    private String fileName;
    private long fileSize;
    private final List<String> partObjectNames = Collections.synchronizedList(new ArrayList<>());
}
