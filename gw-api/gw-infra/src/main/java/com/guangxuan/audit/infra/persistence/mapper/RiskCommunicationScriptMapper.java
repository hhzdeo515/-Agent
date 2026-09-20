package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.RiskCommunicationScriptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 风险沟通话术 Mapper（append-only）。
 *
 * <p>话术不覆盖旧版本：同一风险可能先后发给品牌与设计，两版话术都要能查到。
 */
@Mapper
public interface RiskCommunicationScriptMapper extends BaseMapper<RiskCommunicationScriptEntity> {

    /** 某个风险下全部话术，按受众与修订号排列 */
    @Select("""
            SELECT * FROM risk_communication_script
             WHERE risk_case_id = #{riskId}
             ORDER BY audience, revision_no
            """)
    List<RiskCommunicationScriptEntity> listByRisk(@Param("riskId") Long riskId);

    /** 某风险某受众的最新一版话术 */
    @Select("""
            SELECT * FROM risk_communication_script
             WHERE risk_case_id = #{riskId} AND audience = #{audience}
             ORDER BY revision_no DESC
             LIMIT 1
            """)
    RiskCommunicationScriptEntity findLatest(@Param("riskId") Long riskId,
                                             @Param("audience") String audience);

    /** 下一个修订号 */
    @Select("""
            SELECT COALESCE(MAX(revision_no), 0) + 1 FROM risk_communication_script
             WHERE risk_case_id = #{riskId} AND audience = #{audience}
            """)
    int nextRevisionNo(@Param("riskId") Long riskId, @Param("audience") String audience);

    /** 一次取一个 Case 下全部话术（避免逐条风险查询造成 N+1） */
    @Select("""
            SELECT s.* FROM risk_communication_script s
              JOIN risk_case r ON r.id = s.risk_case_id
             WHERE r.case_id = #{caseId}
             ORDER BY s.risk_case_id, s.audience, s.revision_no
            """)
    List<RiskCommunicationScriptEntity> listByCase(@Param("caseId") Long caseId);
}
