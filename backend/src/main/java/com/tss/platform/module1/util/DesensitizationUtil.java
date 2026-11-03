package com.tss.platform.module1.util;

public class DesensitizationUtil {

    public static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() != 11) {
            return mobile;
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(7);
    }

    public static String maskPassword(String password) {
        if (password == null || password.length() < 4) {
            return "****";
        }
        int len = password.length();
        return password.substring(0, 3) + "****" + password.substring(len - 2);
    }

    public static String maskUsername(String username) {
        if (username == null || username.length() <= 2) {
            return username == null ? null : username.charAt(0) + "***";
        }
        return username.charAt(0) + "***" + username.substring(username.length() - 1);
    }
}
