# gw-audit 后端

广宣法务审核 Agent 的后端服务。设计依据见 `../docs/`。

## 一、快速启动

### 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | 本机已安装 Temurin 17.0.20 |
| Maven | 3.9+ | **本机未装**，用 Docker 构建（见下） |
| Docker | 24+ | 用于构建与运行依赖组件 |

### 启动依赖组件

```powershell
cd deploy
docker compose up -d mysql valkey minio
```

端口刻意避开本机既有服务（3306/6379/9000/9001/6380 已被 coze、ragflow 等项目占用）：

| 服务 | 宿主端口 | 容器端口 | 说明 |
| --- | --- | --- | --- |
| MySQL | **3307** | 3306 | 主数据库 |
| Valkey | **6381** | 6379 | 队列/进度/锁/幂等键。**用 Valkey 而非 Redis 8**，规避 SSPL/AGPL |
| MinIO | **9002** / 9003 | 9000 / 9001 | 对象存储；9003 是控制台 |

### 构建（用 Docker Maven，无需本机装 Maven）

```powershell
cd gw-api
docker run --rm -v "${PWD}:/workspace" -v "gw-maven-repo:/root/.m2" -w /workspace `
  maven:3.9-eclipse-temurin-17 mvn -B clean package -DskipTests
```

首次构建约 3 分钟（下载依赖），之后走 `gw-maven-repo` 卷缓存会快很多。

### 启动应用

```powershell
cd deploy
docker compose up -d gw-audit
docker compose logs -f gw-audit
```

应用地址 `http://localhost:8080`，健康检查 `GET /actuator/health`。

**Flyway 会在启动时自动执行迁移**（`gw-boot/src/main/resources/db/migration/`）。

### 验证主链路

```powershell
pwsh -File deploy/e2e-verify.ps1
```

覆盖：建 Case → 上传 → 解析 → 确认要求 → AI 初审 → 风险列表 → 越权拒绝 →
确认 → 转整改 → V2 → 复审（含拒绝 `FULL`）→ 无签名关闭被拒 → 签名 → 关闭 → 批准 → 反向追溯。

## 二、模块结构

```
gw-api/
├── gw-common/   枚举、错误码、权限点常量、统一响应
├── gw-domain/   领域模型、状态机、权限守卫、Port 接口（刻意不依赖 Spring）
├── gw-infra/    持久化、Redis、MinIO、AI/解析适配器
├── gw-app/      应用服务、编排、事务边界
└── gw-boot/     启动、配置、Controller、安全、Flyway 迁移
```

**分层规则**：`gw-domain` 不依赖 Spring（pom 中刻意不引入）。状态机与权限守卫写在领域层，
使"接口调用"与"内部调用"（定时任务、回调、AI 编排）无法绕过规则。
若该模块出现 Spring 依赖，说明有逻辑放错了层。

## 三、已实现能力

### 数据库（33 表 / 14 触发器 / 2 视图）

- 完整业务模型：Case → Material → Version → Risk Case → Review Record
- **14 个触发器**实现数据库级守卫：版本不可覆盖、审计链不可删、无签名不得关闭风险、
  非法状态跃迁拦截、非 LEGAL 角色不得签名、风险内容变更自动留痕
- **2 个视图**固化口径：`v_material_initial_review`（三分类）、
  `v_case_initial_review_summary`（通过率），避免各处自行计算导致口径矛盾
- 种子数据：6 角色 / 38 权限点 / 95 条角色权限映射 / 16 条状态跃迁白名单

### 领域层

- `RiskStateMachine`：**所有**风险状态变更的唯一入口，集中校验跃迁合法性、
  权限要求与业务前置条件（如关闭前必须有签名）
- `Actor`：AI / HUMAN / SYSTEM 三类主体，`requireLegal()` 等守卫使 AI 从凭据层面
  就做不了签名、关闭、批准
- `RiskDraftValidator`：结构化输出的服务端校验器——锚点存在性、依据真实性、
  置信度封顶、强制转人工判定

### 应用层

- 接收区：建 Case、上传物料（哈希幂等）、确认审核要求、启动初审
- 解析：物料 → 证据锚点（文字行 / 口播句 / 段落），解析失败明确告知重传什么
- 反馈区：AI 初审产出 Risk Case、三分类、人工判断（确认/误判/补料/转整改）
- 终审区：AI 复审（强制范围、拒绝 `FULL`）、法务签名、关闭风险、
  批准版本（校验阻断性风险已全部关闭）、撤销批准

### Web 层

按四模块边界分组的 REST API：

| 路径前缀 | 模块 | 边界约束 |
| --- | --- | --- |
| `/api/intake` | 接收区 | **不返回**风险明细 |
| `/api/feedback` | 反馈区 | **无**版本管理与 Diff 接口 |
| `/api/final-review` | 终审区 | 复审强制 `reviewScope` 且拒绝 `FULL` |
| `/actuator` | 运维 | 健康检查 |

## 四、未实现 / 待办

| 项 | 状态 | 说明 |
| --- | --- | --- |
| **百炼真实适配器** | ⏳ 未实现 | 当前是 `MockAiAdapter` / `MockParseAdapter`。真实实现需等 M0 验证 OCR 坐标格式（V1/V2）后再写，否则契约会变 |
| **JWT 认证** | ⏳ 未实现 | 当前从请求头取主体，**仅供本地联调**。`SecurityConfig` 已标注待收紧 |
| **异步任务与真实进度** | ⏳ 未实现 | 解析与初审当前同步执行。AGENTS.md 第 14 条要求异步 + 真实进度 + 重试入口 |
| **知识库检索** | ⏳ 未实现 | `RetrievalPort` 未定义实现，初审时检索结果恒为空 |
| **Version Diff** | ⏳ 未实现 | 表已建（`version_diff`），生成逻辑未写 |
| **Markdown 报告落库** | ⏳ 部分 | Mock 适配器能生成文本，但未写入 `initial_review_report` |
| **文件上传直传 MinIO** | ⏳ 未实现 | 当前只登记元数据，预签名 URL 接口未写 |
| **前端** | ⏳ 待用户提供参考图 | — |

## 五、关键设计点（改代码前请先读）

1. **状态变更禁止直接 setter**。必须走 `RiskStateMachine`。这是"AI 不能关闭风险"
   等规则不被新代码绕过的前提。
2. **进度必须用可计量单位**（视频=毫秒、文档=页数、批量=物料数），
   不要用凭感觉的百分比，否则后续换真实进度时前端要返工。
3. **`ruleRefs` 只能存知识库 ID**，不允许存模型生成的字符串——这是防虚构法条的机制。
4. **失败一律降级为「待人工判断」，绝不降级为「通过」**。
5. **`PARTIAL` 解析的语义**：允许进入初审，但置信度封顶 0.6、不得算"初审通过"。
   而 `FAILED` 严格阻断且不计入通过率分母。
6. **审计日志禁止写物料原文**，只记字段级摘要与锚点 ID。

## 六、验证记录

| 验证项 | 结果 |
| --- | --- |
| Maven 构建（6 模块） | ✅ BUILD SUCCESS |
| Flyway 迁移（V1–V10 + R） | ✅ 11 个脚本全部执行成功 |
| 数据库对象 | ✅ 33 表 / 14 触发器 / 2 视图 / 16 跃迁 |
| 权限矩阵 | ✅ ADMIN 与 AI_SERVICE 的危险权限数均为 0 |
| **数据库守卫实证** | ✅ **9 项全部 BLOCKED/PASS**（见 `deploy/db-test/guard-verification.sql`） |
| 主链路端到端 | 见 `deploy/e2e-verify.ps1` |
