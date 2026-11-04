package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, non-executing static checks used only for risk routing. A clean
 * result means that this policy found no review signal; it is not a proof that
 * uploaded source is safe.
 */
@Service
public class CodeStaticRiskScanner {

    public static final String SCANNER_VERSION = "code-static-scanner-v3";
    public static final String RISK_POLICY_VERSION = "code-risk-policy-v3";

    private static final int MAX_SCANNED_FILE_BYTES = 1_048_576;
    private static final long MAX_TOTAL_SCANNED_BYTES = 16L * 1_048_576L;
    private static final int MAX_FINDINGS = 200;

    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"
    );
    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|access[_-]?key|secret|token|password)\\b"
                    + "\\s*[:=]\\s*[\\\"'][^\\\"'\\r\\n]{8,}[\\\"']"
    );
    private static final Pattern DYNAMIC_CODE = Pattern.compile(
            "(?m)(?:(?<![A-Za-z0-9_.])(?:eval|exec|compile|__import__)\\s*\\("
                    + "|\\bimportlib\\.import_module\\s*\\("
                    + "|\\b__builtins__\\b)"
    );
    private static final Pattern PROCESS_EXECUTION = Pattern.compile(
            "(?m)(?:\\b(?:os\\.system|os\\.popen|subprocess\\.|shutil\\.which)"
                    + "|(?:^|;)\\s*(?:from|import)\\s+subprocess\\b"
                    + "|(?:^|;)\\s*from\\s+os\\s+import[^\\r\\n]*(?:system|popen)\\b)"
    );
    private static final Pattern NETWORK_ACCESS = Pattern.compile(
            "(?m)(?:^|;)\\s*(?:from|import)\\s+"
                    + "(?:socket|requests|urllib|httpx|aiohttp|ftplib|paramiko|fabric)\\b"
    );
    private static final Pattern NATIVE_LOADING = Pattern.compile(
            "(?m)(?:^|;)\\s*(?:from|import)\\s+(?:ctypes|cffi)\\b"
    );
    private static final Pattern UNSAFE_DESERIALIZATION = Pattern.compile(
            "(?m)\\b(?:pickle\\.loads?|marshal\\.loads?|yaml\\.unsafe_load"
                    + "|torch\\.load|joblib\\.load)\\s*\\("
    );
    private static final Pattern DESTRUCTIVE_FILE_OPERATION = Pattern.compile(
            "(?m)(?:\\b(?:os\\.remove|os\\.unlink)\\s*\\("
                    + "|(?<![A-Za-z0-9_.])open\\s*\\([^,\\r\\n]+,"
                    + "\\s*[\\\"'][wax+][^\\\"']*[\\\"'])"
    );
    private static final Pattern DYNAMIC_DOWNLOAD = Pattern.compile(
            "(?m)\\b(?:hf_hub_download|snapshot_download"
                    + "|load_state_dict_from_url|urlretrieve|download_url)\\s*\\("
    );
    private static final Pattern PACKAGE_INSTALL = Pattern.compile(
            "(?im)(?:python\\s+-m\\s+pip|pip3?|conda)\\s+install\\b"
    );
    private static final Pattern EXTERNAL_DEPENDENCY_SOURCE = Pattern.compile(
            "(?im)^(?:--(?:extra-)?index-url\\s+|.*(?:git\\+https?|https?)://)"
    );
    private static final Pattern UNSAFE_YAML_TAG = Pattern.compile(
            "(?m)(?:!!(?:python|java)|!<tag:yaml.org,2002:python)"
    );

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    public CodeStaticRiskScanner(ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public CodeRiskScanResult scan(Map<String, byte[]> files) {
        if (files == null || files.isEmpty()) {
            return result("HIGH", "BLOCK", "RISK_ARTIFACT_EMPTY", List.of(
                    finding("ARTIFACT_EMPTY", "CRITICAL", "STRUCTURE", null,
                            null, "Code artifact contains no scannable files", true)
            ));
        }
        List<CodeRiskScanFinding> findings = new ArrayList<>();
        long scannedBytes = 0;
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            byte[] bytes = entry.getValue();
            if (path == null || bytes == null) {
                add(findings, finding("SCAN_INPUT_INVALID", "CRITICAL", "STRUCTURE",
                        path, null, "Code scan input is invalid", true));
                continue;
            }
            if (bytes.length > MAX_SCANNED_FILE_BYTES
                    || scannedBytes > MAX_TOTAL_SCANNED_BYTES - bytes.length) {
                add(findings, finding("SCAN_LIMIT_EXCEEDED", "HIGH", "ANALYSIS",
                        path, null, "File exceeds the automatic analysis limit", false));
                continue;
            }
            scannedBytes += bytes.length;
            String text = new String(bytes, StandardCharsets.UTF_8);
            scanSecrets(path, text, findings);
            scanByType(path, text, findings);
        }
        if (findings.size() >= MAX_FINDINGS) {
            findings = new ArrayList<>(findings.subList(0, MAX_FINDINGS));
            findings.set(MAX_FINDINGS - 1, finding(
                    "FINDINGS_TRUNCATED", "HIGH", "ANALYSIS", null, null,
                    "Risk findings exceeded the reporting limit", false
            ));
        }
        boolean blocked = findings.stream().anyMatch(CodeRiskScanFinding::blocking);
        if (blocked) {
            return result("HIGH", "BLOCK", "RISK_POLICY_BLOCKED", findings);
        }
        boolean high = findings.stream().anyMatch(value -> "HIGH".equals(value.severity()));
        if (high) {
            return result("HIGH", "MANUAL_REVIEW", "RISK_REVIEW_REQUIRED", findings);
        }
        if (!findings.isEmpty()) {
            return result("MEDIUM", "MANUAL_REVIEW", "RISK_REVIEW_REQUIRED", findings);
        }
        return result("LOW", "AUTO_APPROVE", "RISK_LOW", List.of());
    }

    private void scanSecrets(
            String path,
            String text,
            List<CodeRiskScanFinding> findings
    ) {
        addMatches(findings, path, text, PRIVATE_KEY,
                "PRIVATE_KEY_MATERIAL", "CRITICAL", "SECRET",
                "Private key material is not allowed", true);
        addMatches(findings, path, text, ASSIGNED_SECRET,
                "EMBEDDED_CREDENTIAL", "HIGH", "SECRET",
                "Possible embedded credential requires review", false);
        if (looksLikeLongEncoding(text)) {
            add(findings, finding(
                    "LONG_ENCODED_PAYLOAD", "HIGH", "OBFUSCATION", path, null,
                    "Long encoded payload requires review", false
            ));
        }
    }

    private void scanByType(
            String path,
            String text,
            List<CodeRiskScanFinding> findings
    ) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) {
            String syntaxError = pythonLexicalError(text);
            if (syntaxError != null) {
                add(findings, finding(
                        "PYTHON_LEXICAL_INVALID", "CRITICAL", "SYNTAX", path, null,
                        syntaxError, true
                ));
            }
            for (CodeRiskScanFinding finding : PythonStaticPolicy.analyze(path, text)) {
                add(findings, finding);
            }
            addMatches(findings, path, text, DYNAMIC_CODE,
                    "DYNAMIC_CODE_EXECUTION", "HIGH", "EXECUTION",
                    "Dynamic code execution requires review", false);
            addMatches(findings, path, text, PROCESS_EXECUTION,
                    "PROCESS_EXECUTION", "HIGH", "PROCESS",
                    "Process execution capability requires review", false);
            addMatches(findings, path, text, NETWORK_ACCESS,
                    "NETWORK_ACCESS", "HIGH", "NETWORK",
                    "Network access capability requires review", false);
            addMatches(findings, path, text, NATIVE_LOADING,
                    "NATIVE_CODE_LOADING", "HIGH", "NATIVE",
                    "Native code loading requires review", false);
            addMatches(findings, path, text, UNSAFE_DESERIALIZATION,
                    "UNSAFE_DESERIALIZATION", "HIGH", "DESERIALIZATION",
                    "Unsafe deserialization capability requires review", false);
            addMatches(findings, path, text, DESTRUCTIVE_FILE_OPERATION,
                    "DESTRUCTIVE_FILE_OPERATION", "HIGH", "FILESYSTEM",
                    "Destructive file operation requires review", false);
            addMatches(findings, path, text, PACKAGE_INSTALL,
                    "PACKAGE_INSTALL", "HIGH", "DEPENDENCY",
                    "Runtime package installation requires review", false);
            addMatches(findings, path, text, DYNAMIC_DOWNLOAD,
                    "DYNAMIC_DOWNLOAD", "HIGH", "NETWORK",
                    "Dynamic model or dependency download requires review", false);
            return;
        }
        if (lower.endsWith(".json")) {
            validateJson(path, text, findings);
            return;
        }
        if (lower.endsWith(".jsonl")) {
            validateJsonLines(path, text, findings);
            return;
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            addMatches(findings, path, text, UNSAFE_YAML_TAG,
                    "UNSAFE_YAML_TAG", "CRITICAL", "DESERIALIZATION",
                    "Unsafe YAML type tag is not allowed", true);
            try {
                yamlMapper.readTree(text);
            } catch (Exception exception) {
                add(findings, finding(
                        "YAML_SYNTAX_INVALID", "CRITICAL", "SYNTAX", path, null,
                        "YAML syntax is invalid", true
                ));
            }
            return;
        }
        if (lower.endsWith("requirements.txt") || lower.endsWith("constraints.txt")) {
            addMatches(findings, path, text, EXTERNAL_DEPENDENCY_SOURCE,
                    "EXTERNAL_DEPENDENCY_SOURCE", "HIGH", "DEPENDENCY",
                    "External dependency source requires review", false);
        }
    }

    private void validateJson(
            String path,
            String text,
            List<CodeRiskScanFinding> findings
    ) {
        try {
            jsonMapper.readTree(text);
        } catch (Exception exception) {
            add(findings, finding(
                    "JSON_SYNTAX_INVALID", "CRITICAL", "SYNTAX", path, null,
                    "JSON syntax is invalid", true
            ));
        }
    }

    private void validateJsonLines(
            String path,
            String text,
            List<CodeRiskScanFinding> findings
    ) {
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].isBlank()) {
                continue;
            }
            try {
                jsonMapper.readTree(lines[index]);
            } catch (Exception exception) {
                add(findings, finding(
                        "JSONL_SYNTAX_INVALID", "CRITICAL", "SYNTAX", path,
                        index + 1, "JSON Lines syntax is invalid", true
                ));
                return;
            }
        }
    }

    private static void addMatches(
            List<CodeRiskScanFinding> findings,
            String path,
            String text,
            Pattern pattern,
            String ruleId,
            String severity,
            String category,
            String safeMessage,
            boolean blocking
    ) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && findings.size() < MAX_FINDINGS) {
            add(findings, finding(
                    ruleId, severity, category, path,
                    lineNumber(text, matcher.start()), safeMessage, blocking
            ));
        }
    }

    private static int lineNumber(String text, int offset) {
        int line = 1;
        for (int index = 0; index < offset && index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                line += 1;
            }
        }
        return line;
    }

    private static boolean looksLikeLongEncoding(String text) {
        for (String line : text.split("\\R", -1)) {
            if (line.length() < 2_048) {
                continue;
            }
            int encoded = 0;
            for (int index = 0; index < line.length(); index++) {
                char value = line.charAt(index);
                if (Character.isLetterOrDigit(value)
                        || value == '+' || value == '/' || value == '=') {
                    encoded += 1;
                }
            }
            if (encoded >= line.length() * 0.95) {
                return true;
            }
        }
        return false;
    }

    private static String pythonLexicalError(String text) {
        Deque<Character> brackets = new ArrayDeque<>();
        char quote = 0;
        boolean triple = false;
        boolean escaped = false;
        boolean comment = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (comment) {
                if (value == '\n') {
                    comment = false;
                }
                continue;
            }
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (value == '\\') {
                    escaped = true;
                    continue;
                }
                if (triple
                        && value == quote
                        && index + 2 < text.length()
                        && text.charAt(index + 1) == quote
                        && text.charAt(index + 2) == quote) {
                    quote = 0;
                    triple = false;
                    index += 2;
                    continue;
                }
                if (!triple && value == quote) {
                    quote = 0;
                    continue;
                }
                if (!triple && value == '\n') {
                    return "Python string literal is not terminated";
                }
                continue;
            }
            if (value == '#') {
                comment = true;
                continue;
            }
            if (value == '\'' || value == '"') {
                quote = value;
                triple = index + 2 < text.length()
                        && text.charAt(index + 1) == value
                        && text.charAt(index + 2) == value;
                if (triple) {
                    index += 2;
                }
                continue;
            }
            if (value == '(' || value == '[' || value == '{') {
                brackets.push(value);
                continue;
            }
            if (value == ')' || value == ']' || value == '}') {
                if (brackets.isEmpty() || !matches(brackets.pop(), value)) {
                    return "Python bracket structure is invalid";
                }
            }
        }
        if (quote != 0) {
            return "Python string literal is not terminated";
        }
        if (!brackets.isEmpty()) {
            return "Python bracket structure is invalid";
        }
        return null;
    }

    private static boolean matches(char opening, char closing) {
        return (opening == '(' && closing == ')')
                || (opening == '[' && closing == ']')
                || (opening == '{' && closing == '}');
    }

    private static void add(
            List<CodeRiskScanFinding> findings,
            CodeRiskScanFinding finding
    ) {
        if (findings.size() < MAX_FINDINGS) {
            findings.add(finding);
        }
    }

    private static CodeRiskScanFinding finding(
            String ruleId,
            String severity,
            String category,
            String path,
            Integer line,
            String safeMessage,
            boolean blocking
    ) {
        return new CodeRiskScanFinding(
                ruleId, severity, category, path, line, line, safeMessage, blocking
        );
    }

    private static CodeRiskScanResult result(
            String riskLevel,
            String disposition,
            String summaryCode,
            List<CodeRiskScanFinding> findings
    ) {
        return new CodeRiskScanResult(
                SCANNER_VERSION,
                RISK_POLICY_VERSION,
                riskLevel,
                disposition,
                summaryCode,
                findings
        );
    }
}
