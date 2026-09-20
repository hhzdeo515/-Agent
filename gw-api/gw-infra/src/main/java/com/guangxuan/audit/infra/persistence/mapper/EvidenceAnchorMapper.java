package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.EvidenceAnchorEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EvidenceAnchorMapper extends BaseMapper<EvidenceAnchorEntity> {

    @Select("""
            SELECT * FROM evidence_anchor
             WHERE material_version_id = #{versionId}
             ORDER BY anchor_type, ordinal
            """)
    List<EvidenceAnchorEntity> listByVersion(@Param("versionId") Long versionId);

    /**
     * 取某版本内全部锚点 ID，用于校验模型输出的 evidence_anchor_ids 是否真实存在。
     *
     * <p>这是把"定位"从字符串模糊匹配变成 ID 查表的关键一步：
     * 校验失败的条目必须转人工判断并记录原因，不得静默丢弃、不得猜测坐标。
     */
    @Select("""
            SELECT anchor_id FROM evidence_anchor WHERE material_version_id = #{versionId}
            """)
    List<String> listAnchorIdsByVersion(@Param("versionId") Long versionId);

    /** 低置信度锚点（用于解析质量巡检：大量低置信度说明 OCR 质量不足） */
    @Select("""
            SELECT COUNT(*) FROM evidence_anchor
             WHERE material_version_id = #{versionId}
               AND confidence IS NOT NULL AND confidence < #{threshold}
            """)
    int countLowConfidence(@Param("versionId") Long versionId,
                           @Param("threshold") double threshold);

    /**
     * 写入风险-锚点引用。
     *
     * <p>该表的复合外键 {@code fk_rar_anchor} 会在数据库层校验锚点必须真实存在于
     * 指定版本——因此应用层即使传错 ID，数据也进不去。
     */
    @Insert("""
            INSERT INTO risk_anchor_ref (risk_case_id, anchor_id, material_version_id, ref_role)
            VALUES (#{riskCaseId}, #{anchorId}, #{versionId}, #{role})
            """)
    int insertAnchorRef(@Param("riskCaseId") Long riskCaseId,
                        @Param("anchorId") String anchorId,
                        @Param("versionId") Long versionId,
                        @Param("role") String role);

    @Select("""
            SELECT COUNT(*) FROM risk_anchor_ref
             WHERE risk_case_id = #{riskCaseId} AND anchor_id = #{anchorId}
            """)
    int countRef(@Param("riskCaseId") Long riskCaseId, @Param("anchorId") String anchorId);
}
