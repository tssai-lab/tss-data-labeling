package com.tss.platform.dto.v2;

import io.swagger.v3.oas.annotations.media.Schema;

public record V2DatasetWorkspaceCreateRequest(
        @Schema(
                description = "所选同资产 READY 基线版本 ID；省略时，新建工作区使用当前 READY",
                example = "dataset-ver-0123456789abcdef"
        )
        String baseVersionId,
        @Schema(
                description = "目标版本展示标签；省略时由服务端分配 v{versionNo}",
                example = "1.0.3"
        )
        String versionLabel
) {

    public V2DatasetWorkspaceCreateRequest(String versionLabel) {
        this(null, versionLabel);
    }
}
