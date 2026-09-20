-- ============================================================================
-- V1 组织、项目与权限
-- 对应设计文档：01-数据模型与DDL.md §3
-- 依据：AGENTS.md 第 2 条（角色职责）、第 8 条（权限约束）
-- ============================================================================

SET NAMES utf8mb4;

-- ── 用户 ────────────────────────────────────────────────────────────────────
CREATE TABLE sys_user (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username     VARCHAR(64)     NOT NULL COMMENT '登录名',
  display_name VARCHAR(64)     NOT NULL COMMENT '显示名',
  dept         VARCHAR(128)    NULL,
  email_enc    VARBINARY(256)  NULL COMMENT '邮箱（加密存储，不得明文）',
  status       VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username),
  CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- ── 角色 ────────────────────────────────────────────────────────────────────
-- LEGAL  = 法务（唯一可签名、关闭风险、批准版本的角色）
-- BRAND/DESIGN/BIZ = 提交物料、上传修改版本，但不能代替法务关闭风险或批准
-- ADMIN  = 维护权限/知识库/产品参数/审计配置，刻意不具备业务审批权（职责分离）
CREATE TABLE sys_role (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code        VARCHAR(32)     NOT NULL COMMENT 'LEGAL/BRAND/DESIGN/BIZ/ADMIN/AI_SERVICE',
  name        VARCHAR(64)     NOT NULL,
  description VARCHAR(255)    NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

CREATE TABLE sys_user_role (
  user_id BIGINT UNSIGNED NOT NULL,
  role_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (user_id, role_id),
  KEY idx_ur_role (role_id),
  CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色';

-- 权限点：code 与 gw-common 的 PermCode 常量、前端 TS 联合类型三处同源
CREATE TABLE sys_permission (
  id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code     VARCHAR(96)     NOT NULL COMMENT '如 risk.close / material.approve',
  name     VARCHAR(64)     NOT NULL,
  category VARCHAR(32)     NOT NULL COMMENT 'API/BUTTON',
  PRIMARY KEY (id),
  UNIQUE KEY uk_perm_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限点';

CREATE TABLE sys_role_permission (
  role_id       BIGINT UNSIGNED NOT NULL,
  permission_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (role_id, permission_id),
  KEY idx_rp_perm (permission_id),
  CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
  CONSTRAINT fk_rp_perm FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限';

-- ── 项目（数据隔离的最小单位） ────────────────────────────────────────────────
-- 跨项目检索历史材料被 AGENTS.md 第 12 条明令禁止，因此所有业务查询必须带 project_id
CREATE TABLE biz_project (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code       VARCHAR(32)     NOT NULL,
  name       VARCHAR(128)    NOT NULL,
  owner_id   BIGINT UNSIGNED NOT NULL,
  status     VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_code (code),
  CONSTRAINT fk_project_owner FOREIGN KEY (owner_id) REFERENCES sys_user(id),
  CONSTRAINT ck_project_status CHECK (status IN ('ACTIVE','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目';

CREATE TABLE biz_project_member (
  project_id BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NOT NULL,
  role_code  VARCHAR(32)     NOT NULL,
  PRIMARY KEY (project_id, user_id),
  KEY idx_pm_user (user_id),
  CONSTRAINT fk_pm_project FOREIGN KEY (project_id) REFERENCES biz_project(id),
  CONSTRAINT fk_pm_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目成员';
