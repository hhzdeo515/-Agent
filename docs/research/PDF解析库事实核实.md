# PDF 解析库事实核实报告（基于官方页面实抓）

- 核实时间：2026-09-20（本机时间）
- 核实方式：使用 web_fetch / HTTP 请求实际打开下列 URL 并读取页面内容；每条结论后标注「我打开的是什么页面」
- 版本与许可证均取自实际打开的页面；凡页面中未明确写出、或指定 URL 无法访问的，一律标注「不确定」，不做推断
- 本报告只陈述事实，不含代码实现与选型建议

---

## A1. PyMuPDF（包名 pymupdf，旧名 fitz）

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| 最新版本号 | **1.28.2**（我打开 PyPI JSON API，读取 `info.version` 字段；同一数值也与文档站页脚「This documentation covers PyMuPDF 1.28.2」一致） | https://pypi.org/pypi/pymupdf/json ；https://pypi.org/project/PyMuPDF/ |
| PyPI 上的许可证字段（页面原文） | PyPI 元数据 License 字段原文：**"Dual Licensed - GNU AFFERO GPL 3.0 or Artifex Commercial License"**（我打开 PyPI JSON，读取 `info.license`；该包的 `license_expression` 字段为空，`license_files` 为空） | https://pypi.org/pypi/pymupdf/json |
| 仓库 LICENSE 文件 | **仓库根目录不存在 `LICENSE` 文件**：`https://github.com/pymupdf/PyMuPDF/blob/main/LICENSE` 对应 raw 地址返回 **HTTP 404**；实际许可证全文在仓库根 **`COPYING`**，我打开 `https://raw.githubusercontent.com/pymupdf/PyMuPDF/main/COPYING`（HTTP 200），首行为 "GNU AFFERO GENERAL PUBLIC LICENSE / Version 3, 19 November 2007"。PyPI 项目描述里的 License 徽章也指向 `.../blob/master/COPYING` | https://github.com/pymupdf/PyMuPDF/blob/main/COPYING ；https://raw.githubusercontent.com/pymupdf/PyMuPDF/main/COPYING |
| 官方文档对许可证的表述（英文原文） | 我打开官方文档 about 页「License and Copyright」小节，原文：**"PyMuPDF and MuPDF are now available under both, open-source AGPL and commercial license agreements."** 以及 **"If you determine you cannot meet the requirements of the AGPL, please contact Artifex for more information regarding a commercial license."** | https://pymupdf.readthedocs.io/en/latest/about.html#license-and-copyright |
| 是 AGPL-3.0 还是商业双授权 | 事实：**双授权**——开源分支为 AGPL-3.0（仓库 `COPYING` 为 AGPLv3 全文），另有 Artifex 商业许可。Artifex 授权页原文：**"Most of our products are dual-licensed under open source with the GNU AGPLv3 license ... or with commercial license agreements."** | https://artifex.com/licensing/ |
| Artifex 对 AGPL 义务的官方措辞（英文原文） | 我打开 Artifex 授权总页「GNU AGPLv3 Usage Restrictions」，原文：**"You cannot deploy our open-source as part of a server-based application or service, without disclosing your own application's full source code under AGPL to any users interacting with it."**；**"Usage of our open-source Community Edition must be limited to AGPL-compliant environments, where all AGPL requirements are fully respected."**；**"If you can't meet the requirements of the GNU AGPLv3 above, a commercial license is required."** | https://artifex.com/licensing/ |
| AGPL 触发点的许可证原文（§13 远程网络交互） | 我打开 GNU 官方 AGPL-3.0 文本页，§13 原文：**"Notwithstanding any other provision of this License, if you modify the Program, your modified version must prominently offer all users interacting with it remotely through a computer network (if your version supports such interaction) an opportunity to receive the Corresponding Source of your version..."** | https://www.gnu.org/licenses/agpl-3.0.html |
| AGPL 触发点的许可证原文（§0 convey 定义、§2 基本许可） | §0 原文：**"To 'convey' a work means any kind of propagation that enables other parties to make or receive copies. Mere interaction with a user through a computer network, with no transfer of a copy, is not conveying."**；§2 原文：**"You may make, run and propagate covered works that you do not convey, without conditions so long as your license otherwise remains in force."** | https://www.gnu.org/licenses/agpl-3.0.html |
| 「企业内网自用不对外分发」是否触发义务 | **不确定（属于法律解释问题，官方页面未就该场景作出明确说明）**。已核实的事实边界是：(1) AGPL 的对外分发义务绑定在 "convey"（向他人提供副本）上，§0 明确「仅通过网络与用户交互而不传输副本，不构成 conveying」；(2) §13 的义务以「修改了程序」且「版本支持通过网络远程交互」为前提，且指向与该版本远程交互的 users；(3) Artifex 授权页只给出「不得在未向交互用户披露自身源码的情况下，把开源版作为服务器端应用/服务部署」这类表述，并另在商业订阅许可说明中写有 "Annual reporting on volume of distribution (No report needed for internal usage)"（注意：这句属于**商业许可**的说明，不是 AGPL 的豁免条款）。上述页面均**没有**出现「企业内部自用、不对外分发即无需履行 AGPL 义务」的明确表述；是否触发取决于是否存在 conveying、是否修改、以及交互用户的范围，需法律判断 | https://www.gnu.org/licenses/agpl-3.0.html ；https://artifex.com/licensing/ ；https://pymupdf.readthedocs.io/en/latest/about.html#license-and-copyright |
| 是否返回 word 级 bbox | **是**。我打开官方文档 Appendix 1「Details on Text Extraction」，WORDS 小节原文：`Page.get_text("words")` 返回 `(x0, y0, x1, y1, "word", block_no, line_no, word_no)` 形式的列表，**"Where the first 4 items are the float coordinates of the words's bbox."**（示例输出 `(50.0, 88.175..., 78.732..., 103.289..., 'Some', 0, 0, 0)`） | https://pymupdf.readthedocs.io/en/latest/app1.html |
| 是否返回 char 级 bbox | **是**。同一 Appendix 1 的 RAWDICT（或 RAWJSON）小节原文：`Page.get_text("rawdict")` 是 DICT 的信息超集，把 span 里的 text 字符串替换为 `"chars": [{"origin": (...), "bbox": (...), "c": "S"}, ...]`，即逐字符 bbox；DICT 输出则给出 block / line / span 级 `bbox` | https://pymupdf.readthedocs.io/en/latest/app1.html |
| get_text 的 API 签名（官方 API 页） | 我打开 Page 类 API 页，原文签名为 `get_text(option, *, clip=None, flags=None, textpage=None, sort=False, delimiters=None)`，其中 option 覆盖 "text"/"blocks"/"words"/"dict"/"rawdict" 等输出格式 | https://pymupdf.readthedocs.io/en/latest/page.html#Page.get_text |
| 提取速度：官方 benchmark | **有官方自测数据**。我打开官方 about 页「Performance」小节（测试集为 8 个 PDF、共 7,031 页，"Text Extraction" 任务）给出：**PyMuPDF 8.01 秒 / XPDF 27.42 秒 / PyPDF2 101.64 秒 / PDFMiner 227.27 秒**；同页 Rendering 任务：PyMuPDF 367.04 秒 / XPDF 646 秒 / PDF2JPG 851.52 秒。官方另提供方法论页面（Appendix 4） | https://pymupdf.readthedocs.io/en/latest/about.html#performance ；https://pymupdf.readthedocs.io/en/latest/app4.html |
| 提取速度：不同输出格式的相对开销（官方自测） | 我打开 Appendix 1「Performance」小节，基线 1.00 为 TEXT：**WORDS 1.02、DICT 3.93、RAWDICT 4.50**；原文还称 **"as to speed, we are not aware of a faster (free) tool. Even the most detailed method, RAWDICT, processes all 1'310 pages of the Adobe PDF References in less than 5 seconds"**。注意：这是**厂商自测**（Artifex 自己发布），截至本次核实我未找到同口径的第三方独立基准 | https://pymupdf.readthedocs.io/en/latest/app1.html |
| 无文本 / 扫描件行为（官方描述） | 我打开 PyPI 项目描述（官方 README）FAQ 原文：**"scanned PDFs will always need OCR — text extraction on scans returns nothing"**；OCR 需另装 Tesseract（`page.get_textpage_ocr()`） | https://pypi.org/project/PyMuPDF/ |

---

## A2. pdfplumber

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| 最新版本号 | **0.11.10**（我打开 PyPI 项目页，页面标题与正文显示 "pdfplumber 0.11.10"；同为 PyPI JSON `info.version`） | https://pypi.org/project/pdfplumber/ |
| 许可证 | **MIT**。证据一：我打开 PyPI 项目页右下「License」字段，原文 **"MIT License"**，分类器含 "OSI Approved :: MIT License"；证据二：我打开仓库 `LICENSE.txt` 原文首行 **"The MIT License (MIT) / Copyright (c) 2015, Jeremy Singer-Vine"** | https://pypi.org/project/pdfplumber/ ；https://github.com/jsvine/pdfplumber/blob/stable/LICENSE.txt |
| 任务给定的官方文档 URL 是否可访问 | **不可访问**：我打开 `https://pdfplumber.readthedocs.io/en/latest/` 返回 **HTTP 404，页面正文为 "404 Project not found — The project you requested does not exist or may have been removed."**；`https://pdfplumber.readthedocs.io/` 与 `https://readthedocs.org/projects/pdfplumber/` 同样 404。该库的官方文档实际以 **GitHub README** 为准（PyPI 项目页内容即 README 镜像） | https://pdfplumber.readthedocs.io/en/latest/ （404）；https://github.com/jsvine/pdfplumber/blob/stable/README.md |
| chars 的坐标字段 | **是，逐字符坐标**。我打开官方 README「Objects / char properties」表，原文：`x0` = "Distance of left side of character from left side of page."；`x1` = "Distance of right side of character from left side of page."；`y0` = "Distance of bottom of character from bottom of page."；`y1` = "Distance of top of character from bottom of page."；另有 `top` / `bottom` / `doctop` / `width` / `height` / `matrix`（CTM）等字段 | https://github.com/jsvine/pdfplumber/blob/stable/README.md#char-properties ；https://pypi.org/project/pdfplumber/ |
| .extract_words() 的坐标 | **是**。官方 README 原文：`extract_words(...)` **"Returns a list of all word-looking things and their bounding boxes."**；另有 `.search()` 返回 "the bounding box coordinates, and the char objects themselves" | https://github.com/jsvine/pdfplumber/blob/stable/README.md |
| 与 PyMuPDF 的速度对比 | 只有**定性**官方表述，没有数字：我打开 pdfplumber 官方 README「Comparison to other libraries」，原文 **"pymupdf is substantially faster than pdfminer.six (and thus also pdfplumber) and can generate and modify PDFs, but the library requires installation of non-Python software (MuPDF)."** 我未找到 pdfplumber 官方或权威第三方给出的同测试集耗时数字 | https://github.com/jsvine/pdfplumber/blob/stable/README.md#comparison-to-other-libraries |
| 库定位（官方自述） | README 原文：**"Plumb a PDF for detailed information about each text character, rectangle, and line. Plus: Table extraction and visual debugging."**；**"Works best on machine-generated, rather than scanned, PDFs. Built on pdfminer.six"**；并明确列出**不提供**的能力：PDF generation、PDF modification、**OCR**、"Strong support for extracting tables from OCR'ed documents" | https://github.com/jsvine/pdfplumber/blob/stable/README.md ；https://pypi.org/project/pdfplumber/ |
| 底层 pdfminer.six 的版本与许可证 | **20260107 / MIT**（我打开 PyPI JSON，读取 `info.version` 与 `license_expression`；pdfplumber README 亦写明其基于 pdfminer.six，并标注 pdfminer.six 为 MIT） | https://pypi.org/pypi/pdfminer.six/json ；https://github.com/jsvine/pdfplumber/blob/stable/README.md#specific-comparisons |
| 有无权威速度对比数据 | **不确定**。除上述 pdfplumber 自己的定性表述与 PyMuPDF 官方自测（该自测未包含 pdfplumber，只包含 PDFMiner/PyPDF2/XPDF）之外，我未找到权威第三方对 pdfplumber 与 PyMuPDF 的同口径对比 | — |

---

## A3. Apache PDFBox

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| 最新版本号（Maven Central 元数据） | **3.0.8**。我打开 Maven Central 的 maven-metadata.xml，原文 `<latest>3.0.8</latest>`、`<release>3.0.8</release>`，`lastUpdated` 为 20260715095645 | https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox/maven-metadata.xml |
| 最新版本号（项目官网） | 官网 News 区：**"Apache PDFBox 3.0.8 released 2026-07-11"**；同时 2.0.x 维护分支仍在发版：**"Apache PDFBox 2.0.37 released 2026-07-15"** | https://pdfbox.apache.org/ |
| 许可证 | **Apache License 2.0**。我打开项目首页，原文 **"Apache PDFBox is published under the Apache License v2.0."**，页脚亦写 **"Licensed under the Apache License, Version 2.0"** | https://pdfbox.apache.org/ ；https://www.apache.org/licenses/LICENSE-2.0 |
| Java 原生 | **是（Java 库）**。项目首页自述原文：**"The Apache PDFBox ® library is an open source Java tool for working with PDF documents. This project allows creation of new PDF documents, manipulation of existing documents and the ability to extract content from documents. Apache PDFBox also includes several command-line utilities."** | https://pdfbox.apache.org/ |
| 在 Java 生态的定位（官方项目首页自述） | 同上一行原文；首页导航把项目定位为 "A Java PDF Library"，功能列表含文本提取（Text Extraction）、表单、签名、命令行工具等 | https://pdfbox.apache.org/ |
| 文字与坐标提取 API 的官方 javadoc 入口 | 官网首页的 Documentation 区给出的 javadoc 链接**指向 javadoc.io**：`https://javadoc.io/doc/org.apache.pdfbox/pdfbox/3.0.8/index.html` 与 `.../2.0.37/index.html`（我从 pdfbox.apache.org 首页 HTML 中提取到的 href 原文）。另外 apache.org 自身仍托管旧版 javadoc：`https://pdfbox.apache.org/docs/2.0.5/javadocs/`（HTTP 200）；而 `https://pdfbox.apache.org/docs/3.0.0/javadocs/...` 返回 404 | https://pdfbox.apache.org/ ；https://javadoc.io/doc/org.apache.pdfbox/pdfbox/3.0.8/index.html ；https://pdfbox.apache.org/docs/2.0.5/javadocs/ |
| TextPosition 提供哪些坐标（javadoc 原文） | 我打开 pdfbox 3.0.8 的 TextPosition javadoc，逐条原文：**`getXDirAdj()`** — "This will get the text direction adjusted x position of the character."；**`getYDirAdj()`** — "This will get the y position of the text, adjusted so that 0,0 is upper left and it is adjusted based on the text direction."；**`getWidthDirAdj()`** — "This will get the width of the string when text direction adjusted coordinates are used."；**`getHeightDir()`** — "This will get the maximum height of all characters in this string."；另有 `getUnicode()`、`getFontSizeInPt()`、`getXScale()`/`getYScale()`、`getWidth()`/`getHeight()`（页面旋转调整坐标）等 | https://javadoc.io/static/org.apache.pdfbox/pdfbox/3.0.8/org/apache/pdfbox/text/TextPosition.html ；https://pdfbox.apache.org/docs/2.0.5/javadocs/org/apache/pdfbox/text/TextPosition.html |
| 是否有 `writeString(String, List<TextPosition>)` 回调 | **有**。我打开 pdfbox 3.0.8 的 PDFTextStripper javadoc，原文：**`protected void writeString(String text, List<TextPosition> textPositions) throws IOException` — "Write a Java string to the output stream."**；同时存在 `protected void writeString(String text)`；另有 `setSortByPosition(boolean)`（"The order of the text tokens in a PDF file may not be in the same as they appear visually on the screen..."） | https://javadoc.io/static/org.apache.pdfbox/pdfbox/3.0.8/org/apache/pdfbox/text/PDFTextStripper.html ；https://pdfbox.apache.org/docs/2.0.5/javadocs/org/apache/pdfbox/text/PDFTextStripper.html |

---

## A4. pypdf

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| 最新版本号 | **6.19.0**（我打开 PyPI JSON，读取 `info.version`） | https://pypi.org/pypi/pypdf/json ；https://pypi.org/project/pypdf/ |
| 许可证 | **BSD-3-Clause**。证据一：我打开 PyPI JSON，`license_expression` 字段原文 **"BSD-3-Clause"**，`license_files` 为 `LICENSE`（同一包在 PyPI 项目页顶部 "License" 区也显示该表达式）；证据二：我打开仓库 LICENSE 文件，为三条款 BSD 文本，第三条原文 **"The name of the author may not be used to endorse or promote products derived from this software without specific prior written permission."** | https://pypi.org/pypi/pypdf/json ；https://github.com/py-pdf/pypdf/blob/main/LICENSE |
| extract_text 是否给坐标 | **`extract_text()` 本身返回纯文本（str），官方文档未把 bbox 列为返回值**；页面级坐标需要走 `visitor_text` / `visitor_operand_before` 回调自行计算 | https://pypdf.readthedocs.io/en/stable/user/extract-text.html |
| visitor_text / 变换矩阵 | **提供变换矩阵**。我打开官方 "Extracting Text from a PDF" 文档，原文：`visitor_text` 有五个参数 **"text: the current text (as long as possible, can be up to a full line) / user_matrix: current matrix to move from user coordinate space (also known as CTM) / tm_matrix: current matrix from text coordinate space / font_dictionary: full font dictionary / font_size: the size (in text coordinate space)"**；并说明 "The matrix stores six parameters. The first four provide the rotation/scaling matrix, and the last two provide the translation (horizontal/vertical). It is recommended to use the user_matrix as it takes into account all transformations."；`visitor_operand_before` 提供 "operator, operand-arguments, current transformation matrix, and text matrix" | https://pypdf.readthedocs.io/en/stable/user/extract-text.html |
| 官方是否明确声明「不提供 bbox」 | **不确定**。我在本次打开的 pypdf 官方文档页中**没有找到**「pypdf 不提供 bbox / 不支持文字坐标」这类明确声明的原文；只能核实到：(1) `extract_text()` 的返回值为文本；(2) 坐标信息需通过 visitor 回调获得的矩阵自行换算；(3) 官方给出 Caveat 原文 **"In complicated documents, the calculated positions may be difficult to determine (if you move from multiple forms to page user space, for example)."**。是否存在对应的官方 issue 声明，本次未核实 | https://pypdf.readthedocs.io/en/stable/user/extract-text.html |
| 其他官方说明（文本提取模式与资源开销） | 文档记录了 `extract_text(extraction_mode="layout", layout_mode_strip_rotated=False)` 等参数；并有内存提示原文 **"Extracting the text of a page requires parsing its whole content stream. This can require quite a lot of memory - we have seen 10 GB RAM being required for an uncompressed content stream..."** | https://pypdf.readthedocs.io/en/stable/user/extract-text.html |

---

## A5. 扫描版（图片型）PDF 判定

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| PyMuPDF：文本层长度 | `Page.get_text()` 支持以 "text" 等格式取文本；官方 README FAQ 明确：**"scanned PDFs will always need OCR — text extraction on scans returns nothing"**（即扫描件文本提取返回空，可据此判断无文本层）。`get_text` 签名与格式见 Page API 页 | https://pypi.org/project/PyMuPDF/ ；https://pymupdf.readthedocs.io/en/latest/page.html#Page.get_text |
| PyMuPDF：图片枚举 | `Page.get_images()` — 官方 API 页原文："PDF only: get list of referenced images"，返回被引用的图片列表。**官方注意**："Be aware that `Page.get_images()` may contain 'dead' entries i.e. images, which the page does not display."；官方 FAQ 另说明 `get_images` **只列出内嵌位图对象、不包含矢量图形**（"Charts and diagrams created by tools like matplotlib, Excel, or R are typically rendered as vector graphics ... `get_images` only lists embedded raster image objects and will not detect vector graphics."） | https://pymupdf.readthedocs.io/en/latest/page.html#Page.get_images ；https://pypi.org/project/PyMuPDF/ |
| PyMuPDF：图片位置信息 | `Page.get_image_info(hashes=False, xrefs=False)` — 官方 API 页原文：**"Return a list of meta information dictionaries for all images displayed by the page. This works for all document types."**（返回含 bbox 的字典列表，可避免 get_images 的 "dead" 条目问题） | https://pymupdf.readthedocs.io/en/latest/page.html#Page.get_image_info |
| PyMuPDF：dict/blocks 中的图片块 | 官方 Appendix 1 原文：**"A text page consists of blocks (= roughly paragraphs). A block consists of either lines and their characters, or an image."**；BLOCKS 小节说明 "Each image appears as a block with one text line, which contains the image's metadata"，DICT 输出 "provides image content and position detail (bbox – boundary boxes in pixel units) for every block, line and span"（文本块示例中 `"type": 0`）。**图片块对应的具体数值常量在我打开的页面中未见字面说明 → 不确定** | https://pymupdf.readthedocs.io/en/latest/app1.html |
| PyMuPDF：OCR 能力（判定后处理） | `Page.get_textpage_ocr()` 官方 API 页列出；官方 README 说明 OCR 依赖外部安装的 Tesseract 及其语言数据（"Requires Tesseract installed and on PATH"） | https://pymupdf.readthedocs.io/en/latest/page.html ；https://pypi.org/project/PyMuPDF/ |
| pdfplumber：.chars / .images 属性 | 官方 README「Objects」小节原文：`.chars` "each representing a single text character"、`.images` "each representing an image"，且 "Each object is represented as a simple Python dict"；`.images` 的 image 对象含 `x0/x1/y0/y1/top/bottom/doctop/srcsize/colorspace/bits/stream/imagemask/name` 等字段。README 另注明：**"Although the positioning and characteristics of image objects are available via pdfplumber, this library does not provide direct support for reconstructing image content."**；库定位写明 **"Works best on machine-generated, rather than scanned, PDFs"**，且**不提供 OCR** | https://github.com/jsvine/pdfplumber/blob/stable/README.md#objects ；https://pypi.org/project/pdfplumber/ |
| OCRmyPDF：`--skip-text` / `--force-ocr` / `--redo-ocr` 的官方语义 | 我打开官方 Advanced features 页「OCR processing mode」，原文表格：**default（无标志）= "Error if text is found"**；**force = "Rasterize all content and run OCR"（legacy equivalent `--force-ocr`）**；**skip = "Skip pages with existing text"（legacy equivalent `--skip-text`）**；**redo = "Re-OCR pages, stripping old OCR layer"（legacy equivalent `--redo-ocr`）**。同页说明："If a page in a PDF seems to have text, by default OCRmyPDF will exit without modifying the PDF. This is to ensure that PDFs that were previously OCRed or were 'born digital' rather than scanned are not processed."；`--mode skip` 的页面会被直接复制到输出；`--mode force` 会把所有页面栅格化（丢弃隐藏 OCR 文本、拍平表单）；另有 `--mode strip` 仅移除不可见文本层（PDF text render mode 3）。v17.0.0 起 `--mode/-m` 统一这些行为，旧标志仍作为别名 | https://ocrmypdf.readthedocs.io/en/latest/advanced.html ；https://ocrmypdf.readthedocs.io/en/latest/cookbook.html |
| OCRmyPDF 版本与许可证（旁证） | **17.12.1 / MPL-2.0**（我打开 PyPI JSON，读取 `info.version` 与 `license_expression`） | https://pypi.org/pypi/ocrmypdf/json |
| 是否存在成熟库专门做「PDF 是否为扫描件」判定 | 本次核实到的官方页面中，**没有**任何库把「判断 PDF 是否为扫描件」列为一个官方功能或独立 API：PyMuPDF 与 pdfplumber 只提供「文本提取结果 / 图片对象 / 文本层是否存在」这些原材料；OCRmyPDF 的 `--skip-text`/`--force-ocr` 是**处理策略**（其默认行为 "Error if text is found" 依赖内部的「页面是否似有文本」判断，但官方文档未把该判断作为公开的扫描件检测 API 发布）。**是否存在其他成熟专门库：本次未核实，不确定** | https://ocrmypdf.readthedocs.io/en/latest/advanced.html |
| 常见工程阈值（如"文本长度小于 N 即判为扫描件""图片面积占比超过 X%"） | **不确定，社区经验值**。本次打开的所有官方页面（PyMuPDF docs、pdfplumber README、OCRmyPDF docs）均**未出现**任何具体的字符数阈值或图片面积占比阈值 | — |

---

## 不确定项清单（本次未能从官方页面确认的事实）

1. **PyMuPDF「企业内网自用、不对外分发」是否触发 AGPL 义务**：官方页面（Artifex 授权页、PyMuPDF about 页、GNU AGPL 正文）均无该场景的明确条款说明；只能确认 AGPL 的触发结构与原文措辞（§0 conveying 定义、§2 基本许可、§13 远程网络交互），结论需法律判断。
2. **Artifex 商业许可中 "No report needed for internal usage" 是否也适用于 AGPL 使用者**：该句出现在 Artifex 授权页的 **Subscription License（商业许可）**说明中，不是 AGPL 条款；是否有针对 AGPL 使用者的类似豁免，页面未说明。
3. **仓库 LICENSE 文件位置**：`https://github.com/pymupdf/PyMuPDF/blob/main/LICENSE` 返回 404（任务给定的该 URL 不存在）；许可证全文在 `COPYING`，但仓库根另有 `LICENSE` 之类的其它文件是否存在，本次未逐一列举核实。
4. **pdfplumber 官方文档站**：任务给定的 `https://pdfplumber.readthedocs.io/en/latest/` 返回 HTTP 404 "Project not found"，`readthedocs.org/projects/pdfplumber/` 同样 404；因此 `.chars` / `.extract_words()` 的字段说明我取自 **GitHub README（官方仓库）与 PyPI 项目页（README 镜像）**，而非 readthedocs。
5. **pdfplumber 与 PyMuPDF 的权威速度对比数字**：仅有 pdfplumber README 的定性表述 "pymupdf is substantially faster than pdfminer.six (and thus also pdfplumber)"；无同测试集的权威量化数据。PyMuPDF 官方 benchmark 未包含 pdfplumber。
6. **PyMuPDF 官方 benchmark 的第三方独立性**：8.01s vs 227.27s 等数字来自 Artifex 自测（about 页 Performance 与 Appendix 4 方法论），本次未找到独立第三方复现。
7. **pypdf 是否官方明确声明「不提供 bbox」**：在打开的 pypdf 官方文档页中未找到该表述；仅能确认 `extract_text()` 返回文本、坐标需经 `visitor_text` 的 `user_matrix`/`tm_matrix` 自行换算，以及官方 Caveat "the calculated positions may be difficult to determine"。相关官方 issue 原话本次未核实。
8. **PDFBox 3.0.8 的 javadoc 官方托管位置**：apache.org 自身对 3.x 的 `/docs/3.0.0/javadocs/` 返回 404，官网首页给出的 3.0.8/2.0.37 javadoc 链接**指向 javadoc.io**；apache.org 上可访问的自身托管 javadoc 我只验证到 2.0.5。
9. **PyMuPDF 图片块（image block）的数值型 type 常量**：官方页面明确写了「一个 block 要么是文本行、要么是图片」，并示例了文本块 `"type": 0`，但**未在打开的页面中找到图片块的数值常量字面说明**（"type": 1 未获官方页面确认）。
10. **是否存在专门判定「PDF 是否为扫描件」的成熟库**：本次只核实了 PyMuPDF / pdfplumber / OCRmyPDF 三个官方来源，未做穷尽检索。
11. **扫描件判定的常见工程阈值**：无任何官方来源，属社区经验值，不确定。

---

## 本次实际打开的页面清单（证据来源）

| 用途 | URL | 结果 |
| --- | --- | --- |
| PyMuPDF 版本与 License 字段 | https://pypi.org/pypi/pymupdf/json | HTTP 200（读取 info.version=1.28.2、info.license） |
| PyMuPDF 项目页/README | https://pypi.org/project/PyMuPDF/ | HTTP 200 |
| PyMuPDF 许可证全文 | https://raw.githubusercontent.com/pymupdf/PyMuPDF/main/COPYING | HTTP 200（AGPLv3 全文） |
| PyMuPDF LICENSE 文件 | https://raw.githubusercontent.com/pymupdf/PyMuPDF/main/LICENSE | **HTTP 404（不存在）** |
| PyMuPDF 许可证与性能 | https://pymupdf.readthedocs.io/en/latest/about.html | HTTP 200 |
| PyMuPDF 文本提取细节（bbox） | https://pymupdf.readthedocs.io/en/latest/app1.html | HTTP 200 |
| PyMuPDF Page API | https://pymupdf.readthedocs.io/en/latest/page.html | HTTP 200 |
| Artifex 授权页 | https://artifex.com/licensing/ | HTTP 200 |
| Artifex 的 AGPLv3 全文页 | https://artifex.com/licensing/gnu-agpl-v3 | HTTP 200 |
| GNU AGPL-3.0 正文 | https://www.gnu.org/licenses/agpl-3.0.html | HTTP 200 |
| pdfplumber 版本与 MIT | https://pypi.org/project/pdfplumber/ | HTTP 200 |
| pdfplumber MIT 原文 | https://raw.githubusercontent.com/jsvine/pdfplumber/stable/LICENSE.txt | HTTP 200 |
| pdfplumber 官方文档（任务给定 URL） | https://pdfplumber.readthedocs.io/en/latest/ | **HTTP 404（Project not found）** |
| pdfplumber README（chars/words/对比） | https://raw.githubusercontent.com/jsvine/pdfplumber/stable/README.md | HTTP 200 |
| pdfminer.six 版本与 MIT | https://pypi.org/pypi/pdfminer.six/json | HTTP 200 |
| PDFBox 首页（版本、许可、定位） | https://pdfbox.apache.org/ | HTTP 200 |
| PDFBox Maven 元数据 | https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox/maven-metadata.xml | HTTP 200（latest=release=3.0.8） |
| PDFBox TextPosition javadoc（3.0.8） | https://javadoc.io/static/org.apache.pdfbox/pdfbox/3.0.8/org/apache/pdfbox/text/TextPosition.html | HTTP 200 |
| PDFBox PDFTextStripper javadoc（3.0.8） | https://javadoc.io/static/org.apache.pdfbox/pdfbox/3.0.8/org/apache/pdfbox/text/PDFTextStripper.html | HTTP 200 |
| PDFBox javadoc（apache.org 旧版） | https://pdfbox.apache.org/docs/2.0.5/javadocs/org/apache/pdfbox/text/TextPosition.html | HTTP 200 |
| pypdf 版本与 BSD-3-Clause | https://pypi.org/pypi/pypdf/json | HTTP 200 |
| pypdf LICENSE 原文 | https://raw.githubusercontent.com/py-pdf/pypdf/main/LICENSE | HTTP 200 |
| pypdf 文本提取文档 | https://pypdf.readthedocs.io/en/stable/user/extract-text.html | HTTP 200 |
| OCRmyPDF 文档首页 | https://ocrmypdf.readthedocs.io/en/latest/ | HTTP 200 |
| OCRmyPDF 处理模式（skip/force/redo） | https://ocrmypdf.readthedocs.io/en/latest/advanced.html | HTTP 200 |
| OCRmyPDF Cookbook | https://ocrmypdf.readthedocs.io/en/latest/cookbook.html | HTTP 200 |
| OCRmyPDF 版本与 MPL-2.0 | https://pypi.org/pypi/ocrmypdf/json | HTTP 200 |
