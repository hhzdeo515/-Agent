package com.guangxuan.audit.infra.provider.mock;

import com.guangxuan.audit.common.enums.AnchorType;
import com.guangxuan.audit.common.enums.MaterialType;
import com.guangxuan.audit.domain.port.ParsePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析端口的 Mock 实现。
 *
 * <p>用途：在尚未接入百炼、或 M0 的关键假设（V1/V2：OCR 文本定位的返回格式与精度）
 * 还没验证之前，让编排链路可以先跑通。
 *
 * <p><b>它不能替代真实解析</b>：这里产出的坐标是按段落平均分配的假数据，
 * 只保证"结构正确"，不保证"定位准确"。因此：
 * <ul>
 *   <li>不要用它做定位精度的验收；</li>
 *   <li>它产出的锚点 {@code sourceEngine} 标记为 {@code mock}，便于在数据里区分真假。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "gw.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockParseAdapter implements ParsePort {

    private static final String ENGINE = "mock";
    private static final String ENGINE_VERSION = "0.1.0-mock";

    @Override
    public OcrResult ocr(OcrRequest request) {
        // 真实实现应调用 qwen-vl-ocr / qwen3.5-ocr 的文本定位能力。
        // 此处返回空结果，避免伪造坐标误导定位精度评估。
        log.debug("[mock] ocr skipped for {}", request.objectKey());
        return new OcrResult(List.of(), ENGINE, ENGINE_VERSION);
    }

    @Override
    public AsrResult asr(AsrRequest request) {
        // 真实实现必须走 Filetrans 异步接口（同步接口不返回时间戳，见 ParsePort 注释）。
        log.debug("[mock] asr skipped for {}", request.objectKey());
        return new AsrResult(List.of(), "zh", ENGINE, ENGINE_VERSION, null);
    }

    /**
     * 文档解析 Mock：把文本按行切成段落锚点。
     *
     * <p>坐标是按固定高度均分的假坐标——结构可用于联调，
     * 但刻意在 {@code sourceEngine} 上标记 mock，避免被当成真实定位结果。
     */
    @Override
    public DocumentResult parseDocument(DocumentRequest request) {
        List<DocParagraph> paragraphs = new ArrayList<>();
        String materialType = request.materialType();

        // 对纯文本物料，构造一段可审核内容，使主链路能产出风险
        String sample = "本产品行业第一，100%保障安全，续航可达1000km。";
        if (MaterialType.TEXT.name().equals(materialType)) {
            paragraphs.add(new DocParagraph(null, 0, sample, 0, sample.length()));
        }

        boolean hasTextLayer = true;
        log.debug("[mock] parseDocument materialType={} paragraphs={}", materialType, paragraphs.size());
        return new DocumentResult(paragraphs, List.of(), hasTextLayer, ENGINE, ENGINE_VERSION);
    }
}
