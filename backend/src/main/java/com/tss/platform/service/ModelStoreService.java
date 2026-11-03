package com.tss.platform.service;

import com.tss.platform.model.ModelRecord;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型记录内存存储（生产可改为数据库）
 */
@Service
public class ModelStoreService {

    private final ConcurrentHashMap<String, ModelRecord> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    public ModelRecord save(ModelRecord record) {
        if (record.getId() == null) {
            record.setId("model-" + idGen.getAndIncrement());
        }
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(java.time.Instant.now().toString());
        }
        store.put(record.getId(), record);
        return record;
    }

    public Optional<ModelRecord> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<ModelRecord> findAll() {
        return List.copyOf(store.values());
    }

    public void delete(String id) {
        store.remove(id);
    }
}
