package com.tss.platform.module1.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** 生成只在新增/恢复账号响应中返回一次的随机临时密码。 */
@Component
public class TemporaryPasswordGenerator {

    private static final int PASSWORD_LENGTH = 16;
    private static final char[] UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWER = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] DIGITS = "23456789".toCharArray();
    private static final char[] SYMBOLS = "!@#$%*+-_".toCharArray();
    private static final char[] ALL = (
            new String(UPPER) + new String(LOWER) + new String(DIGITS) + new String(SYMBOLS)
    ).toCharArray();

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        char[] password = new char[PASSWORD_LENGTH];
        password[0] = randomFrom(UPPER);
        password[1] = randomFrom(LOWER);
        password[2] = randomFrom(DIGITS);
        password[3] = randomFrom(SYMBOLS);
        for (int i = 4; i < password.length; i++) {
            password[i] = randomFrom(ALL);
        }
        for (int i = password.length - 1; i > 0; i--) {
            int j = secureRandom.nextInt(i + 1);
            char current = password[i];
            password[i] = password[j];
            password[j] = current;
        }
        return new String(password);
    }

    private char randomFrom(char[] characters) {
        return characters[secureRandom.nextInt(characters.length)];
    }
}
