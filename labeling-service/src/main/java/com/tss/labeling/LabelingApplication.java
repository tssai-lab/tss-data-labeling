package com.tss.labeling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 扫描当前包及子包中的组件，并按依赖自动配置服务。
@SpringBootApplication
public class LabelingApplication {

    public static void main(String[] args) {
        SpringApplication.run(LabelingApplication.class, args);
    }
}
