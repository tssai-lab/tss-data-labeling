package com.tss.platform.service;

import com.tss.platform.dto.CodeUploadResultDto;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CodeUploadService {

    private final CodeAssetImportService importService;

    public CodeUploadService(CodeAssetImportService importService) {
        this.importService = importService;
    }

    public CodeUploadResultDto upload(
            MultipartFile file,
            String codeName,
            String version,
            String trainingProfile,
            String remark
    ) {
        return importService.importLegacy(
                file,
                codeName,
                version,
                trainingProfile,
                remark
        );
    }
}
