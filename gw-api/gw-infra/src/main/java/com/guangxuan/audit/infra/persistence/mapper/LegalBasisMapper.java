package com.guangxuan.audit.infra.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 法定依据查询。
 *
 * <p>这是"AI 结论有依据"的数据来源：风险类型 → 风险规则 → 关联法条 → 条款原文。
 *
 * <p>它同时是<b>防虚构机制</b>的一环：{@code rule_refs} 只能填写这里查出来的
 * {@code kbItemVersionId}，模型无法凭空造出一个合法的 ID（docs/03 §5.3）。
 */
@Mapper
public interface LegalBasisMapper {

    /** 规则与其关联法条（含条款切片），按风险类型查 */
    @Select("""
            SELECT r.rule_code          AS ruleCode,
                   r.name               AS ruleName,
                   r.description        AS ruleDesc,
                   r.severity_default   AS severity,
                   i.title              AS lawTitle,
                   v.id                 AS kbVersionId,
                   c.chunk_no           AS chunkNo,
                   c.text               AS chunkText
              FROM risk_rule r
              JOIN risk_rule_kb_ref ref ON ref.rule_id = r.id
              JOIN kb_item_version v    ON v.id = ref.kb_item_version_id
              JOIN kb_item i            ON i.id = v.kb_item_id
              LEFT JOIN kb_chunk c      ON c.kb_item_version_id = v.id
             WHERE r.rule_type = #{riskType}
               AND r.status = 'ACTIVE'
               AND i.governance_status = 'PUBLISHED'
             ORDER BY r.id, c.chunk_no
            """)
    List<LegalBasisRow> findByRiskType(@Param("riskType") String riskType);

    /** 关键词检索条款：用于把用户表述与具体条款对上 */
    @Select("""
            SELECT i.title AS lawTitle, v.id AS kbVersionId, c.chunk_no AS chunkNo, c.text AS chunkText
              FROM kb_chunk c
              JOIN kb_item_version v ON v.id = c.kb_item_version_id
              JOIN kb_item i         ON i.id = v.kb_item_id
             WHERE i.governance_status = 'PUBLISHED'
               AND c.text LIKE CONCAT('%', #{keyword}, '%')
             LIMIT 5
            """)
    List<LegalBasisRow> searchByKeyword(@Param("keyword") String keyword);

    /** 全部已发布法规的条款，供助手兜底检索 */
    @Select("""
            SELECT i.title AS lawTitle, v.id AS kbVersionId, c.chunk_no AS chunkNo, c.text AS chunkText
              FROM kb_chunk c
              JOIN kb_item_version v ON v.id = c.kb_item_version_id
              JOIN kb_item i         ON i.id = v.kb_item_id
             WHERE i.governance_status = 'PUBLISHED'
             ORDER BY i.id, c.chunk_no
            """)
    List<LegalBasisRow> listAllPublished();

    /** 一行 = 一条规则 × 一个条款切片（无可选字段，与 strict schema 的约束风格一致） */
    class LegalBasisRow {
        private String ruleCode;
        private String ruleName;
        private String ruleDesc;
        private String severity;
        private String lawTitle;
        private Long kbVersionId;
        private Integer chunkNo;
        private String chunkText;

        public String getRuleCode() { return ruleCode; }
        public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
        public String getRuleName() { return ruleName; }
        public void setRuleName(String ruleName) { this.ruleName = ruleName; }
        public String getRuleDesc() { return ruleDesc; }
        public void setRuleDesc(String ruleDesc) { this.ruleDesc = ruleDesc; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getLawTitle() { return lawTitle; }
        public void setLawTitle(String lawTitle) { this.lawTitle = lawTitle; }
        public Long getKbVersionId() { return kbVersionId; }
        public void setKbVersionId(Long kbVersionId) { this.kbVersionId = kbVersionId; }
        public Integer getChunkNo() { return chunkNo; }
        public void setChunkNo(Integer chunkNo) { this.chunkNo = chunkNo; }
        public String getChunkText() { return chunkText; }
        public void setChunkText(String chunkText) { this.chunkText = chunkText; }
    }
}
