package com.guangxuan.audit.boot.controller;

import com.guangxuan.audit.common.api.Result;
import com.guangxuan.audit.infra.provider.dashscope.DashScopeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 能力状态（AGENTS.md 第 15 条：未实现或未验证的能力必须明确标记，
 * 不得在产品界面中伪装成已经可靠可用）。
 *
 * <p>存在的直接原因：本项目同时存在规则实现（mock）与千问大模型实现（dashscope），
 * 两者在界面上的表现可能一样——都能"给出一堆风险"。如果不说清当前用的是哪一个，
 * 用户会把规则匹配的输出当成大模型的判断，这是最需要避免的误解。
 *
 * <p>因此这里返回"当前生效的是谁、具备哪些能力、哪些能力还没有"，
 * 由界面显式展示，而不是靠配置文件里写了什么来推断。
 *
 * <p><b>不返回 API Key</b>，只返回"是否已配置"。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiStatusController {

    private final DashScopeProperties props;

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        boolean dashscope = props.dashscopeEnabled();
        boolean keyConfigured = props.getDashscope().getApiKey() != null
                && !props.getDashscope().getApiKey().isBlank();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", props.getProvider());
        out.put("providerLabel", dashscope ? "阿里云百炼（千问大模型）" : "规则实现（Mock）");
        out.put("region", props.getRegion());
        out.put("apiKeyConfigured", keyConfigured);

        // 只给打码后的提示，便于用户核对"是不是填错了那把 Key"，
        // 又不至于让日志或页面成为凭证泄露渠道
        out.put("apiKeyHint", keyConfigured ? mask(props.getDashscope().getApiKey()) : null);

        if (dashscope) {
            Map<String, Object> models = new LinkedHashMap<>();
            models.put("text", props.getModels().getText());
            models.put("vision", props.getModels().getVision());
            models.put("ocrPrimary", props.getModels().getOcrPrimary());
            models.put("ocrFallback", props.getModels().getOcrFallback());
            models.put("asr", props.getModels().getAsr());
            models.put("embedding", props.getModels().getEmbedding());
            models.put("rerank", props.getModels().getRerank());
            out.put("models", models);
        }

        out.put("pipelineVersion", props.getPipelineVersion());
        out.put("promptVersion", props.getPromptVersion());
        out.put("rulesetVersion", props.getRulesetVersion());

        List<Map<String, Object>> capabilities = new ArrayList<>();
        // verified 的口径必须严格：只有真的用真实模型调用验证过才算 true。
        // "编译通过"、"有单元级离线验证"都不算——
        // 把没验证过的能力标成可用，正是 AGENTS.md 第 15 条要禁止的事。
        String unverifiedNote = dashscope
                ? "已按官方文档实现并通过编译，但尚未用真实 API Key 做过调用验证"
                : null;

        capabilities.add(cap("文字识别（图片中的文字）",
                dashscope ? "AVAILABLE" : "UNAVAILABLE",
                dashscope ? "由 " + props.getModels().getOcrPrimary() + " 识别，失败自动换 "
                        + props.getModels().getOcrFallback() + " 兜底"
                        : "规则实现没有 OCR 能力，图片物料无法解析",
                !dashscope, unverifiedNote));
        capabilities.add(cap("画面语义理解",
                dashscope ? "AVAILABLE" : "UNAVAILABLE",
                dashscope ? "由 " + props.getModels().getVision() + " 理解画面"
                        : "规则实现没有视觉能力，画面类风险无法识别",
                !dashscope, unverifiedNote));
        capabilities.add(cap("语音转写（视频口播）",
                dashscope ? "AVAILABLE" : "UNAVAILABLE",
                dashscope ? "由 " + props.getModels().getAsr() + " 转写，带毫秒级句/字时间戳"
                        : "规则实现没有语音识别能力，视频物料无法解析",
                !dashscope, unverifiedNote));
        capabilities.add(cap("文档文字提取（PDF/Word/PPT）",
                dashscope ? "AVAILABLE" : "PARTIAL",
                dashscope ? "优先取文本层，扫描件渲染页面图走 OCR 兜底"
                        : "规则实现只能读纯文本（.txt/.md/.csv）",
                !dashscope, unverifiedNote));
        capabilities.add(cap("风险识别与判断",
                dashscope ? "AVAILABLE" : "RULE_BASED",
                dashscope ? "由 " + props.getModels().getText() + " 识别候选并判断，服务端做结构化校验"
                        : "规则实现按关键词匹配，只能覆盖已知表述；主链路已通过 61 项端到端断言",
                !dashscope, unverifiedNote));
        capabilities.add(cap("AI 复审（三问）",
                dashscope ? "AVAILABLE" : "RULE_BASED",
                dashscope ? "模型基于新旧版本原文比对与语义理解作答"
                        : "规则实现按原文是否仍存在作答；主链路已通过端到端断言",
                !dashscope, unverifiedNote));
        capabilities.add(cap("知识库语义检索与重排",
                dashscope ? "AVAILABLE" : "UNAVAILABLE",
                dashscope
                        ? "由 " + props.getModels().getEmbedding() + " 向量召回 + "
                        + props.getModels().getRerank() + " 精排；向量缓存在 Redis"
                        : "规则实现按风险类型做确定性查询，不具备语义检索能力",
                !dashscope, unverifiedNote));
        out.put("capabilities", capabilities);

        out.put("note", dashscope
                ? "AI 结论均为初审意见，不构成法律结论；最终批准必须由有权限的法务人员完成。"
                + "当前为千问大模型实现，已实现但尚未实测，请按 README 的冒烟清单验证后再投入正式使用。"
                : "当前为规则实现，仅用于验证流程，不具备真实审核能力。"
                + "填写 DASHSCOPE_API_KEY 并把 GW_AI_PROVIDER 改为 dashscope 可启用千问大模型。");
        return Result.ok(out);
    }

    private static Map<String, Object> cap(String name, String status, String detail) {
        return cap(name, status, detail, false, null);
    }

    /**
     * @param verified         是否用真实模型调用验证过
     * @param verificationNote 未验证时说明"验证到什么程度、还差什么"
     */
    private static Map<String, Object> cap(String name, String status, String detail,
                                           boolean verified, String verificationNote) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status);
        m.put("detail", detail);
        m.put("verified", verified);
        m.put("verificationNote", verified ? null : verificationNote);
        return m;
    }

    /** 打码：保留前 3 位与后 4 位，够核对、不够冒用 */
    private static String mask(String key) {
        if (key == null || key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }
}
