package com.tss.platform.testsupport;

import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

/** 受限集成入口使用：镜像必须事先准备，缺失直接失败，不自动下载。 */
public final class LocalOnlyImagePullPolicy implements ImagePullPolicy {
    @Override
    public boolean shouldPull(DockerImageName imageName) {
        return false;
    }
}
