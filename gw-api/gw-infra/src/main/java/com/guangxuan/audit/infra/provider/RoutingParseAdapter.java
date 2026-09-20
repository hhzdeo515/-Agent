package com.guangxuan.audit.infra.provider;

import com.guangxuan.audit.domain.port.ParsePort;
import com.guangxuan.audit.infra.provider.dashscope.DashScopeParseAdapter;
import com.guangxuan.audit.infra.provider.dashscope.DashScopeProperties;
import com.guangxuan.audit.infra.provider.mock.MockParseAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 解析路由：按当前供应商分派到规则实现或百炼实现。
 *
 * <p>与 {@link RoutingAiInferenceAdapter} 同理——解析实现也不能在启动时定死，
 * 否则"设置里换供应商"就得重启容器。
 *
 * <p>两套实现的能力差异很大（规则实现只能读纯文本，百炼实现能 OCR/ASR/解析文档），
 * 因此路由不做任何"能力补偿"：选了谁就是谁，能力缺失由各自的实现如实报错，
 * 界面通过 {@code /api/ai/status} 告知用户当前具备哪些能力。
 */
@Slf4j
@Primary
@Component
public class RoutingParseAdapter implements ParsePort {

    private final MockParseAdapter mockAdapter;
    private final DashScopeParseAdapter dashScopeAdapter;
    private final DashScopeProperties props;

    public RoutingParseAdapter(MockParseAdapter mockAdapter,
                               DashScopeParseAdapter dashScopeAdapter,
                               DashScopeProperties props) {
        this.mockAdapter = mockAdapter;
        this.dashScopeAdapter = dashScopeAdapter;
        this.props = props;
    }

    private ParsePort delegate() {
        return props.dashscopeEnabled() ? dashScopeAdapter : mockAdapter;
    }

    @Override
    public OcrResult ocr(OcrRequest request) {
        return delegate().ocr(request);
    }

    @Override
    public AsrResult asr(AsrRequest request) {
        return delegate().asr(request);
    }

    @Override
    public DocumentResult parseDocument(DocumentRequest request) {
        return delegate().parseDocument(request);
    }

    @Override
    public VisionResult vision(VisionRequest request) {
        return delegate().vision(request);
    }
}
