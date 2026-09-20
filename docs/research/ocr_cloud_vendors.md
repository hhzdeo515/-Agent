# 三家云厂商通用文字识别（OCR）官方事实核实

> 调研目的：为「广宣法务审核 Agent」的图片/视频文字解析环节选型，核实**文字框坐标返回格式**、**按量付费（后付费）价格量级与免费额度**、**私有化部署/离线 SDK 可得性**三项事实。
>
> 核实方式：2026 年通过 `web_fetch` / `web_search` 实际抓取官方文档页、官方计费页、官方产品页（百度部分官方文档站为 JS 动态渲染，改用百度智能云官方发布的 PDF 版文档 `bce-cdn.bj.bcebos.com/p3m/pdf/ai-cloud-share/online/OCR/OCR.pdf` 提取正文）。
>
> **所有价格数字均来自下方链接的官方页面原文，未做任何推算或补全；无法从官方页面确认的项目已在文末「不确定项清单」明确标注。**
>
> 注意：官网价格与免费额度随时可能调整（三家文档最近更新时间均在 2025-2026 年），商务采购前请以计费页实时数据为准。

---

## 一、阿里云文字识别（OCR / 读光）

### 1.1 关键接口与文档链接

| 项目 | 内容 | 官方链接 |
| --- | --- | --- |
| 通用文字识别（基础版）API | `RecognizeGeneral` | [API 文档](https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizegeneral) |
| 全文识别高精版 API | `RecognizeAdvanced` | [API 文档](https://help.aliyun.com/zh/ocr/developer-reference/api-ocr-api-2021-07-07-recognizeadvanced) |
| 产品计费（总览） | 扣费顺序、免费额度、计费模式 | [产品计费](https://help.aliyun.com/zh/ocr/product-overview/product-billing/) |
| 按量付费（后付费）阶梯价 | 元/次，自然月阶梯累加制 | [按量付费](https://help.aliyun.com/zh/ocr/product-overview/pay-as-you-go) |
| 免费额度明细 | 每 API 200 次/月 | [免费额度](https://help.aliyun.com/zh/ocr/product-overview/free-quota) |
| 预付费资源包价格 | 共享资源包 / 专用资源包 | [资源包](https://help.aliyun.com/zh/ocr/product-overview/resource-plans) |
| 私有化部署说明 | 支持专有云、混合云 | [产品功能常见问题](https://help.aliyun.com/zh/ocr/support/faq-about-features) |

### 1.2 结论表

| 维度 | 核实结论 | 依据（官方页面原文字段/表述） |
| --- | --- | --- |
| 是否返回文字框坐标 | **返回**，文字块级 + 可选单字级 | `RecognizeAdvanced` 返回参数含 `prism_wordsInfo`（文字块信息）与 `charInfo`（单字信息） |
| 文字块坐标格式 | **四点坐标（四边形，顺时针：左上、右上、右下、左下）** | `pos`："文字块的外矩形四个点的坐标按顺时针排列（左上、右上、右下、左下）"，元素为 `{x, y}` |
| 单字坐标格式 | **左上角 x, y, w, h**（需 `OutputCharInfo=true`） | `charInfo` 内 `x`=单字左上角横坐标、`y`=单字左上角纵坐标、`w`=单字宽度、`h`=单字高度 |
| 后付费单价（基础版） | 0.0825 元/次（≤1 万）→ 0.009 元/次（>100 万） | [按量付费](https://help.aliyun.com/zh/ocr/product-overview/pay-as-you-go) 阶梯表「通用文字识别基础版」 |
| 后付费单价（高精版） | 0.225 元/次（≤1 万）→ 0.036 元/次（>100 万） | 同上，阶梯表「通用文字识别高精版」 |
| 免费额度 | **每 API 200 次/月**，当月生效、过期作废 | [免费额度](https://help.aliyun.com/zh/ocr/product-overview/free-quota) |
| 默认并发 | 每 API 10 QPS，可购 QPS 叠加包扩容 | [产品计费](https://help.aliyun.com/zh/ocr/product-overview/product-billing/) |
| 私有化部署 | **支持**专有云、混合云 | 「印刷文字识别 OCR 支持专有云、混合云的私有化部署，您可联系我们沟通私有化部署相关合作」 |
| 私有化公开报价 | **无公开报价**，需商务联系 | 同上 + [联系我们](https://help.aliyun.com/zh/ocr/support/contact-us)（`ocr_support@list.alibaba-inc.com`、钉钉群 35208328） |
| 离线 SDK | 官网明确「**离线 SDK 现暂不提供支持**」 | 同上页面「OCR服务是否支持离线识别？」条目下的重要提示 |

### 1.3 结论要点（5 条）

1. **坐标返回能力满足「风险回标到图片区域」需求。** `RecognizeGeneral`（基础版）与 `RecognizeAdvanced`（高精版）均返回 `prism_wordsInfo` 数组，其中 `pos` 为文字块外接四边形的四个点（左上→右上→右下→左下，顺时针），配合顶层 `orgWidth` / `orgHeight`（原图宽高）与 `width` / `height`（算法矫正后宽高）即可把风险文字框回标到原图。
2. **同一响应中还有行、段、表格、图案等多级结构，适合做风险定位分层。** 高精版可通过 `Row=true` 取 `prism_rowsInfo`（行）、`Paragraph=true` 取 `prism_paragraphsInfo`（段落）、`OutputTable=true` 取 `prism_tablesInfo`（表格单元格 `pos` 同样为四角坐标）、`OutputFigure=true` 取 `figure`（印章/二维码/人脸等图案的 `x,y,w,h` 与 `points`）。
3. **单字级坐标需要显式开启。** 只有传 `OutputCharInfo=true` 才返回 `charInfo`，且其格式为「左上角 x,y + 宽 w + 高 h」，并非四点坐标——与文字块级的 `pos` 格式不同，接入时不要混用。
4. **后付费为自然月阶梯累加制，价格差异明显。** 基础版首档 0.0825 元/次，高精版首档 0.225 元/次（约为基础版 2.7 倍）；两者在月调用量 >100 万时分别降至 0.009 与 0.036 元/次。开通 OCR 服务会自动开通后付费且**不可关闭**，未超免费额度/资源包时不产生扣费。费用按**成功调用次数**计。
5. **私有化与离线能力要分开看。** 阿里云明确支持专有云/混合云私有化部署（无公开报价，需商务/邮件/钉钉沟通），但官方 FAQ 同时声明**离线 SDK 暂不提供支持**，因此「一体机/端侧离线」路线在阿里云当前公开口径下不可依赖。

---

## 二、腾讯云文字识别（OCR）

### 2.1 关键接口与文档链接

| 项目 | 内容 | 官方链接 |
| --- | --- | --- |
| 通用印刷体识别 API | `GeneralBasicOCR` | [API 文档](https://cloud.tencent.com/document/api/866/33526) |
| 通用文字识别（高精度版）API | `GeneralAccurateOCR` | [API 文档（服务端）](https://cloud.tencent.com/document/product/866/34937) |
| 数据结构（TextDetection / Coord / ItemCoord） | 返回结构定义 | [数据结构](https://cloud.tencent.com/document/api/866/33527) |
| 计费概述（含免费额度、预付费、后付费） | 官方计费页 | [计费概述](https://cloud.tencent.com/document/product/866/17619) |
| 计费错误码说明 | 哪些失败调用也计费 | [错误码计费说明](https://cloud.tencent.com/document/product/866/45470) |
| 评分/定价页 | 官方定价入口 | [文字识别定价](https://buy.cloud.tencent.com/price/ocr/) |
| 私有化部署（TI-OCR 训练平台） | 仅支持私有化部署 | [产品页](https://cloud.tencent.com/product/tiocr) / [快速入门](https://cloud.tencent.com/document/product/1659/83073) |
| 功能常见问题（离线 / 私有化 / 数据存储） | 官方 FAQ 原文 | [功能相关 FAQ](https://cloud.tencent.com/document/product/866/33511) |
| 通用文字识别子产品页 | 产品能力与定价入口 | [通用文字识别](https://cloud.tencent.com/product/generalocr) |

### 2.2 结论表

| 维度 | 核实结论 | 依据（官方页面原文字段/表述） |
| --- | --- | --- |
| 是否返回文字框坐标 | **返回**，文字行级 + 可选单字级 | 输出参数 `TextDetections`（Array of `TextDetection`） |
| 行坐标格式（四点） | **`Polygon`：四个顶点坐标** | `Polygon`（Array of `Coord`）"文本行坐标，以四个顶点坐标表示"；`Coord` 为 `{X, Y}` |
| 行坐标格式（矩形） | **`ItemPolygon`：X, Y, Width, Height** | 示例中 `ItemPolygon: {Height:26, Width:264, X:446, Y:93}`；同页说明为"文本行在旋转纠正之后的图像中的像素坐标" |
| 单字坐标格式 | **`WordCoordPoint.WordCoordinate`：单字四顶点坐标（左上起顺时针）**，需 `IsWords=true` | 数据结构页："单字在原图中的坐标，以四个顶点坐标表示，以左上角为起点，顺时针返回" |
| 段落编号 | `AdvancedInfo` 内 JSON 字符串含 `Parag.ParagNo` | 示例 `"AdvancedInfo": "{\"Parag\":{\"ParagNo\":1}}"` |
| 免费额度 | **1,000 次/月**（共享资源包，每月 1 日自动发放，当月有效） | [计费概述](https://cloud.tencent.com/document/product/866/17619)「免费额度」 |
| 后付费（通用印刷体识别） | 0.15 元/次（<1 万）/ 0.10 元/次（1-10 万）/ 0.06 元/次（10-100 万）/ 100 万及以上联系商务 | 同上「产品价格 → 后付费」表 |
| 后付费（通用文字识别高精度版） | **0.50 元/次（<1 万）/ 0.35 元/次（1-10 万）/ 0.20 元/次（10-100 万）/ 100 万及以上联系商务** | 同上「产品价格 → 后付费」表 |
| 预付费资源包（高精度版） | 1,000 次 400 元 / 1 万次 3,000 元 / 10 万次 15,000 元 / 100 万次 80,000 元 / 1,000 万次 500,000 元（有效期 1 年） | 同上「预付费」刊例价表 |
| QPS | 高精度版默认 10 次/秒；通用印刷体识别默认 20 次/秒 | 各 API 文档「默认接口请求频率限制」 |
| 私有化部署 | **支持**，TI-OCR 训练平台"目前仅支持私有化部署"；官方 FAQ 亦确认"文字识别支持私有化部署吗？**支持**，请[联系我们](https://cloud.tencent.com/about/connect)告知具体的使用场景和需求" | [快速入门](https://cloud.tencent.com/document/product/1659/83073)、[功能相关 FAQ](https://cloud.tencent.com/document/product/866/33511) |
| 离线 / 移动端 | 官方 FAQ："文字识别目前**不支持移动端离线操作**"；"文字识别只支持单张识别" | [功能相关 FAQ](https://cloud.tencent.com/document/product/866/33511) |
| 数据留存 | 官方 FAQ："文字识别服务**不储存用户图片信息**，仅对用户日志**脱敏保存三天**，用于排查客户问题" | [功能相关 FAQ](https://cloud.tencent.com/document/product/866/33511) |
| 私有化公开报价 | **无公开报价**，需申请+商务洽谈+签订合同 | 同上「购买指南 / 服务购买」流程；`无价格数字` |

### 2.3 结论要点（5 条）

1. **同一接口同时给出「四点多边形」和「矩形框」两套坐标，便于不同前端渲染。** `GeneralAccurateOCR` 与 `GeneralBasicOCR` 的 `TextDetections[].Polygon` 是四个顶点坐标（适合画倾斜文字框/贴合海报排版），`ItemPolygon` 是「左上角 X,Y + Width,Height」（适合快速做矩形高亮）。
2. **单字级坐标需要 `IsWords=true` 且格式仍是四点坐标。** `WordCoordPoint.WordCoordinate` 明确为"单字在原图中的坐标，以四个顶点坐标表示，以左上角为起点，顺时针返回"；官方同时注明 `IsWords` "仅 ConfigID 配置为 OCR 时支持"。注意这与阿里云的"单字 x,y,w,h"格式不同。
3. **后付费是高精度版的主要成本项，且最高档不公开。** 高精度版后付费首档 **0.50 元/次**，是通用印刷体（0.15 元/次）的约 3.3 倍，也明显高于阿里云高精版首档（0.225 元/次）与百度高精度版首档（0.030 元/次）。按 0.50 元/次估算，一次 1,000 张海报的批量初审仅 OCR 调用量就约 500 元（若走预付费 1 万次 3,000 元包，则约 0.30 元/次，更划算）。
4. **免费额度按"共享资源包"口径给，不看单个接口，且额度耗尽不会自动兜底。** 官方表述为"属于同一个共享资源包的接口共同享受 1,000 次/月的免费调用额度"，且"当您的免费资源包耗尽时，服务将面临不可用风险"——必须在控制台开通后付费或买预付费包，否则调用会中断，这一点与阿里云"开通服务即自动开通后付费且不可关闭"的行为不同，接入前需先在控制台做一次性配置。另一条与合规相关的官方口径：文字识别服务**不储存用户图片信息，仅对用户日志脱敏保存三天**（用于排查问题），这对广宣物料这类含未发布产品信息的材料是利好。
5. **私有化支持已由官方 FAQ 正面确认，但只有 TI-OCR 训练平台这一条公开路径，且全流程走商务。** [功能相关 FAQ](https://cloud.tencent.com/document/product/866/33511) 明确"文字识别支持私有化部署吗？**支持**"；[TI-OCR 快速入门](https://cloud.tencent.com/document/product/1659/83073) 给出"线上申请 → 申请审核 → 需求确认与商务洽谈 → 出具详细解决方案 → 服务购买（签订合同）→ 腾讯云团队线下部署 → 服务验收"的流程，产品页亦强调"专业团队为您提供安全无忧的私有化部署服务"。**但官网未公示任何私有化价格或套餐规格。** 同时官方 FAQ 明确"文字识别目前**不支持移动端离线操作**"，若需要端侧离线能力，腾讯云公开口径不支持。

---

## 三、百度智能云文字识别（OCR）

### 3.1 关键接口与文档链接

| 项目 | 内容 | 官方链接 |
| --- | --- | --- |
| 通用场景文字识别（文档栏目） | 含高精度版/标准含位置版 | [通用场景文字识别](https://cloud.baidu.com/doc/OCR/s/9k3h7xuv6) |
| 通用文字识别（高精度版）API | `accurate_basic` | [接口说明](https://cloud.baidu.com/doc/OCR/s/Vkibizy8i) |
| 官方 PDF 版完整文档（含 API 参数表与价格表） | 本次实际提取正文的来源 | [OCR.pdf](https://bce-cdn.bj.bcebos.com/p3m/pdf/ai-cloud-share/online/OCR/OCR.pdf?timeStamp=1748542835839) |
| 计费概述 | 免费额度、预付费、后付费、QPS 叠加包、商务咨询 | [计费概述](https://cloud.baidu.com/doc/OCR/s/Jk3h7xtsd) |
| 计费问题 FAQ | 价格获取方式 | [计费问题](https://cloud.baidu.com/doc/OCR/s/kkio8y3sr) |
| 私有化部署方案 | 纯软件版（容器）+ 一体机版 | [文字识别私有化部署方案](https://cloud.baidu.com/product/OCR/private.html) |
| 私有化产品文档 | 部署与授权说明 | [私有化部署文档](https://cloud.baidu.com/doc/OCR/s/9kio79qfv) |
| 离线 SDK | 单台/批量设备授权，含公开价格 | [文字识别离线SDK](https://cloud.baidu.com/product/OCR/sdk.html) |

### 3.2 结论表

| 维度 | 核实结论 | 依据（官方页面原文字段/表述） |
| --- | --- | --- |
| 是否返回文字框坐标 | **返回**，`words_result` 每行含 `location` | `words_result` → `+ location` 标注为「**是**」（必返回） |
| 默认坐标格式 | **左上角 left/top + width/height（外接矩形）** | `left`=左上顶点水平坐标、`top`=左上顶点垂直坐标、`width`=宽度、`height`=高度（坐标 0 点为左上角） |
| 字符级坐标 | **支持**，需 `recognize_granularity=small` 与 `char_probability=true` 配合 | `chars[].char` / `chars[].location`（同为 left/top/width/height）/ `char_prob` |
| 四点/多边形坐标 | **需显式传 `vertexes_location=true`**，此时额外返回 `vertexes_location`、`finegrained_vertexes_location`、`min_finegrained_vertexes_location`（点坐标 x,y） | Acc 文档请求参数表：「vertexes_location | 否 | string | true/false | 是否返回文字外接多边形顶点位置，不支持单字位置。默认为 false」 |
| 后付费（高精度版） | **0.030 元/次（≤5 万）/ 0.024（5-10 万）/ 0.019（10-20 万）/ 0.015（20-50 万）/ 0.012（50-100 万）/ 0.010（>100 万）** | 官方 PDF 文档 购买指南→产品价格→通用文字识别（高精度版）→「按量后付费」表 |
| 后付费（高精度含位置版） | 0.040 / 0.034 / 0.029 / 0.025 / 0.022 / 0.020 元/次（同上阶梯口径） | 同上，高精度含位置版章节 |
| 后付费（标准版） | 0.0050 / 0.0045 / 0.0040 / 0.0035 / 0.0030 / 0.0025 元/次（同上阶梯口径） | 同上，通用文字识别（标准版）章节 |
| 预付费次数包（高精度版） | 1 万次 280 元 / 5 万次 1,350 元 / 10 万次 2,300 元 / 20 万次 3,600 元 / 50 万次 7,000 元 / 100 万次 11,000 元 / 500 万次 38,000 元（有效期 1 年） | 同上 |
| 共享资源包（点数包） | 10 万点 330 元 / 50 万点 1,540 元 / 100 万点 2,570 元 / 500 万点 12,000 元 / 1000 万点 18,000 元 / 5000 万点 60,000 元 / 1 亿点 100,000 元；**高精度版单次抵扣 10 点，标准版抵扣 5 点** | 官方 PDF 购买指南→产品价格→共享资源包 |
| 免费额度（高精度版） | **个人认证 1,000 次/月；企业认证 2,000 次/月** | 官方 PDF 免费测试资源表 + 高精度版价格章节 |
| 免费额度（高精度含位置版） | **个人认证 500 次/月；企业认证 1,000 次/月** | 同上 |
| QPS | 免费状态「不保证并发」（超出免费额度不响应请求）；付费状态「保证 10 次并发」；可购 QPS 叠加包（10 元/天/QPS、180 元/月/QPS），单接口上限 100 QPS | 官方 PDF 计费概述 |
| 私有化部署 | **支持**：纯软件版（Docker 容器化，本地/专有云，适配 CPU/GPU 与国产化系统）+ **一体机版**（软硬一体，开箱即用），可选 30 天免费测试部署包 | [私有化部署方案](https://cloud.baidu.com/product/OCR/private.html) |
| 私有化公开报价 | **无公开报价**；授权"根据 QPS 和使用期限进行授权"，需申请试用/商务咨询 | 同上（产品优势「授权灵活」+ 申请试用入口） |
| 离线 SDK 公开报价 | **有公开价格**：单台设备授权「通用文字识别离线SDK授权」**3~1000 个：299 元/个**，永久授权、免费升级 | [文字识别离线SDK](https://cloud.baidu.com/product/OCR/sdk.html) 产品价格区 |

### 3.3 结论要点（5 条）

1. **`words_result` 确实含 `location`，但默认是矩形框而非四点坐标。** `left/top/width/height` 中 `location` 字段在官方参数表中标注为「是」（每行必然返回），因此"通用文字识别是否返回坐标"这一问题的答案是**返回**；但若要四点/多边形，必须额外传 `vertexes_location=true`（官方明确"不支持单字位置"），这是三家格式差异最大的一处。
2. **字符级坐标是三家最完整的。** `recognize_granularity=small` 可返回 `chars[]`（每字 `char` + 自己的 `location`），配合 `char_probability=true` 还能拿到 `char_prob` 单字置信度。对于"找出海报中究竟是哪几个字构成绝对化用语"的场景，单字级框选能力有直接价值。
3. **三家中按量付费价格最低。** 高精度版首档 **0.030 元/次**，仅为阿里云高精版（0.225 元/次）的约 13%、腾讯云高精度版（0.50 元/次）的 6%。免费额度也最高（企业认证 2,000 次/月，高精度版）。若批量物料初审量大，百度在纯成本维度优势明显。
4. **但要注意「高精度含位置版」与免费额度的不对称。** 含位置版价格更高（0.040 起）、免费额度更低（个人 500 / 企业 1,000 次/月），且高精度版本身默认已返回矩形坐标——只有在确实需要四点/多边形时才应切换到含位置能力或开启 `vertexes_location`，否则等于多付费。
5. **私有化与离线是三家公开信息最完整的。** 私有化明确给出「纯软件版（Docker 容器）」与「一体机版（软硬一体开箱即用）」两条产品线，授权方式为"根据 QPS 和使用期限"（**价格仍需商务咨询**）；同时**离线 SDK 有公开价格 299 元/设备**（3~1000 个档位，永久授权），是三家中唯一能在官网直接看到价格数字的私有化/端侧形态。

---

## 四、三家横向对比（仅列已从官方页面核实的数字）

### 4.1 坐标返回格式

| 厂商 / 接口 | 文字行·块坐标 | 四点/多边形 | 单字（字符）级坐标 | 开启条件 |
| --- | --- | --- | --- | --- |
| 阿里云 `RecognizeGeneral` / `RecognizeAdvanced` | `prism_wordsInfo[].pos` | **默认即为四点**（左上、右上、右下、左下） | `charInfo[].x/y/w/h`（左上角+宽高） | 单字需 `OutputCharInfo=true` |
| 腾讯云 `GeneralAccurateOCR` / `GeneralBasicOCR` | `TextDetections[].ItemPolygon`（X,Y,Width,Height） | `TextDetections[].Polygon`（四顶点） | `WordCoordPoint.WordCoordinate`（**四顶点**，左上起顺时针） | 单字需 `IsWords=true` |
| 百度 `accurate_basic` / `general_basic` | `words_result[].location`（left,top,width,height） | `vertexes_location` / `finegrained_vertexes_location` | `chars[].location`（left,top,width,height）+ `char_prob` | 四点需 `vertexes_location=true`；单字需 `recognize_granularity=small` |

### 4.2 后付费首档单价与免费额度（官方页面原文数字）

| 厂商 | 标准/基础版 | 高精度版 | 免费额度 | 免费额度口径 |
| --- | --- | --- | --- | --- |
| 阿里云 | 0.0825 元/次 | 0.225 元/次 | 200 次/月 | 每 API 每月，当月生效过期作废 |
| 腾讯云 | 0.15 元/次 | 0.50 元/次 | 1,000 次/月 | 共享资源包，多接口共享，每月 1 日发放，当月有效 |
| 百度智能云 | 0.0050 元/次 | 0.030 元/次 | 个人 1,000 / 企业 2,000 次/月（高精度版） | 实名认证后自动发放，按接口按月 |

### 4.3 私有化 / 离线 / 一体机

| 厂商 | 私有化部署 | 一体机 | 离线 SDK | 公开报价 |
| --- | --- | --- | --- | --- |
| 阿里云 | 支持（专有云、混合云） | 官方页面未提及 | **暂不提供支持**（官方 FAQ 重要提示） | 无，需商务 |
| 腾讯云 | 支持（官方 FAQ 正面确认；公开路径为 TI-OCR 训练平台） | 官方页面未提及 | **不支持移动端离线**（官方 FAQ 明确） | 无，需申请+商务洽谈+签合同 |
| 百度智能云 | 支持（纯软件版 Docker） | **支持**（CPU/GPU 软硬一体） | **支持**，299 元/设备（3~1000 个档） | 私有化无公开报价；离线 SDK 有公开价格 |

---

## 五、不确定项清单

以下条目**无法从官方公开页面确认**，已在正文标注，切勿引用为确定结论：

1. **【不确定】阿里云 OCR 私有化部署的具体价格、QPS 档位与交付周期。**
   官方仅在 [产品功能常见问题](https://help.aliyun.com/zh/ocr/support/faq-about-features) 说明"支持专有云、混合云的私有化部署，您可联系我们沟通"，并给出 [联系方式](https://help.aliyun.com/zh/ocr/support/contact-us)（邮箱 `ocr_support@list.alibaba-inc.com`、钉钉群 35208328）。**无任何公开价格数字。**
   链接为：<https://help.aliyun.com/zh/ocr/support/faq-about-features>（计费口径见 <https://help.aliyun.com/zh/ocr/product-overview/product-billing/>）

2. **【不确定】阿里云「读光 OCR 私有化」是否存在独立产品页与报价。**
   读光官网 <https://duguang.aliyun.com/> 为 JS 动态渲染，抓取仅得到标题「读光」，无法取到正文与价格；官方文档中也未检索到名为"读光私有化"的独立计费页。**故不能确认"读光 OCR 私有化"是一个可单独报价的商品。**

3. **【不确定】腾讯云通用识别类 API 在售页面显示的活动价与官方刊例价不一致，实际成交价无法从文档页确认。**
   [通用文字识别子产品页](https://cloud.tencent.com/product/generalocr) 与 [OCR 产品页](https://cloud.tencent.com/product/ocr) 展示的是**活动价**「通用识别类API……**0.011 元/次起**」，而 [计费概述](https://cloud.tencent.com/document/product/866/17619) 公示的是**刊例价**（通用印刷体 0.15 元/次起、高精度版 0.50 元/次起）。两者相差一个数量级，官方文档未说明 0.011 元/次对应的具体接口、档位与活动期限。**故本报告的价格结论一律采用计费概述的刊例价，0.011 元/次不作为成本测算依据。**

4. **【不确定】腾讯云通用文字识别/高精度版的私有化部署价格与规格。**
   [计费概述](https://cloud.tencent.com/document/product/866/17619) 只覆盖公共云 API 的预付费/后付费，未提及私有化授权计费；[TI-OCR 训练平台快速入门](https://cloud.tencent.com/document/product/1659/83073) 仅给出"申请 → 审核 → 需求确认与商务洽谈 → 提供详细部署方案 → 签订合同"的流程，**全程无价格数字**。

5. **【不确定】腾讯云是否有面向通用 OCR 的一体机商品。**
   已核实的官方口径是**否定**离线能力：[功能相关 FAQ](https://cloud.tencent.com/document/product/866/33511) 明确"文字识别目前不支持移动端离线操作"。腾讯云 OCR 产品页（[国内站](https://cloud.tencent.com/product/ocr)、[通用文字识别子产品页](https://cloud.tencent.com/product/generalocr)、[国际站](https://www.tencentcloud.com/zh/products/ocr)）功能列表中均未列出离线 SDK 或一体机。**故「腾讯云一体机」无法确认存在；社区问答中的"支持离线部署"非官方文档，不作为结论。**

6. **【不确定】百度智能云私有化部署的具体报价、最小 QPS 起售与一体机配置价格。**
   [私有化部署方案页](https://cloud.baidu.com/product/OCR/private.html) 只说明"根据 QPS 和使用期限进行授权，可自由选择不同 QPS 配置"并引导"申请试用/免费使用/合作咨询"，**页面无价格数字**。同页显示可申请"30 天免费测试部署包"，但未给出正式报价。

7. **【不确定】腾讯云后付费「100 万及以上」档的实际单价。**
   [计费概述](https://cloud.tencent.com/document/product/866/17619) 后付费表中该档位显示为「**联系商务**」，官网未公示数字。

8. **【不确定】阿里云后付费是否对高并发调用有额外阶梯折扣。**
   [按量付费页](https://help.aliyun.com/zh/ocr/product-overview/pay-as-you-go) 仅写"月调用量超过 100 万的用户可联系商务获取价格折扣"，**未公示折扣后的数字**。

9. **【不确定】百度智能云官网当前是否仍以同一价格售卖。**
   本报告百度价格数字来自百度智能云发布的**官方 PDF 版文档**（`bce-cdn.bj.bcebos.com/p3m/pdf/.../OCR.pdf`，请求参数带 `timeStamp=1748542835839`），因为 HTML 文档站（`cloud.baidu.com/doc/OCR/...`）为 JS 动态渲染，`web_fetch` 只能取到导航壳、**抓不到正文**。该 PDF 由百度官方 CDN 提供、内容权威，但存在版本滞后于线上计费页的可能；采购前请以 [计费概述](https://cloud.baidu.com/doc/OCR/s/Jk3h7xtsd) 与控制台实时价格为准。

10. **【不确定】三家价格与免费额度的未来有效性。**
    三家官方文档最近更新时间为 2025-2026 年（腾讯云计费概述页显示最近更新时间 2026-09-16），价格与额度随时可能调整，本报告数字仅代表抓取时点的官方公示值。

---

## 六、对本项目的选型提示（基于以上事实的工程建议，非法律结论）

- **风险定位（反馈区框选原文）**：三家都能满足。若前端希望统一渲染逻辑，建议在适配层把三家坐标统一归一化为「四点坐标数组 + 图片宽高」的中间结构，再各自做一次转换：阿里云 `pos` 直接可用；腾讯云优先取 `Polygon`；百度默认取 `location` 转四点（或开启 `vertexes_location`）。
- **字符级框选（"哪几个字违规"）**：百度（`recognize_granularity=small`）与阿里云（`OutputCharInfo=true`）都能给单字框，腾讯云需 `IsWords=true`，但腾讯云单字框是四顶点、阿里云是 x/y/w/h，适配层需分别处理。
- **成本敏感度**：按已核实的后付费首档单价，百度 < 阿里云 < 腾讯云（高精度版约为 0.030 : 0.225 : 0.50 元/次）。批量初审建议优先采购预付费资源包（三家折扣均明显），并对"是否需要四点/单字坐标"做按需开启，避免为不需要的能力付费。
- **私有化/内网合规路线**：若项目后期要求物料不出内网，百度是唯一在官网同时给出私有化（软件/一体机）与离线 SDK 公开价格的厂商；阿里云支持专有云/混合云但离线 SDK 明确暂不支持；腾讯云私有化仅 TI-OCR 一条路径且全流程商务。**三者均需商务谈判，无自助下单的私有化报价页。**
