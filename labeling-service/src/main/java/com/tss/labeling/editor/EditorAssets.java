package com.tss.labeling.editor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class EditorAssets implements WebMvcConfigurer {
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/api/labeling/v1/editor-assets/**")
                .addResourceLocations("classpath:/static/editor-assets/").setCachePeriod(3600);
    }
}
