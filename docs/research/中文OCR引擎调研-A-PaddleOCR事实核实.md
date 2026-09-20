# 中文 OCR 引擎事实核实 · A. PaddleOCR

> 核查日期：2026-09-20（UTC+8）
> 核查方式：实际抓取 GitHub REST API（releases / 仓库元数据）、GitHub Raw（LICENSE、README、官方文档 Markdown 源文件）、PyPI JSON API、Docker Hub Registry API、PaddleOCR 官方文档站（www.paddleocr.ai）与 arXiv。
> 原则：所有版本号、许可证、坐标字段名均来自上述官方页面原文；官方页面未写明的标「不确定」；**本节不含选型建议、不含代码实现**。

## A.1 版本号

| 分支 | 最新版本 | 类型 | 发布/上传时间 | 证据链接 |
|---|---|---|---|---|
| 3.x（当前主线） | **3.7.0** | GitHub Release `v3.7.0` + PyPI `paddleocr 3.7.0` | GitHub published 2026-06-11T12:09:14Z；PyPI wheel 上传 2026-06-11T12:09:51Z | https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.7.0 ；https://pypi.org/project/paddleocr/ |
| 3.x（前一版） | 3.6.0 | GitHub Release + PyPI | 2026-05-28 | https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.6.0 |
| 2.x（旧分支） | **2.10.0** | GitHub Release `v2.10.0` | published 2025-03-07T07:03:56Z | https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v2.10.0 |
| 2.x（PyPI 上最后一个 2.x） | 2.9.1 | PyPI `paddleocr 2.9.1` | 上传 2024-10-22T05:58:00Z | https://pypi.org/project/paddleocr/ |
| PyPI `info.version`（最新） | 3.7.0 | PyPI JSON API 字段 | 抓取于 2026-09-20 | https://pypi.org/pypi/paddleocr/json |

关键结论：

1. **PyPI 上同时存在 2.x 与 3.x 两个分支的包，3.x 是当前主线**：`paddleocr` 最新版为 **3.7.0**；2.x 分支的最后一个 GitHub Release 是 **v2.10.0**（2025-03-07），而 2.x 在 PyPI 上最早上传的最后一版是 **2.9.1**（2024-10-22）。证据：https://github.com/PaddlePaddle/PaddleOCR/releases ；https://pypi.org/project/paddleocr/
2. **3.x 版本节奏很快**：3.0.0（2025-05-20）→ 3.1.0（2025-06-29）→ 3.2.0（2025-08-21）→ 3.3.0（2025-10-16）→ 3.4.0（2026-01-29）→ 3.5.0（2026-04-21）→ 3.6.0（2026-05-28）→ 3.7.0（2026-06-11）。证据：https://pypi.org/pypi/paddleocr/json （各版本文件上传时间）
3. **3.7.0 的核心变化是发布 PP-OCRv6**，官方 release note 写明三档模型：tiny（1.5M）/ small（7.7M）/ medium（34.5M）。证据：https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.7.0
4. **3.7.0 默认使用的模型是 PP-OCRv6**（官方使用教程代码注释「# 默认使用 PP-OCRv6 模型」）。证据：https://www.paddleocr.ai/latest/version3.x/pipeline_usage/OCR.html
5. 仓库整体仍活跃：GitHub API 返回 `archived=false`、`pushed_at=2026-09-16`、stars≈89,856。证据：https://github.com/PaddlePaddle/PaddleOCR

## A.2 许可证

| 对象 | 许可证 | 证据链接 |
|---|---|---|
| PaddleOCR 代码仓库 | **Apache-2.0**（LICENSE 文件首行为 “Apache License Version 2.0, January 2004”） | https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE |
| PaddleOCR README 声明 | 「本项目采用 Apache 2.0 许可证发布」 | https://github.com/PaddlePaddle/PaddleOCR/blob/main/readme/README_cn.md |
| PP-OCRv5 / PP-OCRv6 模型权重（HuggingFace 模型卡） | **`license: apache-2.0`** | https://huggingface.co/PaddlePaddle/PP-OCRv5_server_det ；https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det |
| PaddleX（PaddleOCR 3.x 推理/服务化底座） | **Apache-2.0**（GitHub API `license.spdx_id`） | https://github.com/PaddlePaddle/PaddleX |

关键结论：

1. **代码是 Apache-2.0，且模型权重在官方模型卡上也标注为 apache-2.0**，代码与权重许可在本轮核查中未发现不一致。证据：https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE ；https://huggingface.co/PaddlePaddle/PP-OCRv5_server_det
2. 官方**没有**单独的“模型许可证文件”说明 PP-OCR 模型与代码许可不同；仓库内除 Apache-2.0 LICENSE 外，本轮未找到针对模型的额外许可条款。证据：https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE
3. 维护者在 GitHub Discussion 中答复商用问题：「PaddleOCR 项目遵循 Apache 2.0 协议，可以商用，事实上很多商业软件的底层 OCR 模型均来自 PaddleOCR。」——**这是维护者口头答复，不是法律文件**。证据：https://github.com/PaddlePaddle/PaddleOCR/discussions/15986
4. 需要注意 PaddleOCR 3.x 的可选依赖会引入**其他许可**的第三方库（社区在 Discussion 中指出 PyMuPDF / pdf2docx 为 AGPL-3.0），这部分**不是** PaddleOCR 自身的许可，但会影响整体合规评估。证据：https://github.com/PaddlePaddle/PaddleOCR/discussions/16886
5. models 权重许可虽然模型卡写 apache-2.0，但**未找到覆盖全部 PP-OCR / PP-StructureV3 子模型的统一许可声明页面**，若逐模型审计需逐个查模型卡——列为不确定项。

## A.3 官方 Docker 镜像与部署方式

| 部署方式 | 官方地址/镜像名 | 说明 | 证据链接 |
|---|---|---|---|
| 官方镜像仓库（百度云 CCR） | `ccr-2vdh3abv-pub.cnc.bj.baidubce.com/paddlepaddle/paddle:3.0.0`（CPU）/ `...-gpu-cuda11.8-cudnn8.9-trt8.6`（GPU） | 官方安装文档「基于 Docker 安装飞桨」给出的 CPU/GPU 两套命令 | https://www.paddleocr.ai/latest/version3.x/paddlepaddle_installation.html |
| Docker Hub 同名镜像 | `paddlepaddle/paddle`（Docker Hub 官方组织账号） | Docker Hub API：tags 总数 433、stars=140、pulls≈1,331,702；`3.3.1` 标签为 `linux/amd64`、约 1.80 GB | https://hub.docker.com/r/paddlepaddle/paddle/tags |
| PaddleOCR-VL 官方镜像（含离线版） | `ccr-2vdh3abv-pub.cnc.bj.baidubce.com/paddlepaddle/paddleocr-vl:latest-nvidia-gpu`（约 8 GB）；离线版 `...:latest-nvidia-gpu-offline` | 官方文档写明「我们强烈推荐采用 Docker 镜像的方式」；**该镜像要求 NVIDIA GPU**，x64 CPU 用户官方要求走手动安装路径 | https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PaddleOCR-VL.md |
| 服务化部署（官方推荐路径） | PaddleX CLI：`paddlex --install serving` → `paddlex --serve --pipeline OCR` | 官方文档明确「PaddleOCR 推荐用户使用 PaddleX 进行服务化部署」；`--device` 默认「GPU 可用时用 GPU，否则用 CPU」；默认端口 8080 | https://www.paddleocr.ai/latest/version3.x/inference_deployment/serving/serving.html |
| 2.x 时代的部署方式 | hubserving / PaddleServing / PaddleHub | 2.x 文档仍在站内保留（`version2.x/legacy/`），3.x 官方文档顺序中**没有** hubserving 入口 | https://www.paddleocr.ai/latest/version2.x/legacy/paddle_server.html |

关键结论：

1. **有官方 Docker 镜像，但不在 Docker Hub 的“paddleocr”仓库名下**：Docker Hub 上 `paddlepaddle/paddleocr` 仓库**不存在**（Docker Hub API 返回 404），官方镜像位于百度云 CCR 域名 `ccr-2vdh3abv-pub.cnc.bj.baidubce.com`；Docker Hub 的 `paddlepaddle/paddle` 是框架镜像（同名同组织）。证据：https://hub.docker.com/r/paddlepaddle/paddle/tags ；https://www.paddleocr.ai/latest/version3.x/paddlepaddle_installation.html
2. **CPU 用户有官方镜像可用**：官方文档给出了不带 gpu 后缀的标签（CPU），因此内网无 GPU 时可用官方 CPU 镜像。证据：https://www.paddleocr.ai/latest/version3.x/paddlepaddle_installation.html
3. **离线内网部署官方有明确方案**：PaddleOCR-VL 提供 `...-offline` 镜像，并给出 `docker save` → 传输 → `docker load` 的离线流程（但该镜像需要 NVIDIA GPU）。证据：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PaddleOCR-VL.md
4. **3.x 的服务化部署官方推荐 PaddleX，而不是 hubserving**；`--device` 默认行为是「有 GPU 用 GPU，无 GPU 用 CPU」。证据：https://www.paddleocr.ai/latest/version3.x/inference_deployment/serving/serving.html
5. PP-StructureV3 的官方 benchmark 中出现的镜像是 `ccr-2vdh3abv-pub.cnc.bj.baidubce.com/paddlepaddle/paddle:3.1.0-gpu-cuda11.8-cudnn8.9`（GPU）；**官方未在 PP-StructureV3 文档中给出专用 CPU 镜像名**，CPU 场景需自行基于框架 CPU 镜像安装——列为不确定项。证据：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.md

## A.4 返回坐标的 API 形态

官方文档给出的 OCR 产线返回字段（`res` 字典）如下：

| 字段 | 含义（官方文档原文摘要） | 粒度 |
|---|---|---|
| `dt_polys` | 文本检测的多边形框列表，每个框由 4 个顶点坐标构成，数组 shape `(4, 2)`，dtype int16 | 行级（四点） |
| `dt_scores` | 文本检测框置信度列表 | 行级 |
| `rec_texts` | 文本识别结果列表（已按 `text_rec_score_thresh` 过滤） | 行级 |
| `rec_scores` | 文本识别置信度列表 | 行级 |
| `rec_polys` | 经置信度过滤后的文本框列表，格式同 `dt_polys` | 行级（四点） |
| `rec_boxes` | 检测框的矩形边界框数组，shape `(n,4)`，dtype int16，每行为 `[x_min, y_min, x_max, y_max]` | 行级（轴对齐矩形） |
| `text_word` / `text_word_region` / `text_word_boxes` | 需开启参数 `return_word_box=True` 才返回；`text_word_region` 为词/字四点框，`text_word_boxes` 为其矩形化结果 | 词级/单字级（可选） |

证据（字段定义表）：https://www.paddleocr.ai/latest/version3.x/pipeline_usage/OCR.html ；源文件：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/OCR.md

参数 `return_word_box` 官方定义：「是否返回识别结果的文字框坐标。如果不设置，将使用产线初始化的该参数值，默认初始化为 `False`。」证据：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/OCR.md

字段实现（源码，可用于确认字段真实存在）：`paddlex/inference/pipelines/ocr/pipeline.py` 中 `res["text_word"]`、`res["text_word_region"]`、`res["text_word_boxes"]` 的构造逻辑。证据：https://github.com/PaddlePaddle/PaddleX/blob/develop/paddlex/inference/pipelines/ocr/pipeline.py

单字坐标的支持说明：「PP-OCR 系列模型支持返回单文字坐标」列在 **PaddleOCR 3.2.0**（2025-08-21）的“其他升级”中。证据：https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.2.0 ；https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/update/update.md

关键结论：

1. **默认返回的是行级坐标**：`rec_polys`（四点）与 `rec_boxes`（`[x_min,y_min,x_max,y_max]`）与 `rec_texts`、`rec_scores` 一一对应，满足“每行文字 + 边界框”的需求。证据：https://www.paddleocr.ai/latest/version3.x/pipeline_usage/OCR.html
2. **词级/单字级坐标需要显式开启 `return_word_box=True`**（默认 False），开启后新增 `text_word` / `text_word_region` / `text_word_boxes`。证据：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/OCR.md
3. 官方文档对这两个层级的说明**不完整**：文档未在字段表中列出 `text_word*` 的完整结构定义，字段名需从源码确认（`pipeline.py`）。列为不确定项：`text_word` 是按“汉字单字”还是按“英文单词”切分，官方文档未逐字说明。证据：https://github.com/PaddlePaddle/PaddleX/blob/develop/paddlex/inference/pipelines/ocr/pipeline.py
4. **2.x 与 3.x 的 API 形态不同**：3.x 是 `PaddleOCR(...).predict()` 返回上述字典（`rec_texts` / `rec_polys` / `rec_boxes`），2.x 是 `ocr()` / `ocr.ocr()` 返回 `[[[box], (text, score)], ...]` 的旧结构。2.x 文档入口仍在站内：https://www.paddleocr.ai/latest/version2.x/legacy/index.html
5. **PP-StructureV3 返回比 OCR 产线更粗的版面坐标**：`layout_det_res.boxes[].coordinate` 是轴对齐矩形 `[x0,y0,x1,y1]`，并带 `label`（如 `text`、`doc_title`、`paragraph_title`、`figure_title`、`image`），可在同一份结果中看到 `overall_ocr_res` 与 OCR 字段并存。证据：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.md

## A.5 PP-StructureV3 版面分析能力

| 项目 | 事实 | 证据链接 |
|---|---|---|
| 名称/版本 | 产线名 **PP-StructureV3**，随 PaddleOCR **3.x** 发布（本页文档对应 3.7.x 主线；3.0.0 起即有） | https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html |
| 依赖分组 | 属于可选依赖组 `doc-parser`（文档解析：表格、公式、印章、图片等版面元素） | https://www.paddleocr.ai/latest/version3.x/installation.html |
| 版面元素类别 | 版面检测模型含 **20 类**：文档标题、段落标题、文本、页码、摘要、目录、参考文献、脚注、页眉、页脚、算法、公式、公式编号、图像、表格、图和表标题（图标题/表格标题/图表标题）、印章、图表、侧栏文本、参考文献内容；另有 **23 类**版本（增加页眉图像、页脚图像等） | https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.md |
| 能力范围 | 官方描述：版面区域检测、表格识别、公式识别、图表理解、**多栏阅读顺序恢复**、结果转 Markdown | https://www.paddleocr.ai/latest/version3.x/algorithm/PP-StructureV3/PP-StructureV3.html |
| 许可证 | 与主仓库一致 Apache-2.0（未见单独许可文件） | https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE |
| GPU 必要性 | 官方 FAQ：「PP-StructureV3 虽然更推荐在 GPU 环境下进行推理，但也支持在 CPU 上运行」；`device` 参数不设置时「优先使用本地的 GPU 0 号设备，如果没有，则使用 CPU 设备」 | https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html |
| 公开发表评测 | 官方给出与 MinerU / Marker / Mathpix / Docling / Gemini2.5-Pro 等在 OmniDocBench 上的 OverallEdit 对比表 | https://www.paddleocr.ai/latest/version3.x/algorithm/PP-StructureV3/PP-StructureV3.html |

关键结论：

1. **PP-StructureV3 是“产线”而不是独立软件包**，随 PaddleOCR 3.x 分发，需要安装 `doc-parser` 依赖组。证据：https://www.paddleocr.ai/latest/version3.x/installation.html
2. **它确实能区分标题、段落、图片区域、表格、印章、公式、图表等版面元素**（20 类标签），能直接支撑“广告海报/图片中区域级定位”的需求方向。证据：https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.md
3. **PP-StructureV3 可返回表格单元格坐标与文本坐标**（README 明确其与 PaddleOCR-VL 系列的差异在于“提供更细粒度的坐标信息，包括表格单元格坐标、文本坐标等”）。证据：https://github.com/PaddlePaddle/PaddleOCR/blob/main/readme/README_cn.md
4. **CPU 可跑但官方“更推荐 GPU”**；未设置 `device` 时会优先占用 GPU0，无 GPU 才回落 CPU。证据：https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html
5. PP-StructureV3 的官方精度对比表使用的是 **OmniDocBench 公开榜单数据**（论文：arXiv:2412.07626），属于官方自测 + 公开榜单引用，**不是第三方独立评测**。证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-StructureV3/PP-StructureV3.html ；https://arxiv.org/abs/2412.07626

## A.6 CPU 推理可行性与官方耗时数据

官方 PP-OCRv5 文档给出的端到端推理性能（200 张通用+文档图像，含读图与前后处理）：

| 硬件 | 配置 | 平均每图耗时 | 平均每秒预测字符数 | 峰值 RAM |
|---|---|---|---|---|
| **CPU**（Intel Xeon Gold 6271C） | PP-OCRv5_mobile（v5_mobile_det + v5_mobile_rec） | **1.75 s/图** | 371.82 | 2219.98 MB |
| **CPU**（同上） | PP-OCRv4_mobile | 1.37 s/图 | 444.27 | 2090.53 MB |
| **CPU**（同上） | PP-OCRv5_server | **4.34 s/图** | 149.98 | 4020.85 MB |
| **CPU**（同上） | PP-OCRv4_server | 5.42 s/图 | 115.20 | 4018.35 MB |
| GPU（Tesla V100） | v5_mobile | 0.62 s/图 | 1054.23 | 峰值 VRAM 4190 MB |
| GPU（Tesla V100） | v5_server | 0.74 s/图 | 878.84 | 峰值 VRAM 5402 MB |

证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html

PP-OCRv6 官方端到端速度表（官方 PP-OCRv6 简介页，200 张图像测试）：

| 硬件 | 推理后端 | PP-OCRv6_medium | PP-OCRv6_small | PP-OCRv6_tiny | PP-OCRv5_server |
|---|---|---|---|---|---|
| Intel Xeon 8350C | PaddlePaddle | 2.05 s | 0.79 s | 0.32 s | 2.04 s |
| Intel Xeon 8350C | OpenVINO | **1.40 s** | 0.59 s | **0.20 s** | 7.30 s |
| Intel Xeon 8350C | ONNX Runtime | 3.31 s | 0.61 s | 0.22 s | 6.36 s |
| Apple M4 | PaddlePaddle | 8.82 s | 3.07 s | 0.96 s | >10 s |
| NVIDIA A100 | PaddlePaddle | 0.29 s | 0.25 s | 0.13 s | 0.32 s |

（单位：秒/图；表格原文标注为“端到端推理速度（s/image）”，包含读图、前后处理、模型推理全流程）

官方口径摘要：「PP-OCRv6_medium 在所有平台上均匹配或优于 PP-OCRv5_server：A100 上快 1.1×（0.29s vs 0.32s），Intel Xeon OpenVINO 快 5.2×（1.40s vs 7.30s）。」

证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html

PP-StructureV3 官方 CPU 说明（FAQ 原文）：「PP-StructureV3 虽然更推荐在 GPU 环境下进行推理，但也支持在 CPU 上运行。得益于多种配置选项及对轻量级模型的充分优化，在仅有 CPU 环境时，用户可以参考 3.3 节选择轻量化配置进行推理。例如，**在 Intel 8350C CPU 上，每张图片的推理时间约为 3.74 秒**。」

证据：https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html

其他官方 CPU 优化说明：
- 「**CPU 推理速度优化：** 所有产线 CPU 推理默认开启 MKL-DNN」（3.0.1 release note）。证据：https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.0.1
- 高性能推理文档提供各模块「CPU 推理耗时（ms）[常规模式 / 高性能模式]」对照表（硬件：Intel Xeon Gold 6271C @ 2.60GHz；软件：paddlepaddle 3.0.0 / paddleocr 3.0.3）。证据：https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html
- PP-OCRv5 文档注明「PP-OCRv5 的识别模型使用了更大的字典，需要更长的推理时间，导致 PP-OCRv5 的推理速度慢于 PP-OCRv4」。证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html

关键结论：

1. **CPU 单图耗时官方数据在 0.2 秒 ~ 4.3 秒区间**，取决于模型档位与推理后端：PP-OCRv6_tiny + OpenVINO 约 0.20 s/图；PP-OCRv5_mobile 约 1.75 s/图；PP-OCRv5_server 约 4.34 s/图；PP-StructureV3 轻量配置约 3.74 s/图。全部为官方文档数据（非社区实测）。证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html ；https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html ；https://www.paddleocr.ai/latest/version3.x/pipeline_usage/PP-StructureV3.html
2. **官方给出的 CPU 加速路径是 OpenVINO / ONNX Runtime 后端**，PP-OCRv6 在 Intel Xeon 上 OpenVINO 比 Paddle 后端快约 1.46×（1.40 s vs 2.05 s），比 PP-OCRv5_server 快 5.2×。证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html
3. **CPU 内存占用约 2 GB（mobile 档）到 4 GB（server 档）峰值 RAM**；GPU 档峰值 VRAM 约 4.2~5.4 GB。证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html
4. **官方测试环境是服务器级 CPU（Intel Xeon Gold 6271C / 8350C）**，家用或虚拟机 CPU 上的实际耗时官方无数据——列为不确定项。证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html
5. 上述全部为**官方自测数据（官方口径）**，本轮核查未采信任何社区实测数据。

## A.7 中文识别准确率口碑（官方数据）

PP-OCRv5 官方指标（内部多场景评估集）：

| 维度 | PP-OCRv5_server | PP-OCRv4_server | 提升 |
|---|---|---|---|
| 文本检测平均（Hmean） | 0.827 | 0.662 | +16.5 个百分点 |
| 印刷中文检测 | 0.945 | 0.888 | +5.7 |
| 文本识别加权平均（准确率） | 0.8401 | 0.5735 | +26.66 |
| 印刷中文识别 | 0.9013 | 0.8486 | +5.27 |
| 手写中文识别 | 0.5807 | 0.3626 | +21.81 |

官方原文：「在内部多场景复杂评估集上，**PP-OCRv5 较 PP-OCRv4 端到端提升 13 个百分点**。」

证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html

PP-OCRv6 官方指标（官方内部多场景基准，15 类场景）：

- 文本识别：**PP-OCRv6_medium 加权平均准确率 83.2%**，相比 PP-OCRv5_server 提升 **5.1%**；印刷中文 91.5%、手写中文 62.1%、繁体 78.6%、古籍 72.4%、日文 90.5%、艺术字 71.2%、屏幕 82.5%。
- 文本检测：**PP-OCRv6_medium 平均 Hmean 86.2%**，相比 PP-OCRv5_server 提升 **4.6 个百分点**。
- 官方 release note：「medium 档相比 PP-OCRv5_server 检测精度提升 4.6%、识别精度提升 5.1%，以仅 34.5M 参数超越 Qwen3-VL-235B、GPT-5.5 等主流视觉语言大模型。」

证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html ；https://github.com/PaddlePaddle/PaddleOCR/releases/tag/v3.7.0

**权威第三方（论文）佐证**：PP-OCRv6 官方论文已发布在 arXiv（arXiv:2606.13108，2026-06-11 提交，CC BY 4.0），摘要称：「On our in-house benchmarks, PP-OCRv6_medium achieves 83.2% recognition accuracy and 86.2% detection Hmean, outperforming PP-OCRv5_server by +5.1% and +4.6% respectively while surpassing Qwen3-VL-235B, GPT-5.5, and Gemini-3.1-Pro with orders of magnitude fewer parameters. The tiny tier achieves 3.9× faster inference than PP-OCRv5_mobile on Intel Xeon CPU while maintaining comparable accuracy.」证据：https://arxiv.org/abs/2606.13108

关键结论：

1. **PP-OCRv5 相比 v4 的官方提升口径是“端到端 13 个百分点”**（内部多场景复杂评估集），分项上识别加权平均从 0.5735 提升到 0.8401。证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html
2. **PP-OCRv6_medium 相比 PP-OCRv5_server 检测 +4.6%、识别 +5.1%**，且有 arXiv 论文可引用（论文数据与官网一致）。证据：https://arxiv.org/abs/2606.13108
3. **这些数字全部来自官方自建评估集（in-house benchmarks），官方明确说明 v6 的评估集与 v5/v4 不同、不可直接与公开榜单横向比较**。证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html
4. **本轮核查未找到独立的第三方横向评测中文 OCR 准确率的权威来源**（如权威学术评测或第三方机构报告）；官方在 PP-StructureV3 页面引用了 OmniDocBench（arXiv:2412.07626）的公开数据，但 PP-OCR 通用文字识别的第三方评测**不确定**。证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-StructureV3/PP-StructureV3.html
5. 广告物料场景关注的艺术字、繁体、竖排等：官方 PP-OCRv5 识别指标含「艺术字 0.6397」「繁体中文 0.7472」「竖直文本 0.9314」（v5_server），PP-OCRv6 表中含「艺术字 71.2」——均来自官方内部评估集。证据：https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html

## A 节 · 不确定项清单

1. **`text_word` 的切分粒度（中文按单字 / 英文按单词？）官方文档未逐字说明**——仅能从源码字段与 3.2.0 release note「支持返回单文字坐标」推断；字段结构定义未进官方字段表。https://github.com/PaddlePaddle/PaddleX/blob/develop/paddlex/inference/pipelines/ocr/pipeline.py
2. **PP-StructureV3 的专用 CPU Docker 镜像名不确定**：官方文档只给出 GPU 镜像（`paddle:3.1.0-gpu-cuda11.8-cudnn8.9`），CPU 场景的官方镜像标签未在 PP-StructureV3 文档中列出。https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PP-StructureV3.md
3. **非服务器 CPU（家用/虚拟化环境）的实际耗时官方无数据**：官方 CPU 数据基于 Intel Xeon Gold 6271C / 8350C。https://www.paddleocr.ai/latest/version3.x/algorithm/PP-OCRv5/PP-OCRv5.html
4. **是否存在覆盖全部 PP-OCR / PP-StructureV3 子模型的统一模型许可声明页：未找到**；仅逐个 HuggingFace 模型卡标注 `license: apache-2.0`。https://huggingface.co/PaddlePaddle/PP-OCRv5_server_det
5. **Docker Hub 上是否存在官方 `paddlepaddle/paddleocr` 仓库：确认不存在（API 404）**；但官方镜像并非发布在 Docker Hub 而是百度云 CCR，若内网只能访问 Docker Hub，可用性需另行验证（本轮未在 Docker Hub 找到与 PaddleOCR 3.x 对应的官方镜像仓库）。
6. **PP-OCR 通用文字识别是否有独立第三方权威评测：不确定**（未找到可信第三方来源）。
7. **2.x 分支是否仍在维护：不确定**——最后一个 2.x Release 为 v2.10.0（2025-03-07），此后无 2.x release。
