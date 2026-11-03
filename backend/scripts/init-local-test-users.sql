-- 仅供本地开发测试：手动执行，不加入 Flyway 自动迁移。
-- 请先执行 module1-schema-postgresql.sql，创建 users、roles 表和角色。
-- 已有账号（包括禁用或软删除账号）保持原样，不重置密码或提升权限。
-- 以下 BCrypt 哈希对应测试密码 123456，兼容后端使用的 BCrypt 实现。
BEGIN;

INSERT INTO users (username, email, password, role_id, status)
VALUES
    ('user01', 'user01@default.com', '$2a$10$tGgssJl/EIMLaYyEUeST1.2D9RpUzkXX378xNzXJksUQOrSRl5M.K', 3, TRUE),
    ('user02', 'user02@default.com', '$2a$10$tGgssJl/EIMLaYyEUeST1.2D9RpUzkXX378xNzXJksUQOrSRl5M.K', 3, TRUE),
    ('admin01', 'admin01@default.com', '$2a$10$tGgssJl/EIMLaYyEUeST1.2D9RpUzkXX378xNzXJksUQOrSRl5M.K', 2, TRUE)
ON CONFLICT (username) DO NOTHING;

COMMIT;
