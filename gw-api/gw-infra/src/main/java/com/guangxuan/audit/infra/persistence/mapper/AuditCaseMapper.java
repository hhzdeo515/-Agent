package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.AuditCaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 审核任务 Mapper。
 *
 * <p>注意所有查询都必须带 {@code project_id} 条件（数据权限在应用层注入）——
 * AGENTS.md 第 12 条禁止在没有权限的情况下跨项目检索历史材料。
 */
@Mapper
public interface AuditCaseMapper extends BaseMapper<AuditCaseEntity> {

    /** 生成业务编号用的当日流水号（配合唯一键冲突重试） */
    @Select("""
            SELECT COUNT(*) + 1 FROM audit_case
             WHERE case_no LIKE CONCAT(#{prefix}, '%')
            """)
    int nextSequence(@Param("prefix") String prefix);

    /** 统计某 Case 下可参与初审的物料数（用于启动初审前置校验） */
    @Select("""
            SELECT COUNT(*) FROM material
             WHERE case_id = #{caseId} AND parse_status IN ('SUCCEEDED','PARTIAL')
            """)
    int countReviewableMaterials(@Param("caseId") Long caseId);
}
