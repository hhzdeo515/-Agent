package com.guangxuan.audit.infra.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 用户查询（只提供"选人"所需的最小字段）。
 *
 * <p>刻意不返回 {@code email_enc} 等敏感列：整改责任人选择器不需要联系方式，
 * 少查一列就少一个泄露面（AGENTS.md 第 12 条）。
 */
@Mapper
public interface SysUserMapper {

    /**
     * 可作为整改责任方的用户。
     *
     * <p>角色用 {@code GROUP_CONCAT} 聚合，便于界面直接显示"品牌 / 设计"，
     * 不必再为每个用户查一次角色表。
     */
    @Select("""
            SELECT u.id           AS id,
                   u.username     AS username,
                   u.display_name AS displayName,
                   u.dept         AS dept,
                   GROUP_CONCAT(r.code ORDER BY r.id) AS roleCodes
              FROM sys_user u
              LEFT JOIN sys_user_role ur ON ur.user_id = u.id
              LEFT JOIN sys_role r       ON r.id = ur.role_id
             WHERE u.status = 'ACTIVE'
             GROUP BY u.id, u.username, u.display_name, u.dept
             ORDER BY u.id
            """)
    List<Map<String, Object>> listActiveUsers();
}
