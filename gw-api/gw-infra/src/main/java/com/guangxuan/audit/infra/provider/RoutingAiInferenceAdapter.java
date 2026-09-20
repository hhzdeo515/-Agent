package com.guangxuan.audit.infra.provider;

import com.guangxuan.audit.domain.port.AiInferencePort;
import com.guangxuan.audit.infra.provider.dashscope.DashScopeAiAdapter;
import com.guangxuan.audit.infra.provider.dashscope.DashScopeProperties;
import com.guangxuan.audit.infra.provider.mock.MockAiAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 推理路由：按当前供应商把调用分派给规则实现或千问大模型。
 *
 * <h3>为什么需要这一层</h3>
 * 原来的做法是用 {@code @ConditionalOnProperty} 在<b>启动时</b>决定装配哪个实现。
 * 那样"设置里切换供应商"就必须重启应用——而设置界面存在的意义正是不要重启。
 * 改成两个实现都常驻、由一个路由按当前配置分派后，
 * 切换供应商在界面上点一下即刻生效。
 *
 * <p>代价是多一层间接调用（每次调用多一次 if），相对"改配置要重启容器"可以忽略。
 *
 * <h3>为什么用 @Primary 而不是替换 Bean</h3>
 * 业务代码只认 {@link AiInferencePort} 一个类型，路由作为主 Bean 被注入；
 * 两个具体实现仍在容器里，便于单独测试与诊断。
 */
@Slf4j
@Primary
@Component
public class RoutingAiInferenceAdapter implements AiInferencePort {

    private final MockAiAdapter mockAdapter;
    private final DashScopeAiAdapter dashScopeAdapter;
    private final DashScopeProperties props;

    public RoutingAiInferenceAdapter(MockAiAdapter mockAdapter,
                                     DashScopeAiAdapter dashScopeAdapter,
                                     DashScopeProperties props) {
        this.mockAdapter = mockAdapter;
        this.dashScopeAdapter = dashScopeAdapter;
        this.props = props;
    }

    /**
     * 当前生效的实现。
     *
     * <p>选了 dashscope 但没填 Key 时<b>不静默回落</b>到规则实现：
     * 那会让法务以为在用大模型，实际拿到的是关键词匹配的结果。
     * 这种情况由 {@code DashScopeClient} 在调用时报错，错误信息指向"去设置里填 Key"。
     */
    private AiInferencePort delegate() {
        return props.dashscopeEnabled() ? dashScopeAdapter : mockAdapter;
    }

    public String activeProvider() {
        return props.dashscopeEnabled() ? "dashscope" : "mock";
    }

    @Override
    public List<com.guangxuan.audit.domain.ai.RiskDraft> identifyCandidates(CandidateRequest request) {
        return delegate().identifyCandidates(request);
    }

    @Override
    public List<com.guangxuan.audit.domain.ai.RiskDraft> judge(JudgeRequest request) {
        return delegate().judge(request);
    }

    @Override
    public String generateReport(ReportRequest request) {
        return delegate().generateReport(request);
    }

    @Override
    public String generateCommunicationScript(ScriptRequest request) {
        return delegate().generateCommunicationScript(request);
    }

    @Override
    public ConsultationAnswer consult(ConsultationRequest request) {
        return delegate().consult(request);
    }

    /**
     * 复审同样路由。
     *
     * <p>注意语义差异：{@code mock} 实现返回 {@code null}（表示"不支持模型复审"），
     * 调用方据此回落到规则比对；{@code dashscope} 返回真实结论。
     * 路由只负责分派，不替调用方决定"不支持时怎么办"。
     */
    @Override
    public RereviewAnswer rereview(RereviewRequest request) {
        return delegate().rereview(request);
    }
}
