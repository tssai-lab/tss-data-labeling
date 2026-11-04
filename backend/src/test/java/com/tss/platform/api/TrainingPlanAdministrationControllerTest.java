package com.tss.platform.api;

import com.tss.platform.controller.v2.TrainingPlanAdministrationController;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.controller.v2.V2ExceptionHandler;
import com.tss.platform.dto.TrainingPlanAdminDtos;
import com.tss.platform.service.TrainingPlanAdministrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TrainingPlanAdministrationControllerTest {

    private TrainingPlanAdministrationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TrainingPlanAdministrationService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new TrainingPlanAdministrationController(service))
                .setControllerAdvice(new V2ExceptionHandler())
                .build();
    }

    @Test
    void previewAcceptsMultipartAndReturnsStructuredIssues() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "plan.yaml", "application/yaml", new byte[0]
        );
        when(service.preview(any())).thenReturn(new TrainingPlanAdminDtos.Preview(
                null, false, null, null,
                List.of(new TrainingPlanAdminDtos.Issue("YAML_EMPTY", null, "文件不能为空")),
                List.of(), List.of()
        ));

        mvc.perform(multipart("/api/admin/training-plans/preview").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishable").value(false))
                .andExpect(jsonPath("$.issues[0].code").value("YAML_EMPTY"));
    }

    @Test
    void missingPreviewFileStillReachesServiceForAudit() throws Exception {
        when(service.preview(null)).thenReturn(new TrainingPlanAdminDtos.Preview(
                null, false, null, null,
                List.of(new TrainingPlanAdminDtos.Issue("YAML_EMPTY", null, "请选择 YAML 文件")),
                List.of(), List.of()
        ));

        mvc.perform(multipart("/api/admin/training-plans/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[0].code").value("YAML_EMPTY"));

        verify(service).preview(null);
    }

    @Test
    void superAdministratorFailureUsesRealHttp403() throws Exception {
        when(service.list()).thenThrow(new V2BusinessException(
                HttpStatus.FORBIDDEN,
                "TRAINING_PLAN_ADMIN_FORBIDDEN",
                "仅超级管理员可以管理训练方案"
        ));

        mvc.perform(get("/api/admin/training-plans"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TRAINING_PLAN_ADMIN_FORBIDDEN"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void downloadsBackendOwnedYamlTemplateAsAttachment() throws Exception {
        when(service.template("cv-cpu-v2")).thenReturn(
                new TrainingPlanAdminDtos.TemplateFile(
                        "cv-cpu-v2",
                        "training-plan-cv-cpu-v2.yaml",
                        "schemaVersion: tss.training.plan/v2\n"
                )
        );

        mvc.perform(get("/api/admin/training-plans/templates/cv-cpu-v2"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("training-plan-cv-cpu-v2.yaml")
                ))
                .andExpect(content().contentType("application/yaml"))
                .andExpect(content().string("schemaVersion: tss.training.plan/v2\n"));
    }
}
