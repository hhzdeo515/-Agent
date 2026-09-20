package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.LegalSignatureEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 法务签名 Mapper。
 *
 * <p>append-only，且 INSERT 时数据库触发器会校验签名人持有 LEGAL 角色。
 * 因此即使应用层出现漏洞，也无法写入一个非法务的签名。
 */
@Mapper
public interface LegalSignatureMapper extends BaseMapper<LegalSignatureEntity> {

    @Select("""
            SELECT * FROM legal_signature
             WHERE risk_case_id = #{riskCaseId}
             ORDER BY signed_at
            """)
    List<LegalSignatureEntity> listByRisk(@Param("riskCaseId") Long riskCaseId);

    @Select("""
            SELECT * FROM legal_signature
             WHERE material_id = #{materialId} AND version_id = #{versionId}
             ORDER BY signed_at
            """)
    List<LegalSignatureEntity> listByMaterialVersion(@Param("materialId") Long materialId,
                                                     @Param("versionId") Long versionId);
}
