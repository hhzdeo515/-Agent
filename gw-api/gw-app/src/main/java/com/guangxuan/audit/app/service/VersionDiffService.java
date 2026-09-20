package com.guangxuan.audit.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guangxuan.audit.common.enums.AnchorType;
import com.guangxuan.audit.common.enums.MaterialType;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.common.security.PermCode;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.persistence.entity.EvidenceAnchorEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialEntity;
import com.guangxuan.audit.infra.persistence.entity.MaterialVersionEntity;
import com.guangxuan.audit.infra.persistence.entity.VersionDiffEntity;
import com.guangxuan.audit.infra.persistence.mapper.EvidenceAnchorMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialMapper;
import com.guangxuan.audit.infra.persistence.mapper.MaterialVersionMapper;
import com.guangxuan.audit.infra.persistence.mapper.VersionDiffMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 版本差异（AGENTS.md 第 7 条）。
 *
 * <p>终审区需要回答"市场改了什么"，而不是"新版本是什么"。因此差异必须围绕
 * <b>证据锚点</b>而不是文件字节：锚点自带稳定 ID，能直接对上 Risk Case 的位置引用。
 *
 * <h3>算法</h3>
 * 对两个版本的文字类锚点序列做 <b>LCS（最长公共子序列）</b>差异，产出四类变更：
 * DELETED / INSERTED / REPLACED / UNCHANGED。自己实现而不引第三方 diff 库的原因：
 * 需求只针对"有序段落序列"这一种形态，成熟库（如 java-diff-utils）带来的
 * 行内字符级 diff、多种输出格式对本项目都是冗余；而依赖越少，
 * 事后复现同一对版本的差异结果就越可控。
 *
 * <h3>不假装算得出来</h3>
 * 画面内容的变化（构图、配色、人物、镜头）<b>无法用文本比对判定</b>。
 * 因此本服务对这类锚点如实输出 {@code NOT_SUPPORTED} 并写明原因，
 * 而不是给一个看起来像结论的空结果——AGENTS.md 第 15 条禁止把未实现的能力
 * 伪装成可靠可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionDiffService {

    private final VersionDiffMapper versionDiffMapper;
    private final EvidenceAnchorMapper anchorMapper;
    private final MaterialMapper materialMapper;
    private final MaterialVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    @Value("${gw.ai.pipeline-version:0.1.0}")
    private String pipelineVersion;

    /** 差异引擎标识，写入 version_diff.engine，用于解释算法升级后的结果变化 */
    private static final String ENGINE = "lcs-anchor-diff";

    /**
     * LCS 动态规划表的规模上限。
     *
     * <p>DP 需要 O(n×m) 的整型空间：1000×1000 约 4MB 可以接受，
     * 但 5000×5000 就会到 100MB 量级，足以拖垮一次请求。
     * 超过上限时降级为按序号配对，并在结果里标注降级原因——
     * 宁可给出精度较低但明确的结论，也不要让接口超时。
     */
    private static final long MAX_DP_CELLS = 4_000_000L;

    /** 可做文本比对的锚点类型。KEY_FRAME 无文本，不在此列。 */
    private static boolean isTextual(String anchorType) {
        return AnchorType.TEXT_LINE.name().equals(anchorType)
                || AnchorType.SPEECH_SENTENCE.name().equals(anchorType)
                || AnchorType.SUBTITLE_LINE.name().equals(anchorType)
                || AnchorType.DOC_PARAGRAPH.name().equals(anchorType)
                || AnchorType.DOC_SENTENCE.name().equals(anchorType);
    }

    // ════════════════════════════════════════════════════════════════════
    // 对外入口
    // ════════════════════════════════════════════════════════════════════

    /**
     * 生成（或复用）两个版本的差异。
     *
     * <p>同一对版本重复请求时复用已有记录：差异是"当时看到的事实"，
     * 重新计算不应产生第二份，否则复审记录引用的差异会指向哪一份就成了未知数。
     */
    @Transactional
    public DiffView generate(Actor actor, Long materialId, Long fromVersionId, Long toVersionId) {
        actor.requirePermission(PermCode.VERSION_DIFF_VIEW);

        MaterialEntity material = requireMaterial(materialId);
        Long baseId = fromVersionId;
        Long targetId = toVersionId;

        if (targetId == null) {
            targetId = material.getCurrentVersionId();
        }
        MaterialVersionEntity target = requireVersion(targetId);
        if (!Objects.equals(target.getMaterialId(), materialId)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "目标版本不属于该物料");
        }
        if (baseId == null) {
            baseId = target.getParentVersionId();
        }
        if (baseId == null) {
            // 没有上一版本可对比：这是 V1，属于正常情况而非错误
            return DiffView.firstVersion(material, target);
        }
        MaterialVersionEntity base = requireVersion(baseId);
        if (!Objects.equals(base.getMaterialId(), materialId)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "基准版本不属于该物料");
        }

        VersionDiffEntity cached = versionDiffMapper.findByPair(baseId, targetId);
        if (cached != null) {
            return toView(cached);
        }

        List<EvidenceAnchorEntity> baseAnchors = anchorMapper.listByVersion(baseId);
        List<EvidenceAnchorEntity> targetAnchors = anchorMapper.listByVersion(targetId);

        DiffComputation comp = compute(material, base, target, baseAnchors, targetAnchors);

        VersionDiffEntity entity = new VersionDiffEntity();
        entity.setMaterialId(materialId);
        entity.setBaseVersionId(baseId);
        entity.setTargetVersionId(targetId);
        entity.setDiffType(comp.diffType());
        entity.setPayload(toJson(comp.payload()));
        entity.setSummary(comp.summary());
        entity.setHasChange(comp.hasChange());
        entity.setEngine(ENGINE);
        entity.setPipelineVersion(pipelineVersion);
        versionDiffMapper.insert(entity);

        log.info("生成版本差异: material={} {} → {} 变更 {} 处（{}）",
                materialId, base.getVersionLabel(), target.getVersionLabel(),
                comp.changeCount(), comp.diffType());

        return toView(entity, comp.payload());
    }

    /** 读取某物料最近一次差异，供终审区进入时直接展示 */
    @Transactional(readOnly = true)
    public DiffView latest(Actor actor, Long materialId) {
        actor.requirePermission(PermCode.VERSION_DIFF_VIEW);
        VersionDiffEntity e = versionDiffMapper.findLatestByMaterial(materialId);
        return e == null ? null : toView(e);
    }

    // ════════════════════════════════════════════════════════════════════
    // 差异计算
    // ════════════════════════════════════════════════════════════════════

    private record DiffComputation(String diffType, Map<String, Object> payload,
                                   String summary, boolean hasChange, int changeCount) {
    }

    private DiffComputation compute(MaterialEntity material,
                                    MaterialVersionEntity base, MaterialVersionEntity target,
                                    List<EvidenceAnchorEntity> baseAnchors,
                                    List<EvidenceAnchorEntity> targetAnchors) {
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> unsupported = new ArrayList<>();

        // 按锚点类型分组比对：不同类型之间做 diff 没有意义
        // （把图片文字行和视频口播句混在一条序列里比，会得到看似有变化实则无关的结果）
        for (AnchorType type : AnchorType.values()) {
            List<EvidenceAnchorEntity> oldList = filterByType(baseAnchors, type);
            List<EvidenceAnchorEntity> newList = filterByType(targetAnchors, type);

            if (isTextual(type.name())) {
                changes.addAll(diffTextual(type, oldList, newList));
            } else if (!oldList.isEmpty() || !newList.isEmpty()) {
                // KEY_FRAME 等无文本锚点：如实说明无法比对，并给出计数供人工核对
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("anchorType", type.name());
                u.put("status", "NOT_SUPPORTED");
                u.put("baseCount", oldList.size());
                u.put("targetCount", newList.size());
                u.put("reason", switch (type) {
                    case KEY_FRAME -> "关键帧为画面内容，文本比对无法判断画面是否变化；"
                            + "请人工比对两侧关键帧，或依据字幕/口播的差异间接判断";
                    default -> "该锚点类型无文本内容，无法进行文本比对";
                });
                unsupported.add(u);
            }
        }

        // 物料级限制：即使文字全部未变，画面仍可能被改过
        if (material.getMaterialType() != null) {
            MaterialType mt = safeMaterialType(material.getMaterialType());
            if (mt == MaterialType.IMAGE || mt == MaterialType.VIDEO
                    || mt == MaterialType.PPT || mt == MaterialType.PDF) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("anchorType", "VISUAL_CONTENT");
                u.put("status", "NOT_SUPPORTED");
                u.put("reason", "画面、版式、配色的变化需视觉比对，当前差异仅覆盖文字与口播内容");
                unsupported.add(u);
            }
        }

        int changeCount = (int) changes.stream()
                .filter(c -> !"UNCHANGED".equals(c.get("changeType")))
                .count();

        boolean sameHash = base.getFileSha256() != null
                && base.getFileSha256().equals(target.getFileSha256());
        boolean hasChange = !sameHash || changeCount > 0;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("baseVersionLabel", base.getVersionLabel());
        payload.put("targetVersionLabel", target.getVersionLabel());
        payload.put("baseSha256", base.getFileSha256());
        payload.put("targetSha256", target.getFileSha256());
        payload.put("sameFileHash", sameHash);
        payload.put("changes", changes);
        payload.put("notSupported", unsupported);
        payload.put("stats", buildStats(changes, unsupported));

        String summary = buildSummary(base, target, changes, unsupported, sameHash);

        return new DiffComputation(resolveDiffType(material, changes, unsupported),
                payload, summary, hasChange, changeCount);
    }

    private Map<String, Object> buildStats(List<Map<String, Object>> changes,
                                           List<Map<String, Object>> unsupported) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", changes.size());
        for (String t : List.of("DELETED", "INSERTED", "REPLACED", "UNCHANGED")) {
            stats.put(t.toLowerCase(), changes.stream()
                    .filter(c -> t.equals(c.get("changeType"))).count());
        }
        stats.put("changed", changes.stream()
                .filter(c -> !"UNCHANGED".equals(c.get("changeType"))).count());
        stats.put("notSupportedGroups", unsupported.size());
        return stats;
    }

    private String buildSummary(MaterialVersionEntity base, MaterialVersionEntity target,
                                List<Map<String, Object>> changes,
                                List<Map<String, Object>> unsupported, boolean sameHash) {
        long changed = changes.stream()
                .filter(c -> !"UNCHANGED".equals(c.get("changeType"))).count();
        StringBuilder sb = new StringBuilder();
        sb.append(base.getVersionLabel()).append(" → ").append(target.getVersionLabel()).append("：");
        if (sameHash && changed == 0) {
            sb.append("文件哈希与文本内容均无变化");
        } else if (changed == 0) {
            sb.append("文本内容无变化（文件哈希已变，画面可能被修改）");
        } else {
            sb.append("检出 ").append(changed).append(" 处文本变化");
        }
        if (!unsupported.isEmpty()) {
            sb.append("；").append(unsupported.size()).append(" 类内容无法自动比对，需人工核对");
        }
        return sb.length() > 1000 ? sb.substring(0, 1000) : sb.toString();
    }

    private String resolveDiffType(MaterialEntity material,
                                   List<Map<String, Object>> changes,
                                   List<Map<String, Object>> unsupported) {
        // 同时存在可比对文本与不可比对内容时是 MIXED——这比只报 TEXT 更诚实
        boolean hasText = !changes.isEmpty();
        boolean hasUnsupported = !unsupported.isEmpty();
        if (hasText && hasUnsupported) {
            return "MIXED";
        }
        MaterialType mt = safeMaterialType(material.getMaterialType());
        return switch (mt) {
            case IMAGE -> "IMAGE";
            case VIDEO -> "VIDEO";
            case TEXT -> "TEXT";
            case PPT, PDF, WORD -> "DOC";
        };
    }

    // ── 文字类锚点的 LCS 差异 ───────────────────────────────────────────

    /**
     * 对一个锚点类型内的文字序列做差异。
     *
     * <p>比较用<b>归一化文本</b>（去空白与全角半角差异），展示用<b>原文</b>：
     * 否则"行业第一"与"行业 第一"会被判成删除+新增两条，看起来像是改了两处。
     */
    private List<Map<String, Object>> diffTextual(AnchorType type,
                                                  List<EvidenceAnchorEntity> oldList,
                                                  List<EvidenceAnchorEntity> newList) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (oldList.isEmpty() && newList.isEmpty()) {
            return out;
        }

        List<String> a = oldList.stream().map(x -> normalize(x.getText())).toList();
        List<String> b = newList.stream().map(x -> normalize(x.getText())).toList();

        boolean degraded = (long) a.size() * b.size() > MAX_DP_CELLS;
        List<int[]> aligned = degraded ? pairByIndex(a, b) : lcsPairs(a, b);

        // 依据对齐结果切出 UNCHANGED / DELETED / INSERTED 三种片段
        List<Segment> segs = new ArrayList<>();
        int i = 0, j = 0;
        for (int[] p : aligned) {
            if (p[0] > i) {
                segs.add(new Segment("DELETED", i, p[0], j, p[1]));
            }
            if (p[1] > j) {
                segs.add(new Segment("INSERTED", i, p[0], j, p[1]));
            }
            segs.add(new Segment("UNCHANGED", p[0], p[0] + 1, p[1], p[1] + 1));
            i = p[0] + 1;
            j = p[1] + 1;
        }
        if (i < a.size() || j < b.size()) {
            if (i < a.size()) {
                segs.add(new Segment("DELETED", i, a.size(), j, b.size()));
            }
            if (j < b.size()) {
                segs.add(new Segment("INSERTED", i, a.size(), j, b.size()));
            }
        }

        // 单趟按文档顺序输出：把 DELETED 与其紧邻的 INSERTED 合并为 REPLACED——
        // 对法务而言"这句被改成了那句"比"删了一句又加了一句"更贴近事实。
        //
        // 必须单趟处理。早前的写法分三趟（先删除、再插入、最后未变），
        // 结果是 changes 数组并不按文档顺序排列：同一段材料里的变化会被打散成
        // 两组，法务按顺序阅读时会以为改动发生在别处。
        int k = 0;
        while (k < segs.size()) {
            Segment s = segs.get(k);
            if ("DELETED".equals(s.kind)) {
                Segment next = (k + 1 < segs.size()) ? segs.get(k + 1) : null;
                if (next != null && "INSERTED".equals(next.kind)) {
                    out.addAll(buildReplaced(type, oldList, newList, s, next));
                    k += 2;
                } else {
                    out.addAll(build(type, oldList, newList, s));
                    k++;
                }
            } else if ("INSERTED".equals(s.kind)) {
                out.addAll(build(type, oldList, newList, s));
                k++;
            } else {
                // UNCHANGED 全部保留：统计需要真实分母（"改了 3 处 / 共 40 处"），
                // 前端展示时再过滤掉，不在数据层丢信息
                out.addAll(buildUnchanged(type, oldList, s));
                k++;
            }
        }

        if (degraded) {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("anchorType", type.name());
            note.put("status", "DEGRADED");
            note.put("reason", "锚点数量过大（" + a.size() + "×" + b.size()
                    + "），已降级为按序号配对，可能漏报位置调换");
            out.add(note);
        }
        return out;
    }

    private record Segment(String kind, int aFrom, int aTo, int bFrom, int bTo) {
    }

    private List<Map<String, Object>> build(AnchorType type,
                                            List<EvidenceAnchorEntity> oldList,
                                            List<EvidenceAnchorEntity> newList,
                                            Segment s) {
        List<Map<String, Object>> out = new ArrayList<>();
        if ("DELETED".equals(s.kind)) {
            for (int x = s.aFrom; x < s.aTo; x++) {
                out.add(change("DELETED", type, oldList.get(x), null));
            }
        } else if ("INSERTED".equals(s.kind)) {
            for (int y = s.bFrom; y < s.bTo; y++) {
                out.add(change("INSERTED", type, null, newList.get(y)));
            }
        }
        return out;
    }

    /** 把一段删除与紧随其后的插入逐条配对为替换，多出来的部分退化为纯删除/纯插入 */
    private List<Map<String, Object>> buildReplaced(AnchorType type,
                                                    List<EvidenceAnchorEntity> oldList,
                                                    List<EvidenceAnchorEntity> newList,
                                                    Segment del, Segment ins) {
        List<Map<String, Object>> out = new ArrayList<>();
        int oldN = del.aTo - del.aFrom;
        int newN = ins.bTo - ins.bFrom;
        int pairs = Math.min(oldN, newN);
        for (int k = 0; k < pairs; k++) {
            out.add(change("REPLACED", type,
                    oldList.get(del.aFrom + k), newList.get(ins.bFrom + k)));
        }
        for (int k = pairs; k < oldN; k++) {
            out.add(change("DELETED", type, oldList.get(del.aFrom + k), null));
        }
        for (int k = pairs; k < newN; k++) {
            out.add(change("INSERTED", type, null, newList.get(ins.bFrom + k)));
        }
        return out;
    }

    private List<Map<String, Object>> buildUnchanged(AnchorType type,
                                                     List<EvidenceAnchorEntity> oldList,
                                                     Segment s) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int x = s.aFrom; x < s.aTo; x++) {
            out.add(change("UNCHANGED", type, oldList.get(x), null));
        }
        return out;
    }

    private Map<String, Object> change(String changeType, AnchorType type,
                                       EvidenceAnchorEntity oldA, EvidenceAnchorEntity newA) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("changeType", changeType);
        c.put("anchorType", type.name());
        c.put("baseAnchorId", oldA == null ? null : oldA.getAnchorId());
        c.put("targetAnchorId", newA == null ? null : newA.getAnchorId());
        c.put("baseText", oldA == null ? null : oldA.getText());
        c.put("targetText", newA == null ? null : newA.getText());
        c.put("baseOrdinal", oldA == null ? null : oldA.getOrdinal());
        c.put("targetOrdinal", newA == null ? null : newA.getOrdinal());
        // 位置信息原样带出，前端可据此跳到原图区域 / 视频时间点
        c.put("baseLocator", oldA == null ? null : oldA.getLocator());
        c.put("targetLocator", newA == null ? null : newA.getLocator());
        return c;
    }

    // ── LCS ─────────────────────────────────────────────────────────────

    /**
     * 最长公共子序列，返回配对下标。
     *
     * <p>用 LCS 而非逐行比对：整改通常是"删掉一句、别处加一句"，
     * 逐行比对会把后面所有行都标成变化，反而看不出真正改了哪一句。
     */
    private static List<int[]> lcsPairs(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = Objects.equals(a.get(i), b.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<int[]> pairs = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (Objects.equals(a.get(i), b.get(j))) {
                pairs.add(new int[]{i, j});
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return pairs;
    }

    /** 降级路径：按序号一一配对（只用于锚点数量超限时） */
    private static List<int[]> pairByIndex(List<String> a, List<String> b) {
        List<int[]> pairs = new ArrayList<>();
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            if (Objects.equals(a.get(i), b.get(i))) {
                pairs.add(new int[]{i, i});
            }
        }
        return pairs;
    }

    /**
     * 归一化：用于比较，不用于展示。
     *
     * <p>去空白 + 全角转半角 + 统一小写。目的是让"纯格式差异"不被误报为内容变化——
     * 把标点从全角改成半角，对法务而言不是一次实质修改。
     */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c = (char) (c - 0xFEE0);
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static List<EvidenceAnchorEntity> filterByType(List<EvidenceAnchorEntity> list,
                                                           AnchorType type) {
        List<EvidenceAnchorEntity> out = new ArrayList<>();
        for (EvidenceAnchorEntity a : list) {
            if (type.name().equals(a.getAnchorType()) && a.getText() != null
                    && !a.getText().isBlank()) {
                out.add(a);
            }
        }
        return out;
    }

    // ── 组装与工具 ──────────────────────────────────────────────────────

    private DiffView toView(VersionDiffEntity e) {
        return new DiffView(e, readPayload(e.getPayload()));
    }

    private DiffView toView(VersionDiffEntity e, Map<String, Object> payload) {
        return new DiffView(e, payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            // 读取失败不能让整个接口挂掉：差异正文仍是有效的审计记录
            log.warn("version_diff.payload 解析失败: {}", ex.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("parseError", "差异内容无法解析，请查看原始记录");
            return fallback;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "差异内容序列化失败");
        }
    }

    private MaterialEntity requireMaterial(Long materialId) {
        MaterialEntity m = materialMapper.selectById(materialId);
        if (m == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_VISIBLE);
        }
        return m;
    }

    private MaterialVersionEntity requireVersion(Long versionId) {
        MaterialVersionEntity v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_VISIBLE);
        }
        return v;
    }

    private static MaterialType safeMaterialType(String name) {
        try {
            return MaterialType.valueOf(name);
        } catch (Exception e) {
            return MaterialType.TEXT;
        }
    }

    /** 差异视图：实体 + 已解析的 payload，前端不必再解析一次 JSON */
    public record DiffView(VersionDiffEntity entity, Map<String, Object> payload) {

        /** V1 没有可比对的上一版本，这不是错误，如实返回 */
        static DiffView firstVersion(MaterialEntity material, MaterialVersionEntity v) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("firstVersion", true);
            p.put("versionLabel", v.getVersionLabel());
            p.put("note", "这是首个版本，没有可比对的上一版本");
            p.put("changes", List.of());
            p.put("notSupported", List.of());
            VersionDiffEntity e = new VersionDiffEntity();
            e.setMaterialId(material.getId());
            e.setTargetVersionId(v.getId());
            e.setDiffType("TEXT");
            e.setHasChange(false);
            e.setEngine(ENGINE);
            e.setSummary("首个版本，无可比对基准");
            e.setPayload("{}");
            e.setCreatedAt(LocalDateTime.now());
            return new DiffView(e, p);
        }
    }
}
