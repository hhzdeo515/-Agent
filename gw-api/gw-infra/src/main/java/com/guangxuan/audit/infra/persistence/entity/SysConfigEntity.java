package com.guangxuan.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置项（对应 {@code sys_config} 表）。
 *
 * <p>{@code isSecret=1} 的项在库里是 AES-GCM 密文，接口只返回掩码。
 * 这个标记不是"约定"，而是由 {@code SysConfigService} 强制执行的：
 * 读取时按标记决定是否解密、是否允许回显。
 */
@Data
@TableName("sys_config")
public class SysConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configKey;
    /** is_secret=1 时为 AES-GCM 密文（Base64） */
    private String configValue;
    private Integer isSecret;
    private String description;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
