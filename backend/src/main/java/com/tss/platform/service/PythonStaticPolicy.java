package com.tss.platform.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative Python confidence gate for automatic approval. It does not
 * execute uploaded code. Syntax or import forms that cannot be classified with
 * high confidence are routed to manual review instead of being treated as LOW.
 */
final class PythonStaticPolicy {

    private static final Pattern MODULE = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"
    );
    private static final Pattern FROM_IMPORT = Pattern.compile(
            "^from\\s+([^\\s]+)\\s+import\\s+(.+)$"
    );
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern DEF_HEADER = Pattern.compile(
            "^(?:async\\s+)?def\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(.*\\)"
                    + "(?:\\s*->\\s*.+)?$"
    );
    private static final Pattern CLASS_HEADER = Pattern.compile(
            "^class\\s+[A-Za-z_][A-Za-z0-9_]*(?:\\s*\\(.*)?"
    );

    private static final Set<String> AUTO_IMPORT_ROOTS = Set.of(
            "__future__", "argparse", "bisect", "collections", "copy", "csv",
            "dataclasses", "datetime", "decimal", "enum", "fractions", "functools",
            "hashlib", "heapq", "itertools", "json", "logging", "math", "operator",
            "os", "pathlib", "random", "re", "shutil", "statistics", "string",
            "time", "typing", "zipfile",
            "typing_extensions", "uuid",
            "cv2", "joblib", "lightgbm", "matplotlib", "numpy", "pandas", "PIL",
            "scipy", "seaborn", "sklearn", "torch", "torchaudio", "torchvision",
            "tqdm", "transformers", "datasets", "ultralytics", "xgboost"
    );

    private static final Set<String> HIGH_CAPABILITY_IMPORT_ROOTS = Set.of(
            "asyncio", "builtins", "cffi", "code", "codeop", "concurrent", "ctypes",
            "fabric", "ftplib", "glob", "http", "httpx", "importlib", "inspect",
            "marshal", "multiprocessing", "paramiko", "pickle",
            "pip", "pkgutil", "requests", "resource", "runpy", "setuptools",
            "signal", "socket", "sqlite3", "ssl", "subprocess", "sys", "tarfile",
            "tempfile", "threading", "types", "urllib", "webbrowser", "yaml"
    );

    private PythonStaticPolicy() {
    }

    static List<CodeRiskScanFinding> analyze(String path, String text) {
        List<CodeRiskScanFinding> findings = new ArrayList<>();
        String masked = maskStringsAndComments(text);
        String[] originalLines = text.split("\\R", -1);
        String[] maskedLines = masked.split("\\R", -1);
        boolean unverifiedSyntax = false;
        Integer unverifiedLine = null;

        for (int index = 0; index < maskedLines.length; index++) {
            String line = maskedLines[index];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (leadingWhitespaceContainsTab(originalLines[index])
                    || hasTopLevelSemicolon(line)
                    || endsWithExplicitContinuation(line)) {
                unverifiedSyntax = true;
                unverifiedLine = firstLine(unverifiedLine, index + 1);
            }
            ImportResult importResult = analyzeImport(trimmed);
            if (importResult.importStatement()) {
                if (!importResult.classified()) {
                    unverifiedSyntax = true;
                    unverifiedLine = firstLine(unverifiedLine, index + 1);
                }
                for (String root : importResult.roots()) {
                    if (HIGH_CAPABILITY_IMPORT_ROOTS.contains(root)) {
                        findings.add(finding(
                                "HIGH_CAPABILITY_IMPORT", "HIGH", "CAPABILITY",
                                path, index + 1,
                                "High-capability Python import requires review", false
                        ));
                    } else if (!AUTO_IMPORT_ROOTS.contains(root)) {
                        findings.add(finding(
                                "UNREVIEWED_IMPORT", "HIGH", "DEPENDENCY",
                                path, index + 1,
                                "Python import is not in the automatic approval allowlist", false
                        ));
                    }
                }
            }
        }

        StructuralResult structure = structuralResult(maskedLines, originalLines);
        if (structure.errorLine() != null) {
            findings.add(finding(
                    "PYTHON_STRUCTURE_INVALID", "CRITICAL", "SYNTAX",
                    path, structure.errorLine(),
                    "Python suite or indentation structure is invalid", true
            ));
        }
        unverifiedSyntax = unverifiedSyntax || structure.unverified();
        unverifiedLine = firstLine(unverifiedLine, structure.unverifiedLine());
        if (unverifiedSyntax && structure.errorLine() == null) {
            findings.add(finding(
                    "PYTHON_SYNTAX_UNVERIFIED", "HIGH", "SYNTAX",
                    path, unverifiedLine,
                    "Python syntax uses a form that requires manual review", false
            ));
        }
        return findings;
    }

    private static ImportResult analyzeImport(String trimmed) {
        if (trimmed.startsWith("import ")) {
            String body = trimmed.substring("import ".length()).trim();
            List<String> roots = new ArrayList<>();
            boolean classified = !body.isEmpty();
            for (String item : body.split(",", -1)) {
                String value = item.trim();
                String[] alias = value.split("\\s+as\\s+", -1);
                if (alias.length > 2
                        || !MODULE.matcher(alias[0]).matches()
                        || (alias.length == 2 && !IDENTIFIER.matcher(alias[1]).matches())) {
                    classified = false;
                    continue;
                }
                roots.add(root(alias[0]));
            }
            return new ImportResult(true, classified, roots);
        }
        if (trimmed.startsWith("from ")) {
            Matcher matcher = FROM_IMPORT.matcher(trimmed);
            if (!matcher.matches()) {
                return new ImportResult(true, false, List.of());
            }
            String module = matcher.group(1);
            String imported = matcher.group(2).trim();
            boolean classified = MODULE.matcher(module).matches()
                    && !imported.isEmpty()
                    && !imported.contains("(")
                    && !imported.contains(")")
                    && !module.startsWith(".");
            return new ImportResult(
                    true,
                    classified,
                    classified ? List.of(root(module)) : List.of()
            );
        }
        return new ImportResult(false, true, List.of());
    }

    private static StructuralResult structuralResult(
            String[] maskedLines,
            String[] originalLines
    ) {
        Deque<Integer> indents = new ArrayDeque<>();
        indents.push(0);
        boolean requiresIndentedSuite = false;
        int suiteParentIndent = 0;
        int bracketDepth = 0;
        boolean unverified = false;
        Integer unverifiedLine = null;

        for (int index = 0; index < maskedLines.length; index++) {
            String line = maskedLines[index];
            String trimmed = line.trim();
            int beforeDepth = bracketDepth;
            bracketDepth += bracketDelta(line);
            if (bracketDepth < 0) {
                return new StructuralResult(index + 1, unverified, unverifiedLine);
            }
            if (beforeDepth > 0) {
                continue;
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            int indent = leadingSpaces(originalLines[index]);
            if (leadingWhitespaceContainsTab(originalLines[index])) {
                unverified = true;
                unverifiedLine = firstLine(unverifiedLine, index + 1);
                continue;
            }
            if (requiresIndentedSuite) {
                if (indent <= suiteParentIndent) {
                    return new StructuralResult(index + 1, unverified, unverifiedLine);
                }
                indents.push(indent);
                requiresIndentedSuite = false;
            } else {
                if (indent > indents.peek()) {
                    return new StructuralResult(index + 1, unverified, unverifiedLine);
                }
                while (indent < indents.peek() && indents.size() > 1) {
                    indents.pop();
                }
                if (indent != indents.peek()) {
                    return new StructuralResult(index + 1, unverified, unverifiedLine);
                }
            }

            if (bracketDepth > 0) {
                if (startsBlockHeader(trimmed)) {
                    unverified = true;
                    unverifiedLine = firstLine(unverifiedLine, index + 1);
                }
                continue;
            }
            HeaderResult header = headerResult(trimmed);
            if (header.blockHeader() && !header.valid()) {
                return new StructuralResult(index + 1, unverified, unverifiedLine);
            }
            if (header.blockHeader() && header.requiresSuite()) {
                requiresIndentedSuite = true;
                suiteParentIndent = indent;
            }
            if (looksIncompleteStatement(trimmed)) {
                unverified = true;
                unverifiedLine = firstLine(unverifiedLine, index + 1);
            }
        }
        if (bracketDepth != 0 || requiresIndentedSuite) {
            return new StructuralResult(maskedLines.length, unverified, unverifiedLine);
        }
        return new StructuralResult(null, unverified, unverifiedLine);
    }

    private static boolean startsBlockHeader(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("def ")
                || lower.startsWith("async def ")
                || lower.startsWith("class ")
                || lower.startsWith("if ")
                || lower.startsWith("elif ")
                || lower.startsWith("for ")
                || lower.startsWith("async for ")
                || lower.startsWith("while ")
                || lower.startsWith("with ")
                || lower.startsWith("async with ")
                || lower.startsWith("match ")
                || lower.startsWith("case ");
    }

    private static HeaderResult headerResult(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        boolean header = lower.startsWith("def ")
                || lower.startsWith("async def ")
                || lower.startsWith("class ")
                || lower.startsWith("if ")
                || lower.startsWith("elif ")
                || lower.equals("else") || lower.startsWith("else:")
                || lower.startsWith("for ") || lower.startsWith("async for ")
                || lower.startsWith("while ")
                || lower.equals("try") || lower.startsWith("try:")
                || lower.equals("except") || lower.startsWith("except ")
                || lower.startsWith("except:")
                || lower.equals("finally") || lower.startsWith("finally:")
                || lower.startsWith("with ") || lower.startsWith("async with ")
                || lower.startsWith("match ") || lower.startsWith("case ");
        if (!header) {
            return new HeaderResult(false, true, false);
        }
        int colon = topLevelColon(line);
        if (colon < 0) {
            return new HeaderResult(true, false, false);
        }
        String prefix = line.substring(0, colon).trim();
        String prefixLower = prefix.toLowerCase(Locale.ROOT);
        boolean valid;
        if (prefixLower.startsWith("def ") || prefixLower.startsWith("async def ")) {
            valid = DEF_HEADER.matcher(prefix).matches();
        } else if (prefixLower.startsWith("class ")) {
            valid = CLASS_HEADER.matcher(prefix).matches()
                    && (!prefix.contains("(") || prefix.endsWith(")"));
        } else if (prefixLower.equals("else")
                || prefixLower.equals("try")
                || prefixLower.equals("finally")) {
            valid = true;
        } else if (prefixLower.startsWith("for ")
                || prefixLower.startsWith("async for ")) {
            valid = prefixLower.contains(" in ");
        } else if (prefixLower.equals("except")
                || prefixLower.startsWith("except ")) {
            valid = true;
        } else {
            int firstSpace = prefix.indexOf(' ');
            valid = firstSpace >= 0 && !prefix.substring(firstSpace + 1).isBlank();
        }
        boolean requiresSuite = valid && line.substring(colon + 1).trim().isEmpty();
        return new HeaderResult(true, valid, requiresSuite);
    }

    private static String maskStringsAndComments(String text) {
        StringBuilder masked = new StringBuilder(text.length());
        char quote = 0;
        boolean triple = false;
        boolean escaped = false;
        boolean comment = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (comment) {
                if (value == '\n' || value == '\r') {
                    comment = false;
                    masked.append(value);
                } else {
                    masked.append(' ');
                }
                continue;
            }
            if (quote != 0) {
                if (value == '\n' || value == '\r') {
                    masked.append(value);
                } else {
                    masked.append(' ');
                }
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (value == '\\') {
                    escaped = true;
                    continue;
                }
                if (triple && value == quote
                        && index + 2 < text.length()
                        && text.charAt(index + 1) == quote
                        && text.charAt(index + 2) == quote) {
                    masked.append("  ");
                    quote = 0;
                    triple = false;
                    index += 2;
                } else if (!triple && value == quote) {
                    quote = 0;
                }
                continue;
            }
            if (value == '#') {
                comment = true;
                masked.append(' ');
            } else if (value == '\'' || value == '"') {
                quote = value;
                triple = index + 2 < text.length()
                        && text.charAt(index + 1) == value
                        && text.charAt(index + 2) == value;
                // Keep a harmless operand marker so `path / "child"` does not
                // become a false trailing-operator finding after masking.
                masked.append('x');
                if (triple) {
                    masked.append("  ");
                    index += 2;
                }
            } else {
                masked.append(value);
            }
        }
        return masked.toString();
    }

    private static int bracketDelta(String line) {
        int delta = 0;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '(' || value == '[' || value == '{') {
                delta += 1;
            } else if (value == ')' || value == ']' || value == '}') {
                delta -= 1;
            }
        }
        return delta;
    }

    private static int topLevelColon(String line) {
        int depth = 0;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '(' || value == '[' || value == '{') {
                depth += 1;
            } else if (value == ')' || value == ']' || value == '}') {
                depth -= 1;
            } else if (value == ':' && depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static boolean hasTopLevelSemicolon(String line) {
        int depth = 0;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '(' || value == '[' || value == '{') {
                depth += 1;
            } else if (value == ')' || value == ']' || value == '}') {
                depth -= 1;
            } else if (value == ';' && depth == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksIncompleteStatement(String line) {
        return line.matches(".*(?:=|\\+|-|\\*|/|%|&|\\||\\^|<|>|,)\\s*$")
                || line.matches("(?i).*\\b(?:and|or|not|in|is)\\s*$");
    }

    private static boolean endsWithExplicitContinuation(String line) {
        return line.stripTrailing().endsWith("\\");
    }

    private static int leadingSpaces(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces += 1;
        }
        return spaces;
    }

    private static boolean leadingWhitespaceContainsTab(String line) {
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '\t') {
                return true;
            }
            if (value != ' ') {
                return false;
            }
        }
        return false;
    }

    private static String root(String module) {
        int dot = module.indexOf('.');
        return dot < 0 ? module : module.substring(0, dot);
    }

    private static Integer firstLine(Integer current, Integer candidate) {
        return current == null ? candidate : current;
    }

    private static CodeRiskScanFinding finding(
            String ruleId,
            String severity,
            String category,
            String path,
            Integer line,
            String message,
            boolean blocking
    ) {
        return new CodeRiskScanFinding(
                ruleId, severity, category, path, line, line, message, blocking
        );
    }

    private record ImportResult(
            boolean importStatement,
            boolean classified,
            List<String> roots
    ) {
    }

    private record StructuralResult(
            Integer errorLine,
            boolean unverified,
            Integer unverifiedLine
    ) {
    }

    private record HeaderResult(boolean blockHeader, boolean valid, boolean requiresSuite) {
    }
}
