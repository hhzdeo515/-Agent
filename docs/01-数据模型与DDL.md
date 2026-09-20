# 数据模型与 DDL

> 上级文档：`00-技术方案与架构设计.md`
> 数据库：MySQL 8.0.16+（需 CHECK 约束支持）
> 迁移工具：Flyway（`deploy/db/migration/V*.sql`）

---

## 1. 设计原则

这五条是不可协商的，每条都对应 `AGENTS.md` 的明文要求，并配有数据库级保障。

| # | 原则 | 依据 | 数据库级保障 |
| --- | --- | --- | --- |
| P1 | **版本不可覆盖**：新文件不得覆盖旧版本 | 第 7 条 | `material_version` 表**只允许 INSERT**，触发器拒绝 UPDATE/DELETE |
| P2 | **历史不可删除**：风险解决表现为状态进 `Closed`，不得删除记录 | 第 9 条 | `risk_case` / `review_record` / `legal_signature` 拒绝 DELETE |
| P3 | **批准必须绑定精确版本**：对外批准的文件要能反查到批准时用的准确版本 | 第 9 条 | `material_approval` 外键指向 `version_id`（非 material_id），并存 `file_sha256` 快照 |
| P4 | **AI 不得替代法务**：AI 无权签名/关闭/批准 | 第 1、8 条 | 触发器校验 `legal_signature.signer_id` 必须是具备签名权限的人类账号；`to_status='Closed'` 必须存在签名记录 |
| P5 | **全程审计**：每次 AI 判断、人工意见、状态变化、签名、关闭都留痕 | 第 9、12 条 | 所有状态变更必须写 `review_record`；触发器强制校验 |

---

## 2. 实体关系

```
biz_project ──┐
              │
        audit_case ──┬── material ──┬── material_version ──┬── parse_job ── parse_artifact
                     │              │                      │
                     │              │                      └── evidence_anchor ──┐
                     │              │                                            │
                     │              └────────────────────────────────────────────┤
                     │                                                           │
                     └── risk_case ──┬── risk_anchor_ref ────────────────────────┘
                                     ├── review_record
                                     ├── legal_signature
                                     └── material_approval

kb_item ── kb_item_version ── kb_chunk
risk_rule ── risk_rule_kb_ref ── kb_item_version

ai_invocation   (AI 调用与成本，按 case/risk 归集)
async_task      (异步任务与幂等)
audit_log       (操作审计)
```

核心链路：**Case → Material → Version → Risk Case → Review Record**（`AGENTS.md` 第 9 条）。
**Risk Case 必须始终关联"首次发现它的版本"**（`first_version_id`），同时记录 `current_version_id` 表示当前正在处理的版本。

---

## 3. 组织、项目与权限

```sql
-- 用户
CREATE TABLE sys_user (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username      VARCHAR(64)     NOT NULL COMMENT '登录名',
  display_name  VARCHAR(64)     NOT NULL COMMENT '显示名',
  dept          VARCHAR(128)    NULL,
  email_enc     VARBINARY(256)  NULL COMMENT '邮箱（加密存储）',
  status        VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username),
  CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- 角色
CREATE TABLE sys_role (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code        VARCHAR(32)     NOT NULL COMMENT 'LEGAL/BRAND/DESIGN/BIZ/ADMIN',
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

-- 权限点：与后端 @PreAuthorize 的 code 一一对应，保证"前端按钮/后端接口/DB"同源
CREATE TABLE sys_permission (
  id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code     VARCHAR(96)     NOT NULL COMMENT '如 risk.confirm / risk.close / material.approve',
  name     VARCHAR(64)     NOT NULL,
  category VARCHAR(32)     NOT NULL COMMENT 'API/BUTTON',
  PRIMARY KEY (id),
  UNIQUE KEY uk_perm_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限点';

CREATE TABLE sys_role_permission (
  role_id       BIGINT UNSIGNED NOT NULL,
  permission_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
  CONSTRAINT fk_rp_perm FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限';

-- 项目（数据隔离的最小单位；跨项目检索必须被拒绝）
CREATE TABLE biz_project (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code       VARCHAR(32)     NOT NULL,
  name       VARCHAR(128)    NOT NULL,
  owner_id   BIGINT UNSIGNED NOT NULL,
  status     VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_code (code),
  CONSTRAINT fk_project_owner FOREIGN KEY (owner_id) REFERENCES sys_user(id)
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
```

> **数据隔离说明**：所有业务查询必须携带 `project_id` 过滤，由 `gw-app` 层的数据权限拦截器统一注入，**不允许在 Controller 里手工拼接**（否则迟早漏一个）。跨项目检索历史材料是 `AGENTS.md` 第 12 条明令禁止的。

---

## 4. 核心业务表

### 4.1 审核任务 Case

```sql
CREATE TABLE audit_case (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_no           VARCHAR(32)     NOT NULL COMMENT '业务编号，如 GX-2026-0001',
  name              VARCHAR(200)    NOT NULL,
  project_id        BIGINT UNSIGNED NOT NULL,
  submitter_id      BIGINT UNSIGNED NOT NULL COMMENT '提交人',
  owner_id          BIGINT UNSIGNED NOT NULL COMMENT '负责人（法务）',
  deadline          DATETIME(3)     NULL COMMENT '审核截止时间',
  status            VARCHAR(32)     NOT NULL DEFAULT 'DRAFT' COMMENT '见 02 文档状态机',
  review_requirement JSON           NULL COMMENT '审核要求（含模板来源与人工确认标记）',
  requirement_template_code VARCHAR(64) NULL COMMENT '所用模板；为空表示未用模板',
  requirement_confirmed_by  BIGINT UNSIGNED NULL COMMENT '模板内容经谁确认生效（AGENTS.md 第 4 条）',
  requirement_confirmed_at  DATETIME(3)     NULL,
  material_count    INT UNSIGNED    NOT NULL DEFAULT 0,
  lock_version      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '乐观锁',
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_case_no (case_no),
  KEY idx_case_project_status (project_id, status),
  KEY idx_case_owner (owner_id, status),
  CONSTRAINT fk_case_project FOREIGN KEY (project_id) REFERENCES biz_project(id),
  CONSTRAINT fk_case_submitter FOREIGN KEY (submitter_id) REFERENCES sys_user(id),
  CONSTRAINT fk_case_owner FOREIGN KEY (owner_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核任务';
```

> `review_requirement` 存 JSON，结构见 §4.6。`requirement_confirmed_by/at` **必须非空才允许 Case 进入 `AI_REVIEWING`**——这是 `AGENTS.md` 第 4 条"不得把模板内容默认为正式要求，用户选择或确认后才生效"的落地方式。

### 4.2 宣传物料 Material

```sql
CREATE TABLE material (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_id            BIGINT UNSIGNED NOT NULL,
  name               VARCHAR(255)    NOT NULL,
  material_type      VARCHAR(16)     NOT NULL COMMENT 'IMAGE/VIDEO/TEXT/PPT/PDF/WORD',
  parse_status       VARCHAR(24)     NOT NULL DEFAULT 'PENDING'
                     COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/PARTIAL',
  parse_error_code   VARCHAR(64)     NULL,
  parse_error_message VARCHAR(500)   NULL COMMENT '明确告诉用户需重传或补充什么',
  current_version_id BIGINT UNSIGNED NULL COMMENT '当前版本（初始为 V1）',
  initial_review_class VARCHAR(24)   NULL COMMENT 'INITIAL_PASS/PENDING_HUMAN/RISK_FAIL（由视图重算）',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_material_case (case_id),
  KEY idx_material_parse (case_id, parse_status),
  CONSTRAINT fk_material_case FOREIGN KEY (case_id) REFERENCES audit_case(id),
  CONSTRAINT ck_material_type CHECK (material_type IN ('IMAGE','VIDEO','TEXT','PPT','PDF','WORD')),
  CONSTRAINT ck_material_parse CHECK (parse_status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','PARTIAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宣传物料';
```

> `initial_review_class` 是**冗余缓存列**，由 §8 的视图口径重算。冗余是为了列表查询性能，但**必须以视图为准**，任何写路径都必须从视图结果回填，禁止独立计算（否则会出现"界面说通过、库里说风险"的矛盾）。

### 4.3 物料版本 Version（只允许 INSERT）

```sql
CREATE TABLE material_version (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  material_id       BIGINT UNSIGNED NOT NULL,
  version_no        INT UNSIGNED    NOT NULL COMMENT 'V1=1, V2=2 ...',
  version_label     VARCHAR(16)     NOT NULL COMMENT 'V1/V2/V3',
  file_object_key   VARCHAR(512)    NOT NULL COMMENT 'MinIO 对象键（含 sha256，天然不可变）',
  file_sha256       CHAR(64)        NOT NULL,
  file_size         BIGINT UNSIGNED NOT NULL,
  mime_type         VARCHAR(128)    NOT NULL,
  uploader_id       BIGINT UNSIGNED NOT NULL,
  upload_reason     VARCHAR(1000)   NULL COMMENT '修改说明（V2+ 必填）',
  parent_version_id BIGINT UNSIGNED NULL COMMENT '上一版本',
  media_meta        JSON            NULL COMMENT '媒体元数据：duration_ms / width / height / page_count / frame_count 等。视频时长落此列，供时间轴渲染与解析进度分母使用',
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_version_material_no (material_id, version_no),
  UNIQUE KEY uk_version_sha (material_id, file_sha256) COMMENT '同物料下同哈希只允许一个版本，天然幂等',
  KEY idx_version_material (material_id),
  CONSTRAINT fk_version_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT fk_version_parent FOREIGN KEY (parent_version_id) REFERENCES material_version(id),
  CONSTRAINT fk_version_uploader FOREIGN KEY (uploader_id) REFERENCES sys_user(id),
  CONSTRAINT ck_version_no CHECK (version_no >= 1),
  CONSTRAINT ck_version_reason CHECK (version_no = 1 OR (upload_reason IS NOT NULL AND CHAR_LENGTH(upload_reason) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物料版本（不可覆盖）';
```

`ck_version_reason` 用数据库约束强制"V2 及以后必须有修改说明"（`AGENTS.md` 第 7 条）。**这类要求写在 CHECK 里比写在 Service 里可靠得多**——不会有绕过路径。

拒绝 UPDATE/DELETE 的触发器见 §7.1。

### 4.4 解析任务与产物

```sql
CREATE TABLE parse_job (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  material_version_id BIGINT UNSIGNED NOT NULL,
  job_type           VARCHAR(32)     NOT NULL COMMENT 'FULL/OCR/ASR/DOC/FRAME/RETRY',
  status             VARCHAR(16)     NOT NULL DEFAULT 'QUEUED'
                     COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED',
  progress           TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0-100，真实进度',
  attempt            TINYINT UNSIGNED NOT NULL DEFAULT 0,
  idempotency_key    VARCHAR(160)    NOT NULL,
  pipeline_version   VARCHAR(32)     NOT NULL COMMENT '解析流水线版本',
  error_code         VARCHAR(64)     NULL,
  error_message      VARCHAR(500)    NULL,
  started_at         DATETIME(3)     NULL,
  finished_at        DATETIME(3)     NULL,
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_parse_idem (idempotency_key) COMMENT '同一版本同一流水线版本只跑一次',
  KEY idx_parse_version (material_version_id),
  KEY idx_parse_status (status, created_at),
  CONSTRAINT fk_parse_version FOREIGN KEY (material_version_id) REFERENCES material_version(id),
  CONSTRAINT ck_parse_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  CONSTRAINT ck_parse_progress CHECK (progress <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='解析任务';

-- ASR 结果 URL 仅 24 小时有效，落盘后必须记录本地对象键
CREATE TABLE parse_artifact (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  parse_job_id       BIGINT UNSIGNED NOT NULL,
  material_version_id BIGINT UNSIGNED NOT NULL,
  artifact_type      VARCHAR(24)     NOT NULL
                     COMMENT 'OCR/ASR/ASR_RAW/KEYFRAME/SUBTITLE/DOC_TEXT/VISUAL/SCENE',
  payload            JSON            NULL COMMENT '结构化产物',
  raw_object_key     VARCHAR(512)    NULL COMMENT '原始结果落盘键（ASR transcription.json 等）',
  source_expires_at  DATETIME(3)     NULL COMMENT '源 URL 过期时间（ASR 为 24h），用于告警',
  engine             VARCHAR(64)     NOT NULL COMMENT '如 qwen-vl-ocr',
  engine_version     VARCHAR(64)     NULL COMMENT '快照版本，如 2025-11-20',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_artifact_job (parse_job_id),
  KEY idx_artifact_version_type (material_version_id, artifact_type),
  KEY idx_artifact_expire (source_expires_at) COMMENT '用于"未落盘即将过期"告警',
  CONSTRAINT fk_artifact_job FOREIGN KEY (parse_job_id) REFERENCES parse_job(id),
  CONSTRAINT fk_artifact_version FOREIGN KEY (material_version_id) REFERENCES material_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='解析产物';
```

### 4.5 证据锚点 Evidence Anchor

这是定位能力的地基（见 `00` 文档 §3）。

```sql
CREATE TABLE evidence_anchor (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  anchor_id           VARCHAR(32)     NOT NULL COMMENT '业务锚点 ID，如 A-0007，模型引用此值',
  material_version_id BIGINT UNSIGNED NOT NULL,
  anchor_type         VARCHAR(24)     NOT NULL
                      COMMENT 'TEXT_LINE/SPEECH_SENTENCE/SUBTITLE_LINE/KEY_FRAME/DOC_PARAGRAPH/DOC_SENTENCE',
  locator             JSON            NOT NULL COMMENT '类型相关位置信息，见 §6',
  text                TEXT            NULL COMMENT '锚点文本（画面类可为空）',
  confidence          DECIMAL(5,4)    NULL COMMENT 'OCR/ASR 置信度 0-1',
  source_engine       VARCHAR(64)     NOT NULL,
  source_engine_version VARCHAR(64)   NULL,
  ordinal             INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '同类型内顺序，用于稳定排序',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_anchor_vid_aid (material_version_id, anchor_id),
  KEY idx_anchor_version_type (material_version_id, anchor_type, ordinal),
  KEY idx_anchor_confidence (material_version_id, confidence) COMMENT '低置信度锚点审查用',
  CONSTRAINT fk_anchor_version FOREIGN KEY (material_version_id) REFERENCES material_version(id),
  CONSTRAINT ck_anchor_type CHECK (anchor_type IN
    ('TEXT_LINE','SPEECH_SENTENCE','SUBTITLE_LINE','KEY_FRAME','DOC_PARAGRAPH','DOC_SENTENCE')),
  CONSTRAINT ck_anchor_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证据锚点（版本内不可变）';
```

> **锚点只在版本内不可变**：新版本生成新锚点集，旧版本的锚点行必须保留。这是 §7.1 触发器的作用范围——`evidence_anchor` 允许 INSERT，**禁止 UPDATE/DELETE**。

### 4.6 风险记录 Risk Case

```sql
CREATE TABLE risk_case (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  risk_no            VARCHAR(40)     NOT NULL COMMENT '业务编号，如 RK-2026-0001-03',
  case_id            BIGINT UNSIGNED NOT NULL,
  material_id        BIGINT UNSIGNED NOT NULL,
  first_version_id   BIGINT UNSIGNED NOT NULL COMMENT '首次发现该风险的版本（永不改变）',
  current_version_id BIGINT UNSIGNED NOT NULL COMMENT '当前正在处理的版本',
  risk_type          VARCHAR(48)     NOT NULL
                     COMMENT 'ABSOLUTE_CLAIM/EVIDENCE_MISSING/SAFETY_PROMISE/COMPETITOR_COMPARISON/PRICE_CLAIM/DISCLAIMER_MISSING/MISLEADING/OTHER',
  risk_level         VARCHAR(8)      NOT NULL COMMENT 'HIGH/MEDIUM/LOW（潜在影响）',
  confidence         DECIMAL(5,4)    NOT NULL COMMENT 'AI 置信度（对识别结果的把握，独立维度）',
  status             VARCHAR(32)     NOT NULL COMMENT '见 02 文档状态机',
  risk_text          TEXT            NOT NULL COMMENT '风险原文或画面内容',
  location_desc      VARCHAR(500)    NULL COMMENT '人类可读位置描述（如"第2页第3段"）',
  region_hint        VARCHAR(24)     NULL COMMENT '画面类风险的大致区域，无文字锚点时使用',
  reason             TEXT            NOT NULL COMMENT '风险原因',
  rule_refs          JSON            NOT NULL COMMENT '知识库 rule/kb_item_version 的 ID 数组（防虚构，见下）',
  evidence_refs      JSON            NOT NULL COMMENT '证据引用',
  unsupported_claims JSON            NOT NULL COMMENT '模型想引用但检索不到依据的内容，必须转人工',
  suggestion         TEXT            NULL COMMENT 'AI 修改建议',
  recommended_copy   TEXT            NULL COMMENT '推荐表达',
  required_evidence  TEXT            NULL COMMENT '所需证明材料',
  blocked            TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否阻断性风险（阻断性未关闭则物料不能批准）',
  assignee_id        BIGINT UNSIGNED NULL COMMENT '责任方/整改执行人（RISK_TO_REMEDIATION 时必填，02 §3.3 守卫依赖此列）',
  remediation_due_at DATETIME(3)     NULL COMMENT '整改期望完成时间',
  parent_risk_id     BIGINT UNSIGNED NULL COMMENT '父风险：整改后发现的新增风险关联原风险，禁止改写原风险含义（AGENTS.md 第 7 条）',
  dedup_key          CHAR(64)        NOT NULL COMMENT 'version_id+risk_type+主锚点+归一化原文哈希',
  model_id           VARCHAR(64)     NOT NULL COMMENT '产生该判断的模型',
  pipeline_version   VARCHAR(32)     NOT NULL,
  prompt_version     VARCHAR(32)     NOT NULL,
  ruleset_version    VARCHAR(32)     NOT NULL,
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  closed_at          DATETIME(3)     NULL,
  lock_version       INT UNSIGNED    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_risk_no (risk_no),
  UNIQUE KEY uk_risk_dedup (dedup_key) COMMENT '防重复建单（AGENTS.md 第 14 条）',
  KEY idx_risk_case_status (case_id, status),
  KEY idx_risk_material (material_id, status),
  KEY idx_risk_first_version (first_version_id),
  KEY idx_risk_parent (parent_risk_id) COMMENT '追溯整改中发现的新增风险',
  KEY idx_risk_assignee (assignee_id, status) COMMENT '按责任方筛选待办（终审区责任人视图）',
  KEY idx_risk_level_status (risk_level, status) COMMENT '高等级未关闭风险的看板查询',
  CONSTRAINT fk_risk_case FOREIGN KEY (case_id) REFERENCES audit_case(id),
  CONSTRAINT fk_risk_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT fk_risk_first_version FOREIGN KEY (first_version_id) REFERENCES material_version(id),
  CONSTRAINT fk_risk_current_version FOREIGN KEY (current_version_id) REFERENCES material_version(id),
  CONSTRAINT fk_risk_parent FOREIGN KEY (parent_risk_id) REFERENCES risk_case(id),
  CONSTRAINT fk_risk_assignee FOREIGN KEY (assignee_id) REFERENCES sys_user(id),
  CONSTRAINT ck_risk_level CHECK (risk_level IN ('HIGH','MEDIUM','LOW')),
  CONSTRAINT ck_risk_confidence CHECK (confidence >= 0 AND confidence <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险记录';

-- 风险与锚点的多对多：一条 Risk Case 可同时引用口播句、字幕行、关键帧
CREATE TABLE risk_anchor_ref (
  risk_case_id BIGINT UNSIGNED NOT NULL,
  anchor_id    VARCHAR(32)     NOT NULL COMMENT '引用 evidence_anchor.anchor_id',
  material_version_id BIGINT UNSIGNED NOT NULL COMMENT '锚点所属版本，用于校验一致性',
  ref_role     VARCHAR(16)     NOT NULL DEFAULT 'PRIMARY' COMMENT 'PRIMARY/SUPPORTING',
  PRIMARY KEY (risk_case_id, anchor_id),
  KEY idx_rar_anchor (material_version_id, anchor_id),
  CONSTRAINT fk_rar_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id),
  -- 复合外键：由数据库保证"锚点必须真实存在于该版本"，不依赖应用层自觉
  -- 引用 evidence_anchor 的 uk_anchor_vid_aid (material_version_id, anchor_id)
  CONSTRAINT fk_rar_anchor FOREIGN KEY (material_version_id, anchor_id)
    REFERENCES evidence_anchor(material_version_id, anchor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险-锚点引用';

-- 风险内容变更历史：保证"不得悄悄改变原风险含义"有数据支撑（AGENTS.md 第 7 条）
-- 每次 risk_text / risk_level / confidence / suggestion / recommended_copy / required_evidence
-- 发生变更前，旧值快照写入本表（由 §7.1 触发器强制执行，不依赖应用层纪律）
CREATE TABLE risk_case_revision (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  risk_case_id        BIGINT UNSIGNED NOT NULL,
  revision_no         INT UNSIGNED    NOT NULL COMMENT '第几次内容变更，从 1 开始',
  material_version_id BIGINT UNSIGNED NOT NULL COMMENT '该次变更前所处的版本',
  risk_text           TEXT            NOT NULL COMMENT '变更前的风险原文快照',
  risk_level          VARCHAR(8)      NOT NULL,
  confidence          DECIMAL(5,4)    NOT NULL,
  suggestion          TEXT            NULL,
  recommended_copy    TEXT            NULL,
  required_evidence   TEXT            NULL,
  reason              TEXT            NOT NULL COMMENT '变更原因（AI 复审 / 法务修订 / 版本推进）',
  changed_by          BIGINT UNSIGNED NULL COMMENT '人工变更时非空',
  ai_model_id         VARCHAR(64)     NULL COMMENT 'AI 变更时非空',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_risk_rev (risk_case_id, revision_no),
  KEY idx_risk_rev_version (material_version_id),
  CONSTRAINT fk_risk_rev_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id),
  CONSTRAINT fk_risk_rev_version FOREIGN KEY (material_version_id) REFERENCES material_version(id),
  CONSTRAINT ck_risk_rev_level CHECK (risk_level IN ('HIGH','MEDIUM','LOW')),
  CONSTRAINT ck_risk_rev_conf CHECK (confidence >= 0 AND confidence <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险内容变更历史（旧值快照，只允许 INSERT）';
```

**三条关键设计说明**

1. **`rule_refs` 只允许存知识库 ID，不允许存模型自由生成的字符串。** 这是防虚构法条的机制：模型无法凭空造出合法的 `kb_item_version_id`。模型若认为需要某条依据但检索不到，只能写入 `unsupported_claims`，服务端据此**强制转 `PENDING_LEGAL_DECISION`**。
2. **`risk_level` / `confidence` / `status` 是三个独立维度**，`AGENTS.md` 第 11 条明确禁止混用。数据库把它们设为三个独立列，界面上也必须用三套视觉编码（见 `04`）。
3. **`first_version_id` 永不改变**，`current_version_id` 随整改推进。`AGENTS.md` 第 9 条要求"Risk Case 可以跨越多个整改版本，但必须始终关联首次发现它的版本"。

### 4.7 审核记录 Review Record（只允许 INSERT）

```sql
CREATE TABLE review_record (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_id            BIGINT UNSIGNED NOT NULL,
  risk_case_id       BIGINT UNSIGNED NULL COMMENT 'Case 级记录可为空',
  material_version_id BIGINT UNSIGNED NULL,
  actor_type         VARCHAR(8)      NOT NULL COMMENT 'AI/HUMAN/SYSTEM',
  actor_id           BIGINT UNSIGNED NULL COMMENT 'HUMAN 时必填；AI 为空',
  ai_model_id        VARCHAR(64)     NULL COMMENT 'AI 时必填；HUMAN 为空',
  action             VARCHAR(48)     NOT NULL
                     COMMENT 'AI_INITIAL_REVIEW/LEGAL_CONFIRM/LEGAL_FALSE_POSITIVE/REQUEST_EVIDENCE/TO_REMEDIATION/UPLOAD_VERSION/AI_REREVIEW/LEGAL_FINAL_REVIEW/SIGN/CLOSE/APPROVE_MATERIAL/REVOKE_APPROVAL/STATUS_CHANGE',
  result             VARCHAR(32)     NULL COMMENT '判断结果',
  opinion            TEXT            NULL COMMENT '人工意见/理由（误判必须非空）',
  from_status        VARCHAR(32)     NULL,
  to_status          VARCHAR(32)     NULL,
  payload            JSON            NULL COMMENT '结构化明细（差异、复审三问结论等）',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_rr_case (case_id, created_at),
  KEY idx_rr_risk (risk_case_id, created_at),
  KEY idx_rr_actor (actor_type, actor_id, created_at),
  CONSTRAINT fk_rr_case FOREIGN KEY (case_id) REFERENCES audit_case(id),
  CONSTRAINT fk_rr_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id),
  CONSTRAINT ck_rr_actor CHECK (actor_type IN ('AI','HUMAN','SYSTEM')),
  -- AI 与 HUMAN 的必填字段互斥，用约束表达而不是靠代码自觉
  CONSTRAINT ck_rr_actor_fields CHECK (
    (actor_type = 'HUMAN' AND actor_id IS NOT NULL AND ai_model_id IS NULL)
 OR (actor_type = 'AI'    AND actor_id IS NULL     AND ai_model_id IS NOT NULL)
 OR (actor_type = 'SYSTEM')
  ),
  -- 误判必须留理由（AGENTS.md 第 5 条：不能删除，必须保存法务理由）
  CONSTRAINT ck_rr_false_positive_opinion CHECK (
    action <> 'LEGAL_FALSE_POSITIVE' OR (opinion IS NOT NULL AND CHAR_LENGTH(opinion) > 0)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核记录（不可变审计链）';
```

`ck_rr_false_positive_opinion` 把"标记误判必须填理由"变成**数据库约束**。这类要求一旦只写在 Service 里，迟早有绕过路径（补数据脚本、内部调用、新接口）。

### 4.8 法务签名与最终批准

```sql
CREATE TABLE legal_signature (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  risk_case_id  BIGINT UNSIGNED NULL COMMENT '风险关闭签名',
  material_id   BIGINT UNSIGNED NULL COMMENT '物料批准签名',
  version_id    BIGINT UNSIGNED NULL COMMENT '批准针对的确切版本',
  signer_id     BIGINT UNSIGNED NOT NULL COMMENT '必须是有签名权限的人类账号',
  signer_role   VARCHAR(32)     NOT NULL,
  sign_type     VARCHAR(24)     NOT NULL COMMENT 'RISK_CLOSE/MATERIAL_APPROVE/REVOKE',
  file_sha256   CHAR(64)        NULL COMMENT '签名时物料的哈希快照（P3）',
  signature_hash CHAR(64)       NOT NULL COMMENT '对签名上下文计算的哈希，防篡改',
  comment       VARCHAR(1000)   NULL,
  signed_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_sig_risk (risk_case_id),
  KEY idx_sig_material (material_id, version_id),
  KEY idx_sig_signer (signer_id, signed_at),
  CONSTRAINT fk_sig_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id),
  CONSTRAINT fk_sig_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT fk_sig_version FOREIGN KEY (version_id) REFERENCES material_version(id),
  CONSTRAINT fk_sig_signer FOREIGN KEY (signer_id) REFERENCES sys_user(id),
  CONSTRAINT ck_sig_type CHECK (sign_type IN ('RISK_CLOSE','MATERIAL_APPROVE','REVOKE')),
  CONSTRAINT ck_sig_target CHECK (
    (sign_type = 'RISK_CLOSE' AND risk_case_id IS NOT NULL)
 OR (sign_type IN ('MATERIAL_APPROVE','REVOKE') AND material_id IS NOT NULL AND version_id IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='法务签名（只允许 INSERT）';

CREATE TABLE material_approval (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  material_id   BIGINT UNSIGNED NOT NULL,
  version_id    BIGINT UNSIGNED NOT NULL COMMENT '最终批准版本（精确绑定，P3）',
  file_sha256   CHAR(64)        NOT NULL COMMENT '批准时哈希快照，用于事后校验未被替换',
  status        VARCHAR(16)     NOT NULL COMMENT 'APPROVED/REVOKED',
  approved_by   BIGINT UNSIGNED NULL,
  approved_at   DATETIME(3)     NULL,
  signature_id  BIGINT UNSIGNED NULL COMMENT '关联签名记录',
  revoked_by    BIGINT UNSIGNED NULL,
  revoked_at    DATETIME(3)     NULL,
  revoke_reason VARCHAR(1000)   NULL,
  PRIMARY KEY (id),
  KEY idx_approval_material (material_id, status),
  CONSTRAINT fk_approval_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT fk_approval_version FOREIGN KEY (version_id) REFERENCES material_version(id),
  CONSTRAINT fk_approval_signature FOREIGN KEY (signature_id) REFERENCES legal_signature(id),
  CONSTRAINT ck_approval_status CHECK (status IN ('APPROVED','REVOKED')),
  CONSTRAINT ck_approval_revoke CHECK (status <> 'REVOKED' OR (revoked_by IS NOT NULL AND revoke_reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物料最终批准';
```

**为什么 `material_approval` 存 `version_id` 而不是 `material_id` + "当前版本"**：`AGENTS.md` 第 9 条明确"对外批准的文件必须能反向追溯到批准时使用的准确版本，不能只关联到一个可能继续被覆盖的文件地址"。存 `version_id` + `file_sha256` 快照，才能在任何时候证明"当时批准的到底是哪份文件"。

### 4.9 AI 调用与成本

```sql
CREATE TABLE ai_invocation (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_id        BIGINT UNSIGNED NULL,
  risk_case_id   BIGINT UNSIGNED NULL,
  stage          VARCHAR(32)     NOT NULL
                 COMMENT 'PARSE_OCR/PARSE_ASR/PARSE_DOC/VISUAL/CANDIDATE/RETRIEVAL/RERANK/JUDGE/STRUCT_OUT/REPORT/ASSISTANT/EMBED',
  provider       VARCHAR(32)     NOT NULL DEFAULT 'DASHSCOPE',
  model_id       VARCHAR(64)     NOT NULL,
  region         VARCHAR(24)     NULL COMMENT '如 cn-beijing',
  batch_mode     TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否走 Batch（成本减半）',
  prompt_version VARCHAR(32)     NULL,
  pipeline_version VARCHAR(32)   NULL,
  ruleset_version VARCHAR(32)    NULL,
  input_tokens   INT UNSIGNED    NOT NULL DEFAULT 0,
  output_tokens  INT UNSIGNED    NOT NULL DEFAULT 0,
  cache_hit_tokens INT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '注意：多数关键模型不支持缓存',
  latency_ms     INT UNSIGNED    NOT NULL DEFAULT 0,
  success        TINYINT(1)      NOT NULL,
  error_code     VARCHAR(64)     NULL,
  retry_of       BIGINT UNSIGNED NULL,
  request_id     VARCHAR(96)     NULL COMMENT '供应商 request_id，便于对账与排查',
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ai_case (case_id, created_at),
  KEY idx_ai_stage_model (stage, model_id, created_at),
  KEY idx_ai_success (success, created_at) COMMENT '失败率监控',
  CONSTRAINT fk_ai_case FOREIGN KEY (case_id) REFERENCES audit_case(id),
  CONSTRAINT fk_ai_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用记录与成本归集';
```

> **成本监控的正确口径**：不要用"缓存命中率"做核心指标（`qwen3-vl-8b-thinking`、`qwen-vl-ocr`、`qwen3.5-ocr` 均不支持上下文缓存）。应监控 **`batch_mode=1` 的调用占比** 与**单 Case token 成本**。

### 4.10 异步任务与幂等

```sql
CREATE TABLE async_task (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_type       VARCHAR(32)     NOT NULL COMMENT 'PARSE/AI_REVIEW/AI_REREVIEW/BATCH_POLL/EXPORT/CLEANUP',
  biz_id          VARCHAR(64)     NOT NULL COMMENT '业务对象 ID',
  idempotency_key VARCHAR(160)    NOT NULL,
  external_task_id VARCHAR(128)   NULL COMMENT '供应商异步任务 ID（如 ASR task_id）',
  status          VARCHAR(16)     NOT NULL DEFAULT 'QUEUED'
                  COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED',
  attempt         TINYINT UNSIGNED NOT NULL DEFAULT 0,
  max_attempt     TINYINT UNSIGNED NOT NULL DEFAULT 5,
  next_retry_at   DATETIME(3)     NULL,
  payload         JSON            NULL,
  result          JSON            NULL,
  error_code      VARCHAR(64)     NULL,
  error_message   VARCHAR(500)    NULL,
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_async_idem (idempotency_key),
  UNIQUE KEY uk_async_external (task_type, external_task_id) COMMENT '回调幂等：同一外部任务只处理一次',
  KEY idx_async_status (status, next_retry_at),
  CONSTRAINT ck_async_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步任务';
```

`uk_async_external` 是回调幂等的关键：ASR 的 EventBridge 回调可能重复投递（官方明确说明），靠这个唯一键保证同一 `task_id` 只落一次结果。

### 4.11 操作审计

```sql
CREATE TABLE audit_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  actor_id      BIGINT UNSIGNED NULL,
  actor_type    VARCHAR(8)      NOT NULL,
  action        VARCHAR(64)     NOT NULL,
  resource_type VARCHAR(32)     NOT NULL,
  resource_id   VARCHAR(64)     NOT NULL,
  before_state  JSON            NULL,
  after_state   JSON            NULL,
  ip            VARCHAR(45)     NULL,
  user_agent    VARCHAR(255)    NULL,
  trace_id      VARCHAR(64)     NULL,
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_resource (resource_type, resource_id, created_at),
  KEY idx_audit_actor (actor_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计';
```

> ⚠️ **`before_state` / `after_state` 严禁写入物料原文全文。** `AGENTS.md` 第 12 条要求"不得把敏感原文完整写入不受控日志"。审计只记录**字段级变更摘要与锚点 ID**，原文通过锚点 ID 回溯（这也是锚点抽象的又一个好处）。

### 4.12 补充业务表

初版遗漏、经 `04` 文档评审后补齐。这五张表覆盖 T-12 / T-13 / T-14 / T-26 / T-31 五个缺口。

```sql
-- T-26：审核要求模板。选用后必须经人工确认才生效（AGENTS.md 第 4 条），
--       生效标记落在 audit_case.requirement_confirmed_by / _at，不落在本表。
CREATE TABLE review_requirement_template (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code       VARCHAR(64)     NOT NULL,
  name       VARCHAR(128)    NOT NULL,
  content    JSON            NOT NULL COMMENT '关注点清单（结构化），如绝对化宣传/安全承诺/价格宣传等',
  scope      VARCHAR(24)     NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/PROJECT',
  project_id BIGINT UNSIGNED NULL COMMENT 'scope=PROJECT 时必填',
  status     VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tpl_code (code),
  KEY idx_tpl_scope (scope, project_id, status),
  CONSTRAINT fk_tpl_project FOREIGN KEY (project_id) REFERENCES biz_project(id),
  CONSTRAINT fk_tpl_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
  CONSTRAINT ck_tpl_status CHECK (status IN ('ACTIVE','RETIRED')),
  CONSTRAINT ck_tpl_scope CHECK (scope IN ('GLOBAL','PROJECT')),
  CONSTRAINT ck_tpl_project CHECK (scope = 'GLOBAL' OR project_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核要求模板';

-- T-12：AI 法务助手临时文件。与正式物料严格隔离，带 TTL。
--       助手不能创建正式任务；只有用户确认"转为正式审核任务"后才置 PROMOTED 并关联物料。
CREATE TABLE assistant_session_file (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_id           VARCHAR(64)     NOT NULL COMMENT '助手会话 ID',
  user_id              BIGINT UNSIGNED NOT NULL,
  file_object_key      VARCHAR(512)    NOT NULL,
  file_sha256          CHAR(64)        NOT NULL,
  file_size            BIGINT UNSIGNED NOT NULL,
  mime_type            VARCHAR(128)    NOT NULL,
  status               VARCHAR(16)     NOT NULL DEFAULT 'TEMP' COMMENT 'TEMP/PROMOTED/EXPIRED',
  promoted_material_id BIGINT UNSIGNED NULL COMMENT '转为正式任务后对应的物料',
  expires_at           DATETIME(3)     NOT NULL COMMENT '过期即由清理任务删除对象，避免临时文件长期留存',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_asf_session (session_id, created_at),
  KEY idx_asf_expire (status, expires_at) COMMENT '清理任务扫描用',
  KEY idx_asf_user (user_id),
  CONSTRAINT fk_asf_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_asf_material FOREIGN KEY (promoted_material_id) REFERENCES material(id),
  CONSTRAINT ck_asf_status CHECK (status IN ('TEMP','PROMOTED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 法务助手临时文件（带 TTL，与正式物料隔离）';

-- T-13：沟通话术。AGENTS.md 第 6 条要求每个风险下自动生成一段可发给业务方的话术。
--       独立成表的原因：它不是风险本身的字段，有自己的受众维度与版本，
--       且不得塞进 suggestion / recommended_copy（那两个是修改建议与推荐表达，语义不同）。
CREATE TABLE risk_communication_script (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  risk_case_id   BIGINT UNSIGNED NOT NULL,
  revision_no    INT UNSIGNED    NOT NULL DEFAULT 1,
  audience       VARCHAR(24)     NOT NULL COMMENT 'BRAND/DESIGN/BIZ/MARKET',
  script_text    TEXT            NOT NULL COMMENT '说明问题是什么、为何要改、如何改、保留原表达需补什么材料',
  model_id       VARCHAR(64)     NOT NULL,
  prompt_version VARCHAR(32)     NOT NULL,
  generated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_script (risk_case_id, audience, revision_no),
  KEY idx_script_risk (risk_case_id),
  CONSTRAINT fk_script_risk FOREIGN KEY (risk_case_id) REFERENCES risk_case(id),
  CONSTRAINT ck_script_audience CHECK (audience IN ('BRAND','DESIGN','BIZ','MARKET'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险沟通话术（只允许 INSERT）';

-- T-14：AI 初审报告。AGENTS.md 第 6 条要求报告只反映"第一次 AI 初审"，
--       因此本表按 Case 归档，并强制记录生成时的数据快照与数据版本（第 12 条）。
--       报告正文必须由已校验的结构化结果生成，content_hash 用于断言"报告与库中数据一致"。
CREATE TABLE initial_review_report (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_id             BIGINT UNSIGNED NOT NULL,
  revision_no         INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '同一 Case 重新生成时递增；仅反映首次初审',
  content_md          MEDIUMTEXT      NOT NULL COMMENT 'Markdown 报告正文',
  content_hash        CHAR(64)        NOT NULL COMMENT '正文哈希，用于一致性断言与防篡改',
  material_count      INT UNSIGNED    NOT NULL COMMENT '物料总数',
  reviewed_material_count INT UNSIGNED NOT NULL COMMENT '参与初审的物料数（通过率分母）',
  parse_failed_count  INT UNSIGNED    NOT NULL COMMENT '解析失败数（不计入分母，单独列出）',
  initial_pass_count  INT UNSIGNED    NOT NULL,
  pending_human_count INT UNSIGNED    NOT NULL,
  risk_fail_count     INT UNSIGNED    NOT NULL,
  initial_pass_rate   DECIMAL(6,4)    NULL COMMENT '物料层初审通过率，口径见 §8 视图',
  risk_type_distribution JSON         NOT NULL COMMENT '风险类型分布（AGENTS.md 第 6 条）',
  data_snapshot       JSON            NOT NULL COMMENT '生成时的数据版本快照（第 12 条：导出报告须记录数据版本）',
  model_id            VARCHAR(64)     NULL,
  prompt_version      VARCHAR(32)     NULL,
  pipeline_version    VARCHAR(32)     NULL,
  generated_by        BIGINT UNSIGNED NULL COMMENT 'AI 生成为空',
  generated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_report (case_id, revision_no),
  CONSTRAINT fk_report_case FOREIGN KEY (case_id) REFERENCES audit_case(id),
  CONSTRAINT fk_report_generator FOREIGN KEY (generated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 初审报告（只允许 INSERT）';

-- T-31：版本差异落库。AGENTS.md 第 12 条要求能还原当时的真实审核过程，
--       仅在前端实时计算 Diff 会导致事后无法复现"当时看到了什么差异"。
CREATE TABLE version_diff (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  material_id       BIGINT UNSIGNED NOT NULL,
  base_version_id   BIGINT UNSIGNED NOT NULL COMMENT '上一版本',
  target_version_id BIGINT UNSIGNED NOT NULL COMMENT '新版本',
  diff_type         VARCHAR(24)     NOT NULL COMMENT 'TEXT/IMAGE/VIDEO/DOC/MIXED',
  payload           JSON            NOT NULL COMMENT '结构化差异：文本增删改 / 画面变化 / 镜头关键帧变化 / 页面段落变化',
  summary           VARCHAR(1000)   NULL COMMENT '人类可读摘要',
  has_change        TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '版本无变化（哈希相同）时为 0',
  engine            VARCHAR(64)     NOT NULL,
  pipeline_version  VARCHAR(32)     NOT NULL,
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_diff (base_version_id, target_version_id),
  KEY idx_diff_material (material_id, created_at),
  CONSTRAINT fk_diff_material FOREIGN KEY (material_id) REFERENCES material(id),
  CONSTRAINT fk_diff_base FOREIGN KEY (base_version_id) REFERENCES material_version(id),
  CONSTRAINT fk_diff_target FOREIGN KEY (target_version_id) REFERENCES material_version(id),
  CONSTRAINT ck_diff_type CHECK (diff_type IN ('TEXT','IMAGE','VIDEO','DOC','MIXED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本差异（落库用于审计复现）';
```

**为什么沟通话术表必须独立于 `risk_case`**：`suggestion` 是"怎么改"，`recommended_copy` 是"改成什么"，而沟通话术是"怎么跟业务方说"——三者受众与语义都不同。把它们挤进同一列会导致：(1) 无法按受众生成不同版本；(2) 法务修改建议时误改话术；(3) 报告导出时无法区分。

**`initial_review_report` 的一致性硬约束**：`content_hash` 与 `data_snapshot` 的存在使"报告与数据库矛盾"可被自动检测——测试应断言报告中的风险条目数、各等级数量、物料分类数量与实时查询结果**逐项相等**（见 `03` §7.4）。

---

## 5. 知识库

```sql
CREATE TABLE kb_item (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  item_type            VARCHAR(24)     NOT NULL
                       COMMENT 'LAW/REGULATION/INTERPRETATION/INTERNAL_RULE/PRODUCT_PARAM/EVIDENCE/CASE/APPROVED_CLAIM/REJECTED_COPY',
  title                VARCHAR(300)    NOT NULL,
  region               VARCHAR(64)     NULL COMMENT '适用地区',
  applicable_business  VARCHAR(128)    NULL COMMENT '适用业务',
  applicable_product   VARCHAR(128)    NULL COMMENT '适用产品/车型',
  publish_date         DATE            NULL,
  effective_date       DATE            NULL,
  expire_date          DATE            NULL,
  trust_level          VARCHAR(16)     NOT NULL COMMENT 'HIGH/MEDIUM/LOW',
  governance_status    VARCHAR(24)     NOT NULL DEFAULT 'DRAFT'
                       COMMENT 'DRAFT/PENDING_APPROVAL/PUBLISHED/RETIRED（治理与审批，AGENTS.md 第 10 条）',
  maintainer_id        BIGINT UNSIGNED NULL,
  project_id           BIGINT UNSIGNED NULL COMMENT '企业内部规则可按项目隔离；法规为 NULL 表示全局',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_kb_type_status (item_type, governance_status),
  KEY idx_kb_effective (effective_date, expire_date) COMMENT '区分现行/历史规则',
  CONSTRAINT ck_kb_type CHECK (item_type IN
    ('LAW','REGULATION','INTERPRETATION','INTERNAL_RULE','PRODUCT_PARAM','EVIDENCE','CASE','APPROVED_CLAIM','REJECTED_COPY')),
  CONSTRAINT ck_kb_trust CHECK (trust_level IN ('HIGH','MEDIUM','LOW')),
  CONSTRAINT ck_kb_gov CHECK (governance_status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识条目';

CREATE TABLE kb_item_version (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  kb_item_id      BIGINT UNSIGNED NOT NULL,
  version_no      INT UNSIGNED    NOT NULL,
  content         MEDIUMTEXT      NOT NULL COMMENT '条文/规则正文',
  source_url      VARCHAR(512)    NULL COMMENT '可追溯来源',
  source_note     VARCHAR(500)    NULL,
  content_hash    CHAR(64)        NOT NULL,
  effective_from  DATE            NULL,
  effective_to    DATE            NULL,
  approved_by     BIGINT UNSIGNED NULL COMMENT '法规更新需审批（AGENTS.md 第 10 条）',
  approved_at     DATETIME(3)     NULL,
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kbv (kb_item_id, version_no),
  KEY idx_kbv_effective (effective_from, effective_to),
  CONSTRAINT fk_kbv_item FOREIGN KEY (kb_item_id) REFERENCES kb_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识条目版本';

-- 检索切片：dense 用于语义，sparse 用于条款号精确匹配（法务检索刚需）
CREATE TABLE kb_chunk (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  kb_item_version_id BIGINT UNSIGNED NOT NULL,
  chunk_no           INT UNSIGNED    NOT NULL,
  text               TEXT            NOT NULL,
  token_count        INT UNSIGNED    NOT NULL COMMENT 'v4 上限 8192，入库存前必须校验',
  embedding_model    VARCHAR(64)     NULL COMMENT '如 text-embedding-v4',
  embedding_dim      SMALLINT UNSIGNED NULL COMMENT '如 1024',
  embedding_dense    BLOB            NULL COMMENT 'float32 数组；MySQL 8 无原生向量类型，见 A9',
  embedding_sparse   JSON            NULL COMMENT '稀疏向量 {token_id: weight}',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_chunk (kb_item_version_id, chunk_no),
  KEY idx_chunk_scope (kb_item_version_id),
  CONSTRAINT fk_chunk_kbv FOREIGN KEY (kb_item_version_id) REFERENCES kb_item_version(id),
  CONSTRAINT ck_chunk_tokens CHECK (token_count <= 8192)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识切片与向量';

-- 风险规则集（ruleset_version 的来源）
CREATE TABLE risk_rule (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rule_code         VARCHAR(64)     NOT NULL COMMENT '如 ABSOLUTE_CLAIM_01',
  name              VARCHAR(200)    NOT NULL,
  rule_type         VARCHAR(48)     NOT NULL COMMENT '与 risk_case.risk_type 对齐',
  description       TEXT            NOT NULL,
  severity_default  VARCHAR(8)      NOT NULL COMMENT '默认风险等级',
  applicable_scope  JSON            NULL COMMENT '适用物料类型/渠道/业务',
  ruleset_version   VARCHAR(32)     NOT NULL COMMENT '规则集版本，写入 risk_case',
  status            VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rule (rule_code, ruleset_version),
  KEY idx_rule_type_status (rule_type, status),
  CONSTRAINT ck_rule_sev CHECK (severity_default IN ('HIGH','MEDIUM','LOW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险规则';

CREATE TABLE risk_rule_kb_ref (
  rule_id            BIGINT UNSIGNED NOT NULL,
  kb_item_version_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (rule_id, kb_item_version_id),
  CONSTRAINT fk_rrkb_rule FOREIGN KEY (rule_id) REFERENCES risk_rule(id),
  CONSTRAINT fk_rrkb_kbv FOREIGN KEY (kb_item_version_id) REFERENCES kb_item_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则-知识依据关联';
```

**知识库的三条治理约束（`AGENTS.md` 第 10 条）**

1. **区分现行与历史**：靠 `effective_from` / `effective_to` 与 `governance_status`。**检索默认只召回 `PUBLISHED` 且在当前生效期内的条目**，历史条目仅在显式查询历史时召回，且必须在结果中标注为历史。
2. **法务确认与误判不得直接写回高可信库**：`review_record` 是沉淀数据，**它与 `kb_item` 之间没有自动写路径**。要进知识库必须走 `DRAFT → PENDING_APPROVAL → PUBLISHED` 的人工审批流（`approved_by` / `approved_at`）。
3. **Approved Claim 必须带适用条件**：`applicable_product` / `effective_date` / `source_note` 等字段承载"适用车型、时间、渠道、数据来源、统计口径"。检索时**这些条件必须一并返回并展示**，不能只返回一句"这个说法可以用"。

---

## 6. 位置映射模型（locator JSON）

`evidence_anchor.locator` 的结构按 `anchor_type` 区分。**所有坐标基于"送模型的规范化图片"，并记录 `scale_ratio` 以便前端还原到原图。**

```jsonc
// TEXT_LINE —— 图片文字行
{
  "bbox": { "x": 120, "y": 480, "w": 640, "h": 72 },   // 像素，左上原点
  "polygon": [[120,480],[760,480],[760,552],[120,552]], // 可选，倾斜文字
  "image_width": 2480,
  "image_height": 3508,
  "scale_ratio": 0.58,          // 送模型图 / 原图；前端渲染必须用它还原
  "page_no": 1,                 // 文档渲染页时使用
  "ocr_line_no": 37             // OCR 原始行号，便于与供应商结果对账
}

// SPEECH_SENTENCE —— 视频口播句
{
  "begin_ms": 12400,
  "end_ms": 15820,
  "sentence_id": 12,
  "speaker_id": 0,              // 开启说话人分离时
  "channel_id": 0,
  "language": "zh",
  "emotion": "neutral"          // Qwen3-ASR 系列固定返回
}

// SUBTITLE_LINE —— 视频硬字幕
{
  "begin_ms": 12400,
  "end_ms": 15820,
  "frame_ts_ms": 13000,         // 该字幕取自哪一帧
  "bbox": { "x": 200, "y": 900, "w": 1680, "h": 120 },
  "frame_width": 1920,
  "frame_height": 1080,
  "scale_ratio": 1.0
}

// KEY_FRAME —— 关键帧
{
  "ts_ms": 13000,
  "scene_id": 4,
  "object_key": "case/123/material/45/v2/frames/000412.jpg",
  "frame_width": 1920,
  "frame_height": 1080,
  "scale_ratio": 1.0,
  "visual_description": "画面中央为车辆正面特写，右上角有促销角标"
}

// DOC_PARAGRAPH / DOC_SENTENCE
{
  "page_no": 3,                 // ⚠️ 仅 PDF/扫描件有效；DOCX 必须为 null（Word 不存页码，见 06 §3）
  "para_index": 12,
  "sentence_index": 2,          // DOC_SENTENCE 才有
  "char_range": [180, 246],     // 相对该页（DOCX 为相对全文）纯文本的字符区间
  "rendered_page_key": "case/123/material/45/v2/pages/p003.png"  // 扫描件才有
}
```

**统一校验规则**（服务端在写入 `risk_anchor_ref` 前必须执行）：

1. `anchor_id` 必须存在于该 `material_version_id` 的锚点集中；
2. 若风险引用了跨类型锚点（口播 + 字幕），必须同属同一 `material_version_id`；
3. `SPEECH_SENTENCE` 必须满足 `begin_ms < end_ms`；
4. `TEXT_LINE` 的 `bbox` 必须落在 `image_width` × `image_height` 范围内；
5. 校验失败 → 该风险定位标记为无效，**强制转 `PENDING_LEGAL_DECISION` 并记录原因**。

> 规则 1 现已由 `fk_rar_anchor` **复合外键在数据库层强制**（见 §4.6），应用层校验是第二道而非唯一一道。

### 6.1 单位与精度约定（跨栈一致性硬约定）

以下约定若不定死，前后端与不同解析引擎之间必然错位。这是 `04` 文档提出的 T-08 / T-09 的正式答复：

| 项 | 约定 | 理由 |
| --- | --- | --- |
| **`char_range` 计数单位** | **UTF-16 code unit**，与 JavaScript `String.prototype.length` 及 Java `String.length()` 一致 | 前端需按索引切字符串做高亮。若用码点或字形簇，emoji / 代理对会导致偏移错位 |
| **`scale_ratio` 精度** | `DECIMAL(10,6)`（JSON 中保留 6 位小数） | 4 位精度在 4000px 图上误差可达 0.4px，小字框选肉眼可见；6 位足够且不浪费 |
| **`region_hint` 枚举** | `TOP_LEFT` / `TOP_CENTER` / `TOP_RIGHT` / `MIDDLE_LEFT` / `MIDDLE_CENTER` / `MIDDLE_RIGHT` / `BOTTOM_LEFT` / `BOTTOM_CENTER` / `BOTTOM_RIGHT` / `FULL_FRAME` / `NOT_APPLICABLE` | 与 `03` §7.2 契约逐字一致；`NOT_APPLICABLE` 用于"已有文字锚点、无需区域提示"的情形 |
| **时间戳单位** | 统一**毫秒整数**，字段名一律以 `_ms` 结尾 | 与 `04` §1.1 的 `*_ms` 命名硬规则一致；同时规避 `00` §2.2 陷阱 2（任务级 `end_time` 是日期字符串，音频内 `end_time` 是毫秒） |
| **PPT `para_index` 语义** | 该 slide 内文本形状按 `shape_id` **升序**、形状内段落按出现顺序编号 | POI 与 python-pptx 的形状遍历顺序不一致，必须显式排序后再编号，否则同一文件在不同引擎下算出不同 `para_index` |
| **`page_no` 基准** | 从 **1** 开始计数（不是 0） | 界面直接展示，避免各处 +1 |
| **`bbox` 坐标原点** | **左上角**为原点，`x` 向右、`y` 向下 | 与 CSS / Canvas / SVG 一致；PDF 原生坐标系是左下原点，解析层负责翻转后再入库 |

---

## 7. 数据库级不可变性与状态守卫

### 7.1 拒绝 UPDATE/DELETE 的触发器

```sql
DELIMITER //

-- P1: 版本不可覆盖
CREATE TRIGGER trg_material_version_no_update
BEFORE UPDATE ON material_version FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'material_version is immutable: upload a new version instead of overwriting';
END//

CREATE TRIGGER trg_material_version_no_delete
BEFORE DELETE ON material_version FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'material_version cannot be deleted (audit trail required)';
END//

-- P2/P3: 签名只允许 INSERT
CREATE TRIGGER trg_legal_signature_no_update
BEFORE UPDATE ON legal_signature FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'legal_signature is append-only';
END//

CREATE TRIGGER trg_legal_signature_no_delete
BEFORE DELETE ON legal_signature FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'legal_signature cannot be deleted';
END//

-- P2: 审核记录不可变
CREATE TRIGGER trg_review_record_no_update
BEFORE UPDATE ON review_record FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review_record is append-only';
END//

CREATE TRIGGER trg_review_record_no_delete
BEFORE DELETE ON review_record FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review_record cannot be deleted (audit trail required)';
END//

-- P2: 锚点不可变
CREATE TRIGGER trg_evidence_anchor_no_update
BEFORE UPDATE ON evidence_anchor FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'evidence_anchor is immutable within a version';
END//

CREATE TRIGGER trg_evidence_anchor_no_delete
BEFORE DELETE ON evidence_anchor FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'evidence_anchor cannot be deleted (risk location references it)';
END//

-- 风险内容变更自动留痕：旧值快照落 risk_case_revision
-- 目的：AGENTS.md 第 7 条"不得悄悄改变原风险的含义"必须有数据支撑，
--       否则整改推进后无法还原旧版本的风险原文。
-- 取舍：触发器只负责"旧值不丢"，reason 写固定文本、changed_by 留空；
--       变更者的精确身份由应用层在同一事务内写入 review_record。
CREATE TRIGGER trg_risk_case_revision_snapshot
BEFORE UPDATE ON risk_case FOR EACH ROW
BEGIN
  DECLARE v_next INT;
  IF NOT (NEW.risk_text <=> OLD.risk_text)
     OR NOT (NEW.risk_level <=> OLD.risk_level)
     OR NOT (NEW.confidence <=> OLD.confidence)
     OR NOT (NEW.suggestion <=> OLD.suggestion)
     OR NOT (NEW.recommended_copy <=> OLD.recommended_copy)
     OR NOT (NEW.required_evidence <=> OLD.required_evidence) THEN
    -- 用 SELECT ... INTO 中转，规避 MySQL "不能在 INSERT 的子查询中引用目标表" 的限制
    SELECT COALESCE(MAX(revision_no), 0) + 1 INTO v_next
      FROM risk_case_revision WHERE risk_case_id = OLD.id;

    INSERT INTO risk_case_revision
      (risk_case_id, revision_no, material_version_id, risk_text, risk_level, confidence,
       suggestion, recommended_copy, required_evidence, reason)
    VALUES
      (OLD.id, v_next, OLD.current_version_id, OLD.risk_text, OLD.risk_level, OLD.confidence,
       OLD.suggestion, OLD.recommended_copy, OLD.required_evidence, '内容变更自动快照');
  END IF;
END//

-- 快照表只允许 INSERT
CREATE TRIGGER trg_risk_revision_no_update
BEFORE UPDATE ON risk_case_revision FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'risk_case_revision is append-only';
END//

CREATE TRIGGER trg_risk_revision_no_delete
BEFORE DELETE ON risk_case_revision FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'risk_case_revision cannot be deleted (audit trail required)';
END//

DELIMITER ;
```

### 7.2 禁止 AI 越权关闭风险（P4）

```sql
DELIMITER //

-- 风险进入 Closed 必须有对应的法务签名记录
CREATE TRIGGER trg_risk_case_close_requires_signature
BEFORE UPDATE ON risk_case FOR EACH ROW
BEGIN
  IF NEW.status = 'CLOSED' AND (OLD.status IS NULL OR OLD.status <> 'CLOSED') THEN
    IF NOT EXISTS (
      SELECT 1 FROM legal_signature
       WHERE risk_case_id = NEW.id AND sign_type = 'RISK_CLOSE'
    ) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'risk_case cannot be CLOSED without a legal signature (AI has no authority)';
    END IF;
  END IF;
END//

-- 签名人必须具备签名权限（角色校验下推到 DB）
CREATE TRIGGER trg_signature_requires_privilege
BEFORE INSERT ON legal_signature FOR EACH ROW
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM sys_user_role ur
      JOIN sys_role r ON r.id = ur.role_id
     WHERE ur.user_id = NEW.signer_id AND r.code = 'LEGAL'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'signer does not hold the LEGAL role';
  END IF;
END//

DELIMITER ;
```

> ⚠️ **`trg_signature_requires_privilege` 只是最后一道兜底**，它无法表达"项目范围"级别的鉴权。完整的授权判断仍在应用层（见 `02` 文档），触发器的作用是**确保不存在"绕过应用层直接改库"的路径**。

### 7.3 非法状态跃迁的拒绝

状态跃迁的合法集合见 `02` 文档。数据库侧用一张跃迁白名单表 + 触发器统一拦截，避免把几十个 `IF` 写死在触发器里：

```sql
CREATE TABLE risk_status_transition (
  from_status VARCHAR(32) NOT NULL,
  to_status   VARCHAR(32) NOT NULL,
  allowed_role VARCHAR(32) NULL COMMENT 'NULL 表示 AI/SYSTEM 也可触发',
  PRIMARY KEY (from_status, to_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险状态跃迁白名单';

INSERT INTO risk_status_transition (from_status, to_status, allowed_role) VALUES
  ('OPEN','PENDING_LEGAL_DECISION',NULL),
  ('OPEN','CONFIRMED','LEGAL'),
  ('OPEN','REJECTED_FALSE_POSITIVE','LEGAL'),
  ('OPEN','AWAITING_EVIDENCE','LEGAL'),
  ('PENDING_LEGAL_DECISION','CONFIRMED','LEGAL'),
  ('PENDING_LEGAL_DECISION','REJECTED_FALSE_POSITIVE','LEGAL'),
  ('PENDING_LEGAL_DECISION','AWAITING_EVIDENCE','LEGAL'),
  ('CONFIRMED','AWAITING_REVISION','LEGAL'),
  ('AWAITING_EVIDENCE','CONFIRMED','LEGAL'),
  ('AWAITING_EVIDENCE','AWAITING_REVISION','LEGAL'),
  ('AWAITING_REVISION','RESUBMITTED',NULL),
  ('RESUBMITTED','AI_REREVIEW',NULL),
  ('AI_REREVIEW','LEGAL_FINAL_REVIEW',NULL),
  ('AI_REREVIEW','AWAITING_REVISION','LEGAL'),
  ('LEGAL_FINAL_REVIEW','CLOSED','LEGAL'),
  ('LEGAL_FINAL_REVIEW','AWAITING_REVISION','LEGAL');

DELIMITER //
CREATE TRIGGER trg_risk_case_transition_guard
BEFORE UPDATE ON risk_case FOR EACH ROW
BEGIN
  IF NEW.status <> OLD.status THEN
    IF NOT EXISTS (
      SELECT 1 FROM risk_status_transition
       WHERE from_status = OLD.status AND to_status = NEW.status
    ) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'illegal risk_case status transition';
    END IF;
    -- 反馈区不得直接关闭：只有经过 LEGAL_FINAL_REVIEW 才能 CLOSED
    IF NEW.status = 'CLOSED' AND OLD.status <> 'LEGAL_FINAL_REVIEW' THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'risk_case can only be CLOSED from LEGAL_FINAL_REVIEW';
    END IF;
  END IF;
END//
DELIMITER ;
```

> **注意**：`risk_status_transition` 的枚举值必须与 `02` 文档的状态机**完全一致**。建议在 Flyway 迁移里维护这张表，并写一个集成测试断言"文档中的跃迁集合 == 表中的跃迁集合"，防止两边漂移。

---

## 8. 口径视图（通过率与三分类）

`AGENTS.md` 第 6 条要求"通过率的计算口径必须在界面中明确，不能把 Risk Case 数量和物料数量混为一谈"。因此**把口径固化成视图**，界面直接取视图值，杜绝各处自行计算。

```sql
-- 物料级三分类（口径唯一来源）
CREATE OR REPLACE VIEW v_material_initial_review AS
SELECT
  m.id                AS material_id,
  m.case_id,
  m.current_version_id,
  m.material_type,
  CASE
    -- 第三类：风险未通过 —— 存在阻断性且未被判误判/未关闭的风险
    WHEN EXISTS (
      SELECT 1 FROM risk_case r
       WHERE r.material_id = m.id
         AND r.blocked = 1
         AND r.status NOT IN ('REJECTED_FALSE_POSITIVE','CLOSED')
         AND r.risk_level IN ('HIGH','MEDIUM')
    ) THEN 'RISK_FAIL'
    -- 第二类：待人工判断
    WHEN EXISTS (
      SELECT 1 FROM risk_case r
       WHERE r.material_id = m.id
         AND r.status IN ('OPEN','PENDING_LEGAL_DECISION','AWAITING_EVIDENCE')
    ) THEN 'PENDING_HUMAN'
    -- 第一类：AI 初审通过（注意：不等于法务最终批准）
    WHEN m.parse_status = 'SUCCEEDED' THEN 'INITIAL_PASS'
    ELSE 'NOT_REVIEWED'
  END AS initial_review_class,
  (SELECT COUNT(*) FROM risk_case r WHERE r.material_id = m.id) AS risk_count,
  (SELECT COUNT(*) FROM risk_case r
    WHERE r.material_id = m.id AND r.risk_level = 'HIGH'
      AND r.status NOT IN ('REJECTED_FALSE_POSITIVE')) AS high_risk_count
FROM material m
WHERE m.parse_status IN ('SUCCEEDED','PARTIAL');

-- Case 级初审汇总（通过率口径在此固化）
CREATE OR REPLACE VIEW v_case_initial_review_summary AS
SELECT
  c.id AS case_id,
  c.case_no,
  -- 分母：仅"参与初审"的物料（解析成功或部分成功）
  SUM(CASE WHEN m.parse_status IN ('SUCCEEDED','PARTIAL') THEN 1 ELSE 0 END) AS reviewed_material_count,
  -- 解析失败不计入分母，单独计数
  SUM(CASE WHEN m.parse_status = 'FAILED' THEN 1 ELSE 0 END)             AS parse_failed_count,
  SUM(CASE WHEN v.initial_review_class = 'INITIAL_PASS'  THEN 1 ELSE 0 END) AS initial_pass_count,
  SUM(CASE WHEN v.initial_review_class = 'PENDING_HUMAN' THEN 1 ELSE 0 END) AS pending_human_count,
  SUM(CASE WHEN v.initial_review_class = 'RISK_FAIL'     THEN 1 ELSE 0 END) AS risk_fail_count,
  (SELECT COUNT(*) FROM risk_case r WHERE r.case_id = c.id AND r.risk_level = 'HIGH') AS high_risk_items,
  (SELECT COUNT(*) FROM risk_case r WHERE r.case_id = c.id AND r.risk_level = 'MEDIUM') AS medium_risk_items,
  (SELECT COUNT(*) FROM risk_case r WHERE r.case_id = c.id AND r.risk_level = 'LOW') AS low_risk_items,
  -- 物料层初审通过率 = 初审通过物料数 / 参与初审物料数
  ROUND(
    SUM(CASE WHEN v.initial_review_class = 'INITIAL_PASS' THEN 1 ELSE 0 END)
    / NULLIF(SUM(CASE WHEN m.parse_status IN ('SUCCEEDED','PARTIAL') THEN 1 ELSE 0 END), 0)
  , 4) AS initial_pass_rate_by_material
FROM audit_case c
LEFT JOIN material m ON m.case_id = c.id
LEFT JOIN v_material_initial_review v ON v.material_id = m.id
GROUP BY c.id, c.case_no;
```

**界面必须展示的口径说明文案（不可省略）**：

> 初审通过率 = 初审通过物料数 ÷ 参与初审物料数。
> 解析失败物料不计入分母，单独列出。风险项数量与物料数量口径不同，不可相互换算。
> "初审通过"仅表示 AI 未发现明显风险，**不等于法务最终批准**。

### 8.1 `PARTIAL`（部分解析）物料的处理规则

`parse_status='PARTIAL'` 表示部分解析成功、部分失败（例如视频 ASR 成功但关键帧抽取失败）。**允许进入初审**，但必须同时满足三条约束：

1. **界面显式标注"部分解析"**，并列出失败环节（如"硬字幕未提取"）。法务必须知道自己看到的不是全貌。
2. **置信度强制封顶**：该物料下所有 Risk Case 的 `confidence` 封顶为 `0.6`。理由——解析不完整意味着"没发现风险"可能只是"没看到"，模型对结论的完整性没有把握。
3. **不得计入"AI 初审通过"**：`PARTIAL` 物料即使无风险项，也应归入"待人工判断"而非"初审通过"。

**为什么允许而非阻断**：需求第 4 条要求阻断的是"解析失败、文件受损或缺少必要信息"的物料；而 `PARTIAL` 恰恰相反——它已产出可用内容（口播文字仍在）。一律阻断会让大量真实物料无法审核。**正确做法是"带着标注进入审核"，而不是"要么全审、要么不审"。**

对应地，`parse_status='FAILED'` 仍然**严格阻断**，且不计入通过率分母。

> ⚠️ 因此 §8 视图中的 `initial_review_class` 需要相应调整：`PARTIAL` 物料在无风险项时输出 `PENDING_HUMAN` 而非 `INITIAL_PASS`。实现时该分支必须显式写出，不能只判断"有无风险项"。

---

## 9. 索引与性能要点

| 场景 | 索引 |
| --- | --- |
| 反馈区列表（按 Case 筛风险） | `idx_risk_case_status` |
| 高等级未关闭风险看板 | `idx_risk_level_status` |
| 锚点渲染（按版本取全部锚点） | `idx_anchor_version_type` |
| 低置信度锚点审查 | `idx_anchor_confidence` |
| ASR 结果过期告警 | `idx_artifact_expire` |
| 回调幂等 | `uk_async_external` |
| 防重复建单 | `uk_risk_dedup` |
| 重复文件上传复用 | `uk_version_sha` |
| 审计链回溯 | `idx_rr_risk`、`idx_audit_resource` |

**明确不做的事**：
- **不对 `risk_case.risk_text` 做全文索引**。检索需求应通过锚点与 `dedup_key` 满足；全文索引会把敏感原文复制进索引文件，扩大泄露面。
- **不在 `audit_log` 里存原文**（见 §4.11 说明）。

---

## 10. 迁移与初始化数据

Flyway 迁移顺序：

```
V1__org_and_permission.sql      用户/角色/权限/项目
V2__core_case_material.sql      audit_case / material / material_version / review_requirement_template
V3__parse_and_anchor.sql        parse_job / parse_artifact / evidence_anchor
V4__risk_and_review.sql         risk_case / risk_anchor_ref / risk_case_revision / review_record /
                                risk_communication_script / initial_review_report / version_diff
V5__signature_and_approval.sql  legal_signature / material_approval
V6__ai_and_async.sql            ai_invocation / async_task / audit_log / assistant_session_file
V7__knowledge_base.sql          kb_item / kb_item_version / kb_chunk / risk_rule
V8__guards_and_triggers.sql     全部触发器
V9__views_and_transitions.sql   §8 视图 + §7.3 跃迁白名单
R__seed_roles_permissions.sql   角色与权限点（可重复执行）
```

**种子数据（`R__seed_*`）**

1. 角色：`LEGAL`（法务）、`BRAND`（品牌）、`DESIGN`（设计）、`BIZ`（业务）、`ADMIN`（管理员）。
2. 权限点：与 `02` 文档的权限矩阵一一对应。
3. 风险类型字典：`ABSOLUTE_CLAIM`、`EVIDENCE_MISSING`、`SAFETY_PROMISE`、`COMPETITOR_COMPARISON`、`PRICE_CLAIM`、`DISCLAIMER_MISSING`、`MISLEADING`、`OTHER`（对应 `AGENTS.md` 第 6 条的风险类型分布）。
4. **不预置任何法规数据**——法规入库必须走治理流程（`kb_item.governance_status`），且需 `AGENTS.md` 第 10 条要求的来源与审批信息。种子数据只建表结构，不塞内容。

---

## 11. 与 `AGENTS.md` 条款的对应关系

| `AGENTS.md` 条款 | 落地位置 |
| --- | --- |
| 第 4 条 审核要求模板需人工确认后生效 | `audit_case.requirement_confirmed_by/at` |
| 第 5 条 每个独立问题建 Risk Case；误判不可删除须留理由 | `risk_case` 行级建模；`ck_rr_false_positive_opinion`；`trg_review_record_no_delete` |
| 第 6 条 通过率口径明确 | §8 视图 + 界面口径文案 |
| 第 7 条 版本不可覆盖、记录哈希与父版本 | `uk_version_sha`、`ck_version_reason`、`trg_material_version_no_update` |
| 第 7 条 新增风险必须新建关联 Risk Case，**不得改写原风险含义** | `risk_case.parent_risk_id`（自引用外键）+ `risk_case_revision` 旧值快照 + `trg_risk_case_revision_snapshot` |
| `00` §3.3 约束 3「锚点 ID 必须存在」 | `fk_rar_anchor` 复合外键——由 **DB 层**校验，不再只依赖应用层 |
| 第 9 条 数据模型与反向追溯 | §2 关系 + `first_version_id` + `material_approval.version_id` |
| 第 10 条 知识库治理与不得直接写回 | `kb_item.governance_status` + 无自动写路径 |
| 第 11 条 结构化字段、三维度不混用 | `risk_case` 的 `risk_level`/`confidence`/`status` 独立列 |
| 第 12 条 审计与敏感信息 | `review_record` 不可变、`audit_log` 不存原文、`email_enc` 加密 |
| 第 14 条 幂等与去重 | 三层唯一键（`uk_version_sha`、`uk_parse_idem`、`uk_risk_dedup`） |
