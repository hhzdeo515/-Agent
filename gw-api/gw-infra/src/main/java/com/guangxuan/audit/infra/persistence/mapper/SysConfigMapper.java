package com.guangxuan.audit.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guangxuan.audit.infra.persistence.entity.SysConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统配置 Mapper。
 *
 * <p>只做最基础的增删改查：配置读取在启动时一次性载入内存，
 * 不需要在这里堆查询方法。
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfigEntity> {
}
