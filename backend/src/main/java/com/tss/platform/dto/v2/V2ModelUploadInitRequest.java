package com.tss.platform.dto.v2;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
public class V2ModelUploadInitRequest {
    private String targetAssetId;
    private String fileName;
    private Long fileSize;
    private String fileFingerprint;
    private String modelName;
    private String modelVersion;
    @Schema(
            description = "上传者声明的模型目录类别（CV、NLP、POINT_CLOUD、ROBOT 或 OTHER）；"
                    + "OTHER 仅表示未分类，不放宽文件安全校验，也不自动取得训练资格",
            example = "CV",
            allowableValues = {"CV", "NLP", "POINT_CLOUD", "ROBOT", "OTHER"}
    )
    private String taskType;
    private String remark;
    private String commitInfo;
    private Map<String, Object> hyperParams;
}
