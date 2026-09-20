package com.guangxuan.audit.infra.provider.mock;

import com.guangxuan.audit.common.enums.MaterialType;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.domain.port.ParsePort;
import com.guangxuan.audit.infra.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析端口的 Mock 实现（无视觉/语音模型时的降级路径）。
 *
 * <p><b>它到底能做什么、不能做什么，必须说清楚</b>，否则用户上传一张海报得到
 * "未提取到内容"，会以为是自己文件的问题：
 * <ul>
 *   <li><b>纯文本（.txt/.md/.csv）</b>——真解析。从对象存储读取<b>文件真实内容</b>，
 *       按行切成段落锚点并记录真实字符区间。也就是说上传什么就审什么；</li>
 *   <li><b>图片 / 视频 / PDF / Word / PPT</b>——<b>不做假解析</b>。这些格式需要 OCR、
 *       ASR 或版式解析，Mock 一概不具备，因此直接抛出明确的错误，
 *       而不是返回一段固定样例让链路"看起来跑通了"。</li>
 * </ul>
 *
 * <p>关于第二种情况的设计取舍：早期版本对任何文本类物料都返回同一段示例文案，
 * 结果是"用户上传 A、系统审核 B"，而且界面上完全看不出来。
 * 这比报错危险得多——它会让法务以为某份材料已经审过了。宁可失败，不可假装成功。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gw.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockParseAdapter implements ParsePort {

    private static final String ENGINE = "mock";
    private static final String ENGINE_VERSION = "0.2.0-mock-text";

    private static final String MOCK_LIMIT_MESSAGE =
            "当前为 Mock 解析模式：仅支持纯文本（.txt / .md / .csv）的真实解析，"
                    + "无法解析图片、视频、PDF、Word、PPT。"
                    + "请在 deploy 目录下配置 DASHSCOPE_API_KEY，并把 GW_AI_PROVIDER 设为 dashscope 后重试。";

    private final StorageService storageService;

    @Override
    public OcrResult ocr(OcrRequest request) {
        // 真实实现应调用 qwen-vl-ocr / qwen3.5-ocr 的文本定位能力。
        // 这里刻意不给"假坐标"：伪造的 bbox 会让定位精度看起来有数据，实际全是编的。
        throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE, MOCK_LIMIT_MESSAGE);
    }

    @Override
    public AsrResult asr(AsrRequest request) {
        // 真实实现必须走 Filetrans 异步接口（同步接口不返回时间戳，见 ParsePort 注释）。
        throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE, MOCK_LIMIT_MESSAGE);
    }

    @Override
    public VisionResult vision(VisionRequest request) {
        // 画面语义需要视觉理解模型。Mock 没有这个能力，如实拒绝，
        // 而不是返回一段编出来的"画面描述"——那会变成凭空生成的风险依据。
        throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE, MOCK_LIMIT_MESSAGE);
    }

    /**
     * 文档解析：纯文本读真实内容；其余格式明确拒绝。
     */
    @Override
    public DocumentResult parseDocument(DocumentRequest request) {
        if (!MaterialType.TEXT.name().equals(request.materialType())) {
            throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE, MOCK_LIMIT_MESSAGE);
        }
        return parsePlainText(request);
    }

    /**
     * 按行切分纯文本，每行一个段落锚点。
     *
     * <p>为什么按行而不是按整篇：宣传文案常常一行一句口号，
     * 按行切才能让"行业第一"这类表述定位到具体那一句，
     * 而不是定位到整份文件（AGENTS.md 第 5 条要求风险定位匹配物料类型）。
     */
    private DocumentResult parsePlainText(DocumentRequest request) {
        byte[] bytes = storageService.getBytes(request.objectKey());
        String text = decode(bytes);

        List<DocParagraph> paragraphs = new ArrayList<>();
        int paraIndex = 0;
        int offset = 0;
        // 用 -1 保留末尾空行，使字符偏移严格对应原文
        for (String raw : text.split("\r\n|\r|\n", -1)) {
            int start = offset;
            int end = offset + raw.length();
            offset = end + 1;
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            // pageNo 为 null：纯文本没有页码概念
            paragraphs.add(new DocParagraph(null, paraIndex, line, start, end));
            paraIndex++;
        }

        log.debug("[mock] parseDocument text paragraphs={} chars={}", paragraphs.size(), text.length());
        return new DocumentResult(paragraphs, List.of(), true, ENGINE, ENGINE_VERSION);
    }

    /**
     * 解码文本，兼容中文环境常见的两种编码。
     *
     * <p>先按 UTF-8 严格解码：成功即 UTF-8。失败再试 GBK——
     * Windows 上另存的 .txt 默认是 GBK，若一律按 UTF-8 解，会得到满屏乱码，
     * 而且乱码文本照样能"匹配不到关键词"，最后表现为"这份材料没风险"，这是最坏的失败方式。
     */
    private String decode(byte[] bytes) {
        // 去掉 UTF-8 BOM，否则第一个锚点的文本会带一个不可见字符，
        // 导致风险原文与文件里看到的对不上
        int from = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            from = 3;
        }
        String utf8 = strictDecode(bytes, from, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        String gbk = strictDecode(bytes, from, Charset.forName("GBK"));
        if (gbk != null) {
            log.info("[mock] 文本按 GBK 解码成功（非 UTF-8 文件）");
            return gbk;
        }
        // 两种都失败：用 UTF-8 容错解码，至少不丢内容，并在日志里留痕
        log.warn("[mock] 文本既不是合法 UTF-8 也不是合法 GBK，已按 UTF-8 容错解码");
        return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
    }

    /** 严格解码：编码不合法时返回 null，而不是塞一堆替换字符当作成功 */
    private String strictDecode(byte[] bytes, int from, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, from, bytes.length - from)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
