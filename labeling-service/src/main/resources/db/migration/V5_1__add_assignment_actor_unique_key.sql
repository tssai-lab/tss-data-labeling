-- 为 V6 的 assignment 组合外键提供唯一键；不修改已发布的 V4/V6 迁移。
ALTER TABLE assignment
    ADD CONSTRAINT uq_assignment_id_package_actor
    UNIQUE (id, package_id, actor_id);
