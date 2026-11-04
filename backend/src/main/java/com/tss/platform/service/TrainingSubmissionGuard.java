package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tss.platform.dto.TrainingExperimentVersionDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/** 只协调训练创建：办理编号防重、同实验版本号分配；锁随现有事务结束释放。 */
@Component
public class TrainingSubmissionGuard {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TrainingSubmissionGuard(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public TrainingExperimentVersionDto createOnce(Integer actor, String scope, String key, Object request,
            Supplier<TrainingExperimentVersionDto> create, Function<String, TrainingExperimentVersionDto> replay) {
        // 旧客户端不传编号，仍按原协议创建。
        if (key == null) return create.get();
        if (!key.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new IllegalArgumentException("submissionKey 必须是 16 至 128 位字母、数字、横线或下划线");
        }
        String identity = sha256(actor + "\n" + scope + "\n" + key);
        String fingerprint = sha256(canonical(mapper.valueToTree(request)).toString());
        lock("submission:" + identity);
        var previous = jdbc.query("SELECT request_sha256, training_id FROM training_submission WHERE request_id = ?",
                (rs, row) -> new Receipt(rs.getString(1), rs.getString(2)), identity);
        if (!previous.isEmpty()) {
            Receipt receipt = previous.get(0);
            if (!receipt.fingerprint().equals(fingerprint)) {
                throw new IllegalArgumentException("本次提交编号已用于其他参数，请重新发起训练");
            }
            // 仍通过原详情服务校验权限；任务已删除时不能因重试再次创建。
            return replay.apply(receipt.trainingId());
        }
        TrainingExperimentVersionDto created = create.get();
        jdbc.update("INSERT INTO training_submission(request_id, request_sha256, training_id) VALUES (?, ?, ?)",
                identity, fingerprint, created.getId());
        return created;
    }

    public void lockExperiment(String experimentId) {
        lock("experiment:" + experimentId);
    }

    private void lock(String identity) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("训练创建协调必须在事务内执行");
        }
        // PostgreSQL 事务锁跨线程/进程生效，无需新建分布式锁服务或定时清理锁记录。
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, identity);
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            TreeSet<String> fields = new TreeSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.forEach(name -> sorted.set(name, canonical(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode values = mapper.createArrayNode();
            node.forEach(value -> values.add(canonical(value)));
            return values;
        }
        return node;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Receipt(String fingerprint, String trainingId) { }
}
