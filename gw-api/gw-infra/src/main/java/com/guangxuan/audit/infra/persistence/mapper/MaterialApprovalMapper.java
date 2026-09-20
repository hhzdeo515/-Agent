package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.MaterialApprovalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 物料批准 Mapper。
 *
 * <p>关键查询是"该物料当前是否有生效的批准"——批准绑定 {@code version_id}，
 * 因此新版本上传后需要重新批准，旧批准不会自动适用于新版本。
 */
@Mapper
public interface MaterialApprovalMapper extends BaseMapper<MaterialApprovalEntity> {

    @Select("""
            SELECT * FROM material_approval
             WHERE material_id = #{materialId} AND status = 'APPROVED'
             ORDER BY approved_at DESC LIMIT 1
            """)
    MaterialApprovalEntity findActiveByMaterial(@Param("materialId") Long materialId);

    /** 指定版本是否已被批准（用于判断"批准时看到的是哪份文件"） */
    @Select("""
            SELECT COUNT(*) > 0 FROM material_approval
             WHERE material_id = #{materialId} AND version_id = #{versionId} AND status = 'APPROVED'
            """)
    boolean isVersionApproved(@Param("materialId") Long materialId,
                              @Param("versionId") Long versionId);
}
