# 广宣法务审核 Agent：OCR / ASR / 视频 / 文档解析 技术选型调研

> 调研范围：为 Java 17 + Spring Boot 3 主服务补齐「风险定位」所需的解析能力（图片文字坐标、视频时间轴、文档页码段落锚点）。
> 部署约束：MySQL 8 + Redis + MinIO，Docker 企业内网，**很可能无外网出口**，**物料原文不得上传公网服务**。
> 已定主推理模型：DeepSeek（`deepseek-flash` 支持图像理解与截图 OCR 但**不返回坐标框**；`deepseek-v4-pro` 纯文本；不支持 ASR）。
>
> **本文所有版本号、许可证、价格均标注来源链接；无法从官方来源确认的一律标为「不确定」，未做任何推测性填写。**
> 数据核实时间基准：以各来源页面抓取时的最新状态为准（PaddleOCR 3.7.0 发布于 2026-06-11，FunASR 1.4.16 发布于 2026-09-18，PySceneDetect 0.7.1 发布于 2026-07-22，云厂商价格为 2026-09-20 抓取时点，详见各表）。
>
> **配套取证底稿**（含每条事实的原始抓取留痕）：
> - `docs/research/中文OCR引擎调研-完整报告.md`、`docs/research/中文OCR引擎调研-A-PaddleOCR事实核实.md`、`docs/research/ocr_alternatives.md`、`docs/research/ocr_cloud_vendors.md`
> - `ASR调研_中文ASR时间戳_官方来源核实.md`
> - `docs/技术选型调研-PDF-PPTX-DOCX与Java-Python集成.md`、`docs/research/PDF解析库事实核实.md`、`docs/research/Office文档解析方案官方事实核实-python-pptx-docx-POI-docx4j-Aspose-LibreOffice.md`、`docs/技术调研-Java与Python集成-事实核查.md`
> - `docs/research/cloud-ocr-asr-fact-check.md`

---

## 0. 结论速览

| 缺口 | 推荐方案 | 关键理由 |
|---|---|---|
| 中文 OCR（需坐标） | **PaddleOCR 3.7.0（Apache-2.0）+ PP-OCRv6_medium**，CPU 部署 | 原生返回四点坐标 `rec_polys` 与矩形框 `rec_boxes`；Apache-2.0 对私有化部署最友好；同仓库内置 PP-StructureV3 版面分析，可直接用于海报区域切分 |
| 中文 ASR（需时间戳） | **FunASR 1.4.16（工具链 MIT）+ Paraformer-zh**，CPU 部署 | 官方明确「Paraformer 用于需要字符级时间戳（character-level timestamps）的场景」；VAD + 标点 + 说话人齐备；官方 Docker 镜像与 OpenAI 兼容服务 |
| 视频解析 | **ffmpeg（抽帧/裁字幕区）+ PySceneDetect 0.7.1（BSD-3-Clause，镜头切分）**，自建统一时间轴 | 无需 GPU；官方提供带 ffmpeg 的 Docker 镜像；镜头切分与 ASR 时间戳都是「毫秒级区间」，可直接归并到同一时间轴 |
| PDF / PPTX / DOCX | **文档主链路走 Java 原生（Apache PDFBox 3.0.8 + Apache POI 5.5.1，均 Apache-2.0）**，Python 侧仅用 **pdfplumber 0.11.10（MIT）** 做兜底/交叉校验 | 全链路 Apache-2.0/MIT，规避 **PyMuPDF 的 AGPL-3.0** 与 **PaddleOCR 可选依赖中的 AGPL 组件**在「甲方内部法务合规系统」中的授权风险；Java 主服务不必为纯 PDF 文本解析跨进程 |
| Java↔Python 集成 | **独立 Python 解析微服务（FastAPI）+ Redis 队列/任务表异步驱动 + HTTP 回调或轮询**；不推荐 gRPC 起步、不推荐纯 Java 替代 | 满足「必须异步、真实进度、可重试」；解析能力天然是「耗时、可拆步、可幂等」的任务，适合任务表 + 事件驱动 |

**一句话选型：** PaddleOCR（坐标）+ FunASR/Paraformer（时间戳）+ PySceneDetect（镜头）+ Java 原生 PDFBox/POI（文档主链路），统一封装成一个 Python 解析微服务，由 Spring Boot 3 通过异步任务 + 进度回调编排。

---

## 1. 中文 OCR 引擎选型

### 1.1 开源方案逐项核实

| 项目 | 最新稳定版本（来源） | 许可证（来源） | 返回坐标 | CPU 可行性 | 中文口碑 / 备注 |
|---|---|---|---|---|---|
| **PaddleOCR** | **3.7.0**（2026-06-11，[PyPI](https://pypi.org/project/paddleocr/) / [GitHub README](https://github.com/PaddlePaddle/PaddleOCR)） | **Apache-2.0**（[README「📄 License」](https://github.com/PaddlePaddle/PaddleOCR)、PyPI 元数据 `Apache License 2.0`） | **是**：`rec_polys`（每行四点坐标，shape (4,2), int16）、`rec_boxes`（矩形 `[x_min,y_min,x_max,y_max]`）、`dt_polys`（未过滤检测框）；自 PP-OCRv5 起**支持返回单字坐标** | 是，官方给出 CPU 耗时与 `cpu_threads` 参数；PP-OCRv6 提供 OpenVINO 等 CPU 加速后端 | 官方基准：默认模型 PP-OCRv6_medium 相比 PP-OCRv5_server **识别 +5.1% / 检测 +4.6%**；PP-OCRv5_server_rec 中文识别准确率 86.38（官方自建评测集） |
| **RapidOCR** | **rapidocr 3.9.2**（2026-07-21，[PyPI](https://pypi.org/project/rapidocr/)）；旧包 `rapidocr-onnxruntime` 停在 1.4.4（2025-01-17） | **代码 Apache-2.0**（[仓库 LICENSE](https://github.com/RapidAI/RapidOCR)）；⚠️ **权重许可链不完整**（仓库 `MODEL_LICENSES.md` 返回 404 → 不确定） | **行级 + 单字级**（`word_results` 需 `return_word_box=True` 开关） | 官方**推荐** ONNX Runtime CPU 起步 | 自身无精度表，只能引用上游 PaddleOCR 指标；维护活跃（提交 2026-09-15） |
| **CnOCR** | **2.3.3**（2026-07-05，[PyPI](https://pypi.org/project/cnocr/)） | **代码 Apache-2.0**；⚠️ **权重许可未声明**，且**部分模型需购买/会员** | **仅行级** `position` 四点 `(4,2)` | **默认即 CPU**（后端默认 onnx） | 面向中文场景；官方**无中文准确率数据** |
| **EasyOCR** | **1.7.2**（**2024-09-24**，[PyPI](https://pypi.org/project/easyocr/)） | **代码 Apache-2.0**；⚠️ **权重许可未声明** | 仅行/框级 | 可用但需显式 `gpu=False`——⚠️ **其 API 默认 `gpu=True`**，CPU 部署必须改 | 官方无中文准确率数据；**维护活跃度低**（2025-12-05 后仅 README 改动） |
| **Tesseract** | 引擎 **5.5.3**（2026-07-24）；中文数据 `chi_sim` 来自 `tessdata`（**无版本号，最近提交 2024-03-07**）；Python 封装 `pytesseract` 0.3.13 | 引擎 **Apache-2.0**（依赖 Leptonica BSD-2）；**tessdata 全部数据 Apache-2.0** | **粒度最细：页/块/段/行/词**（hOCR `ocrx_word`、TSV level=5；pytesseract 还有字符级） | 纯 CPU 引擎，最快 | ⚠️ **官方无中文准确率数据**（其 Benchmarks 只测速度）；**语言数据约 2.5 年未更新**，这是中文效果落后的结构性原因 |

> ⚠️ **一个重要的事实边界**：RapidOCR / CnOCR / EasyOCR / Tesseract **四者的官方页面均无中文准确率数据**。「PaddleOCR 中文最好、Tesseract 最弱」这一判断在当前证据下是**社区与博客级共识**，而非任何一方官方基准。**验收必须用企业自有海报样本集实测，不能引用本文的横向排序作为验收依据。**

> 关于「中文识别准确率口碑」的证据强度说明：PaddleOCR 的 **86.38%（PP-OCRv5_server_rec 中文）** 与 **+5.1%/+4.6%（PP-OCRv6_medium vs PP-OCRv5_server）** 均来自官方文档自建评测集，官方同时注明「PP-OCRv6 指标基于内部多场景评估集测得，PP-OCRv5/v4 指标基于通用评估集测得，两者评估集不同，指标不可直接对比」。第三方横评（如 [PaddleOCR/EasyOCR/Tesseract 实战对比](https://cloud.baidu.com/article/3617940)）结论方向一致，但多为博客级证据，**不建议作为验收依据**——验收应使用企业自有海报样本集实测。

### 1.2 PaddleOCR 关键工程事实（均有官方出处）

| 事项 | 结论 | 来源 |
|---|---|---|
| 坐标 API 形态 | `predict()` 返回 `res` 字典：`rec_texts`（文本列表）、`rec_scores`（置信度）、`rec_polys`（四点多边形）、`rec_boxes`（矩形框 n×4） | [通用 OCR 产线文档](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/OCR.md) |
| 「是否返回坐标」开关 | 参数 `return_word_box`（含义：是否返回识别结果的文字框坐标） | 同上 |
| 单字坐标 | 「PP-OCR series models now support returning single-character coordinates」（3.2.0 更新日志） | [README 更新日志](https://github.com/PaddlePaddle/PaddleOCR) |
| 版面分析 | **PP-StructureV3**：版面检测模型含 **20~23 类**（文档标题、段落标题、文本、页码、摘要、目录、页眉页脚、公式、图像、表格、图表标题、印章、侧栏文本等）；输出 `layout_det_res.boxes[].coordinate`、`table_res_list[].cell_box_list`（**表格单元格坐标**）、`overall_ocr_res`（全文 OCR + 坐标） | [PP-StructureV3 文档](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.md) |
| CPU 推理耗时（官方基准） | 测试环境 CPU=Intel Xeon Gold 6271C @2.60GHz / FP32 / 8 线程。**PP-OCRv5_server_rec：CPU 31.21ms**（常规=高性能）；**PP-OCRv5_mobile_rec：CPU 21.20ms（常规）/ 5.32ms（高性能）**；**PP-OCRv5_server_det：CPU 383.15ms**（检测模块，与分辨率强相关） | [OCR 产线文档「测试环境说明」](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/OCR.md) |
| PP-OCRv6 耗时 | **官方另有 CPU 实测基准**（PaddleOCR 3.x 文档）：Intel Xeon 8350C + **OpenVINO 后端**下 **PP-OCRv6_tiny 0.20 s/图、PP-OCRv6_medium 1.40 s/图**，同机 PP-OCRv5_server 为 **7.30 s/图**（即 5.2× 提速的来源）。另有 PP-OCRv5_mobile **1.75 s/图**、PP-OCRv5_server **4.34 s/图**（Intel Xeon Gold 6271C）。CPU 峰值内存约 **2.0~4.0 GB** | 同上 + [高性能推理文档](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/local_inference/high_performance_inference.md) |
| 词/单字坐标 | **默认只到行级**。词/单字级需显式开启 **`return_word_box=True`**（默认 `False`） | 同上 |
| 依赖层许可风险 | ⚠️ PaddleOCR 3.x 的**可选依赖中含 AGPL-3.0 第三方库（PyMuPDF、pdf2docx）**。若安装全量依赖，会把这些库带进镜像 → **必须做依赖树裁剪与合规审计**，只装 OCR/版面所需的最小依赖组 | [3.x 安装文档](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/installation.md)（依赖组划分）；[社区讨论 #16886](https://github.com/PaddlePaddle/PaddleOCR/discussions/16886)（AGPL 依赖问题） |
| 官方 Docker | ⚠️ **Docker Hub 上没有 PaddleOCR 官方镜像**（`paddlepaddle/paddleocr` API 返回 404）。官方镜像实际在**百度云 CCR**：`ccr-2vdh3abv-pub.cnc.bj.baidubce.com/paddlepaddle/paddle:3.0.0`（**CPU 版**，GPU 版带 `-gpu-cuda*` 后缀）；Docker Hub 的 `paddlepaddle/paddle` 只是**框架镜像**。PaddleOCR-VL 有官方离线镜像与 `docker save/load` 流程，**但该镜像需要 NVIDIA GPU**。3.x 服务化官方推荐 **PaddleX**（`paddlex --serve --pipeline OCR`，`--device` 默认「有 GPU 用 GPU，无则回落 CPU」）；**hubserving 是 2.x 的历史方式，不要再用** | [服务化部署文档](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/serving/serving.md)、百度云 CCR |
| 高性能推理 | `paddleocr install_hpi_deps {设备类型}`，自动在 Paddle Inference / **OpenVINO** / **ONNX Runtime** / TensorRT 间择优；README 称 PP-OCRv6 有 **5.2× CPU 端到端加速（OpenVINO）** | [高性能推理文档](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/local_inference/high_performance_inference.md) |
| 是否需要 GPU | **不需要**。官方明确 CPU 可用并给出线程参数；GPU（T4）只是更快 | 同上 |

### 1.3 云厂商通用文字识别（对照方案）

> 前提：本项目**物料原文不得出内网**，因此云 OCR **只能作为「效果对标基线」或非敏感物料的补充**，不能作为主链路。

| 厂商 | 是否返回坐标 | 价格量级（官方页面原文） | 私有化 / 离线 |
|---|---|---|---|
| 阿里云 OCR | **默认返回四点坐标**：高精度版 `prism_wordsInfo[].pos`（外矩形四点，顺时针：左上/右上/右下/左下），另有 `orgWidth/orgHeight`；单字可用 `charInfo` 的 x/y/w/h（需 `OutputCharInfo=true`）。官方接口文档原文「支持返回文字内容和位置坐标信息」 | **免费额度：识别类 API 每 API 每月 200 次**。按量阶梯（**累加制**）：**通用文字识别基础版 0.0825/0.0495/0.0415/0.0248/0.009 元/次**（≤1万/1–10万/10–50万/50–100万/>100万，即约 82.5 元/千次起）；**高精度版 0.225/0.09/0.054/0.045/0.036 元/次**（约 225 元/千次起）。默认 10 QPS/API | 产品简介提及 OCR「支持公共云 API 调用与**私有化双部署**模式」，但**无独立私有化文档页、无公开报价、未查到 OCR 离线 SDK**；ASR 未查到私有化说明。⚠️ **本次未找到阿里云 ASR 私有化官方产品页** |
| 腾讯云 OCR | `TextDetections[].ItemPolygon`（X/Y/Width/Height **矩形**）+ `Polygon`（**四点**，每点 X/Y）双格式；官方对比表标注「返回文本行坐标：支持」，高精度版同为支持（官方标注准确率 99% vs 96%）。单字四顶点需 `IsWords=true` | **免费额度 1,000 次/月**（每月 1 号发放、仅当月有效）。**后付费刊例价：通用印刷体识别 0.15/0.10/0.06 元/次**（<1万/1–10万/10–100万，≥100万需联系商务）；**高精度版 0.50/0.35/0.20 元/次**。预付费刊例：通用印刷体 1000 次 120 元 / 1万 800 / 10万 5000 / 100万 30000 / 1000万 200000；高精度版 1000 次 400 元 / 1万 3000 / 10万 15000 / 100万 80000 / 1000万 500000。⚠️ **部分失败错误码也计费**。⚠️ 产品页活动价「0.011 元/次起」与上述刊例价相差一个数量级，**官方未说明适用范围** → 成本口径建议采用刊例价 | FAQ 存在私有化条目，但**未查到 OCR 离线 SDK、无公开报价**；ASR 官方 FAQ 原文「语音识别支持私有化部署，**需要商务对接跟进**」 |
| 百度智能云 OCR | **标准版即返回矩形坐标**：`words_result[].location` = `left/top/width/height`（坐标原点为左上角）；另有「标准含位置版」「高精度含位置版」等 4 个含位置接口；`recognize_granularity=small` 返回单字坐标（含 `char_prob`），`vertexes_location=true` 返回四边形轮廓点 —— **单字信息最完整** | **免费额度（官方 2026-09-11 更新）：标准版/标准含位置版/高精度版 = 个人 1,000 / 企业 2,000 次/月；高精度含位置版 = 个人 500 / 企业 1,000 次/月**。⚠️ **成功与失败调用均消耗免费资源**。按量阶梯（**累加制**）：**标准版 0.0050→0.0025 元/次**（约 5 元/千次起）；**高精度版 0.030→0.010 元/次**（约 30 元/千次起）；高精度含位置版次数包 1 万次 380 元。共享资源包 10 万点 330 元（标准版 5 点/次、高精度版 10 点/次） | **三家中唯一给出完整公开路径**：① **OCR 离线 SDK 有公开阶梯价**——通用文字识别等 SDK 第 3~1000 个 **299 元/个**、1001~5000 个 249 元/个、5001 个及以上 199 元/个；办公文档识别 799/669/499 元/个；**按设备硬件指纹授权、激活后永久有效**。② **OCR 私有化部署服务**——模型部署至本地服务器/私有云，提供**内网 API**，License 绑机器指纹，含按年/永久授权与一体机选项，**无公开报价，需联系商务经理**。③ **语音私有化**（纯软件版+一体机版），官方原文「**数据的存储及处理均在企业内网进行**」，支持海光/鲲鹏国产化，**无公开报价** |

**⚠️ 对本项目最关键的一条云侧约束（三家共同）：**
所有云 OCR / ASR 的输入都需要**公网可达**——阿里云 FAQ 提示图片「可以通过公网正常访问」；腾讯云 ASR 明确 `Url`「需要公网环境浏览器可下载」；百度转写要求 `speech_url` 为「云端可外网访问的 url 链接」。且**三家的 AI 能力文档中均未查到 AI API 支持 VPC 内网接入或专线直连的官方说明**（官网只有通用的「专线接入/私有网络」产品页）。→ **对「很可能无外网出口」的内网部署，云方案在架构上基本不可用，除非采购私有化/离线版本（仅百度三家中有完整公开路径）。**

**数据合规（官方原文）**：阿里云 OCR「公共云服务**不落盘**，用户的原始图片不作保留，识别返回后立即释放」；腾讯云 ASR「**仅供当次识别使用，不会进行保存**」，通用协议「未经您同意不会对用户业务数据进行任何未获授权的使用和披露」。⚠️ **三家均未查到针对 OCR/ASR 输入内容「不用于模型训练」的专门书面条款** → 待确认项。

> 来源：[阿里云计费](https://help.aliyun.com/zh/ocr/product-overview/product-billing/)、[阿里云按量付费](https://help.aliyun.com/zh/ocr/product-overview/pay-as-you-go)、[阿里云 RecognizeGeneral](https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizegeneral)、[腾讯云计费概述](https://cloud.tencent.com/document/product/866/17619)、[腾讯云 GeneralBasicOCR](https://cloud.tencent.com/document/api/866/33526)、[百度 OCR 免费额度](https://cloud.baidu.com/doc/OCR/s/fk3h7xu7h)、[百度 OCR 价格](https://cloud.baidu.com/doc/OCR/s/tlrzzplc1)、[百度 OCR 离线 SDK 价格](https://cloud.baidu.com/doc/OCR/s/ykia5niiu)、[百度 OCR 私有化](https://cloud.baidu.com/doc/OCR/s/1kuqeya49)、[百度语音私有化](https://cloud.baidu.com/doc/SPEECH/s/Al9mh44v7)

### 1.4 结论建议（OCR）

1. **主选 PaddleOCR 3.7.0 + PP-OCRv6_medium（服务端档）**：坐标能力是「一等公民」（四点框 + 矩形框 + 单字坐标），Apache-2.0 许可对私有化交付最干净；这是唯一同时满足「中文精度第一梯队 + 坐标完整 + 许可宽松」的开源选项。
2. **海报场景必须叠加 PP-StructureV3 版面分析**：海报的风险往往按「视觉区块」分布（主标题、卖点列表、免责声明小字、价格角标）。用 `layout_det_res` 的 20+ 类版面框切分区域，再把 OCR 行框归属到区域，可以让法务看到的框选结果更接近人眼理解，而不是一堆散乱文本行。
3. **CPU 完全够用，按「端到端单图耗时」而不是「单模块耗时」做容量规划**：官方 CPU 端到端基准为 **PP-OCRv6_medium 1.40 s/图**（Xeon 8350C + OpenVINO，含检测+识别），PP-StructureV3 轻量配置约 **3.74 s/图**。容量公式可直接用「(单图秒数 × 并发物料数) / CPU 核数」估算。建议：开启高性能推理走 OpenVINO/ONNX Runtime、按容器核数设置 `cpu_threads`（默认配置为 8 线程基准）、限制图片长边、对同一物料做哈希级结果缓存。
4. **必须做依赖树裁剪（合规要求，不是优化项）**：PaddleOCR 3.x 的**可选依赖包含 AGPL-3.0 的 PyMuPDF、pdf2docx**。哪怕只因"图省事"装了全量依赖，也会把 AGPL 组件带进交付镜像。**只安装 OCR / PP-StructureV3 所需的最小依赖组（如 `doc-parser` 组），并在 CI 里加一道依赖许可证白名单扫描。**
5. **RapidOCR 作为「低配降级通道」保留**：如客户内网机器资源极紧或 Paddle 框架安装成为阻力，RapidOCR（代码 Apache-2.0，ONNX Runtime，官方推荐 CPU 起步）可在几乎不改变输出结构的前提下替换；代价是权重许可链不完整、精度上限受所移植模型版本限制。
6. **明确不选**：EasyOCR（维护近乎停滞、API 默认 `gpu=True` 易踩坑、权重许可未声明）、Tesseract（中文语言数据 2.5 年未更新，仅适合纯英文/数字或交叉校验）、CnOCR（仅行级坐标、部分模型需付费）。**云 OCR 原则上不进入主链路**——与「物料原文不出内网」直接冲突；若确需使用，只能限定在已脱敏或公开物料上（三家云 OCR 的价格与私有化事实见 1.3，其中只有百度提供有公开报价的离线 SDK）。

---

## 2. 中文 ASR 引擎选型

### 2.1 FunASR / Paraformer 逐项核实

| 事项 | 结论 | 来源 |
|---|---|---|
| 最新稳定版本 | **funasr 1.4.16**（2026-09-18，[PyPI](https://pypi.org/project/funasr/)）；README 更新日志明确「FunASR 1.4.16」 | [PyPI](https://pypi.org/project/funasr/)、[README](https://github.com/modelscope/FunASR) |
| 许可证（代码） | **MIT**（[仓库 LICENSE](https://github.com/modelscope/FunASR)，文件头为 `MIT License / Copyright (c) 2025 FunASR`） | 同上 |
| 许可证（模型权重） | **与代码分离**：官方明确「Pretrained model weights are licensed separately」，若模型卡指向 [FunASR Model Open Source License Agreement](./MODEL_LICENSE)（v1.1，Copyright Alibaba Group）则适用该协议——**允许使用/复制/修改/分享，但要求署名并保留模型名称**，且含行为约束条款与违约自动终止条款 | [README「License」节](https://github.com/modelscope/FunASR)、[MODEL_LICENSE](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE) |
| Docker 部署 | 官方镜像在**阿里云 registry**：`registry.cn-hangzhou.aliyuncs.com/funasr_repo/funasr`，真实 tag 包括 **离线 C++ CPU `funasr-runtime-sdk-cpu-0.4.7`**、离线 GPU `funasr-runtime-sdk-gpu-0.2.1`、在线/2pass CPU `funasr-runtime-sdk-online-cpu-0.1.13`；另有 **OpenAI 兼容服务 + Docker Compose**（默认 CPU，含 K8s 模板）。⚠️ **Docker Hub 上没有官方 funasr 镜像**（搜索仅社区镜像） | [官方 Docker 文档](https://www.funasr.com/en/docs/docker.html)、[部署矩阵](https://github.com/modelscope/FunASR/blob/main/docs/deployment_matrix.md) |
| 离线转写形态 | ① C++ WebSocket 服务 `funasr-wss-server`（端口 10095，提供 html/Python/C++/**Java**/C# 客户端）② Python HTTP OpenAI 兼容 `funasr-server` ③ ONNX Runtime C++ 二进制 ④ K8s/Compose 模板 | [离线 SDK 高级指南](https://github.com/modelscope/FunASR/blob/main/runtime/docs/SDK_advanced_guide_offline_zh.md) |
| **字级时间戳** | **Paraformer 明确支持**：官方选型指南写「**Switch to Paraformer when your workload is Mandarin-only and you want character-level timestamps or hotwords**」；官方中文文档原文「输出为带标点的文字，**含有字级别时间戳**」。SDK 返回 `timestamp` 为 **token/词/字符区间对 `[[start_ms, end_ms], ...]`**；`sentence_info` 提供句级 `start`/`end`（**毫秒**）。Paraformer 开关：**`pred_timestamp` 优先，否则 `output_timestamp`（默认 False）**；ONNX 二进制支持 `--output-format jsonl`（含 `timestamp` + `stamp_sents`）。⚠️ 官方警告：**粒度和可用性取决于模型/checkpoint**，不可在不同 Paraformer 变体间外推 | [模型选型](https://github.com/modelscope/FunASR/blob/main/docs/model_selection.md)、[Python API 契约](https://github.com/modelscope/FunASR/blob/main/docs/python_api.md)、[ONNX 输出格式](https://www.funasr.com/en/docs/onnx-output.html) |
| 标点恢复 | **ct-punc**（290M，中文/英文），**Apache-2.0**；**不默认启用**，需显式 `punc_model="ct-punc"`；`raw_text` 字段可选保留去标点原文 | [README Model Zoo](https://github.com/modelscope/FunASR)、[ct-punc 模型卡](https://huggingface.co/funasr/ct-punc) |
| VAD | **FSMN-VAD**（官方推荐搭配；亦支持 silero-vad），配置 `vad_model="fsmn-vad"` 后时间戳会**偏移回原始录音时间轴** | 同上 |
| 说话人分离 | **CAM++**（7.2M，**Apache-2.0**）提供说话人向量，AutoModel 做聚类后给 VAD 段落打 `spk` 标签。⚠️ **不默认启用**，需 `spk_model="cam++"` + VAD；官方强调 `spk=0` 这类标签是**单次录音内的匿名索引，不是真实身份，也不跨录音稳定**；只设 `spk_model` 不配置 VAD 时**不会**产生分离 | [Python API 契约](https://github.com/modelscope/FunASR/blob/main/docs/python_api.md) |
| CPU 可行性 | **是，官方主打 CPU 路径**：SenseVoice 提供「CPU-first」示例；`device="cpu"` / `ngpu=0` 明确支持；`ncpu` 默认 4。部署矩阵把 ONNX/C++ runtime 定位为「高并发 CPU 服务」。另有 **GGUF/llama.cpp 单文件二进制**（CPU/边缘），官方称中文 CER **比 whisper.cpp 低约 3 倍** | [部署矩阵](https://github.com/modelscope/FunASR/blob/main/docs/deployment_matrix.md)、[README](https://github.com/modelscope/FunASR) |
| **CPU 速度（官方基准，取代此前的社区数据）** | 官方 ONNX/C++ 基准（AISHELL-1 test，36108.9s 音频）：Paraformer-large 220M，fp32 880MB / **int8 量化后 237MB，两者 CER 均为 1.95%**；**Xeon 8369B（16C32T，含 avx512_vnni）单并发 int8 RTF 0.02826（约 35× 实时）**、fp32 RTF 0.058974（约 17×）；**较老的 Xeon 8163（无 avx512_vnni）单并发 int8 RTF 0.075168（约 13× 实时）** | [ONNX/C++ 基准](https://github.com/modelscope/FunASR/blob/main/runtime/docs/benchmark_onnx_cpp.md) |
| 模型代际 | 除 Paraformer 外还有 **SenseVoiceSmall**（多语种 + 情感/事件标签，CPU 友好，`output_timestamp=True` 可拿 `words` + 毫秒 `timestamp`）与 **Fun-ASR-Nano**（LLM 路线，时间戳「取决于 checkpoint 与路径」）。**若硬要求字符级时间戳，官方指引就是 Paraformer** | [Python API 契约](https://github.com/modelscope/FunASR/blob/main/docs/python_api.md)、[模型选型](https://github.com/modelscope/FunASR/blob/main/docs/model_selection.md) |

### 2.2 Whisper 系对比

| 方案 | 最新版本（来源） | 许可证（来源） | 中文时间戳质量 | CPU 资源占用 |
|---|---|---|---|---|
| **openai/whisper** | `openai-whisper` **v20250625**（[releases](https://github.com/openai/whisper/releases/tag/v20250625)） | **MIT**（README 明确**代码与权重均 MIT**） | 原生 `word_timestamps=True`，但 **CLI 帮助原文把该选项标注为 "(experimental)"**；另有 `hallucination_silence_threshold`（需配合 word_timestamps）。**官方未给中文时间戳质量与 CPU RTF 说明** | 最重，CPU 上不实用 |
| **faster-whisper** | **1.2.1**（2025-10-31，[PyPI](https://pypi.org/project/faster-whisper/)） | **MIT**（[LICENSE](https://github.com/SYSTRAN/faster-whisper)） | 基于 **CTranslate2**，支持 `word_timestamps=True`；官方口径「**up to 4 times faster** than openai/whisper **for the same accuracy**」 | **有官方 CPU 实测**（8 线程 i7-12700K，13 分钟音频，small，beam=5）：openai/whisper fp32 **6m58s/2335MB** → faster-whisper fp32 **2m37s** → **int8 1m42s/1477MB** → **int8+batch8 51s/3608MB**；内置 Silero VAD（批量模式默认开） |
| **WhisperX** | **3.8.6**（2026-05-25，[PyPI](https://pypi.org/project/whisperx/)） | **BSD-2-Clause**（[LICENSE](https://github.com/m-bain/whisperX)） | **中文对齐有专门模型且许可证为 Apache-2.0**：源码 `DEFAULT_ALIGN_MODELS_HF["zh"] = "jonatasgrosman/wav2vec2-large-xlsr-53-chinese-zh-cn"`，且把 `zh` 列入 `LANGUAGES_WITHOUT_SPACES`（走**字符级**对齐）。⚠️ 官方承认 Whisper 原生时间戳为**句级、可能偏差数秒**；官方 Limitations 明确**词典外的词（如 "2014."、"£13.60"）拿不到时间戳**，重叠说话处理差。⚠️ 对齐模型需从 HuggingFace 拉取，**内网必须预置进镜像** | 最重（ASR + 对齐模型 + 可选分离模型） |

> **对「广告视频背景音乐 + 口播 + 字幕叠加」的鲁棒性（证据分级说明）**：
> - **OpenAI 官方对此场景零说明**——不要声称"官方支持带 BGM"。
> - **同行评审证据**：ICASSP 2025 论文 [*Investigation of Whisper ASR Hallucinations Induced by Non-Speech Audio*](https://arxiv.org/abs/2501.11378) 证实**非语音音频会诱发 Whisper 高频幻觉**，且**语音上叠加此类声音同样会诱发**，论文提出 BoH 后处理以降低 WER。
> - **社区证据（非官方）**：openai/whisper Discussion [#2645](https://github.com/openai/whisper/discussions/2645) 标题即「识别中文时，输入为静音或**只有背景噪声**，模型经常输出固定的无关文本」；[#2685](https://github.com/openai/whisper/discussions/2685) 记录中文音乐场景出现**凭空编造版权归属**。
> - **WhisperX 的官方缓解手段**：VAD 分段 + 默认 `condition_on_prev_text=False`（README 注明可降低幻觉）。
> - **结论**：广告视频大量存在「纯 BGM 无人声」段落，这正是 Whisper 系幻觉的高发区。FunASR 的 **FSMN-VAD + Paraformer** 架构天然先做语音活动检测、再对语音段识别，**无语音段不进入识别器**，从机制上规避该风险。⚠️ 但**没有任何来源给出「中文广告口播 + BGM」的量化 CER/幻觉率**——这是需要在企业自有素材上实测的空白项。

### 2.3 云厂商 ASR（对照方案）

| 厂商 | 字级时间戳 | 价格量级（官方页面原文） | 私有化 |
|---|---|---|---|
| 阿里云智能语音交互 | ✅ **支持**：`Words`（List\<WordResult\>）含 `Word/BeginTime/EndTime`，**毫秒**；**需同时满足 `enable_words=true` 与 `version="4.0"`**。⚠️ 字段名由上一轮核实给出，**本次复核未在官方正文定位到该字段 → 待确认** | 新用户 3 个月免费试用。录音文件识别资源包：**40h=100 元（2.50 元/h）**、1000h=1200 元、2万h=2万元、10万h=9万元、25万h=20万元；**按量 2.50 元/h 起**；极速版按量 3.30 元/h 起；闲时版 0.60 元/h 起。**阶梯为累加制** | ⚠️ **本次未找到 ASR 私有化官方产品页** → 不确定，需商务咨询 |
| 腾讯云语音识别 | ✅ **支持**：官方原文「`ResTextFormat`=1/2/3/4/5 增加**词粒度的详细识别结果（包含词级别时间戳）**」，其中 **`=3` 为「识别结果按标点分段，适用字幕场景」**，可配 `SentenceMaxLength` [6,40]。⚠️ `SentenceDetail` 内部具体字段名未从官方页提取到正文 → 待确认 | 免费额度：**录音文件识别 10h/月**（另有实时 5h/月、一句话 5000 次/月、极速版 5h/月；**大模型 1.0/2.0 版无免费额度**）。**录音文件识别后付费 1.75/1.40/0.95 元/h**（0–12万/12–30万/30万+ 小时每月，月结）；极速版 3.10 元/h 起；大模型 2.0 版固定 0.8 元/h。预付费 60h=90 元起。⚠️ **阶梯是按总量套单一价，非分段累加（与阿里云不同）** | 官方 FAQ 原文「语音识别支持私有化部署，**需要商务对接跟进**」，并提及「离在线 SDK」（纯离线仅支持实时识别接口、限企业认证账号）；**无公开报价** |
| 百度智能云语音 | ❌ **不支持字级**：`detailed_result[].words_info` 结构上含 `begin_time/end_time`，但参数表把 `words_info` 标注为「**字粒度（预留参数，暂不启用）**」。另有极速版 `enable_subtitle=2`「开启字幕模式，**返回字粒度时间戳**」，但该接口标注「**处于邀测阶段**」且价格未在产品价格页单列 | **音频文件转写免费额度 10 小时**（个人/企业同）。小时包 1000h=1200 元（**1.2 元/h**）、10万h=7万元（0.7）、50万h=30万元（0.6）；后付费中文普通话/英语 **2 元/h**；**音视频字幕（中文）小时包 1000h=1560 元（1.56 元/h）、后付费 2.5 元/h**；短语音识别标准版 3.4 元/千次起 | **三家中最完整**：「私有化部署方式」页含纯软件版（本地 CPU/GPU、单机/多机/集群，支持海光/鲲鹏国产化）+ 一体机版，官方原文「**数据的存储及处理均在企业内网进行**」；注明**付费项目、需申请，未公布价格** |

**⚠️ 云 ASR 对本项目的决定性约束**：腾讯云 ASR 明确 `Url`「**需要公网环境浏览器可下载**」；百度音频文件转写要求 `speech_url` 为「**云端可外网访问的 url 链接**」。→ **云 ASR 无法在无外网出口的内网中使用**（除非采购私有化版本，仅百度/腾讯有公开说明）。

**说话人分离（云侧）**：阿里云**限制最严**（官方社区答复称仅录音文件识别/闲时版针对 8k 单通道支持角色分离，16k 当时内测未开放）；腾讯云支持（`SpeakerDiarization=1` + `ChannelNum=1` + `SpeakerNumber` 0/1-10，`=3` 为增值服务）；百度**仅特定 PID（8953）支持**。

> 来源：[阿里云 ASR 计费](https://help.aliyun.com/zh/isi/product-overview/billing-10)、[阿里云 ASR 开发参考](https://help.aliyun.com/zh/isi/developer-reference/speech-recognition/)、[腾讯云 ASR 计费](https://cloud.tencent.com/document/product/1093/35686)、[腾讯云录音文件识别 API](https://cloud.tencent.com/document/api/1093/37824)、[腾讯云 ASR FAQ](https://cloud.tencent.com/document/product/1093/35802)、[百度音频文件转写](https://cloud.baidu.com/doc/SPEECH/s/Klbxern8v)、[百度语音计费](https://cloud.baidu.com/doc/SPEECH/s/Al9mh44v7)、[百度语音私有化](https://cloud.baidu.com/doc/SPEECH/s/Al9mh44v7)

### 2.4 结论建议（ASR）

1. **主选 FunASR 1.4.16 + Paraformer-zh（+ FSMN-VAD + ct-punc）**：这是本次调研中**唯一由官方文档明确指出「需要字符级时间戳就用它」**的中文开源方案，且 VAD/标点/说话人三件套齐备、CPU 路径是官方一等公民、工具链 MIT 无授权负担。
2. **注意三个默认值陷阱**：① Paraformer 的时间戳需要开启 `pred_timestamp` 或 `output_timestamp`（**默认 False**）；② 标点 `ct-punc` **不默认启用**；③ 说话人 `cam++` **不默认启用且必须配合 VAD**。也就是说「装了 FunASR 就自动有字级时间戳 + 标点 + 说话人」是错的，必须在调用层显式配置并在解析结果里记录实际启用了哪些能力。
3. **CPU 侧优先考虑 ONNX/int8 量化路径**：官方 ONNX/C++ 基准显示 int8 量化后模型仅 **237MB**、**CER 不下降（仍为 1.95%）**，16 核带 `avx512_vnni` 的 Xeon 单并发 **RTF 0.028（约 35× 实时）**；即使是没有 `avx512_vnni` 的老 CPU 也有 **≈13× 实时**。对比 Whisper 系在 CPU 上的分钟级耗时，FunASR 在「10 分钟宣传片几十秒转完」这个量级上是有官方数据支撑的。⚠️ 官方基准是 AISHELL-1 朗读语料，广告口播（含 BGM）实测会更差，**上线前必须用企业自有素材复测**。
4. **模型权重许可必须在交付清单中单独列出**：FunASR 代码 MIT ≠ 模型权重 MIT。官方明确「预训练权重单独授权，以各模型卡为准」，仓库另附《FunASR 模型开源协议 v1.1》（要求署名 + 保留模型名）。⚠️ 存在**双文本并存**问题：Paraformer 在 ModelScope 与 HuggingFace 均标 **Apache-2.0**，而仓库级协议要求署名；**SenseVoiceSmall 更是两处标注不一致**（ModelScope 标 Apache-2.0，HF 标 `other/model-license` 指向该协议）。**建议在合规评审时直接向模型方书面确认，不要自行择一。**
5. **WhisperX 作为「离线二次校验」选项而非主链路**：它的中文 wav2vec2 强制对齐（Apache-2.0 模型）质量好，适合在**已确认口播文本**的前提下做精确对齐；但组件重、需预置对齐模型、官方承认数字/货币类词拿不到时间戳，且无法规避 Whisper 的静音/BGM 幻觉。
6. **明确不选云 ASR 进主链路**：与「物料原文不出内网」冲突；口播稿是未发布产品的核心商业信息，风险高于 OCR。若必须考虑，事实是：**阿里云与腾讯云支持字级时间戳（百度不支持，`words_info` 官方标为「预留参数，暂不启用」）**，离线按量价量级 **1.2~2.5 元/小时**，但**私有化路径只有腾讯云（纯离线仅支持实时识别接口、限企业认证账号）与百度（付费项目、未公布价）有公开说明，阿里云未找到官方私有化产品页**。

---

## 3. 视频解析工程做法

### 3.1 关键组件与推荐参数

| 环节 | 推荐工具 | 版本 / 许可 | 推荐参数思路（依据官方文档） |
|---|---|---|---|
| 抽帧 / 裁切 / 转码 | **ffmpeg** | 以基础镜像内版本为准（**未核实具体版本号**）。建议用 `jrottenberg/ffmpeg` 或自建 ≥7.x 镜像 | 硬字幕区域裁剪用 `-vf crop=w:h:x:y`；定频抽帧用 `fps` 滤镜；帧级差异定位用 `select='gt(scene,阈值)'`；代表帧用 `thumbnail` 滤镜 |
| 镜头切换检测 | **PySceneDetect** | **0.7.1**（2026-07-22，[PyPI](https://pypi.org/project/scenedetect/)），**BSD-3-Clause**（[LICENSE](https://github.com/Breakthrough/PySceneDetect)） | 官方**默认** `ContentDetector(threshold=27.0, min_scene_len=15)`；快速运动的广告片建议改用 **`AdaptiveDetector(adaptive_threshold=3.0, min_scene_len=15, window_width=2, min_content_val=15.0)`**（官方定位：缓解快速镜头运动导致的误检）；黑场/淡入淡出用 `ThresholdDetector(threshold=12)` |
| 镜头切分容器化 | PySceneDetect 官方镜像 | `ghcr.io/breakthrough/pyscenedetect`（**内置 ffmpeg/mkvmerge**） | 官方 README 明确该镜像含全部依赖，可直接挂载目录跑 `split-video` / `save-images` |

### 3.2 硬字幕（内嵌字幕）OCR 的常见做法与坑

**推荐做法（业界成熟范式）** —— 参考 Apache-2.0 的 [video-subtitle-extractor (VSE)](https://github.com/YaoFANGUK/video-subtitle-extractor)，其流程正是本项目需要的五步：

1. **抽取候选帧**：按固定间隔或镜头内代表帧抽帧，而不是逐帧（VSE 官方把「逐帧检测」列为「非常慢、不推荐」档）。
2. **字幕区域检测**：先在若干帧上做文本框检测，统计**稳定出现的下方区域**作为字幕 ROI，后续只在该 ROI 内 OCR，成本可下降一个数量级。
3. **ROI 内 OCR**：用 PaddleOCR 识别，拿 `rec_texts` + `rec_polys`（ROI 坐标需换算回整帧坐标）。
4. **过滤非字幕文本**：排除水印、台标、画面内固有文字（VSE 提供了文本替换/删除配置 `typoMap.json` 这一同类机制）。
5. **去重 + 生成时间轴**：相邻帧文本相同或高度相似时合并为一个字幕条，起止时间取首末帧时间。

**必须提前预案的坑：**

| 坑 | 说明 | 应对 |
|---|---|---|
| CPU 成本爆炸 | 官方基准中 PaddleOCR **文本检测** CPU 单图 383ms 量级；若逐帧跑 30fps × 60s = 1800 帧，不可接受 | ① 只在字幕 ROI 内检测；② 抽帧频率降到 1~2 fps（字幕显示时长通常 ≥1 秒）；③ 对 ROI 帧做感知哈希去重，仅对「变化帧」跑 OCR |
| 字幕与口播时间轴错位 | 字幕是「显示时刻」，口播是「发声时刻」，二者天然有几帧偏移 | 统一以「视频毫秒时间轴」为唯一坐标系，字幕条与 ASR 句段各自保留原始区间，**不做强行对齐**，只做区间重叠归并 |
| 双语字幕/多行字幕 | 中英双语两行会被识别成两个文本行 | 按 y 坐标聚类，同一时间窗内的多行合并为一条字幕（保留双语原文） |
| 字幕描边/阴影/半透明底 | 影响检测召回 | 抽帧后先做灰度化 + 自适应阈值增强，再做检测 |
| 变分辨率/横竖屏混排 | ROI 坐标不可跨素材复用 | ROI 必须**按物料单独计算**，不能全局固定 |
| 广告片快闪字幕 | 单帧闪现（<0.5s）会被抽样漏掉 | 对快剪段落提高抽帧频率，或用 `select='gt(scene,...)'` 在切点前后强制补帧 |

### 3.3 「口播 ASR 时间戳」与「画面关键帧」对齐到同一时间轴

**设计原则：不做"最佳匹配"，只做"同轴归并"。** 两者本就是同一媒体时间轴上的两类区间，正确做法是建立一个统一的 `timeline_event` 表：

```
video_id | start_ms | end_ms | track_type | payload
```

- `track_type ∈ { asr_sentence, asr_char, subtitle_ocr, scene, keyframe }`
- **镜头区间**：PySceneDetect 输出 `scene[0].get_timecode()` / `frame_num` → 转毫秒（需按 `video.frame_rate` 换算，官方 API 提供 `get_timecode()` 与 `get_seconds()`）
- **关键帧**：每个镜头取首帧 + 中间代表帧（`save-images` 命令即为此用途）
- **口播**：FunASR `sentence_info[].start/end`（**毫秒**）+ `timestamp`（字符区间对，毫秒）
- **硬字幕**：OCR 合并后的字幕条起止毫秒

**对齐与展示规则（建议固化为产品规则）：**

1. **风险区间 = 最小覆盖区间**：若一个风险同时命中 ASR 句段 `[t1,t2]` 与字幕条 `[t3,t4]`，Risk Case 的展示区间取 `[min(t1,t3), max(t2,t4)]`，并把两者原文都作为「风险原文」候选。
2. **镜头边界作为软切分**：风险区间若跨越镜头边界，在详情页展示为「跨 N 个镜头」，允许法务逐镜查看关键帧，但**不自动拆成多个 Risk Case**（避免同一句话被拆成多条风险）。
3. **容差参数显式化**：ASR 与字幕的时间戳都存在 ±0.2~0.5s 量级不确定性。前端播放定位应带前置缓冲（建议起播点回退 0.3~0.5s），后端不做时间戳的"精确相等"判定，一律用区间重叠（IoU 或重叠时长占比）匹配。
4. **单位统一用毫秒整数**：FunASR 官方明确 `sentence_info` 是**毫秒**、Nano 的 `timestamps` 是**秒**，官方警告「Do not relabel SDK milliseconds as service seconds without conversion」。建议全系统内部一律毫秒，进入 API 边界时再转换。
5. **画面语义理解与定位解耦**：关键帧交给 `deepseek-flash` 做画面理解（它不返回坐标，也不需要），**坐标与时间轴由本地 OCR/ASR 提供**，再由后端把「画面描述」与「文字/时间区间」按关键帧 ID 关联。这正是绕开 DeepSeek 两大缺失的正确姿势。

### 3.4 结论建议（视频）

1. **镜头检测用 `AdaptiveDetector` 打底、`ContentDetector` 兜底**：广告片大量快速运镜与转场，官方默认 `ContentDetector(threshold=27)` 在快剪素材上误检偏高，而 `AdaptiveDetector` 的官方定位就是「缓解快速镜头运动」。阈值必须在企业自己的广告素材上标定，不要照搬默认值。
2. **抽帧策略必须"分档"**：普通口播段 1 fps、快剪/字幕变化段 2~4 fps、镜头切换点强制补帧。单一固定频率要么漏字幕、要么算力浪费。
3. **硬字幕 ROI 复用 VSE 的思路而非直接用 VSE**：VSE 是 GUI 工具（Python 3.12+，Apache-2.0），其价值在于流程范式；本项目应把「ROI 检测 → 变化帧筛选 → OCR → 去重成条」实现为服务内的库调用，并把 ROI 结果持久化到物料解析结果中，供前端叠加显示。
4. **所有时间信息统一存毫秒并保留原始 track 类型**：不要把「字幕时间」和「口播时间」合并成一条记录后丢弃来源——法务复核时需要知道这条定位来自"听到的"还是"看到的"。
5. **进度必须真实可算**：视频解析天然可分段（按时长切 chunk），应把「总时长 → 已处理时长」作为进度来源，而不是按步骤数量估算。这直接决定产品能否满足「展示真实进度」的要求。

---

## 4. 文档解析（PDF / PPTX / DOCX）

### 4.1 PDF 文字与坐标提取

| 方案 | 最新版本（来源） | 许可证（来源） | 坐标粒度 | 定位 |
|---|---|---|---|---|
| **PyMuPDF (fitz)** | **1.28.2**（2026-08-06，[PyPI](https://pypi.org/project/pymupdf/)） | ⚠️ **双授权：AGPL-3.0 或 Artifex 商业许可**。[官方 License 页](https://pymupdf.readthedocs.io/en/latest/about.html)：「available under both, open-source AGPL and commercial license agreements… If you determine you cannot meet the requirements of the AGPL, please contact Artifex」。⚠️ **仓库根目录没有 `LICENSE` 文件，许可证全文在 `COPYING`**（[COPYING](https://github.com/pymupdf/PyMuPDF/blob/main/COPYING)） | `words` 给出词级 bbox `(x0,y0,x1,y1,...)`；`rawdict` 给出字符级 `bbox` + `origin`（默认 `dict` 只到 span 级） | 速度与能力最强，但**许可风险最高** |
| **pdfplumber** | **0.11.10**（2026-06-15，[PyPI](https://pypi.org/project/pdfplumber/)） | **MIT**（[LICENSE.txt](https://github.com/jsvine/pdfplumber/blob/stable/LICENSE.txt)） | 是，`chars` 为逐字符对象并提供 `x0`（左边界距页面左侧距离）等坐标属性；`words`/`lines`/`rects`/`images` 同结构（[README 坐标字段表](https://github.com/jsvine/pdfplumber#objects)） | 纯 Python，速度一般但许可干净、**坐标粒度最细** |
| **Apache PDFBox** | **3.0.8**（子代理核实）；Maven Central 检索到 **3.0.7**（2026-03-06）→ **以 3.0.8 为准并复核** | **Apache-2.0**（[LICENSE.txt](https://github.com/apache/pdfbox/blob/trunk/LICENSE.txt)） | 是，`TextPosition` 提供字符级坐标：`getXDirAdj/getYDirAdj/getWidthDirAdj/getHeightDir/getIndividualWidths()`（页面旋转与文本方向两套校正坐标）；可用 `PDFTextStripper.writeString(String, List<TextPosition>)` 覆写以携带坐标。⚠️ 官方 javadoc 提示默认按内容流顺序，**阅读顺序须 `setSortByPosition(true)`** | **Java 原生，主服务可直接内嵌** |
| **pypdf** | **6.19.0**（2026-09-16，[PyPI](https://pypi.org/project/pypdf/)） | **BSD-3-Clause**（PyPI `license_expression`） | 文本提取可用，**是否提供稳定的字符级坐标未核实 → 不确定**（其定位是通用 PDF 操作库） | 轻量文本抽取，不作坐标来源 |
| **pypdfium2** | **5.13.0**（2026-08-13，[PyPI](https://pypi.org/project/pypdfium2/)） | **BSD-3-Clause / Apache-2.0**（依组件） | 可渲染 + 文本提取 | **替代 PyMuPDF 做"渲染页面为图片"的许可干净选项**（扫描件兜底 OCR 需要渲染） |

**PyMuPDF 的 AGPL 对本项目意味着什么（必须让用户拍板）：**

Artifex 授权页原文指出：**不得在未按 AGPL 公开自有完整源码的情况下，将其开源版本作为 server-based application or service 部署**，且约束对象是「**any users interacting with it**」（即通过网络与之交互的用户），而不只是「对外分发」。官方页面对无法满足 AGPL 者的指引是联系 Artifex 获取商业许可。
→ **风险判断：本项目是内网部署、由法务通过浏览器使用的闭源系统，形态上正落在上述「网络交互用户」表述范围内。但「内网自用是否实际触发 AGPL 义务」属于法律解释问题，本报告不下结论，转交法务判断。**
→ **工程结论（在法务放行前即成立）：不把 PyMuPDF 放进默认交付组合。** 渲染改用 pypdfium2（许可干净），坐标提取改用 pdfplumber（Python 侧）或 PDFBox（Java 侧）。**若法务判定必须采购商业许可才可用，那是另一条决策路径。**
> 注意一处极易误读的地方：Artifex 页面上的「No report needed for internal usage」属于**商业订阅**的用量申报说明，**不是 AGPL 的豁免条款**。

### 4.2 扫描版 PDF 的判定与兜底

判定思路（**具体阈值需实测标定，不宜照搬**）：
1. 用 PDFBox / pdfplumber 抽取全文，计算**每页可提取字符数**；
2. 统计页面**图像覆盖率**与是否含文本对象；
3. 经验判定（**推荐权重，非官方标准**）：单页有效字符数 < 某阈值（例如 50~100 字符）**且**页面被整幅图像覆盖 → 判为扫描页；
4. 兜底链路：`pypdfium2 渲染页面 → 提高 DPI（建议 200~300 DPI）→ PaddleOCR（PP-OCRv6_medium）`；
5. **混合型文档必须逐页判定**（一本 PPT 导出的 PDF 常是「文字页 + 图片页」混合），不能整份文件二选一；
6. 解析结果中要显式记录 `page_text_source ∈ {embedded, ocr}`，因为**OCR 得到的文字与原文存在字符级误差**，这个标记会直接影响法务对"风险原文"的信任度。

### 4.3 PPTX / DOCX 结构化解析

| 格式 | 推荐库 | 版本 / 许可 | 能拿到的锚点粒度 |
|---|---|---|---|
| PPTX | **python-pptx** | **1.0.2**（2024-08-07，[PyPI](https://pypi.org/project/python-pptx/)），**MIT**（[LICENSE](https://github.com/scanny/python-pptx/blob/master/LICENSE)） | **slide 索引（天然"页码"）** + 形状级坐标（`shape.left/top/width/height`，官方原文为 **English Metric Units (EMU)**）+ 文本框段落与 run + 图片形状 → **可精确到"第 N 页 + 某个图形的某个文本框"**。⚠️ 该库近两年无新版发布 |
| PPTX | **Apache POI XSLF** | **5.5.1**（2025-11-26，[Maven Central](https://search.maven.org/artifact/org.apache.poi/poi-ooxml)），**Apache-2.0** | 同上，Java 原生。⚠️ **两个必须注意的坑**：① **`getAnchor()` 的官方 javadoc 原文是「All coordinates are expressed in points (72 dpi)」——不是 EMU！** 与 python-pptx 的 EMU **跨栈混用必须换算**；② **POI 官方自己给 XSLF/XWPF 打了保留意见**——官网组件页原文「Please note that **XSLF is still in early development and is a subject to incompatible changes in future**」，`XWPFDocument` 类注释自述「as it's **not a mature and stable API** yet」，`XSLFShape` 另有 `@Beta` 注解 → **必须锁版本并预留回退方案** |
| DOCX | **python-docx** | **1.2.0**（2025-06-16，[PyPI](https://pypi.org/project/python-docx/)），**MIT**（[LICENSE](https://github.com/python-openxml/python-docx/blob/master/LICENSE)） | 段落索引（官方标注按 document order）、run 级文本、表格、内嵌图片；**无页面概念**（依据见 4.4） |
| DOCX | **Apache POI XWPF** | 5.5.1，**Apache-2.0** | 段落索引、run、表格，Java 原生；⚠️ 同样带官方「not a mature and stable API yet」保留意见 |
| DOCX | **docx4j** | Maven 上为 **6.1.2**（2019-02-27），POM 声明 Apache 2（**另有 11.5.3 的检索结果，口径不一致 → 不确定**）；⚠️ **官网不可达，无法确认是否停更** | Java 备选，不建议作为主选 |
| 通用 | **Apache Tika** | Maven 上最新为 **4.0.0-alpha-1**（alpha）；参考稳定档 3.3.1 / Python `tika` 3.3.2 → **生产稳定版本不确定** | 适合做格式嗅探与统一文本抽取，**不适合做坐标/锚点** |

### 4.4 核心问题：如何保留「页码 / 段落 / 句子」级锚点

**先明确一个关键事实（这是最容易做错的地方）：DOCX 格式本身不存储分页信息。**

权威依据有两处，都可直接引用：
1. **ISO/IEC 29500-1 对 `w:lastRenderedPageBreak` 的定义**（经 Microsoft Learn 转述）：「This element specifies that this position delimited the end of a page **when this document was last saved by an application which paginates its content**.」→ DOCX 里没有固化的分页，只有"**上次由一个会分页的应用保存时**"留下的缓存。
2. **python-docx 官方文档**给出更易引用的同义表述：「The position of these can change depending on the printer and page-size, as well as margins, etc. They also will change in response to edits, but **not until Word loads and saves the document**.」并明确「Note these are **never inserted by python-docx because it has no rendering function**」；官方 API 目录中**不存在**任何 document 级 page / page count / page number。

→ **结论：不能承诺"DOCX 跳到第 N 页"**，除非先把 DOCX 转成 PDF（引入 LibreOffice headless 或商业转换组件，会显著增加容器体积与中文字体依赖；⚠️ **关于该镜像体量与中文字体问题的官方依据，本次未找到——官方 wiki 页面 404，只能作为社区经验陈述**）。

**推荐的锚点模型（按格式分档，产品文案必须与此一致）：**

| 格式 | 可承诺的锚点 | 是否可"跳回原文位置" | 实现方式 |
|---|---|---|---|
| **PDF（含文字层）** | **页码 + 段落 + 句子 + 坐标框** | ✅ 可以高亮到页面上精确矩形 | pdfplumber/PDFBox 取字符 bbox → 按行聚类成句 → 前端按 page + bbox 叠加高亮 |
| **PDF（扫描件）** | 页码 + OCR 行框坐标 | ✅ 可高亮，但文字本身是 OCR 结果（需标注） | 渲染 → PaddleOCR 行框 + `rec_polys` |
| **PPTX** | **slide 序号 + 形状（shape）ID + 文本框内段落/run 索引 + 形状坐标** | ✅ 可以精确定位到「第 N 页某个文本框」，甚至在该文本框上叠加高亮框 | python-pptx / POI XSLF：`slides[i]` → `shapes[j]` → `text_frame.paragraphs[k]` |
| **DOCX** | **段落索引 + run 索引（+ 表格行列）**；**页码不可承诺** | ⚠️ 可跳转到段落（需前端渲染 docx 或后端生成定位片段），**无法保证"第 N 页"** | python-docx / POI XWPF；若必须页码，需先转 PDF 并接受版式损失 |
| **纯文本 / 宣传语** | 字符偏移区间 `[start, end]` | ✅ 直接高亮 | 最简单，最精确 |
| **图片 / 海报** | **像素坐标 bbox（相对原图宽高归一化）** | ✅ 框选 | PaddleOCR `rec_boxes` / `rec_polys` |

**落地建议：**
1. 统一锚点 Schema：`{material_id, version_id, anchor_type, page_or_slide, para_index, run_index, char_start, char_end, bbox_normalized, text_source}`——`bbox` 一律**归一化到 0~1**，这样前端在不同缩放/分辨率下都能正确叠加，也不会因换渲染库而失效。
2. **前端渲染策略决定锚点可用性**：PDF 用 pdf.js（支持按坐标高亮）；PPTX/DOCX 若要求像素级高亮，最稳的做法是**服务端预渲染为图片/PDF 缩略图**（LibreOffice headless 转 PDF → pypdfium2 渲染），锚点同时保留「结构锚点（段落/形状）」与「视觉锚点（渲染页 + bbox）」，二者互为兜底。
3. **DOCX 的页码问题必须在需求评审阶段就与法务对齐**：产品文案写「定位到第 3 页」而实际只能定位到段落，会在验收时被判为缺陷。建议统一表述为「定位到第 N 段/第 N 节」。
4. **解析结果必须标记来源**：`text_source ∈ {embedded, ocr}` 与 `confidence`，因为法务要区分「原文就是这么写的」和「机器认出来的」。

### 4.5 结论建议（文档）

1. **文档主链路放在 Java 侧**：PDFBox 3.0.8 + POI 5.5.1 都是 Apache-2.0、都是 Java 原生，能覆盖「PDF 文字+坐标」「PPTX 形状+段落+坐标」「DOCX 段落+run」三大需求，**无需为纯文本类文档引入跨进程调用**，延迟与故障面都更小。
2. **Python 侧只做 Java 做不了/做得差的事**：复杂版面还原与表格结构（PP-StructureV3）、扫描件 OCR、以及用 pdfplumber 做**交叉校验**（双引擎抽取结果不一致时标记为低置信，正好符合产品「低置信高风险进人工判断」的原则）。
3. **渲染必须走许可干净的方案**：用 `pypdfium2`（BSD/Apache）替代 PyMuPDF 渲染，避免 AGPL；只有确认采购 Artifex 商业许可后，才把 PyMuPDF 纳入。
4. **不要把 Aspose 之类商业库当作默认方案**（价格与授权模式需商务谈判，**本次未核实 → 不确定**）；Tika 只用于格式嗅探。
5. **锚点粒度要按格式"如实承诺"**：PDF/PPTX 可做到视觉像素级；DOCX 只能到段落级。这条应写进产品说明与验收标准，而不是留给实现阶段解释。

---

## 5. 集成方式建议（Java 主服务 ↔ Python 解析能力）

### 5.1 候选方案对比

| 方案 | 成熟度 / 关键事实 | 优点 | 缺点 | 适配度 |
|---|---|---|---|---|
| **A. Python 解析微服务 + HTTP（FastAPI）** | FastAPI **0.141.1**（2026-07-29，[PyPI](https://pypi.org/project/fastapi/)），**MIT**（[LICENSE](https://github.com/fastapi/fastapi/blob/master/LICENSE)）；依赖 Pydantic（**MIT**）；Spring Boot 3 侧用 `RestClient`/`WebClient` | 生态最主流、调试最简单、可独立扩缩容与独立装模型 | 需要自己设计任务状态与重试（HTTP 本身无重试语义） | ⭐⭐⭐⭐⭐ **推荐起点** |
| **B. gRPC** | grpc-java **1.73.0**（2025-05-27）、protobuf-java **4.35.0**（2026-05-19）；Python 侧 `grpcio` / `grpcio-tools` **1.84.0**（Apache-2.0）。⚠️ Spring 集成需谨慎选型：`net.devh:grpc-spring-boot-starter` 最新仍为 **3.1.0.RELEASE（2024-04-14）**、更新节奏停滞；`org.xolstice:protobuf-maven-plugin` **仓库已 archived、Maven Central 自 2018-10 无新版**，Spring Boot 官方已改用 `io.github.ascopes:protobuf-maven-plugin`，另有 `spring-projects/spring-grpc`（Apache-2.0，v1.1.1） | 强类型契约、二进制高效、天然支持流式进度 | 需要维护 `.proto`、跨语言调试成本高、Python 侧要额外工程投入；⚠️ 且 **grpc.io 官网的 Python 页与语言列表页均无 GA/支持级别背书**，只能引 PyPI classifier（仍为 `Development Status :: 4 - Beta`） | ⭐⭐⭐ 建议在「进度流式推送」成为瓶颈时再引入 |
| **C. 消息队列异步（RabbitMQ / Kafka / Redis Stream）+ Python worker** | Celery **5.6.3**（2026-03-26，[PyPI](https://pypi.org/project/celery/)），**BSD-3-Clause**；项目已有 Redis | 天然解耦、天然重试、天然削峰、可水平扩容 worker | 需自建死信队列与进度回传通道；进度展示要么回调 Spring 要么写 Redis 供查询 | ⭐⭐⭐⭐ **推荐作为解析编排层** |
| **D. 纯 Java 原生替代** | 可选项：**Tess4J**（真实仓库为 `nguyenq/tess4j`，Apache-2.0，**JNA 包装**而非 JNI；版本号存在 5.16.0 / 5.20.0 两个口径 → 不确定）、**ONNX Runtime Java**（官方文档存在，Java 8+，Maven 上为 1.22.0 或 1.30.0 → 版本不确定）、**`whisper-jni`**（Apache-2.0，JNI 包装 whisper.cpp，最近 push 2025-04-26 → 可用性未验证） | 零跨语言、部署最简单 | ①Tess4J 底层是 Tesseract，**其官方未给出中文准确率**，且中文语言数据 2.5 年未更新；②ONNX Runtime Java 能跑模型，但**官方只有 MNIST 示例、无 OCR/ASR 示例**，且**缺少 PaddleOCR/FunASR 的前后处理流水线、字典、VAD、标点等全部配套工程**，自建成本极高；③PP-StructureV3 版面分析与 FunASR 说话人链路在 Java 侧无对等实现 | ⭐ 不建议作为主方案，仅可用于「纯数字/英文小图」的极简场景 |

> **PS：** PaddleOCR 官方确实提供 **C++ 本地部署**（3.2.0 起覆盖 Linux/Windows，与 Python 精度一致）以及**服务化部署（HTTP 可被任意语言调用）**，这两条路径都可以让 Java 侧"不直接依赖 Python 进程"，但仍属"独立服务"而非"Java 原生库"。参考：[高性能推理/本地部署文档](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/local_inference/high_performance_inference.md)、[服务化部署](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/serving/serving.md)。

### 5.2 针对「必须异步 / 真实进度 / 可重试」的具体设计建议

这套要求决定架构形态，逐条对应：

| 产品要求 | 推荐实现 | 关键设计点 |
|---|---|---|
| **必须异步** | Spring Boot 3 接收上传 → 落 `parse_job` 表（含 `idempotency_key`）→ 投递到 Redis 队列 → Python worker 消费 | 上传接口**只做落盘 + 建任务**，绝不在请求线程内解析；接口立即返回 `job_id` |
| **展示真实进度** | Python worker 按「可计量的原子单位」上报：视频按**已处理毫秒/总毫秒**、PDF 按**已处理页/总页**、图片 OCR 按**已处理区域/总区域** | 进度写入 Redis（`job:{id}:progress`）并可由 Spring 通过 SSE 推给前端；**禁止用"第几步/共几步"伪造百分比** |
| **可重试** | 任务表状态机：`PENDING → RUNNING → SUCCEEDED / FAILED / RETRYING`，`attempt_count` 上限 + 指数退避 | ①解析必须**幂等**：以「文件哈希 + 解析器版本 + 参数指纹」为 `idempotency_key`，重试直接复用已完成的分片结果；②分片级重试（只重跑失败页/失败片段），而不是整文件重来；③`FAILED` 必须记录**失败材料 + 失败原因**，供前端展示重试入口（对齐 AGENTS.md 第 14 节） |
| **可观测** | 每个 job 记录：解析器版本、模型版本、耗时、失败片段、置信度分布 | 这也是后续「解析质量回归测试」的数据基础 |

**推荐落地形态（分两步走）：**

- **第一步（主链路打通）**：一个 Python 服务（FastAPI）承载 **OCR / ASR / 视频抽帧 / 复杂版面**四类能力，对外只有 3~4 个异步任务接口 + 1 个进度查询接口；Spring 通过 Redis 队列投递、通过 HTTP 查询结果与进度。文档类简单解析留在 Java。
- **第二步（按需演进）**：当 OCR/ASR 的算力需求差异变大（例如 ASR 需要长时任务、OCR 需要高并发短任务）时，再**按能力拆成独立 worker 池**，共用同一套任务表与进度协议——不要一开始就拆成多个服务。

### 5.3 结论建议（集成）

1. **推荐：独立 Python 解析微服务 + 消息队列异步编排 + HTTP 回传结果/进度**，而非 gRPC 起步、更不是纯 Java 替代。理由是解析任务天然是「长耗时、可分段、需重试」的批处理，队列 + 任务表的组合天生匹配这些语义，而 gRPC 的价值（低延迟流式）在分钟级解析任务上收益很小、维护成本却实打实。
2. **进度协议要在一开始就定义成"可计量单位"**，这是本项目最容易埋雷的地方：一旦按步骤数上报，后面换成真实进度会导致前端进度条语义变化，属于返工。
3. **幂等键必须是「内容 + 版本 + 参数」的指纹**，不能只用文件哈希——否则升级 OCR 模型后无法触发重解析，也无法区分「同一文件不同解析参数」的两个任务。
4. **模型权重必须在构建期打进镜像**：内网无外网出口意味着运行期不能下载 PaddleOCR/FunASR/WhisperX 的权重。所有模型（含 WhisperX 的 wav2vec2 中文对齐模型、FunASR 的 VAD/标点/CAM++ 权重）都应在 CI 阶段下载并烘入镜像，并在启动时以 `disable_update=True` / `local_files_only=True` 之类的离线开关运行。FunASR 官方也明确要求「为离线运行准备并验证完整的本地快照」。
5. **Java 与 Python 之间只传「结构化锚点数据」，不传文件字节**：文件走 MinIO 共享（Java 生成预签名 URL 或直接把 MinIO 路径给 Python），避免大文件过 HTTP body，也便于分片重试。

---

## 6. 推荐组合

| 能力 | 推荐方案 | 版本 | 许可证 | CPU/GPU | 不选其他方案的核心理由 |
|---|---|---|---|---|---|
| 中文 OCR（坐标） | **PaddleOCR**（PP-OCRv6_medium 服务端档） | **3.7.0** | 代码与 PP-OCRv5/v6 权重模型卡均 **Apache-2.0**；⚠️ 3.x 可选依赖含 **AGPL-3.0** 库（PyMuPDF/pdf2docx），**必须裁剪依赖** | **CPU 完全够用**（官方端到端基准：**PP-OCRv6_medium 1.40 s/图**，Xeon 8350C + OpenVINO；tiny 0.20 s/图；CPU 峰值内存 2~4GB）；GPU 可选 | 唯一同时具备「四点坐标 + 单字坐标（`return_word_box=True`）+ 20 类版面分析 + Apache-2.0」的开源方案；Tesseract 中文语言数据 2.5 年未更新、EasyOCR 维护停滞且 API 默认 `gpu=True`、RapidOCR 权重许可链不完整（保留为降级通道）、CnOCR 仅行级坐标 |
| 海报/文档版面分析 | **PaddleOCR PP-StructureV3** | 随 PaddleOCR 3.7.0 | Apache-2.0 | **CPU 可**（官方 FAQ 明确支持 CPU；轻量配置约 **3.74 s/图**） | 与 OCR 同仓库同坐标体系，省掉一套坐标换算；**20 类版面标签** + 表格单元格坐标，直接支持「按区块框选」 |
| 中文 ASR（时间戳） | **FunASR 工具链 + Paraformer-zh**（须显式配 FSMN-VAD + ct-punc；CAM++ 可选） | **funasr 1.4.16** | 工具链 **MIT**；Paraformer 权重在 ModelScope/HF 标 **Apache-2.0**；⚠️ 仓库另有《模型开源协议 v1.1》（须署名）→ **双文本并存，需法务确认** | **CPU 完全够用**（官方 ONNX int8 基准：模型仅 237MB、**CER 不变 1.95%**、Xeon 8369B 单并发 **RTF 0.028（≈35×实时）**，老 CPU 无 avx512_vnni 亦 ≈13×）；GPU 可选 | 官方明确「要字符级时间戳就用 Paraformer」；「VAD + 识别」架构从机制上规避 Whisper 在纯 BGM 段的幻觉（ICASSP 2025 论文 + 官方 Discussion #2645 已证实该幻觉）；Whisper 系官方对 BGM 场景**零说明**且 `word_timestamps` 自标 experimental |
| 镜头切分 / 关键帧 | **PySceneDetect**（+ ffmpeg） | **0.7.1** | **BSD-3-Clause** | CPU 可 | 官方默认参数开箱可用，且 `AdaptiveDetector` 专门解决快剪误检；官方提供含 ffmpeg 的 Docker 镜像 |
| 硬字幕 OCR | 自建流水线（ROI 检测 → 变化帧筛选 → PaddleOCR → 去重成条），范式参考 **video-subtitle-extractor** | 取范式，不直接用（VSE 是 GUI 工具） | Apache-2.0 | CPU 可（成本靠抽帧策略控制） | 没有可嵌入内网服务的现成"字幕提取库"；VSE 的 GUI 形态不符合服务化要求 |
| PDF 文字+坐标 | **Apache PDFBox**（Java 原生）；**pdfplumber**（MIT）作 Python 侧交叉校验 | PDFBox **3.0.8**（复核）；pdfplumber **0.11.10** | **Apache-2.0** / **MIT** | CPU | **规避 PyMuPDF 的 AGPL-3.0**：Artifex 原文禁止「作为 server-based application 部署而不按 AGPL 公开自有源码」，约束对象是「**any users interacting with it**」，与本项目形态冲突；⚠️ 内网自用是否触发义务**属法律解释，须法务拍板** |
| PDF 渲染（扫描件兜底） | **pypdfium2** | **5.13.0** | **BSD-3-Clause / Apache-2.0** | CPU | 替代 PyMuPDF 做渲染，许可干净（PyMuPDF 连 `LICENSE` 文件都没有，全文在 `COPYING`） |
| PPTX 解析 | **Apache POI XSLF**（Java 原生）；**python-pptx** 作 Python 侧补充 | POI **5.5.1**；python-pptx **1.0.2** | **Apache-2.0** / **MIT** | CPU | PPTX 的 slide 序号天然是"页码"，形状坐标齐全。⚠️ 两个坑：**POI `getAnchor()` 单位是 points(72dpi)，python-pptx 是 EMU，跨栈必须换算**；**POI 官方自述 XSLF「still in early development… subject to incompatible changes」、XWPF「not a mature and stable API yet」**——需锁版本并留回退方案 |
| DOCX 解析 | **Apache POI XWPF**（Java 原生）；**python-docx** 作 Python 侧补充 | POI **5.5.1**；python-docx **1.2.0** | **Apache-2.0** / **MIT** | CPU | 同上；**必须接受「DOCX 无真实页码」这一格式事实**（依据见 4.4） |
| 集成方式 | **Python 解析微服务（FastAPI）+ Redis 队列异步 + HTTP 结果/进度查询**；Java 内嵌 PDFBox/POI 处理简单文档 | FastAPI **0.141.1**；Celery **5.6.3**（如需） | **FastAPI MIT**（依赖 Pydantic 亦 MIT）；**Celery BSD-3-Clause** | — | 比 gRPC 起步维护成本低（`net.devh:grpc-spring-boot-starter` 停更于 2024-04，`org.xolstice:protobuf-maven-plugin` 已归档）；比纯 Java 替代可行（Java 侧无 PP-StructureV3/FunASR 对等实现）；比"Java 直接调 Python 库"稳定 |

**统一部署要求（无论选哪套）：**
- 全部模型权重在**构建期**烘入镜像（PaddleOCR 的 PP-OCRv6/PP-StructureV3 权重、FunASR 的 Paraformer/FSMN-VAD/ct-punc/CAM++ 权重、WhisperX 如启用则含 wav2vec2 中文对齐模型），运行期禁止联网下载。
- 官方镜像现状需特别注意可达性：**Docker Hub 上没有 PaddleOCR 官方镜像**（`paddlepaddle/paddleocr` API 返回 404），官方镜像在**百度云 CCR** `ccr-2vdh3abv-pub.cnc.bj.baidubce.com/paddlepaddle/paddle:3.0.0`（CPU 版）；**FunASR 官方镜像在阿里云 registry** `registry.cn-hangzhou.aliyuncs.com/funasr_repo/funasr`。⚠️ **这两个 registry 在内网环境很可能都不可达**，必须在可联网环境 `docker pull` 后 `docker save/load` 导入，并把这个步骤写进交付手册。**上线前请先验证这两个域名在企业网络中的可达性。**
- 建议**首期只配 CPU**：三套核心组件（OCR / ASR / 镜头检测）官方均给出 CPU 可用性声明与官方 CPU 基准数据；GPU 属"提速选项"而非"前置条件"。若后期并发上来，优先升级 CPU 核数、启用 OpenVINO / ONNX int8 推理，再考虑 GPU。
- ⚠️ **注意 Redis 许可证变更**：**Redis 8 起为 RSALv2 / SSPLv1 / AGPLv3 三许可，7.2 及更早仍为 BSD-3**。若项目用的是 Redis 8+ 且对外交付，需纳入合规评审。

---

## 7. 不确定项 / 需要用户拍板项

### 7.1 必须由用户/法务拍板的决策

| # | 事项 | 为什么必须拍板 |
|---|---|---|
| 1 | **PyMuPDF 的 AGPL 在内网自用场景下是否触发义务** | Artifex 原文禁止「作为 server-based application or service 部署而不按 AGPL 公开自有源码」，约束对象是「any users interacting with it」。本项目正是这种形态。**内网自用是否构成"网络交互"属法律解释，本报告不下结论。** 工程侧已按"不采用"给出替代方案；若法务判定需商业许可，是另一条路径。⚠️ 注意 Artifex 页面上「No report needed for internal usage」是**商业订阅**说明，不是 AGPL 豁免。 |
| 2 | **PaddleOCR 依赖树中的 AGPL-3.0 组件如何处理** | PaddleOCR 3.x 可选依赖含 PyMuPDF/pdf2docx（AGPL-3.0）。**必须决定是"裁剪依赖"还是"接受并采购商业许可"**，并决定由谁在 CI 中做依赖许可证白名单扫描。 |
| 3 | **FunASR 模型权重的许可口径以哪份文本为准** | 仓库级《模型开源协议 v1.1》要求署名+保留模型名，而 Paraformer 在 ModelScope/HF 模型卡标 Apache-2.0；**SenseVoiceSmall 两处不一致**。**建议直接向模型方书面确认**，不要自行择一。 |
| 4 | **是否允许任何云 OCR/ASR 参与生产** | **架构上基本不可行**：三家公有云 AI API **均要求输入公网可达**（腾讯 ASR `Url`「需要公网环境浏览器可下载」；百度转写需「云端可外网访问的 url 链接」；阿里云 FAQ 提示图片需「公网正常访问」），且**三家 AI 能力文档中均未查到 VPC 内网接入或专线直连的官方说明**。若完全禁止，本文所有云方案只做效果对标；若要走云，只能采购**私有化/离线版本**——三家中有完整公开路径的只有**百度**（OCR 离线 SDK 公开价 299 元/设备起 + OCR 私有化内网 API + 语音私有化「数据存储及处理均在企业内网进行」），腾讯 ASR「需商务对接」、阿里云未见独立私有化文档。**另需确认「输入内容不用于模型训练」的书面条款——三家均未查到专门条款。** |
| 5 | **DOCX 能否接受"只到段落级、不承诺页码"** | 这是 DOCX 格式的固有限制（依据见 4.4）。若产品需求书写了"跳转到第 N 页"，需先改需求或增加 DOCX→PDF 转换链路（会引入 LibreOffice headless 与中文字体依赖）。 |
| 6 | **Redis 版本与许可** | Redis 8+ 为 RSALv2/SSPLv1/AGPLv3 三许可，7.2 及更早为 BSD-3。若当前规划使用 Redis 8+，需纳入合规评审。 |
| 7 | **是否需要 GPU** | 首期建议 CPU-only 上线（官方 CPU 基准支持这一判断）；若单次任务达上百个视频，需提前规划 GPU 与显存。 |
| 8 | **是否需要说话人分离** | 若宣传视频均为单人口播，CAM++ 链路可整体省略，显著降低复杂度。 |
| 9 | **可接受的解析精度/耗时基线** | 需要用户给出「单张海报 OCR ≤ N 秒」「10 分钟视频 ASR ≤ M 分钟」这类可验收指标；否则无法做容量规划与模型档位选择（medium/small/tiny）。 |

### 7.2 本次未能从官方来源确认的事项（标注「不确定」）

> 下列为**本次调研结束后仍然开放**的项。已在调研中被官方数据证伪或补全的早期疑点（如 PP-OCRv6 的 CPU 耗时、faster-whisper 的加速倍数、云厂商 OCR/ASR 单价与字级时间戳支持度、Tesseract 版本号）已从清单中移除，对应事实见正文各节。

| # | 事项 | 状态 |
|---|---|---|
| 1 | **「中文广告口播 + 背景音乐」的量化鲁棒性数据** | ⚠️ **不存在任何来源给出该场景的 CER / 幻觉率数字**。ICASSP 2025 论文研究的是通用非语音音频诱发 Whisper 幻觉，不等于该场景。**上线前必须用企业自有素材实测**——这是本文最重要的空白项 |
| 2 | **PaddleOCR `return_word_box=True` 时的切分粒度** | 官方字段表未定义中文字/英文词的具体切分规则，需读源码确认 → **不确定** |
| 3 | **PP-StructureV3 专用 CPU 镜像名** | 官方未列出 → **不确定** |
| 4 | **PaddleOCR 官方 CPU 基准的硬件代表性** | 官方数据只覆盖 **Intel Xeon 服务器 CPU**（6271C / 8350C / 8369B），非服务器 CPU、ARM、国产化平台无数据 → **不确定，需在目标机型实测** |
| 5 | **PP-OCR 全系列是否有统一模型许可页** | 只有逐模型卡标注，无统一页 → **不确定** |
| 6 | **PP-OCR 通用识别的独立第三方权威评测** | **未找到**。官方指标为 in-house 评测集；社区/博客级横评不能作验收依据 |
| 7 | **PaddleOCR 2.x 是否仍在维护** | 最后 Release 为 v2.10.0（2025-03-07）→ **不确定**（3.x 为当前主线 3.7.0） |
| 8 | **RapidOCR / CnOCR / EasyOCR 的权重许可与自身中文准确率** | 三者官方均未声明权重许可、均无中文准确率数据；RapidOCR 的 `MODEL_LICENSES.md` 返回 404 → **不确定** |
| 9 | **EasyOCR 发版计划** | 最新版 2024-09-24，2025-12-05 后仅 README 改动 → **不确定** |
| 10 | **Tesseract `chi_sim` 的中文准确率** | 官方 Benchmarks 只测速度，无准确率 → **不确定**；语言数据最近提交 2024-03-07 |
| 11 | **SenseVoiceSmall 许可证** | ModelScope 标 Apache-2.0，HF 标 `other/model-license` → **两处不一致，不确定** |
| 12 | **FunASR 模型许可证双文本冲突的官方消解说明** | **不存在** → **不确定** |
| 13 | **云厂商「字级时间戳 + 说话人分离 + 字幕分段」的组合能力** | 无官方交叉验证 → **不确定** |
| 13b | **阿里云 ASR 字级时间戳的确切字段名** | 上一轮核实给出 `Words[].Word/BeginTime/EndTime`，但**本次复核未在官方正文定位到该字段** → **待确认** |
| 13c | **腾讯云 ASR `SentenceDetail` 内部字段清单** | 官方文档页未提取到正文 → **不确定** |
| 13d | **百度 ASR `words_info` 的启用条件** | 参数表标注「预留参数，暂不启用」，启用条件需向官方确认 → **不确定**；极速版 `enable_subtitle=2` 处于**邀测阶段且价格未公开** |
| 13e | **阿里云 ASR 是否提供私有化部署** | **未找到官方产品页** → **不确定，需商务咨询** |
| 13f | **三家云厂商「输入内容不用于模型训练」的书面条款** | **均未查到**专门条款（仅查到阿里 OCR「不落盘」、腾讯 ASR「仅供当次识别使用，不会进行保存」）→ **需商务/法务确认** |
| 13g | **三家 AI API 的 VPC 内网接入 / 专线直连能力** | **均未查到官方说明**（官网仅有通用专线/私有网络产品）→ **不确定，需商务确认** |
| 14 | **腾讯云后付费阶梯的逐档单价** | 官方页未列具体单价 → **不确定** |
| 15 | **腾讯云 OCR 活动价（0.011 元/次）与刊例价（0.50 元/次）的差异原因** | 官方未说明适用范围，**相差一个数量级** → **不确定**；成本口径建议采用刊例价 |
| 16 | **百度私有化部署规格与价格** | 官方仅说明"付费项目、需申请"，未公布价格 → **不确定，需商务咨询** |
| 17 | **阿里云 ASR 是否提供私有化部署** | 本次未找到官方产品页 → **不确定，需商务咨询** |
| 18 | **三家云厂商价格与规格的有效期** | 以上价格均代表 **2026-09-20** 抓取时点，且百度部分价格取自官方 PDF（HTML 站为 JS 渲染）→ **可能滞后，签约前须复核** |
| 19 | **ONNX Runtime 的许可证** | Python PyPI 元数据为 `MIT License`，但另有核实指出**官方 LICENSE 页正文抓取失败** → **以 MIT 记录但未双重确认，建议复核** |
| 20 | **ONNX Runtime Java 的版本号与 Java 侧跑通 OCR/ASR 前后处理的工作量** | 版本号存在 1.22.0 / 1.30.0 两个来源（**不确定**）；官方只有 MNIST 示例，**无 OCR/ASR 示例**，工程量未评估 |
| 21 | **Tess4J 的仓库归属与版本** | 真实仓库为 `nguyenq/tess4j`（**`tesseract-ocr/tess4j` 不存在**），Apache-2.0，JNA 包装；版本号存在 5.16.0（Maven 检索）与 5.20.0（子代理核实）两个口径 → **不确定，需复核** |
| 22 | **`whisper-jni` 的可用性** | 仓库存在（Apache-2.0，JNI 包装 whisper.cpp），但最近 push 为 2025-04-26 → **可用性未验证** |
| 23 | **docx4j 的许可证与维护状态** | 官网不可达；Maven 上为 6.1.2（2019-02-27），POM 声明 Apache 2 → **不确定** |
| 24 | **LibreOffice headless 镜像体量与中文字体缺失问题** | 官方 wiki 页面 404，**无官方依据** → 若方案涉及 DOCX→PDF，相关论断只能标为社区经验 |
| 25 | **Apache Tika 的稳定版本号** | Maven 上最新为 4.0.0-alpha-1（alpha）；参考到 3.3.1 / Python `tika` 3.3.2 → **生产稳定版不确定** |
| 26 | **gRPC Python 侧的官方成熟度背书** | grpc.io 的 Python 页与语言列表页**均无 GA/支持级别字样**，只能引 PyPI classifier（`Development Status :: 4 - Beta`）→ **不确定** |
| 27 | **`org.xolstice:protobuf-maven-plugin` 的替代方案成熟度** | 该插件仓库已 archived、2018 年后无新版；Spring Boot 官方改用 `io.github.ascopes:protobuf-maven-plugin`；另有 `spring-projects/spring-grpc`（Apache-2.0，v1.1.1）→ **若走 gRPC 需重新评估** |
| 28 | **商业 OCR（ABBYY 等）的报价与中文效果** | 本次未调研 → **不确定** |
| 29 | **Aspose 等商业文档库的授权条款与价格** | 仅核实其评估版限制（Words 加 watermark + 限"a few hundred paragraphs"；Slides 加 watermark + **提取文本限 1 张幻灯片**），**正式授权需商务谈判 → 不确定** |

---

## 8. 参考链接汇总（官方来源）

**OCR**
- PaddleOCR 仓库与 LICENSE：https://github.com/PaddlePaddle/PaddleOCR
- PaddleOCR PyPI（3.7.0）：https://pypi.org/project/paddleocr/
- PaddleOCR v3.7.0 Release：https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.7.0
- PaddleOCR 官方文档站（通用 OCR / PP-StructureV3 / 服务化 / 高性能推理）：https://www.paddleocr.ai/latest/version3.x/pipeline_usage/OCR.html
- PaddleOCR PP-OCRv5 算法页（CPU 耗时基准）：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html
- PaddleOCR PP-OCRv6 算法页（CPU 耗时基准）：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html
- PaddleOCR 3.x 安装文档（依赖分组）：https://www.paddleocr.ai/latest/version3.x/paddlepaddle_installation.html
- PaddleOCR AGPL 依赖讨论：https://github.com/PaddlePaddle/PaddleOCR/discussions/16886
- PP-OCRv6 技术论文：https://arxiv.org/abs/2606.13108
- RapidOCR：https://github.com/RapidAI/RapidOCR ｜ https://pypi.org/project/rapidocr/
- CnOCR：https://pypi.org/project/cnocr/
- EasyOCR：https://pypi.org/project/easyocr/
- Tesseract：https://github.com/tesseract-ocr/tesseract ｜ tessdata：https://github.com/tesseract-ocr/tessdata ｜ pytesseract：https://pypi.org/project/pytesseract/
- 阿里云 OCR 计费：https://help.aliyun.com/zh/ocr/product-overview/product-billing/ ｜ 按量付费：https://help.aliyun.com/zh/ocr/product-overview/pay-as-you-go ｜ RecognizeGeneral：https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizegeneral ｜ 产品简介（私有化表述）：https://help.aliyun.com/zh/ocr/product-overview/product-introduction/
- 腾讯云 OCR 计费：https://cloud.tencent.com/document/product/866/17619 ｜ GeneralBasicOCR：https://cloud.tencent.com/document/api/866/33526 ｜ 错误码计费说明：https://cloud.tencent.com/document/product/866/45470
- 百度 OCR 免费额度：https://cloud.baidu.com/doc/OCR/s/fk3h7xu7h ｜ 价格：https://cloud.baidu.com/doc/OCR/s/tlrzzplc1 ｜ 离线 SDK 价格：https://cloud.baidu.com/doc/OCR/s/ykia5niiu ｜ 私有化部署：https://cloud.baidu.com/doc/OCR/s/1kuqeya49

**ASR**
- FunASR 仓库与 LICENSE（MIT）：https://github.com/modelscope/FunASR
- FunASR PyPI（1.4.16）：https://pypi.org/project/funasr/ ｜ Releases（含 v1.4.16）：https://github.com/modelscope/FunASR/releases
- FunASR Model Open Source License Agreement：https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE
- FunASR 模型选型（字符级时间戳指引）：https://github.com/modelscope/FunASR/blob/main/docs/model_selection.md
- FunASR Python API 契约（timestamp/sentence_info 字段与单位）：https://github.com/modelscope/FunASR/blob/main/docs/python_api.md
- FunASR 部署矩阵（Docker Compose / runtime / ONNX / GGUF）：https://github.com/modelscope/FunASR/blob/main/docs/deployment_matrix.md
- FunASR 官方 Docker 镜像与 tag：https://www.funasr.com/en/docs/docker.html
- FunASR 离线 SDK 高级指南：https://github.com/modelscope/FunASR/blob/main/runtime/docs/SDK_advanced_guide_offline_zh.md
- FunASR ONNX/C++ 官方 CPU 基准（RTF / int8）：https://github.com/modelscope/FunASR/blob/main/runtime/docs/benchmark_onnx_cpp.md
- FunASR ONNX 输出格式（jsonl 含 timestamp）：https://www.funasr.com/en/docs/onnx-output.html
- ct-punc 模型卡（Apache-2.0）：https://huggingface.co/funasr/ct-punc ｜ Paraformer-zh 模型卡：https://www.modelscope.cn/models/iic/speech_paraformer-large-vad-punc_asr_nat-zh-cn-16k-common-vocab8404-pytorch
- Whisper 中文幻觉：ICASSP 2025 论文 https://arxiv.org/abs/2501.11378 ｜ Discussion https://github.com/openai/whisper/discussions/2645 ｜ https://github.com/openai/whisper/discussions/2685
- openai/whisper：https://github.com/openai/whisper ｜ Releases：https://github.com/openai/whisper/releases/tag/v20250625
- faster-whisper（含官方 CPU benchmark 表）：https://github.com/SYSTRAN/faster-whisper ｜ https://pypi.org/project/faster-whisper/
- WhisperX：https://github.com/m-bain/whisperX ｜ 中文对齐模型映射：https://github.com/m-bain/whisperX/blob/main/whisperx/alignment.py
- 阿里云 ASR 计费：https://help.aliyun.com/zh/isi/product-overview/billing-10 ｜ 开发参考：https://help.aliyun.com/zh/isi/developer-reference/speech-recognition/
- 腾讯云 ASR 计费：https://cloud.tencent.com/document/product/1093/35686 ｜ 录音文件识别 API：https://cloud.tencent.com/document/api/1093/37824 ｜ FAQ（私有化）：https://cloud.tencent.com/document/product/1093/35802
- 百度音频文件转写：https://cloud.baidu.com/doc/SPEECH/s/Klbxern8v ｜ 语音计费：https://cloud.baidu.com/doc/SPEECH/s/Al9mh44v7

**视频**
- PySceneDetect 检测算法与默认参数：https://www.scenedetect.com/docs/latest/api/detectors.html
- PySceneDetect 仓库 / 官方 Docker 镜像 / BSD-3-Clause：https://github.com/Breakthrough/PySceneDetect
- video-subtitle-extractor（硬字幕提取范式，Apache-2.0）：https://github.com/YaoFANGUK/video-subtitle-extractor
- ffmpeg 滤镜文档：https://ffmpeg.org/ffmpeg-filters.html

**文档与集成**
- PyMuPDF 许可说明（AGPL-3.0 或商业）：https://pymupdf.readthedocs.io/en/latest/about.html
- pdfplumber：https://pypi.org/project/pdfplumber/ ｜ pypdf：https://pypi.org/project/pypdf/ ｜ pypdfium2：https://pypi.org/project/pypdfium2/
- Apache PDFBox：https://pdfbox.apache.org/ ｜ 版本：https://search.maven.org/artifact/org.apache.pdfbox/pdfbox
- Apache POI：https://poi.apache.org/ ｜ 版本：https://search.maven.org/artifact/org.apache.poi/poi-ooxml
- python-pptx：https://python-pptx.readthedocs.io/ ｜ python-docx：https://python-docx.readthedocs.io/
- FastAPI：https://pypi.org/project/fastapi/ ｜ Celery：https://pypi.org/project/celery/
- gRPC Java：https://github.com/grpc/grpc-java ｜ protobuf：https://github.com/protocolbuffers/protobuf
