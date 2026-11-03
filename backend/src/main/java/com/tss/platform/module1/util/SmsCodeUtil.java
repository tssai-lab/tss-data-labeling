package com.tss.platform.module1.util;

import org.springframework.stereotype.Component;
import com.tss.platform.module1.sms.SmsPurpose;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component // 交给Spring管理，可注入
public class SmsCodeUtil {

    // 只有 local 开发模式保存明文验证码；真实供应商只保存“已发码”标记。
    private final Map<String, String> localCodeCache = new ConcurrentHashMap<>();
    // 内存缓存发送时间：key=手机号，value=发送时间戳（秒）
    private final Map<String, Long> issueTimeCache = new ConcurrentHashMap<>();
    private final Map<String, SmsPurpose> purposeCache = new ConcurrentHashMap<>();

    private static final long EXPIRE = 300; // 验证码5分钟过期（300秒）
    private static final long LIMIT = 60;   // 60秒防刷（同一手机号只能发一次）
    public void saveLocal(String mobile, String code, SmsPurpose purpose) {
        String key = normalize(mobile);
        localCodeCache.put(key, code);
        issueTimeCache.put(key, now());
        purposeCache.put(key, purpose);
    }

    public void markIssued(String mobile, SmsPurpose purpose) {
        String key = normalize(mobile);
        localCodeCache.remove(key);
        issueTimeCache.put(key, now());
        purposeCache.put(key, purpose);
    }

    public boolean hasActiveIssue(String mobile, SmsPurpose purpose) {
        if (mobile == null) {
            return false;
        }
        String key = normalize(mobile);
        Long issueTime = issueTimeCache.get(key);
        if (issueTime == null) {
            return false;
        }
        if (now() - issueTime > EXPIRE) {
            consume(key);
            return false;
        }
        return purpose == purposeCache.get(key);
    }

    public boolean hasLocalCode(String mobile) {
        return mobile != null && localCodeCache.containsKey(normalize(mobile));
    }

    /** 仅供 local 开发模式校验，不消耗验证码。 */
    public boolean verifyLocal(String mobile, String code, SmsPurpose purpose) {
        if (mobile == null || code == null) {
            return false;
        }
        String key = normalize(mobile);
        if (!hasActiveIssue(key, purpose)) {
            return false;
        }
        return code.trim().equals(localCodeCache.get(key));
    }

    /** 校验通过后消耗验证码 */
    public void consume(String mobile) {
        if (mobile == null) {
            return;
        }
        String key = normalize(mobile);
        localCodeCache.remove(key);
        issueTimeCache.remove(key);
        purposeCache.remove(key);
    }

    // 检查是否60秒内重复发送
    public boolean isLimited(String mobile) {
        if (mobile == null) {
            return false;
        }
        String key = normalize(mobile);
        Long sendTime = issueTimeCache.get(key);
        if (sendTime == null) {
            return false;
        }
        long age = now() - sendTime;
        if (age > EXPIRE) {
            consume(key);
            return false;
        }
        return age < LIMIT;
    }

    private static String normalize(String mobile) {
        return mobile == null ? "" : mobile.trim();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
