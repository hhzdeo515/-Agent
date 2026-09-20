package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.VersionDiffEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 版本差异 Mapper。
 *
 * <p>append-only：只提供插入与查询，没有 update / delete 路径。
 * 差异是"当时看到的事实"，可以被重新生成（命中唯一键则复用），但不该被改写。
 */
@Mapper
public interface VersionDiffMapper extends BaseMapper<VersionDiffEntity> {

    /** 按版本对查（唯一键维度），用于重复生成时的复用判断 */
    @Select("""
            SELECT * FROM version_diff
             WHERE base_version_id = #{baseVersionId} AND target_version_id = #{targetVersionId}
            """)
    VersionDiffEntity findByPair(@Param("baseVersionId") Long baseVersionId,
                                 @Param("targetVersionId") Long targetVersionId);

    /**
     * 取某物料最近一次已生成的差异（终审区进入时直接展示，避免每次重算）。
     */
    @Select("""
            SELECT * FROM version_diff
             WHERE material_id = #{materialId}
             ORDER BY id DESC LIMIT 1
            """)
    VersionDiffEntity findLatestByMaterial(@Param("materialId") Long materialId);
}
