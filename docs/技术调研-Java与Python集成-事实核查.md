# 技术调研：Java 17 + Spring Boot 3 后端与 Python 解析服务集成 / Java 原生替代 Python

> **取证方法**：下表每一条结论都来自本次会话中通过 `web_fetch` **实际打开**的页面（含官方文档站、PyPI、Maven Central `maven-metadata.xml`、GitHub API 读取的官方仓库文件）。凡是本次未能打开或页面被截断而无法读到正文的，一律写明「不确定」。**未做任何版本号/许可证推断**。
>
> **时间口径**：下列版本与日期均为本次抓取时页面显示的内容（页面显示的年份为 2026 年）。版本会持续变化，引用前请以同一 URL 复核。
>
> 说明：用户提供的 `https://github.com/tesseract-ocr/tess4j` 不存在（Tess4J 的真实仓库是 `nguyenq/tess4j`，Tesseract 引擎仓库是 `tesseract-ocr/tesseract`），已在 C5 中更正。

---

## C1. HTTP/REST 与 gRPC（Python 侧）

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| FastAPI 最新版本 | **0.141.1**（该版本发布于 Jul 29, 2026；含 sdist 与 whl） | https://pypi.org/project/fastapi/ |
| FastAPI 许可证 | **MIT**。页面「License」段原文 "This project is licensed under the terms of the MIT license."，且 PyPI 元数据 License expression = `MIT` | https://pypi.org/project/fastapi/ |
| FastAPI 官方文档首页 | https://fastapi.tiangolo.com/ （PyPI 页面「Documentation」字段指向此地址） | https://fastapi.tiangolo.com/ |
| FastAPI 其它已读事实 | `Requires Python >=3.10`；PyPI 分类器仍是 `Development Status :: 4 - Beta`（即官方未标注 5 - Production/Stable） | https://pypi.org/project/fastapi/ |
| grpcio 最新版本 | **1.84.0**（PyPI JSON 元数据 `info.version`） | https://pypi.org/pypi/grpcio/json |
| grpcio 许可证 | **Apache-2.0**（元数据 `license_expression: "Apache-2.0"`，`license_files: ["LICENSE"]`） | https://pypi.org/pypi/grpcio/json |
| grpcio 官方文档 | 元数据 `project_urls.Documentation = https://grpc.github.io/grpc/python`，`Homepage = https://grpc.io` | https://grpc.github.io/grpc/python |
| grpcio-tools 最新版本 | **1.84.0**（与 grpcio 同版本号）；依赖 `protobuf<8.0.0,>=7.35.1`、`grpcio>=1.84.0`、`setuptools>=77.0.1` | https://pypi.org/pypi/grpcio-tools/json |
| grpcio-tools 许可证 | **Apache-2.0**（元数据 `license_expression: "Apache-2.0"`） | https://pypi.org/pypi/grpcio-tools/json |
| gRPC Python 官方文档首页 | 页面仅有 Quick start / Basics tutorial / ALTS / Generated code / API / Daily builds 等子页链接；**该页未出现任何 "GA"、"supported level"、"stable/experimental" 字样**，页面署名 "Last modified December 18, 2020" | https://grpc.io/docs/languages/python/ |
| gRPC 官方支持语言列表 | 列出 13 种语言/平台：C#/.NET、C++、Dart、Go、Java、Kotlin、Node、Objective-C、PHP、Python、Ruby、Rust、Swift。**该页同样没有任何 GA / 支持级别（支持等级）的说明**，只有 "Select a language to get started" | https://grpc.io/docs/languages/ |
| grpcio 成熟度 | PyPI 分类器 `Development Status :: 5 - Production/Stable`（这是官方包元数据，不是 gRPC 官网的正式声明） | https://pypi.org/pypi/grpcio/json |
| gRPC 官方性能说明（Python 专属） | **不确定**：grpc.io 文档导航中存在 "Performance Best Practices"（`/docs/guides/performance/`）与 "Benchmarking"（`/docs/guides/benchmarking/`）条目，但**本次未打开其正文**，无法确认是否含 Python 专属性能/成熟度结论 | https://grpc.io/docs/guides/ |

---

## C2. Java 侧

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| Spring Framework 版本（文档站当前版） | 文档站显示 **Spring Framework 7.0.9**（页面右上角版本号） | https://docs.spring.io/spring-framework/reference/integration/rest-clients.html |
| `RestClient` 状态 | **存在且是官方推荐选择**。该页原文列出 4 种 REST 调用方式：`RestClient`（synchronous client with a fluent API）、`WebClient`（non-blocking, reactive client with fluent API）、`RestTemplate`（synchronous client with template method API, **now deprecated in favor of `RestClient`**）、HTTP Service Clients | https://docs.spring.io/spring-framework/reference/integration/rest-clients.html |
| `WebClient` 文档链接 | 该页把 `WebClient` 作为「non-blocking, reactive client with fluent API」列出；其专门章节链接出现在官方文档导航中：`/spring-framework/reference/web/webflux-webclient.html`。**该子页正文本次未单独打开** | https://docs.spring.io/spring-framework/reference/web/webflux-webclient.html |
| Spring Boot 当前最新 GA 版本 | **4.1.1**。GitHub releases/latest 返回 `tag_name: v4.1.1`、`prerelease: false`、`published_at: 2026-08-20`（因此是正式版，非里程碑/候选版） | https://github.com/spring-projects/spring-boot/releases/tag/v4.1.1 |
| Spring Boot Maven Central 版本事实 | `spring-boot-starter-parent` 的 `<latest>`/`<release>` = **4.2.0-M1**（里程碑版本，**不是 GA**）；版本序列中 3.x 的最后一个版本是 **3.5.16**；`lastUpdated=20260820134435` | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml |
| grpc-java 最新版本 | **1.84.0**（Maven Central `io.grpc:grpc-netty-shaded` 的 `<latest>`/`<release>`；`lastUpdated=20260901160217`） | https://repo1.maven.org/maven2/io/grpc/grpc-netty-shaded/maven-metadata.xml |
| grpc-java 许可证 | **Apache-2.0**。通过 GitHub API 读取 `grpc/grpc-java` 的 `LICENSE` 文件，内容为 "Apache License Version 2.0, January 2004"，GitHub 识别 `spdx_id: Apache-2.0` | https://github.com/grpc/grpc-java/blob/master/LICENSE |
| `org.xolstice.maven.plugins:protobuf-maven-plugin` 最新版本 | **0.6.1**（全部版本只有 0.5.0 / 0.5.1 / 0.6.1 三个；`lastUpdated=20181001024915`，即 **2018-10-01 之后 Maven Central 上没有新发布**） | https://repo1.maven.org/maven2/org/xolstice/maven/plugins/protobuf-maven-plugin/maven-metadata.xml |
| xolstice/protobuf-maven-plugin 维护状态 | **GitHub 仓库 `archived: true`（已归档）**；`pushed_at: 2025-04-26`；`open_issues_count: 0`；`stargazers_count: 253`；GitHub 未能识别许可证（`license.spdx_id: NOASSERTION`）；仓库本身是 `sergei-ivanov/maven-protoc-plugin` 的 fork | https://github.com/xolstice/protobuf-maven-plugin |
| 备选 protoc 插件（官方推荐事实） | Spring Boot 4.1.1 官方参考文档「gRPC」章节管理的 Maven 插件是 **`io.github.ascopes:protobuf-maven-plugin`**（不是 xolstice），并写明用 `spring-boot-starter-parent` 会自带 `protoc` 版本、`binary-maven` 配置与 `generate` goal 执行配置 | https://docs.spring.io/spring-boot/reference/io/grpc.html |
| `protoc-jar` / `buf` 官方链接与版本 | **不确定**：本次未打开 `protoc-jar` 或 `buf` 的官方页面，无法给出经核实的版本与维护状态 | （未取证） |
| 是否存在官方 `spring-grpc` 项目 | **存在**。`spring-projects/spring-grpc`，非 fork、`archived: false`、`license: Apache-2.0`、创建于 2024-09-03、`pushed_at: 2026-09-17`、`stargazers_count: 413`、`open_issues_count: 13` | https://github.com/spring-projects/spring-grpc |
| spring-grpc 版本状态 | 最新正式 release = **v1.1.1**（`prerelease: false`，`published_at: 2026-08-21`，release notes 为 "Main changes are dependency updates."） | https://github.com/spring-projects/spring-grpc/releases/tag/v1.1.1 |
| Spring Boot 对 gRPC 的官方支持形态 | Spring Boot 4.1.1 参考文档有独立 **gRPC 章节**：提供 `spring-boot-grpc-server` 模块与 `spring-boot-starter-grpc-server` starter；可用 `@GrpcService`；官方指向 Spring gRPC 1.1 文档；可切换 `grpc-netty-shaded` 或 `grpc-servlet-jakarta`；有 reflection/health/security 自动配置。同一文档的依赖升级清单中出现 "Upgrade to Spring gRPC 1.1.1" | https://docs.spring.io/spring-boot/reference/io/grpc.html |

---

## C3. 消息队列 / 任务队列

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| Spring Boot 官方文档 RabbitMQ（AMQP）章节 | **存在**（当前文档版本为 Spring Boot **4.1.1**）。原文："Spring Boot offers several conveniences for working with AMQP through RabbitMQ, including the `spring-boot-starter-amqp` starter."，含 `@RabbitListener`、`spring.rabbitmq.*`、RabbitMQ Streams 等章节 | https://docs.spring.io/spring-boot/reference/messaging/amqp.html |
| Spring Boot 官方文档 Kafka 章节 | **存在**：Messaging → "Apache Kafka Support"，URL 为 `/reference/messaging/kafka.html`（该链接取自同一官方参考文档的目录树）。注意：用户提到 `spring-boot-starter-kafka`；本次**未在 Kafka 章节正文中逐字确认 starter 名称** | https://docs.spring.io/spring-boot/reference/messaging/kafka.html |
| Spring for Apache Kafka 最新版本 | Maven Central 的 `<latest>`/`<release>` = **4.2.0-M1**（**里程碑版本，不是 GA**）；版本列表中最新的 GA 是 **4.1.1**；`lastUpdated=20260820104326` | https://repo1.maven.org/maven2/org/springframework/kafka/spring-kafka/maven-metadata.xml |
| Spring Kafka GA 版本交叉印证 | Spring Boot 4.1.1 官方 release notes 的依赖升级清单含 "Upgrade to Spring Kafka 4.1.1" | https://github.com/spring-projects/spring-boot/releases/tag/v4.1.1 |
| Spring Data Redis 的 Redis Stream 支持 | **官方确认**。文档版本 Spring Data Redis **4.1.1**，独立 "Redis Streams" 章节：`org.springframework.data.redis.connection` 与 `org.springframework.data.redis.stream` 包；高层 API 为 **`StreamOperations`**（`add`/`read`/`acknowledge`）；异步消费为 **`StreamMessageListenerContainer`（驱动 `StreamListener`）** 与响应式 **`StreamReceiver`**；含 `ReadOffset`（latest / from / lastConsumed）与自动 ack（`receiveAutoAck`）说明 | https://docs.spring.io/spring-data/redis/reference/redis/redis-streams.html |
| Celery 最新版本 | **5.6.3**（PyPI 版本页元数据 `version: 5.6.3`，上传时间 2026-03-26，描述首行 "5.6.3 (recovery)"）；`requires_python: >=3.9` | https://pypi.org/pypi/celery/5.6.3/json |
| Celery 许可证 | **BSD-3-Clause**。PyPI 元数据 `license: "BSD-3-Clause"`；官方文档首页原文 "Celery is Open Source and licensed under the BSD License"，链接到 `BSD-3-Clause` | https://pypi.org/pypi/celery/5.6.3/json 、 https://docs.celeryq.dev/en/stable/ |
| Celery 官方文档 | https://docs.celeryq.dev/en/stable/ （页面标题 "Celery 5.6.3 documentation"，正文说明 "This document describes the current stable version of Celery (5.6)"） | https://docs.celeryq.dev/en/stable/ |
| Celery 对 broker 的官方说明 | 「Backends and Brokers」页给出官方对照表：**RabbitMQ = Stable（Monitoring Yes / Remote Control Yes）**、**Redis = Stable（Yes / Yes）**、Amazon SQS = Stable（No / No）、Zookeeper = Experimental、**Kafka = Experimental**、GC PubSub = Experimental。原文注明 "Experimental brokers may be functional but they don't have dedicated maintainers."。另：PyPI 描述中 "The RabbitMQ, Redis transports are feature complete" | https://docs.celeryq.dev/en/stable/getting-started/backends-and-brokers/index.html |
| RabbitMQ 许可证 | 官方仓库 `rabbitmq/rabbitmq-server` 的 `LICENSE` 文件内容（GitHub API 读取）："**RabbitMQ server and its tier 1 (core) plugins source code is licensed under the MPL 2.0.** ... Some RabbitMQ server OCF files are licensed under the Apache Software License 2.0." → 核心为 **MPL-2.0**（注意：GitHub 的自动识别字段是 `Other / NOASSERTION`，不是 SPDX 结论） | https://github.com/rabbitmq/rabbitmq-server/blob/main/LICENSE |
| RabbitMQ 官网「licensing」页 | **不存在**：我实际访问 `https://www.rabbitmq.com/licensing` 与 `https://www.rabbitmq.com/docs/licensing` 均返回 **HTTP 404**（页面为 Docusaurus 的 "Page Not Found"） | （404，无有效官方页） |
| Kafka 许可证 | **Apache-2.0**。官方仓库 `apache/kafka` 的 `LICENSE` 文件内容为 "Apache License Version 2.0, January 2004"，GitHub 识别 `spdx_id: Apache-2.0` | https://github.com/apache/kafka/blob/trunk/LICENSE |
| Redis 许可证（我实际打开/读到的页面） | 我**打开过** `https://redis.io/legal/licenses/`（HTTP 200，页面标题 "Licenses \| Redis"），但抓取结果在站点大型导航（mega-menu）处被截断，**未读到许可证正文**。因此许可证结论改以**官方代码仓库**为准：Redis 官方仓库 `redis/redis` 的 `LICENSE.txt` 开头原文为："Starting with Redis 8, Redis Open Source is moving to a **tri-licensing model** ... contributions are subject to your choice of: (a) the Redis Source Available License v2 (**RSALv2**); or (b) the Server Side Public License v1 (**SSPLv1**); or (c) the GNU Affero General Public License v3 (**AGPLv3**). **Redis Open Source 7.2 and prior releases remain subject to the BSDv3 clause license**" | https://redis.io/legal/licenses/ （正文未读到） 、 https://github.com/redis/redis/blob/unstable/LICENSE.txt （正文已读到） |
| "2018 年 Redis 4.0 起部分模块改用 RSALv2/SSPL" 的说法 | **不确定**：本次未找到并打开支持该时间线与模块范围说法的 Redis 官方页面；官方 `LICENSE.txt` 只说明「Redis 8 起三许可、7.2 及更早仍为 BSDv3」，未逐字复述 2018/4.0 的历史 | （未取证） |

---

## C4. 进度上报与重试工程模式

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| SSE（MDN） | **确认存在**：MDN "Using server-sent events"，标注 Baseline **Widely available**（"available across browsers since January 2020"）。官方提示：非 HTTP/2 时每浏览器每域连接上限很低（**6**），HTTP/2 下并发流由协商决定（默认 100）；服务端 MIME 必须为 `text/event-stream` | https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events |
| SSE（WHATWG HTML 规范） | **§9.2 Server-sent events**（含 9.2.1 Introduction、9.2.2 The `EventSource` interface、9.2.3 Processing model、9.2.4 `Last-Event-ID`、9.2.5 解析、9.2.6 解释、9.2.7 Authoring notes、9.2.8 Connectionless push、9.2.9 GC、9.2.10 Implementation advice）。规范定义 `EventSource` 接口、`readyState` = CONNECTING(0)/OPEN(1)/CLOSED(2)、`text/event-stream`、`event`/`data`/`id`/`retry` 字段、UTF-8 编码。页面署名 "Living Standard — Last Updated 17 September 2026" | https://html.spec.whatwg.org/multipage/server-sent-events.html |
| WebSocket（RFC 6455） | **RFC 6455: The WebSocket Protocol**，作者 I. Fette、A. Melnikov，**Proposed Standard**（"Category: Standards Track"），December 2011。RFC Editor 页面明确标注 **"This RFC was updated, see RFC 7936, RFC 8307, RFC 8441"** | https://www.rfc-editor.org/info/rfc6455/ |
| Spring 官方 WebSocket 支持 | **确认存在**：Spring Framework 参考文档 "WebSockets" 章节（Servlet 栈），覆盖 raw WebSocket、SockJS 模拟、STOMP 子协议；正文引用 RFC 6455 并给出 HTTP Upgrade 握手示例 | https://docs.spring.io/spring-framework/reference/web/websocket.html |
| Spring 官方 SSE 支持 | **确认存在 SSE 章节**：Spring MVC 参考文档 "Asynchronous Requests" 页面导语原文列出 "Controllers can **stream multiple values, including SSE and raw data**"，且页面目录含锚点 `#mvc-ann-async-sse`。**说明：本次抓取结果被截断在该章节正文之前，"`SseEmitter`" 这一具体类名未在可见正文中逐字读到** → 类名归属标注为「未逐字确认」 | https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html |
| 「任务表状态机 + 轮询」权威参考（Spring Batch） | **权威且可引用**。Spring Batch 6.0.5 官方「The Domain Language of Batch」章节：`JobRepository` 被定义为 "**the persistence mechanism for all of the stereotypes mentioned earlier** … provides CRUD operations for `JobLauncher`, `Job`, and `Step` implementations"；`JobExecution` 属性表给出 `Status` = `BatchStatus#STARTED` / `#FAILED` / `#COMPLETED`，另有 `startTime`/`endTime`/`exitStatus`/`createTime`/`lastUpdated`/`executionContext`/`failureExceptions`；并列出具名元数据表 `BATCH_JOB_INSTANCE`、`BATCH_JOB_EXECUTION_PARAMS`、`BATCH_JOB_EXECUTION`、`BATCH_STEP_EXECUTION`、`BATCH_STEP_EXECUTION_CONTEXT`。`JobOperator` 接口提供 start/startNextInstance/stop/restart/abandon | https://docs.spring.io/spring-batch/reference/domain.html |
| 「任务状态机」权威参考（AWS Step Functions） | **部分确认**。官方页面确认 Step Functions 基于 **state machines（工作流）**，由 **states**（Flow states / Task states）组成，用 Amazon States Language 定义；运行实例称 **execution**，可 "**monitor the status of your workflow executions**"，出错可 **redrive**；并说明 transition（`Next`/`StartAt`）与终止态（`Type: Succeed`/`Fail`/`End: true`）。**但专门的 execution 状态枚举页（`.../apireference/API_ExecutionStatus.html`）本次抓取被重定向到 API 参考首页，未取到枚举全集** → 具体状态名清单标注为不确定 | https://docs.aws.amazon.com/step-functions/latest/dg/concepts-statemachines.html |
| Google Cloud Tasks 的任务状态机模型 | **不确定**：本次未打开 Google Cloud Tasks 的官方页面，无法给出经核实的任务状态机/状态枚举链接 | （未取证） |

---

## C5. Java 原生替代（完全避开 Python）

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| Tess4J Maven 坐标与最新版本 | 坐标 `net.sourceforge.tess4j:tess4j`；Maven Central `<latest>`/`<release>` = **5.20.0**；`lastUpdated=20260727220527` | https://repo1.maven.org/maven2/net/sourceforge/tess4j/tess4j/maven-metadata.xml |
| Tess4J 官方仓库与许可证 | 真实仓库是 **`nguyenq/tess4j`**（`https://github.com/tesseract-ocr/tess4j` 不存在）。`archived: false`，`license.spdx_id: **Apache-2.0**`，`pushed_at: **2026-07-28**`，`stargazers_count: 1757`，`open_issues_count: 26`，`default_branch: master` | https://github.com/nguyenq/tess4j |
| Tess4J 的绑定方式 | 仓库官方描述为 "**Java JNA wrapper for Tesseract OCR API**" → 通过 **JNA**（而非 JNI）调用 Tesseract 的本地库 | https://api.github.com/repos/nguyenq/tess4j |
| Tess4J 是否必须依赖本机 Tesseract 原生库 + 本机 tessdata | **不确定（部分）**：官方 README（`raw.githubusercontent.com/nguyenq/tess4j/master/README.md`）本次两次抓取均失败/超时，**未能读到安装说明**；从"JNA wrapper for Tesseract OCR API"这一官方描述可推断需要 Tesseract 本地库，但**具体依赖形态与 tessdata 目录要求本次未取得官方原文** | （README 未取到） |
| Tesseract 引擎仓库与许可证 | `tesseract-ocr/tesseract`，`license.spdx_id: **Apache-2.0**`，`archived: false`，`pushed_at: **2026-09-11**`，`stargazers_count: 76583`，`open_issues_count: 484`，官网 `https://tesseract-ocr.github.io/` | https://github.com/tesseract-ocr/tesseract |
| 中文（chi_sim）官方训练数据 | **官方页面确认**：tessdoc「Traineddata Files for Version 4.00+」列出 **`chi_sim`（Chinese - Simplified）与 `chi_tra`（Chinese - Traditional）**，并提供下载链接（4.00 分支 `chi_sim.traineddata`）。官方三套模型仓库：**`tessdata`**（Legacy + LSTM，支持 legacy 引擎、不可重训）、**`tessdata_best`**（LSTM only，最准、最慢，**唯一可作微调基座**）、**`tessdata_fast`**（整数化 LSTM，最快、最不准）。官方提醒：用 `tessdata_best`/`tessdata_fast` 时只支持 LSTM 引擎（`--oem 1`），`--oem 0/2` 不可用 | https://tesseract-ocr.github.io/tessdoc/Data-Files.html |
| Tesseract 官方对中文识别效果的说明 | **官方未给出中文（chi_sim）准确率数据**：上述官方 Data-Files 页只按"速度 / 准确度 / 是否支持 legacy / 是否可重训"做**相对排序**，没有中文或任何单语种的量化准确率 → **不确定** | https://tesseract-ocr.github.io/tessdoc/Data-Files.html |
| ONNX Runtime Java 官方文档 | **确认存在**：官方 "Get Started with ORT for Java" 页。原文 "The ONNX runtime provides a Java binding for running inference on ONNX models on a JVM."；**Supported Versions: Java 8 or newer** | https://onnxruntime.ai/docs/get-started/with-java.html |
| ONNX Runtime Java Maven 坐标与平台 | `com.microsoft.onnxruntime:onnxruntime`（CPU）与 `com.microsoft.onnxruntime:onnxruntime_gpu`（GPU/CUDA），发布于 **Maven Central**。平台支持官方表格：CPU = Windows x64 / Linux x64 / macOS x64；GPU = Windows x64 / Linux x64 | https://onnxruntime.ai/docs/get-started/with-java.html |
| ONNX Runtime Maven Central 最新版本 | **1.30.0**（`<latest>`/`<release>`；`lastUpdated=20260914203258`） | https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/maven-metadata.xml |
| ONNX Runtime Java 是否有官方 OCR / ASR 示例 | **没有 OCR/ASR 示例**。官方 docs 与 Sample 只给出 **MNIST 打分** 示例（`ScoreMNIST`、`cnn_mnist_pytorch.onnx`、`lr_mnist_scikit.onnx`）与通用推理教程（`OrtEnvironment`/`OrtSession`/`OnnxTensor`）。文档站导航另存在 "Generate API (Preview) → Java API"（`/docs/genai/api/java.html`），**该页正文本次未打开** | https://onnxruntime.ai/docs/get-started/with-java.html |
| ONNX Runtime 许可证 | **不确定**：本次未打开 ONNX Runtime 的 LICENSE 文件或官方许可证页面，无法确认是否为 MIT（用户猜测未获证实） | （未取证） |
| Whisper 的 Java 绑定（上游 whisper.cpp） | 上游仓库现为 **`ggml-org/whisper.cpp`**（原 `ggerganov/whisper.cpp`，GitHub API 返回该新路径）：`Port of OpenAI's Whisper model in C/C++`，`license.spdx_id: **MIT**`，`archived: false`，`pushed_at: **2026-09-18**`，`stargazers_count: 53785`。**说明：本次未在其仓库内容中确认是否存在"官方 Java 绑定"** → 上游是否自带官方 Java 绑定标注为不确定 | https://github.com/ggml-org/whisper.cpp |
| Whisper 的 Java 绑定（whisper-jni，存在性核实） | **真实存在**。`GiviMAD/whisper-jni`，官方描述："**A JNI wrapper for using whisper.cpp, allows to transcribe speech to text in Java.**"；`license.spdx_id: **Apache-2.0**`，`archived: false`，创建 2023-06-10，**`pushed_at: 2025-04-26`**，`stargazers_count: 161`，`open_issues_count: 17` | https://github.com/GiviMAD/whisper-jni |
| whisper-jni 可用性结论 | **存在且为 JNI 封装，但维护活跃度有限**（最近一次 push 为 2025-04-26，相对上游 whisper.cpp 的 2026-09-18 存在约一年半的滞后）；是否满足生产可用要求，**本次未做构建/运行验证 → 不确定** | https://github.com/GiviMAD/whisper-jni |

---

## 不确定项清单

1. **gRPC 官方语言支持级别（GA / supported）**：`grpc.io/docs/languages/` 与 `grpc.io/docs/languages/python/` 两个页面**均未出现** GA 或支持级别字样，因此"Python 是 GA / 受支持级别"这一说法**本次无官方页面支撑**。
2. **gRPC 官方对 Python 的性能/成熟度说明**：仅从 PyPI 元数据读到 `Development Status :: 5 - Production/Stable`；grpc.io 的 "Performance Best Practices"、"Benchmarking" 页**正文未打开**，Python 专属性能结论不确定。
3. **`protoc-jar` 与 `buf`**：未打开官方页面，版本、维护状态、许可证均不确定。
4. **Spring Boot 3 系列的确切支持状态与推荐版本**：仅从 Maven Central 元数据确认 3.x 最后版本为 3.5.16；未打开 Spring 官方支持周期（support policy / end-of-support）页面，因此"Spring Boot 3 是否仍在 OSS 支持期"不确定。
5. **`spring-boot-starter-kafka` 这一 starter 名称**：未在 Spring Boot 官方 Kafka 章节正文中逐字确认（仅确认章节 URL 存在）。
6. **Redis 许可证正文**：`https://redis.io/legal/licenses/` 已打开（HTTP 200）但正文被站点导航截断未读到；确认落点在官方仓库 `LICENSE.txt`。**"2018 年 Redis 4.0 起部分模块用 RSALv2/SSPL" 的时间线与模块范围未获官方页面证实**。
7. **RabbitMQ 官网许可证专页**：`https://www.rabbitmq.com/licensing` 与 `/docs/licensing` 均为 404；结论改由官方仓库 `LICENSE` 文件支撑（核心 = MPL-2.0）。
8. **Spring 的 `SseEmitter` 类名**：官方 SSE 章节与锚点已确认，但 `SseEmitter` 这一具体类名未在本次抓取可见正文中逐字读到。
9. **AWS Step Functions execution 状态枚举全集**：`API_ExecutionStatus.html` 抓取被重定向，未取到枚举清单。
10. **Google Cloud Tasks 的任务状态机模型**：未打开官方页面，无经核实链接。
11. **Tess4J 的本地依赖要求（原生 Tesseract 库 / tessdata 目录）**：官方 README 抓取失败，未取得原文；仅确认其为 JNA 包装。
12. **Tesseract 中文（chi_sim）识别准确率**：官方**未给出量化数据**，只有相对速度/精度排序。
13. **ONNX Runtime 的许可证**：未打开其 LICENSE 或官方许可证页面，"MIT" 未被证实。
14. **ONNX Runtime Java 是否官方支持 OCR / ASR 模型**：官方 Java 文档与示例**只有 MNIST 打分**，无 OCR/ASR 示例；GenAI 的 Java API 页存在但正文未打开，是否可用于 OCR/ASR 不确定。
15. **whisper.cpp 上游是否自带官方 Java 绑定**：未在其仓库内容中核实；`whisper-jni` 是第三方（GiviMAD）JNI 封装，非上游官方绑定。
16. **whisper-jni 的生产可用性**：未做构建/运行验证；最近 push 为 2025-04-26。
17. **用户提供的 `https://github.com/tesseract-ocr/tess4j` 不存在**（Tess4J 实际为 `nguyenq/tess4j`），本报告已更正并全部按真实仓库取证。
