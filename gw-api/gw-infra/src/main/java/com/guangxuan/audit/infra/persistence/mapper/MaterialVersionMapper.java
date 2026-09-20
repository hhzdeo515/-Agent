package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.MaterialVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 物料版本 Mapper。
 *
 * <p>本表不可变（触发器拒绝 UPDATE / DELETE），因此只提供 INSERT 与查询能力——
 * 这是"新文件不得覆盖旧版本"（AGENTS.md 第 7 条）在代码层面的体现：
 * 没有 update 路径，就不存在误用。
 */
@Mapper
public interface MaterialVersionMapper extends BaseMapper<MaterialVersionEntity> {

    @Select("""
            SELECT * FROM material_version
             WHERE material_id = #{materialId}
             ORDER BY version_no
            """)
    List<MaterialVersionEntity> listByMaterial(@Param("materialId") Long materialId);

    /** 取最大版本号，用于决定下一个 Vn */
    @Select("""
            SELECT COALESCE(MAX(version_no), 0) FROM material_version WHERE material_id = #{materialId}
            """)
    int maxVersionNo(@Param("materialId") Long materialId);

    /** 按哈希查已有版本：同一文件重复上传时复用，天然幂等（AGENTS.md 第 14 条） */
    @Select("""
            SELECT * FROM material_version
             WHERE material_id = #{materialId} AND file_sha256 = #{sha256}
            """)
    MaterialVersionEntity findBySha(@Param("materialId") Long materialId,
                                    @Param("sha256") String sha256);
}
