package com.guangxuan.audit.app.service;

import com.guangxuan.audit.common.enums.AnchorType;
import com.guangxuan.audit.common.enums.MaterialType;
import com.guangxuan.audit.common.enums.ParseStatus;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.domain.port.ParsePort;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.persistence.entity.EvidenceAnchorEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialVersionEntity;
import com.guangxuan.audit.infra.persistence.mapper.EvidenceAnchorMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialVersionMapper;
import com.guangxuan.audit.infra.persistence.mapper.RiskCaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析应用服务：把物料版本转成"可审核内容 + 证据锚点"。
 *
 * <p>这是整套定位能力的地基（docs/00 §3）：解析阶段产出带稳定 ID 的锚点，
 * 模型只引用锚点 ID，定位 = ID 查表。
 *
 * <p>真实环境应把本方法提交到异步队列并回报真实进度（AGENTS.md 第 14 条）；
 * 此处同步执行以便端到端联调。无论同步还是异步，
 * <b>解析失败的物料必须阻止进入正式初审并明确告知需要重传什么</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseAppService {

    private final ParsePort parsePort;
    private final MaterialMapper materialMapper;
    private final MaterialVersionMapper versionMapper;
    private final EvidenceAnchorMapper anchorMapper;
    private final RiskCaseMapper riskCaseMapper;
    private final IntakeAppService intakeAppService;

    /**
     * 解析一个物料（按其当前版本）。
     *
     * @return 产出的锚点数量
     */
    @Transactional
    public int parse(Actor actor, Long materialId) {
        MaterialEntity material = materialMapper.selectById(materialId);
        if (material == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "物料不存在：" + materialId);
        }
        Long versionId = material.getCurrentVersionId();
        if (versionId == null) {
            intakeAppService.updateParseStatus(actor, materialId, ParseStatus.FAILED,
                    "NO_VERSION", "该物料还没有任何版本，请先上传文件");
            return 0;
        }
        MaterialVersionEntity version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "版本不存在：" + versionId);
        }

        intakeAppService.updateParseStatus(actor, materialId, ParseStatus.RUNNING, null, null);

        MaterialType type = MaterialType.valueOf(material.getMaterialType());
        try {
            List<EvidenceAnchorEntity> anchors = switch (type) {
                case TEXT, WORD, PDF, PPT -> parseAsDocument(version, type);
                case IMAGE -> parseAsImage(version);
                case VIDEO -> parseAsVideo(version);
            };

            if (anchors.isEmpty()) {
                // 没有锚点意味着后续风险无处定位，必须如实标记为失败或部分成功，
                // 而不是让物料"看起来解析成功但什么都审不出来"
                intakeAppService.updateParseStatus(actor, materialId,
                        type == MaterialType.VIDEO ? ParseStatus.PARTIAL : ParseStatus.FAILED,
                        "NO_ANCHOR",
                        "未能从该物料中提取到可审核的文本或画面锚点，请确认文件内容是否清晰、是否加密或受损");
                return 0;
            }

            for (EvidenceAnchorEntity a : anchors) {
                anchorMapper.insert(a);
            }

            // 视频若只有口播没有字幕，仍属可用（部分解析），但置信度会被封顶
            ParseStatus status = (type == MaterialType.VIDEO && anchors.stream()
                    .noneMatch(a -> AnchorType.SUBTITLE_LINE.name().equals(a.getAnchorType())))
                    ? ParseStatus.PARTIAL : ParseStatus.SUCCEEDED;
            intakeAppService.updateParseStatus(actor, materialId, status, null, null);

            // 新版本解析成功 → 把该物料下"整改中"的风险推进为"已重新提交"。
            // 这一步是终审区闭环的起点：市场/设计上传新版本后，风险应当自动进入待复审，
            // 而不是要求法务手工逐条推动（AGENTS.md 第 7 条）。
            advanceRemediationRisks(actor, material, versionId);

            // 解析也可能正是"最后一个待满足条件"，因此每次解析后都要尝试推进任务状态；
            // 否则任务会一直卡在 PARSING，导致无法启动初审。
            intakeAppService.tryAdvanceToReady(actor, material.getCaseId());

            log.info("物料 {} 解析完成：类型={} 锚点={} 状态={}",
                    materialId, type, anchors.size(), status);
            return anchors.size();

        } catch (DomainException e) {
            // 明确的能力缺失（例如 Mock 模式不支持 OCR）：原样把原因透给用户，
            // 不要包装成"解析过程出错"——用户需要知道的是"该配什么"，不是"出错了"
            log.warn("物料 {} 解析被拒：{}", materialId, e.getMessage());
            intakeAppService.updateParseStatus(actor, materialId, ParseStatus.FAILED,
                    e.errorCode().name(), e.getMessage());
            return 0;
        } catch (Exception e) {
            log.error("物料 {} 解析异常", materialId, e);
            intakeAppService.updateParseStatus(actor, materialId, ParseStatus.FAILED,
                    "PARSE_ERROR", "解析过程出错：" + e.getMessage() + "，请重新上传或联系管理员");
            return 0;
        }
    }

    // ── 各类型解析 ──────────────────────────────────────────────────────

    /**
     * 新版本解析成功 → 推进"整改中"的风险为"已重新提交"。
     *
     * <p>注意这里只做状态推进，<b>不</b>做复审判断——复审必须显式触发，
     * 且范围受 {@code ReviewScope} 限制，以避免终审区退化为第二次全量初审。
     */
    private void advanceRemediationRisks(Actor actor, MaterialEntity material, Long newVersionId) {
        var awaiting = riskCaseMapper.listAwaitingRevisionByMaterial(material.getId());
        for (var e : awaiting) {
            var domain = new com.guangxuan.audit.domain.risk.RiskCase();
            domain.setId(e.getId());
            domain.setRiskNo(e.getRiskNo());
            domain.setCaseId(e.getCaseId());
            domain.setCurrentVersionId(e.getCurrentVersionId());
            domain.setLockVersion(e.getLockVersion());
            domain.setStatusDirectly(com.guangxuan.audit.common.enums.RiskStatus
                    .valueOf(e.getStatus()));

            var result = com.guangxuan.audit.domain.risk.RiskStateMachine
                    .markResubmitted(domain, actor, newVersionId);

            int affected = riskCaseMapper.updateStatusWithLock(
                    e.getId(), result.to().name(), e.getLockVersion());
            if (affected > 0) {
                riskCaseMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<
                                com.guangxuan.audit.infra.persistence.entity.RiskCaseEntity>()
                                .eq("id", e.getId())
                                .set("current_version_id", newVersionId));
                log.info("风险 {} 推进为已重新提交（新版本 {}）", e.getRiskNo(), newVersionId);
            }
        }
    }

    private List<EvidenceAnchorEntity> parseAsDocument(MaterialVersionEntity v, MaterialType type) {
        ParsePort.DocumentResult doc = parsePort.parseDocument(
                new ParsePort.DocumentRequest(v.getFileObjectKey(), v.getMimeType(), type.name()));

        List<EvidenceAnchorEntity> out = new ArrayList<>();
        int ordinal = 0;
        for (ParsePort.DocParagraph p : doc.paragraphs()) {
            if (p.text() == null || p.text().isBlank()) {
                continue;
            }
            ordinal++;
            out.add(anchor(v, "A-" + String.format("%04d", ordinal),
                    AnchorType.DOC_PARAGRAPH,
                    locatorForParagraph(p), p.text(), null,
                    doc.engine(), doc.engineVersion(), ordinal));
        }
        return out;
    }

    private List<EvidenceAnchorEntity> parseAsImage(MaterialVersionEntity v) {
        ParsePort.OcrResult ocr = parsePort.ocr(
                new ParsePort.OcrRequest(v.getFileObjectKey(), v.getMimeType(), "CHN_ENG"));

        List<EvidenceAnchorEntity> out = new ArrayList<>();
        int ordinal = 0;
        for (ParsePort.OcrLine line : ocr.lines()) {
            if (line.text() == null || line.text().isBlank()) {
                continue;
            }
            ordinal++;
            out.add(anchor(v, "A-" + String.format("%04d", ordinal),
                    AnchorType.TEXT_LINE,
                    locatorForTextLine(line), line.text(),
                    line.confidence() == null ? null
                            : BigDecimal.valueOf(line.confidence()).setScale(4, RoundingMode.HALF_UP),
                    ocr.engine(), ocr.engineVersion(), ordinal));
        }
        return out;
    }

    private List<EvidenceAnchorEntity> parseAsVideo(MaterialVersionEntity v) {
        // 视频需要两条轨：口播（ASR）与硬字幕（抽帧+OCR）。
        // 真实实现应先用 ffmpeg 抽音轨再提交 Filetrans 异步任务；
        // 此处调用 ParsePort，由具体适配器决定。
        ParsePort.AsrResult asr = parsePort.asr(
                new ParsePort.AsrRequest(v.getFileObjectKey(), v.getMimeType(), false, false));

        List<EvidenceAnchorEntity> out = new ArrayList<>();
        int ordinal = 0;
        for (ParsePort.AsrSentence s : asr.sentences()) {
            if (s.text() == null || s.text().isBlank()) {
                continue;
            }
            ordinal++;
            out.add(anchor(v, "A-" + String.format("%04d", ordinal),
                    AnchorType.SPEECH_SENTENCE,
                    locatorForSpeech(s), s.text(), null,
                    asr.engine(), asr.engineVersion(), ordinal));
        }
        return out;
    }

    // ── locator 构造（结构见 docs/01 §6）────────────────────────────────

    private String locatorForParagraph(ParsePort.DocParagraph p) {
        // page_no 对 DOCX 为 null —— Word 分页在渲染期决定，文件不存页码（docs/06 §3）
        return String.format(
                "{\"page_no\":%s,\"para_index\":%d,\"char_range\":[%d,%d]}",
                p.pageNo() == null ? "null" : p.pageNo(),
                p.paraIndex(), p.charStart(), p.charEnd());
    }

    private String locatorForTextLine(ParsePort.OcrLine line) {
        // bbox 以左上角为原点；scale_ratio 用于前端还原到原图（docs/01 §6.1）
        return String.format(
                "{\"bbox\":{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d},"
                        + "\"scale_ratio\":1.0,\"ocr_line_no\":%d}",
                line.x(), line.y(), line.w(), line.h(), line.lineNo());
    }

    private String locatorForSpeech(ParsePort.AsrSentence s) {
        // 注意：音频内时间戳是毫秒整数；不要与"任务级 end_time（日期字符串）"混淆
        return String.format(
                "{\"begin_ms\":%d,\"end_ms\":%d,\"sentence_id\":%d%s%s}",
                s.beginMs(), s.endMs(), s.sentenceId(),
                s.speakerId() == null ? "" : ",\"speaker_id\":" + s.speakerId(),
                s.emotion() == null ? "" : ",\"emotion\":\"" + s.emotion() + "\"");
    }

    private EvidenceAnchorEntity anchor(MaterialVersionEntity v, String anchorId,
                                        AnchorType type, String locator, String text,
                                        BigDecimal confidence, String engine,
                                        String engineVersion, int ordinal) {
        EvidenceAnchorEntity a = new EvidenceAnchorEntity();
        a.setAnchorId(anchorId);
        a.setMaterialVersionId(v.getId());
        a.setAnchorType(type.name());
        a.setLocator(locator);
        a.setText(text);
        a.setConfidence(confidence);
        // source_engine 标记为 mock 时，说明这是联调数据而非真实解析结果，
        // 便于在数据里区分真假、避免用假坐标做定位精度验收
        a.setSourceEngine(engine);
        a.setSourceEngineVersion(engineVersion);
        a.setOrdinal(ordinal);
        return a;
    }
}
