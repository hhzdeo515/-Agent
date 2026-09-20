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

    /**
     * Case 级初审汇总（通过率口径固化在视图中）。
     *
     * <p>刻意逐列写别名而不是 {@code SELECT *}：Map 结果的键就是 JDBC 列标签，
     * 用 {@code SELECT *} 会得到下划线键名，与上面的 {@code materialId} 风格不一致，
     * 前端与报告要各写一套取值逻辑。别名写死之后键名只有一种。
     */
    @Select("""
            SELECT case_id                     AS caseId,
                   case_no                     AS caseNo,
                   case_name                   AS caseName,
                   case_status                 AS caseStatus,
                   reviewed_material_count     AS reviewedMaterialCount,
                   parse_failed_count          AS parseFailedCount,
                   total_material_count        AS totalMaterialCount,
                   initial_pass_count          AS initialPassCount,
                   pending_human_count         AS pendingHumanCount,
                   risk_fail_count             AS riskFailCount,
                   high_risk_items             AS highRiskItems,
                   medium_risk_items           AS mediumRiskItems,
                   low_risk_items              AS lowRiskItems,
                   open_blocking_risk_items    AS openBlockingRiskItems,
                   initial_pass_rate_by_material AS initialPassRateByMaterial
              FROM v_case_initial_review_summary
             WHERE case_id = #{caseId}
            """)
    Map<String, Object> selectInitialReviewSummary(@Param("caseId") Long caseId);
}
