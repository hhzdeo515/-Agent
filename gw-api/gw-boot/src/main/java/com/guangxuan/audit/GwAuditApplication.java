package com.guangxuan.audit;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 广宣法务审核 Agent 后端入口。
 *
 * <p>产品边界（AGENTS.md 第 1、8 条）：AI 可以阅读、检索、归纳、定位、解释和辅助判断，
 * 但不得冒充法务给出不可撤销的最终法律结论；所有正式宣传物料的最终批准必须由有权限的法务人员完成。
 * 该边界由三层共同保证：前端按钮、后端领域守卫、数据库触发器（见 02 文档 §5）。
 */
@SpringBootApplication(scanBasePackages = "com.guangxuan.audit")
@MapperScan("com.guangxuan.audit.infra.persistence.mapper")
@EnableAsync
@EnableScheduling
public class GwAuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(GwAuditApplication.class, args);
    }
}
