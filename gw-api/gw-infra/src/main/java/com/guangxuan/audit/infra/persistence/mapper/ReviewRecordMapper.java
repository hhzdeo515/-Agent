package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.ReviewRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 审核记录 Mapper。
 *
 * <p>本表 append-only（触发器拒绝 UPDATE / DELETE），因此只提供 INSERT 与查询。
 * 这是审计链完整性的基础：记录一旦写入就不可篡改。
 */
@Mapper
public interface ReviewRecordMapper extends BaseMapper<ReviewRecordEntity> {

    /** 某风险的完整处理轨迹——从任一 Risk Case 都能找到它的全部审核记录 */
    @Select("""
            SELECT * FROM review_record
             WHERE risk_case_id = #{riskCaseId}
             ORDER BY created_at, id
            """)
    List<ReviewRecordEntity> listByRisk(@Param("riskCaseId") Long riskCaseId);

    /** 某 Case 的完整操作轨迹 */
    @Select("""
            SELECT * FROM review_record
             WHERE case_id = #{caseId}
             ORDER BY created_at, id
            """)
    List<ReviewRecordEntity> listByCase(@Param("caseId") Long caseId);

    /**
     * 反向追溯用：给定物料版本，找出所有引用过它的审核记录。
     *
     * <p>AGENTS.md 第 9 条的验收要求之一是"从最终版本反查全部风险和审核记录"。
     */
    @Select("""
            SELECT rr.*, r.risk_no AS riskNo
              FROM review_record rr
              LEFT JOIN risk_case r ON r.id = rr.risk_case_id
             WHERE rr.material_version_id = #{versionId}
             ORDER BY rr.created_at, rr.id
            """)
    List<Map<String, Object>> listByVersion(@Param("versionId") Long versionId);
}
