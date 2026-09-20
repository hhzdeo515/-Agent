package com.guangxuan.audit.boot.controller;

import com.guangxuan.audit.app.service.AssistantAppService;
import com.guangxuan.audit.common.api.Result;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.domain.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 法务助手接口（AGENTS.md 第 3 条）。
 *
 * <p><b>本模块明确不提供</b>：创建正式审核任务、批次审核、批次通过率、
 * 修改 Risk Case 状态、终审、签名、批准、关闭。因此这里没有也不会有那些端点。
 *
 * <p>「转为正式审核任务」在本控制器里<b>只返回预填充数据</b>，
 * 真正的创建仍走 {@code POST /api/intake/cases}，由用户在接收区确认后触发。
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantAppService assistantAppService;

    // ── 单条咨询 ────────────────────────────────────────────────────────

    public record ChatRequest(@NotBlank String message,
                              String sessionId,
                              String attachmentName,
                              String attachmentText) {
    }

    /**
     * 单条咨询。
     *
     * <p>支持携带一个临时附件（已在 {@code /upload} 上传并提取出文本），
     * 但附件不进入正式物料库，也不会产生 Risk Case。
     */
    @PostMapping("/chat")
    @PreAuthorize("@perm.has('assistant.use')")
    public Result<Map<String, Object>> chat(Actor actor, @RequestBody @Valid ChatRequest req) {
        AssistantAppService.ConsultationResult r = assistantAppService.chat(
                actor, req.sessionId(), req.message(),
                req.attachmentName(), req.attachmentText());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", r.sessionId());
        out.put("reply", r.reply());
        out.put("needsFormalReview", r.needsFormalReview());
        out.put("risks", r.risks());
        // 推理过程原样返回，前端折叠展示「AI 是怎么想的」
        out.put("trace", r.trace().stream().map(t -> {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stage", t.stage());
            step.put("summary", t.summary());
            step.put("details", t.details());
            step.put("costMs", t.costMs());
            return step;
        }).toList());
        return Result.ok(out);
    }

    // ── 助手内临时上传 ──────────────────────────────────────────────────

    /**
     * 助手内上传临时文件。
     *
     * <p>与接收区的上传是两件事：这里不建 Material / Version、不进审核流程、
     * 不产生风险记录，仅用于本次咨询的即时分析。
     */
    @PostMapping("/upload")
    @PreAuthorize("@perm.has('assistant.file_upload')")
    public Result<Map<String, Object>> upload(Actor actor,
                                              @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "请选择要上传的文件");
        }
        try {
            AssistantAppService.TempUploadResult r = assistantAppService.uploadTemp(
                    actor, file.getOriginalFilename(), file.getContentType(), file.getBytes());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("objectKey", r.objectKey());
            out.put("sha256", r.sha256());
            out.put("size", r.size());
            out.put("fileName", file.getOriginalFilename());
            // 提取到的文本回传前端，下次 /chat 时带上即可让助手分析该文件
            out.put("extractedText", r.extractedText());
            out.put("textExtracted", !r.extractedText().isEmpty());
            return Result.ok(out);
        } catch (IOException e) {
            throw new DomainException(ErrorCode.STORAGE_ERROR, "读取上传文件失败");
        }
    }

    // ── 转为正式审核（仅预填充） ────────────────────────────────────────

    public record PromoteRequest(String sessionId, @NotBlank String message) {
    }

    /**
     * 生成「转为正式审核任务」的预填充数据。
     *
     * <p>刻意<b>不</b>在这里创建 Case：正式审核任务的创建必须由人在接收区明确确认，
     * 否则会污染正式审核台账与通过率统计口径（AGENTS.md 第 3、4 条）。
     */
    @PostMapping("/promote")
    @PreAuthorize("@perm.has('assistant.to_formal_case')")
    public Result<Map<String, Object>> promote(Actor actor, @RequestBody @Valid PromoteRequest req) {
        AssistantAppService.PromoteDraft d = assistantAppService.promoteToFormal(
                actor, req.sessionId(), req.message());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposedCaseName", d.proposedName());
        out.put("sourceText", d.sourceText());
        out.put("suggestedRequirements", d.suggestedRequirements());
        out.put("attachmentKeys", d.attachmentKeys());
        out.put("targetEndpoint", "/api/intake/cases");
        out.put("note", "需在接收区确认后才会创建正式任务");
        return Result.ok(out);
    }

    // ── 能力说明（供前端渲染侧栏，避免把边界写死在界面里） ──────────────

    @GetMapping("/capabilities")
    public Result<Map<String, Object>> capabilities() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("can", List.of("单条宣传语或文案分析", "临时上传图片或文档",
                "解释风险原因与适用规则", "给出改写建议与所需材料", "转为正式审核任务"));
        out.put("cannot", List.of("创建正式审核任务", "对多份材料做批次审核",
                "生成批次通过率或改变风险状态", "执行终审、签名、批准或关闭风险"));
        return Result.ok(out);
    }
}
