package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.MaterialEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface MaterialMapper extends BaseMapper<MaterialEntity> {

    /**
     * 物料三分类结果（口径唯一来源：v_material_initial_review 视图）。
     *
     * <p>刻意走视图而不是在 Java 里重算——AGENTS.md 第 6 条要求通过率口径明确，
     * 各处自行计算迟早会出现"界面说通过、库里说风险"的矛盾。
     */
    @Select("""
            SELECT material_id AS materialId, material_name AS materialName,
                   material_type AS materialType, parse_status AS parseStatus,
                   initial_review_class AS initialReviewClass, risk_count AS riskCount,
                   high_risk_count AS highRiskCount,
                   open_blocking_risk_count AS openBlockingRiskCount
              FROM v_material_initial_review
             WHERE case_id = #{caseId}
             ORDER BY material_id
            """)
    List<Map<String, Object>> selectInitialReviewClasses(@Param("caseId") Long caseId);

    /** Case 级初审汇总（通过率口径固化在视图中） */
    @Select("""
            SELECT * FROM v_case_initial_review_summary WHERE case_id = #{caseId}
            """)
    Map<String, Object> selectInitialReviewSummary(@Param("caseId") Long caseId);
}
