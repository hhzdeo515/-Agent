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
| **向量检索与重排** | ⏳ 部分 | 初审的"规则与证据检索"当前是确定性的知识库查询（按风险类型取 ACTIVE 规则 + 已发布条款），**不是**期望的 `text-embedding-v4` dense+sparse + `qwen3-rerank`。但检索结果不再恒为空——恒为空会让校验器丢弃全部法条引用（详见第五节第 7 条） |
| **Version Diff** | ✅ 已实现 | `VersionDiffService`：自写 LCS，四类变更；图片/视频锚点如实标记 `NOT_SUPPORTED` 而非假装算出来；结果落 `version_diff`，同一对版本复用同一份记录 |
| **AI 复审** | ✅ 已实现 | `RiskRereviewService`：三问结论由服务端按新版本锚点算出（不是前端传参）；发现新增风险时新建关联 Risk Case |
| **Markdown 报告落库** | ✅ 已实现 | `InitialReviewReportService`：报告骨架由服务端按数据库视图拼装（数字不交给模型），沟通话术由 AI 生成并单独落 `risk_communication_script`；报告 append-only，重新生成产生新修订 |
| **文件上传** | ✅ 已实现 | `POST /api/intake/materials/upload`（multipart）+ 浏览器生成 File 直接把文本材料入库；预签名直传 URL 仍未实现 |
| **前端四模块** | ✅ 已实现 | 四个模块全部接真实接口，无静态假数据；任务可在模块间流转（`stores/activeCase.ts`） |

### 已修复的历史缺陷（值得记住，避免重犯）

| 缺陷 | 后果 | 修法 |
| --- | --- | --- |
| 检索阶段恒返回空 | 校验器把**所有**法条引用判为幻觉并丢弃 ⇒ 每条风险都显示"依据不足" | 检索改为确定性查询知识库；校验器的"防虚构"语义未变 |
| `MockParseAdapter` 对所有文本物料返回同一段示例文案 | "上传 A、审核 B"，界面上完全看不出来 | 改为读取对象存储里文件的真实内容，逐行切片；不支持的类型直接报错并说明该配什么 |
| `legalFinalReviewAccept` 以 `LEGAL_FINAL_REVIEW` 为目标 | 风险经 AI 复审后已处于该状态，自跃迁被状态机拒绝 ⇒ 该接口 100% 失败 | 认可 = 只落终审意见、不改状态；不认可 = 退回整改 |
| `revokeApproval` 需要 `approvalId` 但无接口可查 | 撤销批准实际无法执行 | 补 `GET /materials/{id}/approvals` |
| 依据还原时贴出整部法规 | 一部法规七八条切片全量渲染，"违反哪一款"被淹没 | 抽出 `LegalClausePicker`，按风险类型选条款并截到相关句子 |

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
7. **检索不能返回空集**。这条是踩过坑的：阶段 5 的校验器会把"本次检索结果之外的
   引用"判为幻觉并丢弃，因此检索恒为空 ⇒ 全部法条引用被丢弃 ⇒ 每条风险都是
   "依据不足"。看起来更保守，实际是让整套引用体系空转。检索至少要返回
   "该风险类型当前现行有效的依据"。
8. **报告的数字不交给模型**。报告骨架由服务端按视图拼装，模型只产语言（沟通话术）。
   一旦把计数交给模型，它就有机会把 7 条写成 6 条——而报告与库不一致是致命的。
9. **Mock 也必须诚实**。不支持的类型要报错并说明该配什么，不能返回示例内容让链路
   "看起来跑通了"。假成功比失败危险得多：它会让法务以为某份材料已经审过了。

## 六、验证记录

| 验证项 | 结果 |
| --- | --- |
| Maven 构建（6 模块） | ✅ BUILD SUCCESS |
| Flyway 迁移（V1–V11 + R） | ✅ 全部脚本执行成功 |
| 数据库对象 | ✅ 33 表 / 14 触发器 / 2 视图 / 16 跃迁 / 38 权限 |
| 权限矩阵 | ✅ ADMIN 与 AI_SERVICE 的危险权限数均为 0 |
| **数据库守卫实证** | ✅ **9 项全部 BLOCKED/PASS**（见 `deploy/db-test/guard-verification.sql`） |
| **主链路端到端** | ✅ **61 项全部 PASS**（`deploy/e2e-verify.ps1`）：建任务 → 真实上传 → 解析 → 要求确认 → AI 初审 → 依据还原 → 报告一致性 → 越权拒绝 → 确认/转整改 → V2 → Version Diff → AI 复审（结论由服务端算出）→ 法务终审 → 无签名关闭被拒 → 签名 → 关闭 → 批准 → 撤销批准 → 反向追溯 |
| **演示数据** | ✅ `deploy/demo-seed.ps1`（`-Remediate` 可产出终审区场景）：上传真实文案 → 7 条风险各自引用到具体条款（续航→广告法第十一条、立减→第八条、最优→第十三条） |
| **界面截图** | ✅ `deploy/shoot.ps1` 生成 `deploy/shots/*.png`（四模块） |
