package com.guangxuan.audit.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guangxuan.audit.common.api.TraceIdHolder;
import com.guangxuan.audit.common.enums.RiskStatus;
import com.guangxuan.audit.domain.risk.RiskStateMachine;
import com.guangxuan.audit.domain.security.Actor;
import com.guangxuan.audit.infra.persistence.entity.ReviewRecordEntity;
import com.guangxuan.audit.infra.persistence.mapper.ReviewRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 审核记录写入服务——<b>所有状态变更的唯一留痕入口</b>。
 *
 * <p>AGENTS.md 第 8 条要求"状态变更必须由明确事件触发并写入 Review Record"；
 * 第 9 条要求"每一次 AI 判断、人工意见、状态变化、签名和关闭操作都应形成 Review Record"。
 *
 * <p>本表在数据库层是 append-only（触发器拒绝 UPDATE / DELETE），
 * 因此记录一旦写入就不可篡改——这是审计链完整性的基础。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewRecordService {

    private final ReviewRecordMapper reviewRecordMapper;
    private final ObjectMapper objectMapper;

    /** 用 REQUIRED 传播：与业务变更同一事务，避免"状态变了但记录没写" */
    @Transactional(propagation = Propagation.REQUIRED)
    public void append(Long caseId, Long riskCaseId, Long versionId,
                       Actor actor, String action, String opinion,
                       RiskStatus from, RiskStatus to, Object payload) {
        ReviewRecordEntity entity = new ReviewRecordEntity();
        entity.setCaseId(caseId);
        entity.setRiskCaseId(riskCaseId);
        entity.setMaterialVersionId(versionId);

        // 三种主体的字段互斥关系由数据库 CHECK 约束兜底，
        // 这里按主体类型只填对应字段，不依赖约束来报错
        switch (actor.type()) {
            case AI -> entity.setAiModelId(actor.modelId());
            case HUMAN -> entity.setActorId(actor.userId());
            case SYSTEM -> { /* 两者都留空 */ }
        }
        entity.setActorType(actor.type().name());
        entity.setAction(action);
        entity.setOpinion(opinion);
        entity.setFromStatus(from == null ? null : from.name());
        entity.setToStatus(to == null ? null : to.name());
        entity.setTraceId(TraceIdHolder.current());

        if (payload != null) {
            try {
                entity.setPayload(payload instanceof String s ? s : objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                // 审计记录不能因为 payload 序列化失败而丢失
                log.warn("审计 payload 序列化失败，已降级为字符串: {}", e.getMessage());
                entity.setPayload(String.valueOf(payload));
            }
        }

        reviewRecordMapper.insert(entity);
        log.debug("review_record appended: case={} risk={} action={} {} -> {}",
                caseId, riskCaseId, action, from, to);
    }

    /** 记录一次状态机跃迁。状态机返回的结果里已包含 action / opinion / payload。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void appendTransition(Long caseId, Long riskCaseId, Long versionId, Actor actor,
                                 RiskStateMachine.TransitionResult result) {
        append(caseId, riskCaseId, versionId, actor, result.action(),
                result.opinion(), result.from(), result.to(), result.payload());
    }

    @Transactional(readOnly = true)
    public List<ReviewRecordEntity> listByRisk(Long riskCaseId) {
        return reviewRecordMapper.listByRisk(riskCaseId);
    }

    @Transactional(readOnly = true)
    public List<ReviewRecordEntity> listByCase(Long caseId) {
        return reviewRecordMapper.listByCase(caseId);
    }

    /** 反向追溯：从物料版本查所有相关审核记录 */
    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> listByVersion(Long versionId) {
        return reviewRecordMapper.listByVersion(versionId);
    }
}
