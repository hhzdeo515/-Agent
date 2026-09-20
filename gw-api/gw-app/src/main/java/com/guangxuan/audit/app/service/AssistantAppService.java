package com.guangxuan.audit.app.service;

import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.port.AiInferencePort;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 法务助手（AGENTS.md 第 3 条）。
 *
 * <p><b>职责边界（这是本类最重要的一段）</b>：助手是轻量、即时的咨询入口，
 * <b>不能</b>创建正式审核任务、<b>不能</b>对多份材料执行批次审核、<b>不能</b>生成正式通过率、
 * <b>不能</b>改变 Risk Case 状态，也<b>不能</b>执行终审、签名、批准或关闭。
 * 因此本类不依赖任何 Risk 或 Case 的写服务——从依赖关系上就做不到那些事。
 *
 * <p>用户若希望把咨询转成正式审核，走 {@link #promoteToFormal}：
 * 它只返回「预填充数据」，真正的创建仍由用户在接收区确认后触发。
 *
 * <p>⚠️ 会话状态当前放在内存里（进程重启即失效），因为助手不需要长期留痕。
 * 正式审核链路的所有状态都在数据库且不可变，两者刻意不同。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantAppService {

    private final AiInferencePort aiInferencePort;
    private final StorageService storageService;

    /** 会话上下文：sessionId -> 最近若干轮问答。仅用于让助手"记得"前文。 */
    private final Map<String, List<String>> sessions = new ConcurrentHashMap<>();

    private static final int MAX_HISTORY = 6;

    /**
     * 单条咨询。
     *
     * @param attachmentName 临时附件名，可为空
     * @param attachmentText 附件的已提取文本；为空时仅凭文本问题作答
     */
    @Transactional(readOnly = true)
    public ConsultationResult chat(Actor actor, String sessionId, String message,
                                   String attachmentName, String attachmentText) {
        actor.requirePermission(PermCode.ASSISTANT_USE);

        if (message == null || message.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "请输入要咨询的内容");
        }

        String sid = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : sessionId;

        // 附件文本并入问题一起分析；助手不接收正式物料，附件是临时的
        String fullQuestion = attachmentText == null || attachmentText.isBlank()
                ? message
                : message + "\n\n【附件内容】\n" + attachmentText;

        AiInferencePort.Attachment attachment = attachmentName == null ? null
                : new AiInferencePort.Attachment(attachmentName, null, attachmentText);

        AiInferencePort.ConsultationAnswer answer = aiInferencePort.consult(
                new AiInferencePort.ConsultationRequest(fullQuestion, attachment));

        remember(sid, message, answer.reply());

        log.info("助手咨询完成: session={} 命中风险 {} 处 建议转正式={}",
                sid, answer.risks().size(), answer.needsFormalReview());

        return new ConsultationResult(sid, answer.reply(), answer.risks(),
                answer.trace(), answer.needsFormalReview());
    }

    /**
     * 助手内临时上传文件。
     *
     * <p>与正式物料的区别：这里不建 Material、不建 Version、不进审核流程，
     * 只把内容放到对象存储的临时前缀下，并尽量提取文本供本次咨询使用。
     *
     * @return 提取到的文本与对象键
     */
    @Transactional(readOnly = true)
    public TempUploadResult uploadTemp(Actor actor, String fileName, String contentType, byte[] content) {
        actor.requirePermission(PermCode.ASSISTANT_FILE_UPLOAD);
        if (content == null || content.length == 0) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "文件内容为空");
        }
        if (content.length > 20 * 1024 * 1024) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "助手内临时文件不得超过 20MB");
        }

        // caseId / materialId 传 0：明确标记这不是正式物料，避免与正式路径混淆
        StorageService.UploadedFile f = storageService.put(0L, 0L, "assistant-temp",
                fileName, content, contentType);

        // 纯文本类文件直接读取内容；其他类型当前不做解析（真实环境应走 ParsePort）
        String extracted = tryExtractText(fileName, contentType, content);

        log.info("助手临时文件已入库: {} ({} bytes)", f.objectKey(), f.size());
        return new TempUploadResult(f.objectKey(), f.sha256(), f.size(), extracted);
    }

    /**
     * 转为正式审核任务：只生成预填充数据，<b>不创建任何记录</b>。
     *
     * <p>AGENTS.md 第 3 条要求：提供「转为正式审核任务」入口，把输入材料和已填写信息
     * 带入接收区，由用户确认后再创建任务。因此本方法只做数据打包。
     */
    @Transactional(readOnly = true)
    public PromoteDraft promoteToFormal(Actor actor, String sessionId, String message) {
        actor.requirePermission(PermCode.ASSISTANT_TO_FORMAL_CASE);
        return new PromoteDraft(
                "由助手咨询转入",
                message,
                List.of("绝对化宣传", "安全承诺", "无依据数据"),
                List.of());
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private void remember(String sessionId, String question, String reply) {
        List<String> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
        history.add("Q: " + question);
        history.add("A: " + reply);
        while (history.size() > MAX_HISTORY * 2) {
            history.remove(0);
        }
    }

    /** 只对纯文本类做提取；二进制类型需要解析能力，助手场景下不展开 */
    private String tryExtractText(String fileName, String contentType, byte[] content) {
        boolean isText = (contentType != null && contentType.startsWith("text/"))
                || (fileName != null && (fileName.endsWith(".txt") || fileName.endsWith(".md")
                || fileName.endsWith(".csv") || fileName.endsWith(".json")));
        if (!isText) {
            return "";
        }
        String text = new String(content, StandardCharsets.UTF_8);
        // 防止超长文本拖垮对话
        return text.length() > 8000 ? text.substring(0, 8000) + "\n…（内容过长已截断）" : text;
    }

    // ── 结果类型 ────────────────────────────────────────────────────────

    public record ConsultationResult(String sessionId, String reply,
                                     List<com.guangxuan.audit.domain.ai.RiskDraft> risks,
                                     List<AiInferencePort.TraceStep> trace,
                                     boolean needsFormalReview) {
    }

    public record TempUploadResult(String objectKey, String sha256, long size, String extractedText) {
    }

    public record PromoteDraft(String proposedName, String sourceText,
                               List<String> suggestedRequirements,
                               List<String> attachmentKeys) {
    }
}
