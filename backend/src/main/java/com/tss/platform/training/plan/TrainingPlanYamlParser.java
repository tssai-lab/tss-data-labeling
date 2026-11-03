package com.tss.platform.training.plan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.scanner.Scanner;
import org.yaml.snakeyaml.scanner.ScannerImpl;
import org.yaml.snakeyaml.tokens.Token;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Strict, side-effect-free parser shared by built-in and future uploaded plans. */
@Component
public class TrainingPlanYamlParser {

    public static final int MAX_BYTES = 256 * 1024;

    private final ObjectMapper mapper;
    private final LoaderOptions loaderOptions;

    public TrainingPlanYamlParser() {
        loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setWarnOnDuplicateKeys(false);
        loaderOptions.setAllowRecursiveKeys(false);
        loaderOptions.setMaxAliasesForCollections(0);
        loaderOptions.setNestingDepthLimit(32);
        loaderOptions.setCodePointLimit(MAX_BYTES);
        loaderOptions.setMergeOnCompose(false);
        loaderOptions.setTagInspector(tag -> false);

        YAMLFactory factory = YAMLFactory.builder()
                .loaderOptions(loaderOptions)
                .build();
        mapper = new ObjectMapper(factory)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public TrainingPlanDefinition parse(byte[] bytes, String source) {
        String safeSource = source == null || source.isBlank() ? "<unknown>" : source;
        if (bytes == null || bytes.length == 0) {
            throw invalid(safeSource, "YAML_EMPTY", "文件不能为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw invalid(safeSource, "YAML_TOO_LARGE", "文件不能超过 " + MAX_BYTES + " 字节");
        }

        String yaml = decodeUtf8(bytes, safeSource);
        if (yaml.charAt(0) == '\uFEFF') {
            throw invalid(safeSource, "YAML_BOM_NOT_ALLOWED", "文件不能包含 UTF-8 BOM");
        }
        if (yaml.isBlank()) {
            throw invalid(safeSource, "YAML_EMPTY", "文件不能为空");
        }
        rejectAdvancedYamlTokens(yaml, safeSource);

        try (JsonParser parser = mapper.getFactory().createParser(yaml)) {
            TrainingPlanDefinition plan = mapper.readValue(parser, TrainingPlanDefinition.class);
            if (parser.nextToken() != null) {
                throw invalid(safeSource, "YAML_MULTIPLE_DOCUMENTS", "只允许一个 YAML 文档");
            }
            return plan;
        } catch (TrainingPlanValidationException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            int line = exception.getLocation() == null ? -1 : exception.getLocation().getLineNr();
            int column = exception.getLocation() == null ? -1 : exception.getLocation().getColumnNr();
            String location = line > 0 ? "第 " + line + " 行，第 " + Math.max(column, 1) + " 列" : "未知位置";
            throw invalid(safeSource, "YAML_PARSE_ERROR", "YAML 语法或字段非法（" + location + "）");
        } catch (Exception exception) {
            throw invalid(safeSource, "YAML_PARSE_ERROR", "YAML 解析失败");
        }
    }

    private void rejectAdvancedYamlTokens(String yaml, String source) {
        try {
            Scanner scanner = new ScannerImpl(new StreamReader(yaml), loaderOptions);
            while (scanner.checkToken()) {
                Token token = scanner.getToken();
                Token.ID id = token.getTokenId();
                if (id == Token.ID.Anchor || id == Token.ID.Alias) {
                    throw invalid(source, "YAML_ALIAS_NOT_ALLOWED", "禁止使用 YAML 锚点和别名");
                }
                if (id == Token.ID.Tag || id == Token.ID.Directive) {
                    throw invalid(source, "YAML_TAG_NOT_ALLOWED", "禁止使用 YAML 标签和指令");
                }
                if (id == Token.ID.StreamEnd) {
                    return;
                }
            }
        } catch (TrainingPlanValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid(source, "YAML_PARSE_ERROR", "YAML 词法解析失败");
        }
    }

    private String decodeUtf8(byte[] bytes, String source) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid(source, "YAML_INVALID_UTF8", "文件必须使用有效的 UTF-8 编码");
        }
    }

    private TrainingPlanValidationException invalid(String source, String code, String message) {
        return new TrainingPlanValidationException(source, List.of(code + ": " + message));
    }

}
