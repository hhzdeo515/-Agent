# 中文 OCR 引擎技术选型调研（事实核实版）

> 核查日期：**2026-09-20**（UTC+8）
> 核查方式：实际抓取 GitHub REST API / GitHub Raw（README、LICENSE、官方文档源文件）、PyPI JSON API、Docker Hub Registry API、各官方文档站（paddleocr.ai、rapidai.github.io、cnocr.readthedocs.io、tesseract-ocr.github.io、jaided.ai）、arXiv、三家云厂商官方计费页。
> 原则：**所有版本号、许可证、价格、坐标字段名均来自官方页面原文**；官方页面未写明的，一律标注「不确定」。**本报告只陈述事实，不含代码实现、不含最终技术选型建议。**

---

# A. PaddleOCR

## A.1 版本号

| 分支 | 最新版本 | 发布/上传时间 | 证据链接 |
|---|---|---|---|
| **3.x（当前主线）** | **3.7.0** | GitHub published 2026-06-11T12:09:14Z；PyPI wheel 上传 2026-06-11T12:09:51Z | https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.7.0 ；https://pypi.org/project/paddleocr/ |
| 3.x（前一版） | 3.6.0 | 2026-05-28 | https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.6.0 |
| **2.x（旧分支，GitHub 最后一个 Release）** | **2.10.0** | published 2025-03-07T07:03:56Z | https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v2.10.0 |
| 2.x（PyPI 上的最后一版） | 2.9.1 | 上传 2024-10-22T05:58:00Z | https://pypi.org/project/paddleocr/ |
| PyPI `info.version` | 3.7.0 | 抓取于 2026-09-20 | https://pypi.org/pypi/paddleocr/json |

**结论**

1. **3.x 与 2.x 并存，3.x 是主线**：`paddleocr` PyPI 最新版 **3.7.0**；2.x 分支最后 GitHub Release 为 **v2.10.0**（2025-03-07），PyPI 上最后上传的 2.x 是 **2.9.1**（2024-10-22）。链接：[GitHub releases](https://github.com/PaddlePaddle/PaddleOCR/releases) / [PyPI](https://pypi.org/project/paddleocr/)
2. **3.x 发版节奏很快**：3.0.0(2025-05-20) → 3.1.0(2025-06-29) → 3.2.0(2025-08-21) → 3.3.0(2025-10-16) → 3.4.0(2026-01-29) → 3.5.0(2026-04-21) → 3.6.0(2026-05-28) → 3.7.0(2026-06-11)。链接：[PyPI JSON](https://pypi.org/pypi/paddleocr/json)
3. **3.7.0 的核心变化是发布 PP-OCRv6**，三档模型：tiny 1.5M / small 7.7M / medium 34.5M 参数。链接：[v3.7.0 release note](https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.7.0)
4. **3.7.0 默认使用 PP-OCRv6 模型**（官方教程代码注释「# 默认使用 PP-OCRv6 模型」）。链接：[OCR 使用教程](https://www.paddleocr.ai/latest/version3.x/pipeline_usage/OCR.html)
5. 仓库活跃：GitHub API `archived=false`、`pushed_at=2026-09-16`、stars≈89,856。链接：https://github.com/PaddlePaddle/PaddleOCR

## A.2 许可证

| 对象 | 许可证 | 证据链接 |
|---|---|---|
| PaddleOCR 代码仓库 | **Apache-2.0**（LICENSE 首行 "Apache License Version 2.0, January 2004"） | https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE |
| README 声明 | 「本项目采用 Apache 2.0 许可证发布」 | https://github.com/PaddlePaddle/PaddleOCR/blob/main/readme/README_cn.md |
| PP-OCRv5 / PP-OCRv6 模型权重（HuggingFace 模型卡元数据） | **`license: apache-2.0`** | https://huggingface.co/PaddlePaddle/PP-OCRv5_server_det ；https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det |
| PaddleX（3.x 推理与服务化底座） | **Apache-2.0**（GitHub API `license.spdx_id`） | https://github.com/PaddlePaddle/PaddleX |

**结论**

1. **代码是 Apache-2.0，模型权重在官方模型卡上也标注 apache-2.0**，本轮未发现「代码与模型许可不同」的官方声明。链接：[LICENSE](https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE) / [PP-OCRv5_server_det](https://huggingface.co/PaddlePaddle/PP-OCRv5_server_det)
2. **仓库内没有单独的“模型许可证文件”**，除 Apache-2.0 LICENSE 外未找到针对模型的额外条款。链接：https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE
3. 维护者在 Discussion 中答复商用问题：「PaddleOCR 项目遵循 Apache 2.0 协议，可以商用，事实上很多商业软件的底层 OCR 模型均来自 PaddleOCR。」——**这是维护者答复，不是法律文件**。链接：https://github.com/PaddlePaddle/PaddleOCR/discussions/15986
4. 3.x 的**可选依赖**会引入其他许可的第三方库（社区 Discussion 指出 PyMuPDF / pdf2docx 为 **AGPL-3.0**），这属于依赖层合规风险，不属于 PaddleOCR 自身许可。链接：https://github.com/PaddlePaddle/PaddleOCR/discussions/16886
5. **未见覆盖全部 PP-OCR / PP-StructureV3 子模型的统一许可声明页**，逐模型审计需逐个查模型卡（见不确定项）。

## A.3 官方 Docker 镜像与部署方式

| 部署方式 | 官方镜像 / 命令 | 关键限制 | 证据链接 |
|---|---|---|---|
| 官方飞桨框架镜像（百度云 CCR） | CPU：`ccr-2vdh3abv-pub.cnc.bj.baidubce.com/paddlepaddle/paddle:3.0.0`；GPU：`...:3.0.0-gpu-cuda11.8-cudnn8.9-trt8.6`、`...-gpu-cuda12.6-cudnn9.5-trt10.5` | 官方安装文档「基于 Docker 安装飞桨」 | https://www.paddleocr.ai/latest/version3.x/paddlepaddle_installation.html |
| Docker Hub 同名镜像 | `paddlepaddle/paddle` | Docker Hub API：tags 433 个、stars 140、pulls≈1,331,702；`3.3.1` 为 `linux/amd64`、≈1.80 GB | https://hub.docker.com/r/paddlepaddle/paddle/tags |
| PaddleOCR-VL 官方镜像（含离线版） | `ccr-2vdh3abv-pub.cnc.bj.baidubce.com/paddlepaddle/paddleocr-vl:latest-nvidia-gpu`（≈8 GB）；离线 `...:latest-nvidia-gpu-offline` | **要求 NVIDIA GPU**；x64 CPU 用户官方要求走手动安装路径 | https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PaddleOCR-VL.md |
| 服务化部署（3.x 官方推荐） | `paddlex --install serving` → `paddlex --serve --pipeline OCR` | `--device` 默认「GPU 可用时用 GPU，否则用 CPU」；默认端口 8080；官方称高稳定性方案性能可能不及 2.x PaddleServing 方案 | https://www.paddleocr.ai/latest/version3.x/inference_deployment/serving/serving.html |
| 2.x 时代方式 | PaddleServing / hubserving / PaddleHub | 文档仍保留在 `version2.x/legacy/`，**3.x 导航中已无 hubserving 入口** | https://www.paddleocr.ai/latest/version2.x/legacy/paddle_server.html |

**结论**

1. **有官方 Docker 镜像，但不在 Docker Hub 的 `paddlepaddle/paddleocr` 仓库下**：该仓库在 Docker Hub API 上返回 **404**；官方镜像发布在百度云 CCR 域名。链接：https://hub.docker.com/r/paddlepaddle/paddle/tags
2. **CPU 用户有官方镜像**：官方安装文档明确给出不带 gpu 后缀的 CPU 标签与命令。链接：https://www.paddleocr.ai/latest/version3.x/paddlepaddle_installation.html
3. **内网离线部署官方有流程**：PaddleOCR-VL 提供 `-offline` 镜像并给出 `docker save` → 传输 → `docker load` 步骤（但该镜像需 NVIDIA GPU）。链接：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PaddleOCR-VL.md
4. **3.x 服务化官方推荐 PaddleX，不是 hubserving**；`--device` 无 GPU 时自动回落 CPU。链接：https://www.paddleocr.ai/latest/version3.x/inference_deployment/serving/serving.html
5. **PP-StructureV3 官方 benchmark 中出现的镜像是 GPU 镜像** `ccr-2vdh3abv-pub.cnc.bj.baidubce.com/paddlepaddle/paddle:3.1.0-gpu-cuda11.8-cudnn8.9`；**PP-StructureV3 文档未给出专用 CPU 镜像名**（见不确定项）。链接：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.md

## A.4 返回坐标的 API 形态

官方 OCR 产线返回字段（`res` 字典，字段定义表原文）：

| 字段 | 官方定义摘要 | 粒度 |
|---|---|---|
| `dt_polys` | 文本检测多边形框列表，每框 4 个顶点，shape `(4,2)`，dtype int16 | 行级·四点 |
| `dt_scores` | 文本检测框置信度列表 | 行级 |
| `rec_texts` | 文本识别结果列表（已按 `text_rec_score_thresh` 过滤） | 行级 |
| `rec_scores` | 文本识别置信度列表（已按阈值过滤） | 行级 |
| `rec_polys` | 过滤后的文本框列表，格式同 `dt_polys` | 行级·四点 |
| `rec_boxes` | 矩形边界框数组，shape `(n,4)`，dtype int16，每行 `[x_min, y_min, x_max, y_max]` | 行级·矩形 |
| `text_word` / `text_word_region` / `text_word_boxes` | **仅 `return_word_box=True` 时返回**；`text_word_region` 为词/字四点框，`text_word_boxes` 为其矩形化结果 | 词级/单字级（可选） |

证据：[OCR 使用教程字段表](https://www.paddleocr.ai/latest/version3.x/pipeline_usage/OCR.html) ；源文件 https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/OCR.md

- 参数 `return_word_box` 官方定义：「是否返回识别结果的文字框坐标……默认初始化为 `False`。」链接：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/OCR.md
- 字段真实存在（源码依据）：`paddlex/inference/pipelines/ocr/pipeline.py` 中构造 `res["text_word"]`、`res["text_word_region"]`、`res["text_word_boxes"]`。链接：https://github.com/PaddlePaddle/PaddleX/blob/develop/paddlex/inference/pipelines/ocr/pipeline.py
- 「PP-OCR 系列模型支持返回单文字坐标」列入 **3.2.0**（2025-08-21）更新日志。链接：https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.2.0 ；https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/update/update.md

**结论**

1. **默认返回行级坐标**：`rec_texts` / `rec_scores` / `rec_polys`（四点）/ `rec_boxes`（`[x_min,y_min,x_max,y_max]`）一一对应，可直接满足“每行文字 + 边界框”。链接：https://www.paddleocr.ai/latest/version3.x/pipeline_usage/OCR.html
2. **词级/单字级坐标需显式开启 `return_word_box=True`**（默认 False），开启后新增 `text_word` / `text_word_region` / `text_word_boxes`。链接：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/OCR.md
3. **官方文档未在字段表列出 `text_word*` 的完整结构定义**，字段名需读源码确认；切分粒度（中文按字/英文按词）官方文档未逐字说明（见不确定项）。链接：https://github.com/PaddlePaddle/PaddleX/blob/develop/paddlex/inference/pipelines/ocr/pipeline.py
4. **2.x 与 3.x API 形态不同**：3.x 为 `PaddleOCR(...).predict()` 返回上述字典；2.x 为 `ocr()` 返回 `[[[box], (text, score)], ...]` 旧结构。链接：https://www.paddleocr.ai/latest/version2.x/legacy/index.html
5. **PP-StructureV3 额外给出版面区域坐标**：`layout_det_res.boxes[].coordinate` 为矩形 `[x0,y0,x1,y1]` 且带 `label`（`text`、`doc_title`、`paragraph_title`、`figure_title`、`image` 等），与 OCR 字段并存于同一份结果。链接：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.md

## A.5 PP-StructureV3 版面分析能力

| 项目 | 事实 | 证据链接 |
|---|---|---|
| 名称 / 版本 | 产线名 **PP-StructureV3**，随 PaddleOCR 3.x 发布（当前文档对应 3.7.x 主线，3.0.0 起即有） | https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html |
| 依赖分组 | 可选依赖组 `doc-parser`（表格、公式、印章、图片等版面元素）。`paddleocr` 本体仅装 OCR 所需依赖 | https://www.paddleocr.ai/latest/version3.x/installation.html |
| 版面元素类别 | 版面检测模型 **20 类**：文档标题、段落标题、文本、页码、摘要、目录、参考文献、脚注、页眉、页脚、算法、公式、公式编号、图像、表格、图和表标题、印章、图表、侧栏文本、参考文献内容；另列 **23 类**版本（增加页眉图像、页脚图像等） | https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.md |
| 能力范围 | 版面区域检测、表格识别、公式识别、图表理解、**多栏阅读顺序恢复**、转 Markdown | https://www.paddleocr.ai/latest/version3.x/algorithm/PP-StructureV3/PP-StructureV3.html |
| 许可证 | Apache-2.0（与主仓库一致，未见单独许可） | https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE |
| GPU 必要性 | 官方 FAQ：「虽然更推荐在 GPU 环境下进行推理，**但也支持在 CPU 上运行**」；`device` 不设置时「优先使用本地的 GPU 0 号设备，如果没有，则使用 CPU 设备」 | https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html |
| 公开评测 | 官方给出与 MinerU / Marker / Mathpix / Docling / Gemini2.5-Pro 等在 **OmniDocBench** 上的 OverallEdit 对比表 | https://www.paddleocr.ai/latest/version3.x/algorithm/PP-StructureV3/PP-StructureV3.html |

**结论**

1. **PP-StructureV3 是“产线”而非独立包**，随 PaddleOCR 3.x 分发，需安装 `doc-parser` 依赖组。链接：https://www.paddleocr.ai/latest/version3.x/installation.html
2. **可识别标题、段落、图片区域、表格、印章、公式、图表等 20 类版面元素**，具备区域级定位能力。链接：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.md
3. **README 明确其提供更细粒度坐标**：「与 PaddleOCR-VL 系列模型不同，它提供更细粒度的坐标信息，包括**表格单元格坐标、文本坐标**等」。链接：https://github.com/PaddlePaddle/PaddleOCR/blob/main/readme/README_cn.md
4. **CPU 可跑但官方更推荐 GPU**，且不设 `device` 时会优先占用 GPU0、无 GPU 才回落 CPU。链接：https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html
5. 其精度对比引用的是 **OmniDocBench 公开榜单**（论文 arXiv:2412.07626）+ 官方自测，**不是第三方独立机构评测**。链接：https://arxiv.org/abs/2412.07626

## A.6 CPU 推理可行性与官方耗时数据

官方 PP-OCRv5 端到端推理性能（200 张通用+文档图像，含读图与前后处理）：

| 硬件 | 配置 | 平均每图耗时 | 平均每秒预测字符数 | 峰值 RAM / VRAM |
|---|---|---|---|---|
| **CPU** Intel Xeon Gold 6271C | PP-OCRv5_mobile | **1.75 s/图** | 371.82 | RAM 2219.98 MB |
| **CPU** 同上 | PP-OCRv4_mobile | 1.37 s/图 | 444.27 | RAM 2090.53 MB |
| **CPU** 同上 | PP-OCRv5_server | **4.34 s/图** | 149.98 | RAM 4020.85 MB |
| **CPU** 同上 | PP-OCRv4_server | 5.42 s/图 | 115.20 | RAM 4018.35 MB |
| GPU Tesla V100 | v5_mobile | 0.62 s/图 | 1054.23 | 峰值 VRAM 4190 MB |
| GPU Tesla V100 | v5_server | 0.74 s/图 | 878.84 | 峰值 VRAM 5402 MB |

证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html

官方 PP-OCRv6 端到端速度（单位 s/图，200 张图像，含读图/前后处理/推理全流程）：

| 硬件 | 后端 | v6_medium | v6_small | v6_tiny | v5_server |
|---|---|---|---|---|---|
| **Intel Xeon 8350C** | PaddlePaddle | 2.05 | 0.79 | 0.32 | 2.04 |
| **Intel Xeon 8350C** | OpenVINO | **1.40** | 0.59 | **0.20** | 7.30 |
| **Intel Xeon 8350C** | ONNX Runtime | 3.31 | 0.61 | 0.22 | 6.36 |
| Apple M4 | PaddlePaddle | 8.82 | 3.07 | 0.96 | >10 |
| NVIDIA A100 | PaddlePaddle | 0.29 | 0.25 | 0.13 | 0.32 |

官方口径：「PP-OCRv6_medium 在所有平台上均匹配或优于 PP-OCRv5_server：A100 上快 1.1×（0.29s vs 0.32s），Intel Xeon OpenVINO 快 5.2×（1.40s vs 7.30s）。」
证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html

PP-StructureV3 官方 CPU 说明（FAQ 原文）：「PP-StructureV3 虽然更推荐在 GPU 环境下进行推理，但也支持在 CPU 上运行……例如，**在 Intel 8350C CPU 上，每张图片的推理时间约为 3.74 秒**。」
证据：https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html

其他官方 CPU 相关说明：
- 「**CPU 推理速度优化：** 所有产线 CPU 推理默认开启 MKL-DNN」（3.0.1 release note）。链接：https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.0.1
- 「PP-OCRv5 的识别模型使用了更大的字典，需要更长的推理时间，导致 PP-OCRv5 的推理速度慢于 PP-OCRv4。」链接：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html

**结论**

1. **CPU 单图官方耗时落在 0.20 ~ 4.34 秒区间**：v6_tiny+OpenVINO ≈0.20 s；v6_medium+OpenVINO ≈1.40 s；v5_mobile ≈1.75 s；v5_server ≈4.34 s；PP-StructureV3 轻量配置 ≈3.74 s。**全部为官方文档数据，非社区实测**。链接：[PP-OCRv6](https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html) / [PP-OCRv5](https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html) / [PP-StructureV3](https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html)
2. **官方给出的 CPU 加速路径是 OpenVINO / ONNX Runtime 后端**：同一 Intel Xeon 上 v6_medium OpenVINO 1.40 s vs Paddle 后端 2.05 s，对比 v5_server 7.30 s 提升 5.2×。链接：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html
3. **CPU 峰值内存约 2.0 GB（mobile 档）~ 4.0 GB（server 档）**；GPU 档峰值显存约 4.2~5.4 GB。链接：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html
4. **官方测试硬件是服务器级 CPU（Intel Xeon Gold 6271C / 8350C）**，非服务器 CPU 环境无官方数据（见不确定项）。链接：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html
5. 高性能推理文档还提供各模块「CPU 推理耗时（ms）[常规模式 / 高性能模式]」对照表（Intel Xeon Gold 6271C @2.60GHz；paddlepaddle 3.0.0 / paddleocr 3.0.3）。链接：https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html

## A.7 中文识别准确率（官方数据）

PP-OCRv5 官方指标（内部多场景评估集）：

| 维度 | PP-OCRv5_server | PP-OCRv4_server | 差值 |
|---|---|---|---|
| 文本检测平均 Hmean | 0.827 | 0.662 | +16.5 pt |
| 印刷中文检测 | 0.945 | 0.888 | +5.7 pt |
| 文本识别加权平均 | 0.8401 | 0.5735 | +26.66 pt |
| 印刷中文识别 | 0.9013 | 0.8486 | +5.27 pt |
| 手写中文识别 | 0.5807 | 0.3626 | +21.81 pt |

官方原文：「在内部多场景复杂评估集上，**PP-OCRv5 较 PP-OCRv4 端到端提升 13 个百分点**。」
证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html

PP-OCRv6 官方指标（内部 15 类场景基准）：
- 识别：**v6_medium 加权平均 83.2%**，比 v5_server 提升 **5.1%**；印刷中文 91.5%、手写中文 62.1%、繁体 78.6%、古籍 72.4%、日文 90.5%、艺术字 71.2%、屏幕 82.5%。
- 检测：**v6_medium 平均 Hmean 86.2%**，比 v5_server 提升 **4.6 个百分点**。
- release note 原文：「medium 档相比 PP-OCRv5_server 检测精度提升 4.6%、识别精度提升 5.1%，以仅 34.5M 参数超越 Qwen3-VL-235B、GPT-5.5 等主流视觉语言大模型。」

证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html ；https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.7.0

**论文佐证**：PP-OCRv6 官方论文（arXiv:2606.13108，2026-06-11，CC BY 4.0）摘要原文："On our in-house benchmarks, PP-OCRv6_medium achieves 83.2% recognition accuracy and 86.2% detection Hmean, outperforming PP-OCRv5_server by +5.1% and +4.6% respectively while surpassing Qwen3-VL-235B, GPT-5.5, and Gemini-3.1-Pro with orders of magnitude fewer parameters. The tiny tier achieves 3.9× faster inference than PP-OCRv5_mobile on Intel Xeon CPU while maintaining comparable accuracy."
链接：https://arxiv.org/abs/2606.13108

**结论**

1. **PP-OCRv5 相对 v4 的官方提升口径是“端到端 13 个百分点”**（内部多场景复杂评估集），识别加权平均 0.5735→0.8401。链接：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html
2. **PP-OCRv6_medium 相对 v5_server 检测 +4.6%、识别 +5.1%**，且有 arXiv 论文可引用（论文数字与官网一致）。链接：https://arxiv.org/abs/2606.13108
3. **这些数字全部来自官方自建评估集（in-house benchmarks）**，官方明确 v6 的评估集与 v5/v4 不同、不可直接横向比较。链接：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html
4. **未找到独立第三方横向评测 PP-OCR 中文准确率的权威来源**；PP-StructureV3 页面引用的 OmniDocBench（arXiv:2412.07626）是公开榜单，但针对的是**文档版面解析**而非通用文字识别。链接：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-StructureV3/PP-StructureV3.html
5. 广告物料相关场景官方指标：v5_server 艺术字 0.6397、繁体中文 0.7472、竖直文本 0.9314；v6 表中艺术字 71.2 —— 均来自官方内部评估集。链接：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html

---

# B. 备选开源方案

## B.0 四方案总览表

| 项目 | 最新版本 | 发布日期 | 许可证（代码 / 权重） | 坐标返回 | CPU 可行性 | 中文口碑（官方数据） | 维护活跃度 |
|---|---|---|---|---|---|---|---|
| **RapidOCR** | **3.9.2**（`rapidocr`） | 2026-07-21 | 代码 Apache-2.0；权重派生自 PaddleOCR，README 称按 Apache-2.0 再分发，但引用的 `MODEL_LICENSES.md` **404** | **行级 + 可选单字级**：`boxes (N,4,2)`、`txts`、`scores`；`word_results` 需 `return_word_box=True` | **官方推荐 CPU 起步**（ONNX Runtime CPU） | 自身无精度表；上游 PaddleOCR 官方：v6_medium 检测 86.2%、识别 83.2% | 活跃：release 2026-07-21，最近提交 2026-09-15，Star 7,879，未归档 |
| **CnOCR** | **2.3.3** | 2026-07-05 | 代码 Apache-2.0；**权重许可官方未声明**；另有官方明示需购买/会员的 Pro 模型 | **行级**：`text`/`score`/`position (4,2)`；**无词/字级字段** | **默认即 CPU**（`context` 默认 `cpu`，后端默认 `onnx`） | 官方无准确率/CER 数据 | 中等：release 2026-07-05，最近提交 2026-07-05，Star 3,769，未归档 |
| **EasyOCR** | **1.7.2** | **2024-09-24** | 代码 Apache-2.0；**权重许可官方未声明** | **行/文本框级**：`readtext` 返回 `([4点], text, conf)`；`detect()` 返回 `horizontal_list`/`free_list`；**无词/字级字段** | 可行，但**官方 API 文档写明 `gpu` 默认 `True`**，需显式 `gpu=False` | 官方无中文精度数据 | **低**：最新 release 2024-09-24；2025-12-05 后仅 README 改动；Star 30,007，未归档 |
| **Tesseract** | 引擎 **5.5.3**；中文模型来自 `tessdata`（无版本号） | 引擎 2026-07-24；`tessdata` 最近提交 **2024-03-07** | 引擎 Apache-2.0（依赖 Leptonica BSD-2）；**tessdata 全部数据 Apache-2.0**（含 chi_sim） | **粒度最细：页/块/段/行/词**；hOCR `ocr_line`/`ocrx_word`、TSV `level=4/5`；pytesseract 另有字符级 `image_to_boxes()` | **纯 CPU 方案**（官方 Benchmarks 即在 CPU 上测；5.0.1 起 OpenMP 默认关） | 官方无中文精度数据（Benchmarks 只测速度） | 引擎活跃（2026-07-24，Star 76,583）；**语言数据约 2.5 年未更新** |

## B.1 RapidOCR

| 字段 | 结论 | 链接 |
|---|---|---|
| 最新版本 / 日期 | **3.9.2 / 2026-07-21**（PyPI `rapidocr`；tag `v3.9.2`） | https://pypi.org/project/rapidocr/ ；https://github.com/RapidAI/RapidOCR/releases/tag/v3.9.2 |
| 旧包名 | `rapidocr-onnxruntime` 最新 **1.4.4**（2025-01-17，`Requires-Python <3.13,>=3.6`），明显落后；官方未见弃用声明 | https://pypi.org/project/rapidocr-onnxruntime/ |
| 许可证 | 代码 **Apache-2.0**；权重派生自 PaddleOCR、按 Apache-2.0 再分发；README 引用的 `MODEL_LICENSES.md` 仓库内**不存在（404）** | https://github.com/RapidAI/RapidOCR/blob/main/LICENSE ；https://github.com/RapidAI/RapidOCR/blob/main/README.md |
| 坐标返回 | `RapidOCROutput.boxes (N,4,2)`、`txts`、`scores`；`word_results` 仅 `return_word_box=True` 时有值，结构 `(文本, 置信度, [[左上],[右上],[右下],[左下]])`；检测单跑返回 `TextDetOutput.boxes` | https://rapidai.github.io/RapidOCRDocs/main/install_usage/rapidocr/usage/ |
| CPU | 官方「推荐可以先使用 ONNX Runtime CPU 版作为推理引擎」；支持 ONNX Runtime / OpenVINO / MNN / Paddle / TensorRT / PyTorch；Docker 有 `make build-onnxruntime-cpu` | https://github.com/RapidAI/RapidOCR/blob/main/README.md |
| 中文口碑 | 自身无精度表；上游 PaddleOCR 官方 v6 数据（检测 Hmean medium 86.2%/small 84.1%/tiny 80.6%，识别 medium 83.2%） | https://rapidai.github.io/RapidOCRDocs/main/model_list/ |
| 维护 | 最近提交 2026-09-15，`pushed_at` 2026-09-20，Star 7,879，未归档；近一年 release 密集（3.4.5→3.9.2） | https://api.github.com/repos/RapidAI/RapidOCR |

**结论**

1. **坐标能力对标 PaddleOCR**：默认行级四点 `boxes`，开启 `return_word_box=True` 后可得**单字级** `word_results`。链接：https://rapidai.github.io/RapidOCRDocs/main/install_usage/rapidocr/usage/
2. **CPU 是官方推荐路线**（ONNX Runtime CPU），且支持多种推理后端切换。链接：https://github.com/RapidAI/RapidOCR/blob/main/README.md
3. **权重许可链不完整**：README 指向的 `MODEL_LICENSES.md` 在仓库中 404；中文 README「OCR 模型版权归百度所有」与英文 README「按 Apache-2.0 再分发」措辞不完全一致。链接：https://github.com/RapidAI/RapidOCR/blob/main/README.md
4. **自身无中文准确率数据**，只能引用上游 PaddleOCR 官方指标。链接：https://rapidai.github.io/RapidOCRDocs/main/model_list/
5. 维护活跃度高（release 2026-07-21，提交 2026-09-15），但仍需注意 `rapidocr` 3.x 与 `rapidocr-onnxruntime` 1.4.4 的包名迁移。链接：https://github.com/RapidAI/RapidOCR

## B.2 CnOCR

| 字段 | 结论 | 链接 |
|---|---|---|
| 最新版本 / 日期 | **2.3.3 / 2026-07-05**（release 名 "support PP-OCRv6 models"） | https://pypi.org/project/cnocr/ ；https://github.com/breezedeus/CnOCR/releases/tag/v2.3.3 |
| 许可证 | 代码 **Apache-2.0**；**模型权重许可官方未声明**；README 明示 `*-densenet_lite_666-gru_large` 为「Pro 模型，购买后可使用」，`*-densenet_lite_246-gru_base` 为知识星球会员优先 | https://github.com/breezedeus/CnOCR/blob/master/LICENSE ；https://github.com/breezedeus/CnOCR/blob/master/README.md |
| 坐标返回 | `CnOcr.ocr()` 返回 `List[Dict]`（每元素=一行）：`text`、`score`、`position`（`np.ndarray`，shape `(4,2)` 四点）；`ocr_for_single_line()` **不含 position**；**无词/字级字段** | https://github.com/breezedeus/CnOCR/blob/master/docs/usage.md |
| CPU | **默认即 CPU**：`context` 默认 `cpu`（仅 pytorch 后端有效）；`rec_model_backend`/`det_model_backend` 默认 `onnx`（官方称约 2 倍速） | https://cnocr.readthedocs.io/zh-cn/stable/usage/ |
| 中文口碑 | **官方无量化精度数据**（模型表只有模型名/大小/语言/竖排，无准确率或 CER）；仅定性表述「精度更高」 | https://cnocr.readthedocs.io/zh-cn/stable/models/ |
| 维护 | 最近 release/提交均为 2026-07-05，Star 3,769，未归档 | https://api.github.com/repos/breezedeus/CnOCR |

**结论**

1. **只提供行级四点坐标 `position`**，逐行返回 `text`/`score`/`position`。链接：https://cnocr.readthedocs.io/zh-cn/stable/usage/
2. **官方未提供词级/单字级坐标字段**（不确定是否存在未文档化能力）。链接：https://github.com/breezedeus/CnOCR/blob/master/docs/usage.md
3. **CPU 是默认配置**（context 默认 cpu、后端默认 onnx），无 GPU 也可直接跑。链接：https://cnocr.readthedocs.io/zh-cn/stable/usage/
4. **模型授权存在混合情况**：官方 README 明示部分模型需购买或会员权限，权重许可条款未声明。链接：https://github.com/breezedeus/CnOCR/blob/master/README.md
5. 官方**没有任何中文准确率数字**，官方未来工作仍列「模型精度进一步优化」。链接：https://cnocr.readthedocs.io/zh-cn/stable/models/

## B.3 EasyOCR

| 字段 | 结论 | 链接 |
|---|---|---|
| 最新版本 / 日期 | **1.7.2 / 2024-09-24** | https://pypi.org/project/easyocr/ ；https://github.com/JaidedAI/EasyOCR/releases/tag/v1.7.2 |
| 许可证 | 代码 **Apache-2.0**；**权重（CRAFT 检测 + CRNN 识别，含中文模型）许可官方未声明** | https://github.com/JaidedAI/EasyOCR/blob/master/LICENSE ；https://www.jaided.ai/easyocr/modelhub |
| 坐标返回 | `readtext` 返回「a bounding box, the text detected and confident level」，示例 `([[189,75],[469,75],[469,165],[189,165]], '愚园路', 0.375…)`；`detail=0` 只返回字符串；`detect()` 返回 `horizontal_list`（`[x_min,x_max,y_min,y_max]`）与 `free_list`（四点）；**无词/字级字段** | https://github.com/JaidedAI/EasyOCR/blob/master/README.md ；https://www.jaided.ai/easyocr/documentation |
| CPU | 可行但需显式 `gpu=False`；**API 文档写明 `gpu` 默认 `True`**；CPU 模式默认启用动态量化（v1.2.5）；v1.6.2 增加 DBNet CPU 支持（默认检测器为 CRAFT） | https://www.jaided.ai/easyocr/documentation ；https://github.com/JaidedAI/EasyOCR/blob/master/releasenotes.md |
| 中文口碑 | **官方无量化精度数据** | https://github.com/JaidedAI/EasyOCR/blob/master/releasenotes.md |
| 维护 | 最新 release 2024-09-24；最近提交 **2025-12-05，内容仅 "Update README.md"**；Star 30,007，未归档；README 自述超过 6 个月的 issue 自动关闭 | https://api.github.com/repos/JaidedAI/EasyOCR |

**结论**

1. **只返回行/文本框级坐标**，格式为四点多边形 + 文本 + 置信度；无词/字级字段。链接：https://www.jaided.ai/easyocr/documentation
2. **`gpu` 参数默认 `True`**，无 GPU 环境必须显式 `gpu=False` 才不会报错/退化。链接：https://www.jaided.ai/easyocr/documentation
3. **版本线停滞约 2 年**：最新 release 1.7.2（2024-09-24），此后唯一提交是 2025-12-05 的 README 修改。链接：https://github.com/JaidedAI/EasyOCR/commits/master
4. **官方无中文准确率数据**，README 仅有 "80+ languages" 覆盖性描述。链接：https://github.com/JaidedAI/EasyOCR/blob/master/releasenotes.md
5. **模型权重许可未在官方 README / API 文档 / Model Hub 声明**（检测权重来自 clovaai/CRAFT-pytorch 预训练模型）。链接：https://github.com/JaidedAI/EasyOCR/blob/master/README.md

## B.4 Tesseract（chi_sim）

| 字段 | 结论 | 链接 |
|---|---|---|
| 最新版本 / 日期 | 引擎 **5.5.3 / 2026-07-24**；中文模型 `chi_sim.traineddata` 位于独立仓库 `tesseract-ocr/tessdata`（无版本号，最近提交 2024-03-07） | https://github.com/tesseract-ocr/tesseract/releases/tag/5.5.3 ；https://github.com/tesseract-ocr/tessdata |
| 许可证 | 引擎代码 **Apache-2.0**（依赖 Leptonica 为 **BSD-2-Clause**）；**tessdata 全部数据 Apache-2.0**：README「All data in the repository are licensed under the Apache-2.0 License」（tessdata_best / tessdata_fast 同句）；`chi_sim.traineddata` 44,366,093 字节 | https://github.com/tesseract-ocr/tessdata/blob/main/README.md ；https://github.com/tesseract-ocr/tessdata/blob/main/LICENSE |
| 坐标返回 | **页/块/段/行/词五级**：hOCR 含 `ocr_page`/`ocr_carea`/`ocr_par`/`ocr_line`/`ocrx_word`，各级带 `title="bbox x0 y0 x1 y1"`，词级另带 `x_wconf`；TSV 列含 `level … left top width height conf text`（`level=4` 行、`level=5` 词）。pytesseract 另有 `image_to_boxes()`（**字符级**）、`image_to_data()`、`image_to_alto_xml()` | https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html ；https://github.com/madmaze/pytesseract/blob/master/README.rst |
| CPU | **纯 CPU 方案**，官方 Benchmarks 即在 CPU（i7-10750H 6 核 16GB）上测试；**5.0.1 起 OpenMP 默认关闭**（官方称 OpenMP "known to waste a lot of CPU time"），4.x+ 建议 `OMP_THREAD_LIMIT=1` | https://tesseract-ocr.github.io/tessdoc/Benchmarks.html |
| 中文口碑 | **官方无中文精度数据**：Benchmarks 页只测速度；UNLV 测试页是 1995 年英文测试重现，无中文结果 | https://tesseract-ocr.github.io/tessdoc/Benchmarks.html ；https://tesseract-ocr.github.io/tessdoc/UNLV-Testing-of-Tesseract.html |
| 维护 | 引擎活跃（2026-07-24 release，2026-09-11 提交，Star 76,583）；`tessdata`（chi_sim 所在）Star 7,657，**最近提交 2024-03-07（约 2.5 年无更新）** | https://api.github.com/repos/tesseract-ocr/tesseract ；https://github.com/tesseract-ocr/tessdata/commits/main |
| 附 pytesseract | 最新 **0.3.13**，Apache-2.0，Star 6,390；官方前提：必须先安装 tesseract 可执行文件 | https://pypi.org/project/pytesseract/ |

**结论**

1. **坐标粒度是四者中最细的**：hOCR 与 TSV 原生提供**词级** bbox，pytesseract 还提供**字符级** `image_to_boxes()`。链接：https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html
2. **chi_sim 语言数据明确 Apache-2.0**，是四者中权重许可最清晰的一个。链接：https://github.com/tesseract-ocr/tessdata/blob/main/README.md
3. **纯 CPU 引擎，无 GPU 依赖**；但官方 5.0.1 起关闭 OpenMP，多线程需自行配置。链接：https://tesseract-ocr.github.io/tessdoc/Benchmarks.html
4. **中文效果官方无任何量化数据**，官方 Benchmarks 只测英文速度、UNLV 页为 1995 年英文测试。链接：https://tesseract-ocr.github.io/tessdoc/UNLV-Testing-of-Tesseract.html
5. **中文模型（tessdata）约 2.5 年未更新**（最近提交 2024-03-07），而引擎仍在活跃发版 —— 语言数据与引擎的维护节奏不一致。链接：https://github.com/tesseract-ocr/tessdata/commits/main

---

# C. 云厂商 OCR

## C.0 三家横向对比（均取自官方页面原文数字）

**坐标返回格式**

| 厂商 / 接口 | 文字行·块坐标 | 四点/多边形 | 单字（字符）级坐标 | 开启条件 |
|---|---|---|---|---|
| 阿里云 `RecognizeGeneral` / `RecognizeAdvanced` | `prism_wordsInfo[].pos` | **默认即四点**（左上、右上、右下、左下） | `charInfo[].x/y/w/h`（左上角+宽高） | 单字需 `OutputCharInfo=true` |
| 腾讯云 `GeneralBasicOCR` / `GeneralAccurateOCR` | `TextDetections[].ItemPolygon`（X,Y,Width,Height） | `TextDetections[].Polygon`（四顶点） | `WordCoordPoint.WordCoordinate`（**四顶点**，左上起顺时针） | 单字需 `IsWords=true` |
| 百度 `accurate_basic` / `general_basic` | `words_result[].location`（left,top,width,height，参数表标注「是」必返回） | `vertexes_location` / `finegrained_vertexes_location` | `chars[].location` + `char_prob` | 四点需 `vertexes_location=true`；单字需 `recognize_granularity=small` |

**价格与免费额度**

| 厂商 | 标准/基础版后付费首档 | 高精度版后付费首档 | 免费额度 | 免费额度口径 |
|---|---|---|---|---|
| 阿里云 | 0.0825 元/次 | 0.225 元/次 | 200 次/月 | 每 API 每月，当月生效过期作废 |
| 腾讯云 | 0.15 元/次 | **0.50 元/次** | 1,000 次/月 | 共享资源包，多接口共享，每月 1 日发放 |
| 百度智能云 | 0.0050 元/次 | **0.030 元/次** | 个人 1,000 / 企业 2,000 次/月（高精度版） | 实名认证后按接口按月发放 |

**私有化 / 离线 / 一体机**

| 厂商 | 私有化部署 | 一体机 | 离线 SDK | 公开报价 |
|---|---|---|---|---|
| 阿里云 | 支持（专有云、混合云） | 官方页面未提及 | **暂不提供支持**（官方 FAQ） | 无，需商务 |
| 腾讯云 | 支持（官方 FAQ 正面确认；公开路径为 TI-OCR 训练平台） | 官方页面未提及 | **不支持移动端离线**（官方 FAQ） | 无，需申请+商务洽谈+签合同 |
| 百度智能云 | 支持（纯软件版 Docker 容器） | **支持**（CPU/GPU 软硬一体） | **支持，299 元/设备**（3~1000 个档） | 私有化无公开价；离线 SDK 有公开价 |

## C.1 阿里云 OCR

| 维度 | 结论 | 官方链接 |
|---|---|---|
| 坐标 | `RecognizeGeneral`（基础版）与 `RecognizeAdvanced`（高精版）均返回 `prism_wordsInfo`；`pos`=「文字块的外矩形四个点的坐标按顺时针排列（左上、右上、右下、左下）」；单字 `charInfo` 需 `OutputCharInfo=true`，格式 `x/y/w/h`；顶层有 `orgWidth`/`orgHeight` 原图宽高 | https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizegeneral ；https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizeadvanced |
| 后付费价格 | 基础版：0.0825（≤1万）/ 0.0495（1-10万）/ 0.0415（10-50万）/ 0.0248（50-100万）/ 0.009（>100万）元/次；高精版：0.225 / 0.09 / 0.054 / 0.045 / 0.036 元/次。自然月阶梯累加制，按成功调用计费；开通服务即自动开通后付费且**不可关闭** | https://help.aliyun.com/zh/ocr/product-overview/pay-as-you-go |
| 免费额度 | **每 API 200 次/月**，当月生效、过期作废 | https://help.aliyun.com/zh/ocr/product-overview/free-quota |
| 预付费资源包 | 专用包：全文识别高精版 500 次 90 元 / 1000 次 167 元 / 1 万次 1,100 元 / 10 万次 5,610 元 / 50 万次 18,563 元 / 100 万次 28,050 元；通用文字识别 500 次 45 元 / 1 万次 550 元 / 10 万次 2,805 元。共享资源包按“点数”售卖：5 千点 45 元 / 1 万点 83.3 元 / 10 万点 577.5 元 / 100 万点 2,805 元（**高精版单次抵扣 20 点、基础版 10 点**） | https://help.aliyun.com/zh/ocr/product-overview/resource-plans |
| 私有化 | 支持专有云、混合云；**无公开报价**，需 `ocr_support@list.alibaba-inc.com` 或钉钉群 35208328；官方 FAQ 明确「**离线 SDK 现暂不提供支持**」 | https://help.aliyun.com/zh/ocr/support/faq-about-features ；https://help.aliyun.com/zh/ocr/support/contact-us |

**结论**

1. **默认就返回四点坐标**（`prism_wordsInfo[].pos`），是目前三家文档中唯一“四点坐标默认开启”的，可直接用于海报文字回标。链接：https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizeadvanced
2. **单字级坐标需显式开启 `OutputCharInfo=true`，且格式与块级不同**（`x/y/w/h` 而非四点），接入时不能混用。链接：https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizeadvanced
3. **高精版首档 0.225 元/次**，为三家中档水平；免费额度最低（200 次/月/API）；后付费开通后**不可关闭**，需注意计费边界。链接：https://help.aliyun.com/zh/ocr/product-overview/pay-as-you-go
4. **响应中还能拿到行/段/表格/图案多级结构**：`Row=true` → `prism_rowsInfo`、`Paragraph=true` → `prism_paragraphsInfo`、`OutputTable=true` → `prism_tablesInfo`（单元格 `pos` 同为四角坐标）、`OutputFigure=true` → `figure`（印章/二维码等）。链接：https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizeadvanced
5. **私有化支持但离线 SDK 明确不支持**：「印刷文字识别 OCR 支持专有云、混合云的私有化部署，您可联系我们沟通」，但 FAQ 同时写明离线 SDK 暂不提供支持。链接：https://help.aliyun.com/zh/ocr/support/faq-about-features

## C.2 腾讯云 OCR

| 维度 | 结论 | 官方链接 |
|---|---|---|
| 坐标 | `GeneralAccurateOCR` / `GeneralBasicOCR` 输出 `TextDetections[]`，**同时给两套坐标**：`Polygon`（四顶点 `Coord{X,Y}`）与 `ItemPolygon`（X, Y, Width, Height，旋转纠正后像素坐标）；单字 `WordCoordPoint.WordCoordinate` 需 `IsWords=true`，为**四顶点**坐标（左上起顺时针）；`AdvancedInfo` 含 `Parag.ParagNo` 段落号 | https://cloud.tencent.com/document/product/866/34937 ；https://cloud.tencent.com/document/api/866/33527 |
| 后付费价格（刊例价） | 通用印刷体：0.15（<1万）/ 0.10（1-10万）/ 0.06（10-100万）元/次，100 万及以上「联系商务」；**高精度版：0.50 / 0.35 / 0.20 元/次，100 万及以上「联系商务」** | https://cloud.tencent.com/document/product/866/17619 |
| 免费额度 | **1,000 次/月**（共享资源包，每月 1 日发放，当月有效，多接口共享）；额度耗尽「服务将面临不可用风险」，需**手动**开通后付费（不像阿里云自动兜底） | https://cloud.tencent.com/document/product/866/17619 |
| 预付费资源包（高精度版） | 1,000 次 400 元 / 1 万次 3,000 元 / 10 万次 15,000 元 / 100 万次 80,000 元 / 1,000 万次 500,000 元（有效期 1 年）；通用印刷体识别：1,000 次 120 元 / 1 万次 800 元 / 10 万次 5,000 元 / 100 万次 30,000 元 / 1,000 万次 200,000 元 | https://cloud.tencent.com/document/product/866/17619 |
| QPS | 高精度版默认 10 次/秒；通用印刷体识别默认 20 次/秒 | 各 API 文档「默认接口请求频率限制」 |
| 计费边界 | 官方另有「**错误码计费说明**」列出部分失败调用也计费 | https://cloud.tencent.com/document/product/866/45470 |
| 私有化 | 官方 FAQ 正面确认「文字识别支持私有化部署吗？**支持**」；公开路径为 TI-OCR 训练平台（「目前仅支持私有化部署」），流程：线上申请→审核→商务洽谈→方案→签合同→线下部署→验收；**无任何公开价格** | https://cloud.tencent.com/document/product/1659/83073 ；https://cloud.tencent.com/product/tiocr ；https://cloud.tencent.com/document/product/866/33511 |
| 数据合规口径 | 官方 FAQ：「文字识别服务**不储存用户图片信息**，仅对用户日志**脱敏保存三天**」；「文字识别目前**不支持移动端离线操作**」「只支持单张识别」 | https://cloud.tencent.com/document/product/866/33511 |

**结论**

1. **同一次返回同时给四点 `Polygon` 与矩形 `ItemPolygon`**，前端可任选渲染方式。链接：https://cloud.tencent.com/document/api/866/33527
2. **单字级坐标需 `IsWords=true`，且仍是四顶点格式**（与阿里云 `x/y/w/h` 不同，适配层需分别处理）。链接：https://cloud.tencent.com/document/api/866/33527
3. **高精度版后付费首档 0.50 元/次，是三家中最高**（通用印刷体 0.15 元/次；100 万以上档官网只写「联系商务」）。链接：https://cloud.tencent.com/document/product/866/17619
4. **免费额度是「共享资源包」口径且耗尽不自动兜底**，必须在控制台一次性开通后付费或买预付费包，否则调用会中断。链接：https://cloud.tencent.com/document/product/866/17619
5. **私有化支持已由官方 FAQ 正面确认，但只有 TI-OCR 一条公开路径且全流程商务；官方明确不支持移动端离线操作**。链接：https://cloud.tencent.com/document/product/866/33511

## C.3 百度智能云 OCR

| 维度 | 结论 | 官方链接 |
|---|---|---|
| 坐标 | `words_result[].location` 在官方参数表中标注为「**是**」（必返回），格式为 **left/top/width/height 外接矩形**；四点/多边形需显式传 `vertexes_location=true`（官方注明「不支持单字位置」），此时返回 `vertexes_location`、`finegrained_vertexes_location`、`min_finegrained_vertexes_location`；字符级 `recognize_granularity=small` 返回 `chars[]`（每字 `char` + 自己的 `location`），配 `char_probability=true` 得 `char_prob` 单字置信度 | https://cloud.baidu.com/doc/OCR/s/Vkibizy8i ；官方 PDF 文档 https://bce-cdn.bj.bcebos.com/p3m/pdf/ai-cloud-share/online/OCR/OCR.pdf |
| 后付费价格（元/次，月调用量阶梯） | 高精度版：**0.030（≤5万）/ 0.024 / 0.019 / 0.015 / 0.012 / 0.010（>100万）**；高精度含位置版：0.040 / 0.034 / 0.029 / 0.025 / 0.022 / 0.020；标准版：0.0050 / 0.0045 / 0.0040 / 0.0035 / 0.0030 / 0.0025 | https://cloud.baidu.com/doc/OCR/s/Jk3h7xtsd |
| 免费额度 | 高精度版：**个人认证 1,000 次/月、企业认证 2,000 次/月**；高精度含位置版：个人 500 / 企业 1,000 次/月。免费状态「不保证并发、超出免费额度不响应请求」；付费状态「保证 10 次并发」 | https://cloud.baidu.com/doc/OCR/s/Jk3h7xtsd |
| 预付费次数包（高精度版） | 1 万次 280 元 / 5 万次 1,350 元 / 10 万次 2,300 元 / 20 万次 3,600 元 / 50 万次 7,000 元 / 100 万次 11,000 元 / 500 万次 38,000 元；共享资源包（点数）：10 万点 330 元 / 50 万点 1,540 元 / 100 万点 2,570 元 / 500 万点 12,000 元 / 1,000 万点 18,000 元 / 5,000 万点 60,000 元 / 1 亿点 100,000 元（**高精度版单次抵扣 10 点、标准版 5 点**） | 官方 PDF 购买指南（同上 PDF） |
| 计费边界 | 官方说明「'调用次数'只包括成功调用，调用失败不计费也不抵扣次数包额度」 | https://cloud.baidu.com/doc/OCR/s/Jk3h7xtsd |
| QPS 叠加包 | 10 元/天/QPS、180 元/月/QPS，单接口上限 100 QPS | https://cloud.baidu.com/doc/OCR/s/Jk3h7xtsd |
| 私有化 | 支持「纯软件版（Docker 容器化，本地/专有云，适配 CPU/GPU 与国产化系统）」+「**一体机版**（CPU/GPU 软硬一体，开箱即用）」；可申请 30 天免费测试部署包；授权「根据 QPS 和使用期限」，**无公开报价** | https://cloud.baidu.com/product/OCR/private.html ；https://cloud.baidu.com/doc/OCR/s/9kio79qfv |
| 离线 SDK | **有公开价格**：单台设备授权「通用文字识别离线SDK授权」**3~1000 个：299 元/个**，永久授权、免费升级 | https://cloud.baidu.com/product/OCR/sdk.html |

**结论**

1. **必须返回坐标，但默认是矩形框而非四点**：`words_result[].location` 标注为「是」；四点需显式 `vertexes_location=true`（官方明确「不支持单字位置」）。链接：https://cloud.baidu.com/doc/OCR/s/Vkibizy8i
2. **字符级坐标是三家中最完整的**：`recognize_granularity=small` 返回 `chars[]`（每字独立 `location`），配 `char_probability=true` 还有单字置信度。链接：https://cloud.baidu.com/doc/OCR/s/Vkibizy8i
3. **高精度版后付费首档 0.030 元/次，是三家中最低**（阿里云 0.225、腾讯云 0.50）；免费额度也最高（企业 2,000 次/月）。链接：https://cloud.baidu.com/doc/OCR/s/Jk3h7xtsd
4. **「高精度含位置版」更贵且免费额度更低**（0.040 元/次起；个人 500/企业 1,000 次/月），而高精度版本身已默认返回矩形坐标 —— 只有确需四点/多边形时才应切换。链接：https://cloud.baidu.com/doc/OCR/s/Jk3h7xtsd
5. **私有化与离线是三家公开信息最完整的**：私有化给出「纯软件版（Docker 容器）」与「一体机版」两条产品线（价格需商务咨询）；**离线 SDK 有公开价格 299 元/设备**（3~1000 个档，永久授权），是三家中唯一在官网直接可见价格的私有化/端侧形态。链接：https://cloud.baidu.com/product/OCR/private.html ；https://cloud.baidu.com/product/OCR/sdk.html

---

# 不确定项清单（汇总）

## PaddleOCR

1. **`text_word` 的切分粒度**（中文按单字 / 英文按单词？）——官方文档字段表未定义，只能从源码与 3.2.0 release note「支持返回单文字坐标」推断。https://github.com/PaddlePaddle/PaddleX/blob/develop/paddlex/inference/pipelines/ocr/pipeline.py
2. **PP-StructureV3 的专用 CPU Docker 镜像名不确定**：官方文档只给出 GPU 镜像（`paddle:3.1.0-gpu-cuda11.8-cudnn8.9`），CPU 场景的官方标签未列出。https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.md
3. **非服务器 CPU（家用/虚拟化）的实际耗时官方无数据**：官方 CPU 数据基于 Intel Xeon Gold 6271C / 8350C。https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html
4. **是否存在覆盖全部 PP-OCR / PP-StructureV3 子模型的统一模型许可声明页：未找到**；仅逐个 HuggingFace 模型卡标注 `license: apache-2.0`。https://huggingface.co/PaddlePaddle/PP-OCRv5_server_det
5. **Docker Hub 上是否存在官方 `paddlepaddle/paddleocr` 仓库：API 返回 404，确认不存在**；官方镜像发布在百度云 CCR。若内网只能访问 Docker Hub，可用性需另行验证。https://hub.docker.com/r/paddlepaddle/paddle/tags
6. **PP-OCR 通用文字识别是否有独立第三方权威评测：不确定**。
7. **2.x 分支是否仍在维护：不确定**（最后 2.x Release 为 v2.10.0，2025-03-07）。https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v2.10.0

## 备选开源方案

8. **RapidOCR 逐模型许可/哈希**：README 引用的 `MODEL_LICENSES.md` 在仓库根目录不存在（404）。https://github.com/RapidAI/RapidOCR/blob/main/README.md
9. **RapidOCR 自身中文识别准确率**：官方模型列表页与 README 均无精度数字，只有上游 PaddleOCR 指标，不能等同于 RapidOCR 部署效果。https://rapidai.github.io/RapidOCRDocs/main/model_list/
10. **CnOCR 权重许可证**：仓库 LICENSE 覆盖代码，官方未声明自研与 PP-OCR 来源权重的条款。https://github.com/breezedeus/CnOCR/blob/master/LICENSE
11. **CnOCR 是否有词级/单字级坐标**：官方 usage 只写 `position`（行级 `(4,2)`）。https://github.com/breezedeus/CnOCR/blob/master/docs/usage.md
12. **CnOCR Pro / 会员专享模型的授权范围与商用条款**：官方只给购买链接与「会员专享」表述。https://github.com/breezedeus/CnOCR/blob/master/README.md
13. **EasyOCR 模型权重许可证**：官方 README / API 文档 / Model Hub 均未声明。https://github.com/JaidedAI/EasyOCR/blob/master/README.md
14. **EasyOCR 中文准确率与是否支持词/字级坐标**：官方无中文精度数字，`readtext`/`detect` 只返回文本框。https://www.jaided.ai/easyocr/documentation
15. **EasyOCR 后续发版计划**：仓库未归档，官方未给下一版本时间表。https://github.com/JaidedAI/EasyOCR
16. **Tesseract chi_sim 中文识别准确率**：官方 Benchmarks 只测速度、UNLV 为 1995 年英文测试、README 只声明 100+ 语言。https://tesseract-ocr.github.io/tessdoc/Benchmarks.html
17. **Tesseract 是否支持 GPU 加速**：官方 README / Installation / CLI 文档中均未找到 GPU 说明。https://tesseract-ocr.github.io/tessdoc/Installation.html
18. **chi_sim 上游训练语料许可链**：tessdata 自述 Apache-2.0 并指向 `tesseract-ocr/langdata`，而 langdata README 未单独声明语料许可；`tessdata_contrib`（用户贡献）明确未声明许可。https://github.com/tesseract-ocr/langdata
19. **pytesseract 发布日期口径不一致**：GitHub release published_at=2023-10-15，PyPI 文件上传=2024-08-16。https://pypi.org/project/pytesseract/
20. **`rapidocr-onnxruntime` 是否已官方弃用**：最新版 1.4.4（2025-01-17）明显落后于 `rapidocr` 3.x，但官方无「已弃用/请迁移」声明。https://pypi.org/project/rapidocr-onnxruntime/
21. **四方案的中文口碑均无第三方佐证**：本次未采集任何第三方 benchmark，所有「中文口碑」项在官方页面均无可靠数据。

## 云厂商

22. **阿里云 OCR 私有化价格、QPS 档位与交付周期不确定**：官方仅说明「支持专有云、混合云私有化部署，请联系我们」，无价格数字。https://help.aliyun.com/zh/ocr/support/faq-about-features
23. **阿里云「读光 OCR 私有化」是否存在独立产品页与报价：不确定**：`duguang.aliyun.com` 为 JS 动态渲染抓不到正文，官方文档中亦无独立计费页。https://duguang.aliyun.com/
24. **腾讯云在售页活动价与刊例价不一致**：产品页显示「通用识别类 API 0.011 元/次起」，而计费概述公示刊例价高精度版 0.50 元/次起，相差一个数量级，官方未说明对应接口与期限——**本报告成本口径一律采用刊例价**。https://cloud.tencent.com/product/generalocr ；https://cloud.tencent.com/document/product/866/17619
25. **腾讯云私有化部署价格与规格不确定**：计费概述只覆盖公共云 API；TI-OCR 流程无价格数字。https://cloud.tencent.com/document/product/1659/83073
26. **腾讯云是否有面向通用 OCR 的一体机商品：无法确认存在**；官方 FAQ 明确「不支持移动端离线操作」。https://cloud.tencent.com/document/product/866/33511
27. **百度智能云私有化报价、最小 QPS 起售与一体机配置价格不确定**：私有化页无价格数字，仅引导申请试用/商务咨询。https://cloud.baidu.com/product/OCR/private.html
28. **腾讯云后付费「100 万及以上」档单价不确定**：官网显示「联系商务」。https://cloud.tencent.com/document/product/866/17619
29. **阿里云高并发额外阶梯折扣不确定**：按量付费页仅写「月调用量超过 100 万的用户可联系商务获取价格折扣」。https://help.aliyun.com/zh/ocr/product-overview/pay-as-you-go
30. **百度价格数字来源为官方 PDF 文档**（HTML 文档站为 JS 动态渲染，`web_fetch` 抓不到正文）：该 PDF 由百度官方 CDN 提供，但可能存在版本滞后，采购前需以计费页与控制台实时价格为准。https://bce-cdn.bj.bcebos.com/p3m/pdf/ai-cloud-share/online/OCR/OCR.pdf
31. **三家价格与免费额度仅代表抓取时点（2026-09-20）的官方公示值**，随时可能调整。
