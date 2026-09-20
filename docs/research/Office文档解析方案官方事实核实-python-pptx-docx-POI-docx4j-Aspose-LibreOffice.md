# Office 文档解析方案官方事实核实报告

**核实方式**：全部结论均来自本次调研中实际打开的官方页面（PyPI、GitHub API、Read the Docs 官方文档、Apache POI 官网与 Javadoc、Maven Central metadata、Microsoft Learn、Ecma International、LibreOffice 官方帮助）。每条事实后标注来源页面类型与官方 URL。未能在官方页面确认的内容统一列入最后一节「不确定项」，不做推断填充。

**范围声明**：本报告只陈述事实，不包含代码实现，也不包含选型建议。

---

## B1. python-pptx

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| PyPI 最新版本 | `1.0.2`（页面标题 `python-pptx 1.0.2`，Latest release） | https://pypi.org/project/python-pptx/ |
| 最新版发布时间 | 2024-08-07（PyPI「Key dates → Released: Aug 7, 2024」；release files 上传日期同为 Aug 7, 2024） | https://pypi.org/project/python-pptx/ |
| 许可证（PyPI License 字段） | `MIT License (MIT)`，classifier 为 `OSI Approved :: MIT License` | https://pypi.org/project/python-pptx/ |
| 许可证（仓库 LICENSE 原文） | GitHub License API 返回 `license.key = mit`、`spdx_id = MIT`、`name = "MIT License"`；文件正文首行为 `The MIT License (MIT)`，`Copyright (c) 2013 Steve Canny`；文件 SHA `67ebe716ec4b2d64d6146b5e7cecd77ac7af9b79` | https://api.github.com/repos/scanny/python-pptx/license （页面同 https://github.com/scanny/python-pptx/blob/master/LICENSE ） |
| 维护状态 / 最近 release | 最新 release 为 1.0.2（2024-08-07）。PyPI 历史列表显示 1.0.2 之前为 1.0.1（2024-08-05）、1.0.0（2024-08-03）、0.6.23（2023-11-02）。Development Status classifier 为 `5 - Production/Stable`。PyPI 记录维护者 1 人（scanny） | https://pypi.org/project/python-pptx/ |
| 文档版本 | Read the Docs 页面标题为 `python-pptx 1.0.0 documentation` | https://python-pptx.readthedocs.io/en/latest/api/shapes.html |
| `shape.left/top/width/height` 是否存在 | 存在。`Picture` 类定义 `left`、`top`、`width`、`height`（均为 Read/write）；`GraphicFrame` 同样定义这四个属性 | https://python-pptx.readthedocs.io/en/latest/api/shapes.html |
| 坐标单位是否为 EMU | 是。文档原文：`left` = "Integer distance of the left edge of this shape from the left edge of the slide. Read/write. Expressed in English Metric Units (EMU)"；`top` = "Distance from the top edge of the slide to the top edge of this shape. Read/write. Expressed in English Metric Units (EMU)"；`width` = "Distance between left and right extents of this shape. Read/write. Expressed in English Metric Units (EMU)."；`height` = "Read/write. Integer distance between top and bottom extents of shape in EMUs." | https://python-pptx.readthedocs.io/en/latest/api/shapes.html |
| `Length` / `Emu` 单位类 | `pptx.util.Length`（基类，`int` 子类）提供 `emu`（"Integer length in English Metric Units"）、`inches`、`cm`、`mm`、`pt`、`centipoints` 属性；`pptx.util.Emu` 为 "Convenience constructor for length in english metric units"，与 `Inches` / `Cm` / `Mm` / `Pt` / `Centipoints` 并列 | https://python-pptx.readthedocs.io/en/latest/api/util.html |
| 读取文本框段落与 run | `TextFrame.paragraphs` = "Sequence of paragraphs in this text frame. A text frame always contains at least one paragraph."；`_Paragraph.runs` = "Sequence of runs in this paragraph."；`_Run` 对应 `a:r` 元素，含 `text`、`font`、`hyperlink` | https://python-pptx.readthedocs.io/en/latest/api/text.html |
| 图片形状识别 | `Picture` 类：`shape_type` = "Unconditionally MSO_SHAPE_TYPE.PICTURE in this case."；`Picture.image` = "The Image object for this picture. Provides access to the properties and bytes of the image in this picture shape."；`Picture` 基于 `p:pic` 元素 | https://python-pptx.readthedocs.io/en/latest/api/shapes.html |
| 图片二进制与元数据 | `Image.blob` = "The binary image bytestream of this image."；另有 `content_type`（MIME）、`dpi`、`ext`、`filename`、`sha1`（"SHA1 hash digest of the image blob."）、`size`（像素宽高） | https://python-pptx.readthedocs.io/en/latest/api/image.html |
| 「页」= slide 索引 | `Slides` 对象 = "Sequence of slides belonging to an instance of Presentation. Has list semantics for access to individual slides. Supports indexed access, len(), and iteration."；`Slides.index(slide)` = "Map slide to its zero-based position in this slide sequence."；另有 `Slide.slide_id`（唯一 ID，不随位置变化） | https://python-pptx.readthedocs.io/en/latest/api/slides.html |

---

## B2. python-docx

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| PyPI 最新版本 | `1.2.0`（页面标题 `python-docx 1.2.0`，Latest release） | https://pypi.org/project/python-docx/ |
| 最新版发布时间 | 2025-06-16（PyPI「Key dates → Released: Jun 16, 2025」；release files 上传日期同为 Jun 16, 2025） | https://pypi.org/project/python-docx/ |
| 许可证（PyPI License 字段） | `MIT License (MIT)`，classifier 为 `OSI Approved :: MIT License` | https://pypi.org/project/python-docx/ |
| 许可证（仓库 LICENSE 原文） | GitHub License API 返回 `license.key = mit`、`spdx_id = MIT`；正文首行 `The MIT License (MIT)`，`Copyright (c) 2013 Steve Canny`；文件 SHA 与 python-pptx 相同（`67ebe716ec4b2d64d6146b5e7cecd77ac7af9b79`） | https://api.github.com/repos/python-openxml/python-docx/license |
| 段落索引 | `Document.paragraphs` = "The Paragraph instances in the document, **in document order**."，并说明 `w:ins` / `w:del` 修订标记内的段落不出现在该列表；`Document.iter_inner_content()` = "Generate each Paragraph or Table in this document in document order." | https://python-docx.readthedocs.io/en/latest/api/document.html |
| run 级信息 | `Paragraph.runs` = "Sequence of Run instances corresponding to the `<w:r>` elements in this paragraph."；`Paragraph.iter_inner_content()` 按出现顺序生成 runs 与 hyperlinks | https://python-docx.readthedocs.io/en/latest/api/text.html |
| run 级格式（bold 等） | `Run.bold`、`Run.italic`、`Run.underline` 为「三态」读写属性（`True`/`False`/`None`，`None` 表示继承样式层级）；`Run.font` 提供 `Font` 对象（`Font.bold`、`Font.italic`、`Font.size`、`Font.name`、`Font.color` 等） | https://python-docx.readthedocs.io/en/latest/api/text.html |
| 图片（inline shapes） | `Document.inline_shapes` = "The InlineShapes collection for this document. An inline shape is a graphical object, such as a picture, contained in a run of text and behaving like a character glyph…"；`Run.add_picture(...)` 返回 `InlineShape` | https://python-docx.readthedocs.io/en/latest/api/document.html 、 https://python-docx.readthedocs.io/en/latest/api/text.html |
| 图片（rels / 形状 API 页） | 官方文档目录中存在独立的 "Shape-related objects" 章节，含 `InlineShapes` 与 `InlineShape` 对象；用户指南章节为 "Understanding pictures and other shapes" | https://python-docx.readthedocs.io/en/latest/index.html 、 https://python-docx.readthedocs.io/en/latest/api/shape.html |
| 表格 | `Document.tables` = "All Table instances in the document, in document order."，并说明嵌套在表格单元格内的表格不出现在该列表、`w:ins`/`w:del` 内的表格也不出现 | https://python-docx.readthedocs.io/en/latest/api/document.html |
| 页眉页脚 | `Section.header` / `Section.footer`、`Section.first_page_header` / `first_page_footer`、`Section.even_page_header` / `even_page_footer`；`_Header`/`_Footer` 提供 `paragraphs`、`tables`、`add_paragraph()`、`add_table()` | https://python-docx.readthedocs.io/en/latest/api/section.html |
| 分页 / 页码 API | **未提供页码或页码计数 API**。官方文档目录（API Documentation 全部条目）中不存在 document-level 的 page 对象、page count、page number 属性；`Section` 提供的仅为页面设置：`page_height`、`page_width`、`orientation`、`top_margin`、`bottom_margin`、`left_margin`、`right_margin`、`gutter`、`header_distance`、`footer_distance`、`start_type`（"Type of page-break (if any) inserted at the start of this section"） | https://python-docx.readthedocs.io/en/latest/index.html 、 https://python-docx.readthedocs.io/en/latest/api/section.html |
| 唯一的「分页」相关 API：`RenderedPageBreak` | `docx.text.pagebreak.RenderedPageBreak` = "A page-break **inserted by Word during page-layout for print or display purposes**. This usually does not correspond to a 'hard' page-break inserted by the document author, rather just that Word ran out of room on one page… **The position of these can change depending on the printer and page-size, as well as margins, etc.** They also will change in response to edits, **but not until Word loads and saves the document.**" ；并明确 "**Note these are never inserted by python-docx because it has no rendering function.** These are generally only useful for text-extraction of existing documents when python-docx is being used solely as a document 'reader'." | https://python-docx.readthedocs.io/en/latest/api/text.html |
| `RenderedPageBreak` 的读取入口 | `Paragraph.rendered_page_breaks`（"All rendered page-breaks in this paragraph… Most often an empty list"）、`Paragraph.contains_page_break`、`Run.contains_page_break`、`Hyperlink.contains_page_break`；`RenderedPageBreak.preceding_paragraph_fragment` / `following_paragraph_fragment` 返回的是「脱离文档正文的松散段落」（"The returned paragraph is divorced from the document body. Any changes made to it will not be reflected in the document."） | https://python-docx.readthedocs.io/en/latest/api/text.html |
| 官方 FAQ 是否存在 | **未找到** `python-docx` 官方 FAQ 页面：`https://python-docx.readthedocs.io/en/latest/user/faq.html` 与 `.../user/index.html` 均返回 404；官方文档首页 User Guide 目录中也不含 FAQ 条目 | https://python-docx.readthedocs.io/en/latest/user/faq.html （HTTP 404）、 https://python-docx.readthedocs.io/en/latest/index.html |
| 关于 "page number" 的官方 issue 说明 | 本次核实**未能定位**到一个专门讨论 "page number" 的官方 issue/FAQ 条目（试查的 `python-docx` issue #34 实为 "character style"，与分页无关）。因此「python-docx 官方明确拒绝/说明页码能力」这一说法，本报告**只能依据上一条官方 API 文档证据，不能引用官方 FAQ/issue 原句** | https://api.github.com/repos/python-openxml/python-docx/issues/34 （与分页无关，仅作为「该假设未被证实」的说明） |

---

## B3. Apache POI

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| 最新版本号（官网首页） | `5.5.1`。首页 Project News 标题为 "30 November 2025 - POI 5.5.1 available"，正文 "The Apache POI team is pleased to announce the release of 5.5.1." | https://poi.apache.org/ |
| 最新版本号（Maven Central metadata） | `<latest>5.5.1</latest>`、`<release>5.5.1</release>`；`<lastUpdated>20251130185820</lastUpdated>` | https://repo1.maven.org/maven2/org/apache/poi/poi/maven-metadata.xml |
| Java 版本要求 | 首页原文："POI requires Java 8 or newer since version 4.0.1." | https://poi.apache.org/ |
| 许可证（项目 Legal 页） | "Apache POI™ releases are available under the **Apache License, Version 2.0.**" | https://poi.apache.org/legal.html |
| 已知安全公告（官网首页） | CVE-2025-31672："This issue affects Apache POI component poi-ooxml before 5.4.0… Users are recommended to upgrade to version poi-ooxml 5.4.0 or later" | https://poi.apache.org/ |
| 源码仓库迁移 | 首页 Project News："7 July 2025 - Source repository switched from Subversion to Git… the source-code is now officially available at … https://github.com/apache/poi" | https://poi.apache.org/ |
| XWPF（docx）类 javadoc | `XWPFDocument`（"High(ish) level class for working with .docx files."）、`XWPFParagraph`、`XWPFRun` 均有官方 javadoc 页面 | https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/XWPFDocument.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/XWPFParagraph.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/XWPFRun.html |
| XWPF 成熟度官方表述 | `XWPFDocument` 类注释原文："This class tries to hide some of the complexity of the underlying file format, but **as it's not a mature and stable API yet**, certain parts of the XML structure come through. You'll therefore almost certainly need to refer to the OOXML specifications from http://www.ecma-international.org/publications/standards/Ecma-376.htm at some point in your use." | https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/XWPFDocument.html |
| XWPF 形状 / 图片坐标可得性 | `XWPFRun.getEmbeddedPictures()` = "Returns the embedded pictures of the run." 返回 `List<XWPFPicture>`；`XWPFRun.getCTR()` = "Get the currently used CTR object"（可下钻到 XML）；`XWPFPicture.getCTPicture()` = "Return the underlying CTPicture bean that holds all properties for this picture"；`XWPFPicture.getWidth()` = "Returns the width of the picture (in points)."、`getDepth()` = "Returns the depth of the picture (in points)."；`XWPFPicture.getPictureData()` 取图片数据 | https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/XWPFRun.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/XWPFPicture.html |
| XWPF 坐标单位 | `XWPFRun.addPicture(..., int width, int height)` 的 javadoc 明确写："`width` - width in **EMUs**. To convert to / from points use `Units`"、"`height` - height in **EMUs**."；而 `XWPFPicture.getWidth()/getDepth()` 返回 **points**。`XWPFParagraph` 的缩进类方法返回/接收 **twips**（如 "indentation in twips"）。即：XWPF 中不同 API 的坐标单位不统一（EMU / points / twips 混用），drawing anchor 的 extent 原始值为 EMU | https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/XWPFRun.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/XWPFPicture.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/XWPFParagraph.html |
| XSLF（pptx）类 javadoc | `XSLFSlide`、`XSLFShape`、`XSLFTextParagraph`、`XSLFTextRun` 均有官方 javadoc 页面 | https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFSlide.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFShape.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFTextParagraph.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFTextRun.html |
| `XSLFShape.getAnchor()` 与返回类型 | `XSLFShape` 未自行声明 `getAnchor()`，而是继承自接口 `org.apache.poi.sl.usermodel.Shape`；`Shape.getAnchor()` 签名为 `java.awt.geom.Rectangle2D getAnchor()`，javadoc 原文："Returns the anchor (the bounding box rectangle) of this shape. **All coordinates are expressed in points (72 dpi).**" | https://poi.apache.org/apidocs/dev/org/apache/poi/sl/usermodel/Shape.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFShape.html |
| XSLF 坐标单位结论 | **points（72 dpi）**，不是 EMU。即从 POI 的 `getAnchor()` 拿到的是 `Rectangle2D`（points），与 OOXML 文件中的 EMU 原始值不同，需要单位换算 | https://poi.apache.org/apidocs/dev/org/apache/poi/sl/usermodel/Shape.html |
| XSLF 文本对象 | `XSLFTextParagraph.getText()`、`getTextRuns()`、`addNewTextRun()`；`XSLFTextRun.getRawText()` / `setText()`、`isBold()` / `setBold()`、`getFontSize()`（"font size in points or null if font size is not set."）、`getFontFamily()` / `setFontFamily()`、`getHyperlink()` / `createHyperlink()` | https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFTextParagraph.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFTextRun.html |
| XSLF slide 索引 | `XSLFSlide.getSlideNumber()` = "Returns: the 1-based slide no."；`XSLFSlide.getTitle()` = "title of this slide or null if title is not set"；`XSLFSlide.isHidden()` / `setHidden()` 提供幻灯片可见性 | https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFSlide.html |
| 官方对 XSLF 成熟度的说明（组件页原文） | slideshow 组件页原文："Please note that **XSLF is still in early development and is a subject to incompatible changes in future.**"；同页也说明 "Whilst HSLF and XSLF provide similar features, **there is not a common interface across the two of them at this time.**" | https://poi.apache.org/components/slideshow/index.html |
| 官方对 XSLF 成熟度的说明（Javadoc 注解） | `@Beta` 注解出现在 `XSLFShape`、`XSLFSlide`、`XSLFTextParagraph`、`XSLFTextRun` 的类声明上（`org.apache.poi.util.Beta`） | https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFShape.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFSlide.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFTextParagraph.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFTextRun.html |
| HSLF/XSLF 依赖坐标 | HSLF 位于 scratchpad："This code currently lives the scratchpad area of the POI Git repository… ensure you have the Scratchpad Jar on your classpath, or a dependency defined on the _poi-scratchpad_ artifact - the main POI jar is not enough!" | https://poi.apache.org/components/slideshow/index.html |
| XSLF 组件页自身的一处笔误（原件如此） | 组件页原文写 "XSLF is the POI Project's pure Java implementation of the PowerPoint 2007 OOXML (**.xlsx**) file format"——原文即为 `.xlsx`，与 XSLF 实际处理的 `.pptx` 不符，属官方页面笔误，引用时需注意 | https://poi.apache.org/components/slideshow/index.html |

---

## B4. 「PPTX 的页 = slide 索引；DOCX 无固定页码（分页是渲染期决定）」

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| ISO/IEC 29500-1 对 `w:lastRenderedPageBreak` 的定义（原文） | "**lastRenderedPageBreak (Position of Last Calculated Page Break)** — This element specifies that this position delimited the end of a page **when this document was last saved by an application which paginates its content.**" | https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.lastrenderedpagebreak?view=openxml-3.0.1 |
| ISO/IEC 29500-1 的 Guidance 原文 | "\[_Guidance_: This element **must be used by applications to specify the locations of page breaks within a document when it is saved as WordprocessingML**, in order to allow other applications (e.g. assistive software) to utilize this information when reading the document. _end guidance_\]" | https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.lastrenderedpagebreak?view=openxml-3.0.1 |
| 该页对标准出处的标注 | 页面 Remarks 小节标题为 "\[ISO/IEC 29500-1 1st Edition\]"，页尾标注 "© ISO/IEC29500: 2008."；类摘要为 "Position of Last Calculated Page Break. This class is available in Office 2007 and above."，序列化后限定名为 `w:lastRenderedPageBreak`，父元素为 `r (§22.1.2.87); r (§17.3.2.25)` | https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.lastrenderedpagebreak?view=openxml-3.0.1 |
| 标准示例（说明分页信息是「保存时缓存」） | 示例说明 `end` 是上一页最后一个词时，把该信息随文件保存：`<w:r><w:t>This is the end</w:t><w:lastRenderedPageBreak/><w:t xml:space="preserve"> of the page</w:t></w:r>`，并说明 "The lastRenderedPageBreak element indicates that there was a page break resulting from pagination of this content, which occurred between the word _end_ and the word _of_." | https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.lastrenderedpagebreak?view=openxml-3.0.1 |
| ECMA-376 标准本体页 | ECMA-376 "Office Open XML file formats"，**5th edition, December 2021**；ISO/IEC number = **29500**；四个部分：Part 1 "Fundamentals And Markup Language Reference"（5th ed., Dec 2016）、Part 2 "Open Packaging Conventions"（5th ed., Dec 2021）、Part 3 "Markup Compatibility and Extensibility"（5th ed., Dec 2015）、Part 4 "Transitional Migration Features"（5th ed., Dec 2016）；各 Part 提供官方 zip 下载地址 | https://ecma-international.org/publications-and-standards/standards/ecma-376/ |
| 微软 OI29500 实现说明页（同一元素的官方实现差异说明） | 存在 "[MS-OI29500]: Part 1 Section 17.3.3.13, lastRenderedPageBreak (Position of Last Calculated Page Break)" 官方页面 | https://learn.microsoft.com/zh-tw/openspecs/office_standards/ms-oi29500/e0d0fc07-96bb-4248-a832-dd0e8e42f001 |
| python-docx 侧对「分页不由库生成、位置会变」的官方表述 | "The position of these can change depending on the printer and page-size, as well as margins, etc. They also will change in response to edits, but not until Word loads and saves the document." ；"Note these are never inserted by python-docx because it has no rendering function." | https://python-docx.readthedocs.io/en/latest/api/text.html |
| Apache POI 侧同类事实（DOCX） | `XWPFParagraph.isPageBreak()` 描述的是「硬分页」属性（"Specifies that when rendering this document in a paginated view, the contents of this paragraph are rendered on the start of a new page"），即作者设定，而非渲染结果的页码；XWPF 未提供页码/页数 API（本次核实的 `XWPFDocument` javadoc 方法清单中未见页码或页数方法） | https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/XWPFParagraph.html 、 https://poi.apache.org/apidocs/dev/org/apache/poi/xwpf/usermodel/XWPFDocument.html |
| PPTX 侧「页 = slide」的官方依据 | POI `XSLFSlide.getSlideNumber()` = "the 1-based slide no."；python-pptx `Slides` 提供零基索引与 `len()` | https://poi.apache.org/apidocs/dev/org/apache/poi/xslf/usermodel/XSLFSlide.html 、 https://python-pptx.readthedocs.io/en/latest/api/slides.html |

**可直接引用的关键英文原句（两处）**
1. "This element specifies that this position delimited the end of a page when this document was last saved by an application which paginates its content."（ISO/IEC 29500-1，经 Microsoft Learn 官方页转述）
2. "Note these are never inserted by python-docx because it has no rendering function."（python-docx 官方文档）

---

## B5. 备选方案

| 项目 | 事实 | 官方链接 |
| --- | --- | --- |
| docx4j 最新版本（Maven Central metadata） | `<latest>6.1.2</latest>`、`<release>6.1.2</release>`；`<lastUpdated>20190227043711</lastUpdated>`（即 2019-02-27） | https://repo1.maven.org/maven2/org/docx4j/docx4j/maven-metadata.xml |
| docx4j 6.1.2 制品时间戳 | 目录列表中 `docx4j-6.1.2.jar` / `.pom` / `-sources.jar` / `-javadoc.jar` 时间戳均为 **2019-02-27 04:32** | https://repo1.maven.org/maven2/org/docx4j/docx4j/6.1.2/ |
| docx4j 许可证 | `docx4j-6.1.2.pom` 中 `<licenses><license><name>Apache 2</name><url>http://www.apache.org/licenses/LICENSE-2.0.txt</url><distribution>repo</distribution><comments>A business-friendly OSS license</comments></license></licenses>` | https://repo1.maven.org/maven2/org/docx4j/docx4j/6.1.2/docx4j-6.1.2.pom |
| docx4j 项目地址（POM 内声明） | `<url>http://www.docx4java.org/</url>`；`<scm><developerConnection>scm:git\|git@github.com:plutext/docx4j.git</developerConnection></scm>`；`<inceptionYear>2007</inceptionYear>` | https://repo1.maven.org/maven2/org/docx4j/docx4j/6.1.2/docx4j-6.1.2.pom |
| docx4j 维护状态 | 本次核实中 `https://www.docx4java.org/` 与 `https://docx4java.org/`、`https://www.docx4java.org/downloads.html` 均**无法访问**（fetch failed），因此**无法从官网确认其当前维护状态与最近发布日期**；GitHub Releases API 对 `plutext/docx4j` 返回空数组 `[]`（无 release 记录）。可确认的客观事实仅为：Maven Central 上该坐标最新发布版本 6.1.2，时间戳 2019-02-27 | https://api.github.com/repos/plutext/docx4j/releases 、 https://repo1.maven.org/maven2/org/docx4j/docx4j/maven-metadata.xml |
| Aspose.Words for Java — 授权页 | 官方授权说明页存在；免费评估版限制原文："The Trial version of Aspose.Words for Java and Aspose.Words for Android via Java without the specified license provides full product functionality, but **inserts an evaluative watermark at the top of the document upon loading and saving and limits the maximum document size to a few hundred paragraphs.**" | https://docs.aspose.com/words/java/licensing/ |
| Aspose.Words for Java — 是否商业授权 / 价格 | 属商业授权（perpetual 或 metered）。官方定价页列出的 license 类型与价格（页面标注 "all prices are in USD"）：Developer Small Business **US$1199**、Developer OEM **US$3597**、Developer SDK **US$23980**、Site Small Business **US$5995**、Site OEM **US$16786**、Site SDK **US$59950**；Metered Small Business / Metered OEM **from US$1999/month** | https://purchase.aspose.com/pricing/words/java |
| Aspose.Words for Java — 临时许可 | 官方临时许可页："A temporary license provides a **full 30-day license**"；"**We require a valid business email address** for temporary license requests. Personal or free email addresses are not accepted for this purpose."；"Temporary licenses are limited to **three (3), thirty (30) day licenses per customer per twelve (12) month period.**" | https://purchase.aspose.com/temporary-license/ |
| Aspose 授权类型（通用官方说明） | 官方 license-types 页说明 10 种类型（Developer/Site/Metered/Publicity × Small Business/OEM/SDK），并明确 "It does not support distribution of end user software to third parties, public facing websites/applications, extranets, multi-site intranets or SaaS project usage scenarios. **Only OEM Licenses support this form of distribution.**"（针对 Developer/Site Small Business 类型） | https://purchase.aspose.com/policies/license-types/ |
| Aspose.Slides for Java — 授权页与评估版限制 | 官方授权页原文："**Evaluation version limitations** — While Aspose.Slides evaluation version (without a license specified) provides full product functionality, it **inserts an evaluation watermark at the top of the document on open and save operations.** — **You are limited to one slide when extracting texts from presentation slides.**"；"To test Aspose.Slides without limitations, you can ask for a **30-Day Temporary License**." | https://docs.aspose.com/slides/java/licensing/ |
| Aspose.Slides for Java — 定价页 | 本次**未打开** Aspose.Slides 的官方 pricing 页面（`https://purchase.aspose.com/pricing/slides/java` 未核实），因此本报告**不列** Aspose.Slides 的具体价格数字 | — （未核实项，见「不确定项」） |
| LibreOffice headless 转换 — 官方参数文档 | 官方帮助页 "Starting LibreOffice Software With Parameters" 列出：`--headless` = "Starts in 'headless mode' which allows using the application without user interface."；`--convert-to OutputFileExtension[:OutputFilterName[:OutputFilterParams]] [--outdir output_dir]`，示例含 `--convert-to pdf *.doc`、`--convert-to pdf:writer_pdf_Export --outdir /home/user *.doc`；并提示 "If `--outdir` is not specified, then current working directory is used as the result."；另注 "LibreOffice requires write access to its user profile directory." | https://help.libreoffice.org/latest/en-US/text/shared/guide/start_parameters.html |
| LibreOffice 版本状态（官方 wiki） | 官方 wiki 首页标注 "LibreOffice **26.8.0** is our latest, feature-rich release"、"LibreOffice **26.2.6** — Safe for production use by most users and enterprises"；Upcoming releases 列 "LibreOffice 26.8: August 2026"（页面原文如此） | https://wiki.documentfoundation.org/Main_Page |
| LibreOffice 镜像体量的官方依据 | **未找到**任何官方页面说明 Docker 镜像体量。官方 wiki 中 `/Development/BuildingOnDocker`、`/Development/Build_In_Docker`、`/Development/Docker` 三个页面均不存在（404 / 空页）；Docker Hub 页面 `https://hub.docker.com/r/libreoffice/soffice` 与 `/tags` 本次均 fetch failed | https://wiki.documentfoundation.org/Development/Docker （404）、 https://hub.docker.com/r/libreoffice/soffice （fetch failed） |
| LibreOffice 中文字体缺失问题的官方依据 | **未找到**官方说明。官方帮助页仅提出运行期要求（如需写入 user profile 目录），未涉及字体安装；官方 wiki 检索结果中与 CJK 相关的是会议演讲稿 PDF 与社区问答，非官方文档性结论 | https://help.libreoffice.org/latest/en-US/text/shared/guide/start_parameters.html （未涉及字体） |

---

## 不确定项清单

以下条目**未能在官方页面确认**，不得当作已核实事实使用：

1. **docx4j 的当前维护状态与最近发布日期**：官网 `docx4java.org` 三个入口本次全部无法访问，无法确认；仅能确认 Maven Central 上 `org.docx4j:docx4j` 最新为 6.1.2，时间戳 2019-02-27。GitHub Releases 为空数组，不能据此推断「已停止维护」。
2. **docx4j 是否有 Maven Central 之外的发布渠道 / 更新版本**：未核实。
3. **Aspose.Slides for Java 的具体价格**：未打开其 pricing 页面，故不列数字。
4. **python-docx 官方 FAQ 中关于 "page number" 的原文说明**：官方 FAQ 页面不存在（404），也未能定位到专门的官方 issue；因此「python-docx 官方明确说明不支持页码」这一说法，只能用 API 文档证据支撑（未提供页码/页数 API、只有依赖 Word 上次保存的 `RenderedPageBreak`），**不能用官方 FAQ/issue 原句引用**。
5. **LibreOffice Docker 镜像体量**：无官方说明，属「仅社区经验」范畴。
6. **LibreOffice 容器内中文字体缺失及需额外安装字体**：无官方说明，属「仅社区经验」范畴。
7. **`libreoffice/soffice` 是否为 The Document Foundation 官方维护的 Docker 镜像**：无法访问 Docker Hub 页面确认，未核实。
8. **`--convert-to pdf` 在 headless 下的中文字形正确性**：官方帮助页未涉及字体覆盖范围，未核实。
9. **ECMA-376 Part 1 的具体条款号（如 §17.3.3.13 的正文页码）**：本次未下载 ECMA-376 Part 1 的 zip 原件逐条比对；`lastRenderedPageBreak` 的标准原文是通过 Microsoft Learn 的官方 ISO/IEC 29500-1 转述页获得的，且 [MS-OI29500] 页面标题自述为 "Part 1 Section 17.3.3.13"。若需精确条款号，建议以 ECMA-376 Part 1（5th edition, December 2016）官方 zip 原件复核。
10. **Apache POI 首页与 POI 各 Javadoc 页脚显示的年份与 Maven metadata 时间戳存在不一致**（首页新闻为 2025-11-30 发布 5.5.1，页面版权脚注与 Javadoc 页脚显示 2026/2022 等年份）：本次仅记录页面原文与 metadata 时间戳，不对「当前日期」做任何推断。
11. **POI 的 `XWPF`/`XSLF` 是否提供页数统计**：本次逐一查看的 `XWPFDocument`、`XWPFParagraph`、`XSLFSlide` javadoc 方法清单中均未见页数方法；但未做全 javadoc 全量检索，故不能断言「绝对不存在任何相关方法」。
12. **python-pptx / python-docx 的 GitHub 仓库 LICENSE 页正文**：GitHub HTML 页面正文被导航内容截断，最终以 GitHub License API 的 base64 原文 + SPDX 判定为准（该 API 属 GitHub 官方接口，非第三方转述）。

---

## 附：本次核实的来源页面清单（按类型）

- **PyPI 项目页**：`pypi.org/project/python-pptx/`、`pypi.org/project/python-docx/`
- **GitHub 官方 API（LICENSE 原文与 SPDX 判定）**：`api.github.com/repos/scanny/python-pptx/license`、`api.github.com/repos/python-openxml/python-docx/license`
- **官方文档站（Read the Docs）**：`python-pptx.readthedocs.io/en/latest/api/{shapes,util,text,image,slides}.html`；`python-docx.readthedocs.io/en/latest/{index,api/document,api/text,api/section}.html`
- **Apache POI 官网**：`poi.apache.org/`、`poi.apache.org/legal.html`、`poi.apache.org/components/slideshow/index.html`
- **Apache POI 官方 Javadoc（apidocs/dev）**：`XWPFDocument`、`XWPFParagraph`、`XWPFRun`、`XWPFPicture`、`XSLFSlide`、`XSLFShape`、`XSLFTextParagraph`、`XSLFTextRun`、`org.apache.poi.sl.usermodel.Shape`
- **Maven Central**：`repo1.maven.org/maven2/org/apache/poi/poi/maven-metadata.xml`、`repo1.maven.org/maven2/org/docx4j/docx4j/{maven-metadata.xml,6.1.2/,6.1.2/docx4j-6.1.2.pom}`
- **Microsoft Learn**：`learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.lastrenderedpagebreak`（含 ISO/IEC 29500-1 转述）；`learn.microsoft.com/zh-tw/openspecs/office_standards/ms-oi29500/e0d0fc07-96bb-4248-a832-dd0e8e42f001`
- **Ecma International**：`ecma-international.org/publications-and-standards/standards/ecma-376/`
- **Aspose 官方**：`docs.aspose.com/words/java/licensing/`、`docs.aspose.com/slides/java/licensing/`、`purchase.aspose.com/pricing/words/java`、`purchase.aspose.com/policies/license-types/`、`purchase.aspose.com/temporary-license/`
- **LibreOffice 官方**：`help.libreoffice.org/latest/en-US/text/shared/guide/start_parameters.html`、`wiki.documentfoundation.org/Main_Page`
