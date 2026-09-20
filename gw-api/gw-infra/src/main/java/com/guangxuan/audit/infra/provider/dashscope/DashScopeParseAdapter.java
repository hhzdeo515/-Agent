package com.guangxuan.audit.infra.provider.dashscope;

import com.guangxuan.audit.domain.port.ParsePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 百炼解析适配器：把 {@link ParsePort} 的三个方法分派给三个专职客户端。
 *
 * <p>拆成三个客户端而不是一个巨类，因为三者失败模式完全不同：
 * OCR 是秒级同步、ASR 是分钟级异步、文档解析是本地 IO + OCR 兜底。
 * 混在一个类里，重试与超时策略只能取一个折中值，结果三者都不合适。
 *
 * <p>本类刻意不含业务判断：拿不到内容就抛异常，由
 * {@code ParseAppService} 决定是标 FAILED 还是 PARTIAL，
 * 并且必须把失败原因原样透给用户（"需要重传什么"是产品要求）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeParseAdapter implements ParsePort {

    private final DashScopeOcrClient ocrClient;
    private final DashScopeAsrClient asrClient;
    private final DashScopeDocumentClient documentClient;

    @Override
    public OcrResult ocr(OcrRequest request) {
        return ocrClient.ocr(request);
    }

    @Override
    public AsrResult asr(AsrRequest request) {
        return asrClient.asr(request);
    }

    @Override
    public DocumentResult parseDocument(DocumentRequest request) {
        return documentClient.parseDocument(request);
    }

    /**
     * 画面语义理解。
     *
     * <p>复用 OCR 客户端里的视觉能力：两者都要把图片送到同一个多模态端点，
     * 且必须是同一张同尺寸图，分开放会导致定位错位。
     */
    @Override
    public VisionResult vision(VisionRequest request) {
        return ocrClient.vision(request);
    }
}
