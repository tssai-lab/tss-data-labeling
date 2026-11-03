package com.tss.platform.module1.util;

/**
 * 日志查询数据范围（由 Token 角色决定，不信任前端入参）
 */
public enum LogAccessScope {
    /** 超管：全部，可含 IP */
    ALL,
    /** 普管：仅普通用户操作日志，不含 IP */
    NORMAL_USERS_ONLY,
    /** 普通用户：仅本人 */
    SELF_ONLY
}
