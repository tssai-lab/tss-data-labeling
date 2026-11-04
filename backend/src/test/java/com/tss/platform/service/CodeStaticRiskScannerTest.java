package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeStaticRiskScannerTest {

    private final CodeStaticRiskScanner scanner = new CodeStaticRiskScanner(new ObjectMapper());

    @Test
    void miniRbtAcceptanceTrainingCodePassesTheRealStaticRiskGate() throws Exception {
        Path script = Path.of(
                "..",
                "examples",
                "acceptance",
                "minirbt_text_classification",
                "train.py"
        );

        CodeRiskScanResult result = scanner.scan(Map.of("train.py", Files.readAllBytes(script)));

        assertEquals("LOW", result.riskLevel(), result.findings().toString());
        assertEquals("AUTO_APPROVE", result.disposition());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void cleanTextOnlyArtifactIsEligibleForAutomaticApproval() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("train.py", bytes("def train(value):\n    return value + 1\n"));
        files.put("config.yaml", bytes("epochs: 2\nlearningRate: 0.01\n"));
        files.put("labels.json", bytes("{\"label\": \"ok\"}"));

        CodeRiskScanResult result = scanner.scan(files);

        assertEquals("LOW", result.riskLevel());
        assertEquals("AUTO_APPROVE", result.disposition());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void processNetworkAndDynamicCodeSignalsRequireManualReview() {
        CodeRiskScanResult result = scanner.scan(Map.of(
                "train.py",
                bytes("import requests\nimport subprocess\nexec('print(1)')\n")
        ));

        assertEquals("HIGH", result.riskLevel());
        assertEquals("MANUAL_REVIEW", result.disposition());
        assertTrue(result.findings().stream()
                .anyMatch(value -> "NETWORK_ACCESS".equals(value.ruleId())));
        assertTrue(result.findings().stream()
                .anyMatch(value -> "PROCESS_EXECUTION".equals(value.ruleId())));
        assertTrue(result.findings().stream()
                .anyMatch(value -> "DYNAMIC_CODE_EXECUTION".equals(value.ruleId())));
    }

    @Test
    void privateKeyAndInvalidStructuredContentAreBlockedWithoutEchoingContent() {
        CodeRiskScanResult result = scanner.scan(Map.of(
                "secret.txt", bytes("-----BEGIN PRIVATE KEY-----\nsensitive\n"),
                "config.json", bytes("{broken")
        ));

        assertEquals("BLOCK", result.disposition());
        assertTrue(result.findings().stream().anyMatch(CodeRiskScanFinding::blocking));
        assertTrue(result.findings().stream()
                .noneMatch(value -> value.safeMessage().contains("sensitive")));
    }

    @Test
    void oversizedFileFailsClosedIntoManualReview() {
        byte[] large = new byte[1_048_577];
        java.util.Arrays.fill(large, (byte) 'a');

        CodeRiskScanResult result = scanner.scan(Map.of("large.txt", large));

        assertEquals("HIGH", result.riskLevel());
        assertEquals("MANUAL_REVIEW", result.disposition());
        assertEquals("SCAN_LIMIT_EXCEEDED", result.findings().get(0).ruleId());
    }

    @Test
    void malformedPythonLexicalStructureIsBlocked() {
        CodeRiskScanResult result = scanner.scan(Map.of(
                "train.py", bytes("def train(:\n    return (1\n")
        ));

        assertEquals("BLOCK", result.disposition());
        assertTrue(result.findings().stream()
                .anyMatch(value -> "PYTHON_LEXICAL_INVALID".equals(value.ruleId())));
    }

    @Test
    void capabilityImportsAndDynamicImportFormsCannotBypassManualReview() {
        for (String source : new String[]{
                "from os import system\nsystem('id')\n",
                "from pickle import loads\nloads(b'x')\n",
                "import os, requests\nprint('ok')\n",
                "import importlib\nimportlib.import_module('requests')\n"
        }) {
            CodeRiskScanResult result = scanner.scan(Map.of("train.py", bytes(source)));

            assertEquals("HIGH", result.riskLevel(), source);
            assertEquals("MANUAL_REVIEW", result.disposition(), source);
            assertTrue(result.findings().stream().anyMatch(value ->
                    "HIGH_CAPABILITY_IMPORT".equals(value.ruleId())
                            || "PROCESS_EXECUTION".equals(value.ruleId())
                            || "DYNAMIC_CODE_EXECUTION".equals(value.ruleId())), source);
        }
    }

    @Test
    void unknownImportsFailClosedButOrdinaryMultilineSyntaxCanAutoApprove() {
        CodeRiskScanResult unknownImport = scanner.scan(Map.of(
                "train.py", bytes("import company_runtime\ncompany_runtime.run()\n")
        ));
        assertEquals("MANUAL_REVIEW", unknownImport.disposition());
        assertTrue(unknownImport.findings().stream()
                .anyMatch(value -> "UNREVIEWED_IMPORT".equals(value.ruleId())));

        CodeRiskScanResult multilineSyntax = scanner.scan(Map.of(
                "train.py", bytes("values = [\n    1,\n    2,\n]\nprint(values)\n")
        ));
        assertEquals("AUTO_APPROVE", multilineSyntax.disposition());
        assertTrue(multilineSyntax.findings().isEmpty());
    }

    @Test
    void commonHuggingFaceTrainingCodeCanAutoApprove() {
        CodeRiskScanResult result = scanner.scan(Map.of(
                "train.py", bytes("""
                        from pathlib import Path
                        import os
                        import shutil
                        import zipfile
                        from datasets import load_dataset
                        from transformers import AutoModelForImageClassification

                        def train(output: Path) -> int:
                            child = output / "_hf_model"
                            model = AutoModelForImageClassification.from_pretrained(output)
                            if output.exists():
                                shutil.rmtree(output)
                            output.mkdir(parents=True)
                            model.save_pretrained(output)
                            return 0
                        """)
        ));

        assertEquals("AUTO_APPROVE", result.disposition());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void officialUltralyticsTrainingImportCanAutoApprove() {
        CodeRiskScanResult result = scanner.scan(Map.of(
                "train.py", bytes("""
                        from ultralytics import YOLO

                        def train() -> None:
                            model = YOLO("yolo11n.pt")
                            print(model)
                        """)
        ));

        assertEquals("AUTO_APPROVE", result.disposition());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void multilineExpressionCanBeTheFirstStatementOfAnIndentedSuite() {
        CodeRiskScanResult result = scanner.scan(Map.of(
                "train.py", bytes("""
                        def evaluate(rows):
                            for row in rows:
                                values.append({
                                    "label": row["label"],
                                    "prediction": row["prediction"],
                                })
                            metrics = {
                                "accuracy": 1.0,
                                "f1": 1.0,
                            }
                            return metrics, values
                        """)
        ));

        assertEquals("AUTO_APPROVE", result.disposition());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void missingIndentedSuiteIsBlocked() {
        CodeRiskScanResult result = scanner.scan(Map.of(
                "train.py", bytes("def train(value):\nreturn value\n")
        ));

        assertEquals("BLOCK", result.disposition());
        assertTrue(result.findings().stream()
                .anyMatch(value -> "PYTHON_STRUCTURE_INVALID".equals(value.ruleId())));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
