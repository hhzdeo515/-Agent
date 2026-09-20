package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.RiskCaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RiskCaseMapper extends BaseMapper<RiskCaseEntity> {

    /**
     * 该物料下未关闭的阻断性风险数。
     *
     * <p>AGENTS.md 第 7 条：只有一份物料关联的所有阻断性风险都关闭后，
     * 该物料版本才可以被标记为最终批准版本。批准前必须查这个数。
     */
    @Select("""
            SELECT COUNT(*) FROM risk_case
             WHERE material_id = #{materialId}
               AND blocked = 1
               AND status NOT IN ('CLOSED','REJECTED_FALSE_POSITIVE')
            """)
    int countOpenBlockingRisks(@Param("materialId") Long materialId);

    /** 是否存在 RISK_CLOSE 签名——关闭风险前的硬前置 */
    @Select("""
            SELECT COUNT(*) > 0 FROM legal_signature
             WHERE risk_case_id = #{riskCaseId} AND sign_type = 'RISK_CLOSE'
            """)
    boolean hasCloseSignature(@Param("riskCaseId") Long riskCaseId);

    @Select("""
            SELECT * FROM risk_case
             WHERE case_id = #{caseId}
             ORDER BY FIELD(risk_level,'HIGH','MEDIUM','LOW'), id
            """)
    List<RiskCaseEntity> listByCase(@Param("caseId") Long caseId);

    /**
     * 带乐观锁的状态更新。
     *
     * <p>不使用 MyBatis-Plus 的 updateById（它依赖 @Version 自动处理），
     * 而是显式带 lock_version 条件并在影响行数为 0 时抛出冲突——
     * 这样"两人同时确认同一风险"这种并发场景会明确失败，而不是静默覆盖。
     */
    @Update("""
            UPDATE risk_case
               SET status = #{toStatus}, lock_version = lock_version + 1
             WHERE id = #{id} AND lock_version = #{lockVersion}
            """)
    int updateStatusWithLock(@Param("id") Long id,
                             @Param("toStatus") String toStatus,
                             @Param("lockVersion") Integer lockVersion);

    /** 风险类型分布（初审报告需要，AGENTS.md 第 6 条） */
    @Select("""
            SELECT risk_type AS riskType, COUNT(*) AS cnt
              FROM risk_case WHERE case_id = #{caseId}
             GROUP BY risk_type ORDER BY cnt DESC
            """)
    List<java.util.Map<String, Object>> countByRiskType(@Param("caseId") Long caseId);

    /** 该物料下处于"整改中"的风险——新版本解析成功后需要把它们推进为"已重新提交" */
    @Select("""
            SELECT * FROM risk_case
             WHERE material_id = #{materialId} AND status = 'AWAITING_REVISION'
            """)
    List<RiskCaseEntity> listAwaitingRevisionByMaterial(@Param("materialId") Long materialId);

    /** 某物料下仍未关闭的风险数（非阻断也计入），用于判断整改是否全部收敛 */
    @Select("""
            SELECT COUNT(*) FROM risk_case
             WHERE material_id = #{materialId}
               AND status NOT IN ('CLOSED','REJECTED_FALSE_POSITIVE')
            """)
    int countOpenRisks(@Param("materialId") Long materialId);
}
