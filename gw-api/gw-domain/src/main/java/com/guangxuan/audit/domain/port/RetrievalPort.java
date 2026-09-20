package com.guangxuan.audit.domain.port;

import java.util.List;

/**
 * 知识库检索端口：把"这段宣传语该看哪几条法规"变成一次可解释的检索。
 *
 * <p>为什么必须存在（这不是优化项）：阶段 5 的校验器会把"本次检索结果之外的引用"
 * 判定为幻觉并丢弃。检索缺失时，模型给的每一条法条都会被丢掉，
 * 每条风险都会变成"依据不足"——整套引用体系空转（这个问题真实发生过）。
 *
 * <p>实现必须是<b>语义检索</b>而不是关键词匹配：法务与模型描述同一件事用词往往不同
 * （"绝对化用语" vs "国家级" vs "最高级"），只靠字面匹配会大面积漏召回。
 *
 * <p>真实实现走 embedding 召回 + rerank 精排：向量召回保证"意思相近的能进来"，
 * 重排保证"最相关的排在最前面"。只做其中一步都不够——
 * 纯向量召回的相关性排序不够准，纯重排又必须先把候选找出来。
 */
public interface RetrievalPort {

    /**
     * 检索与 query 相关的现行依据。
     *
     * @return 按相关性降序的条款；检索不可用时返回空列表（调用方需回落到确定性查询，
     *         不得把"检索失败"当成"没有依据"）
     */
    List<RetrievedChunk> retrieve(RetrievalRequest request);

    /** 当前是否有可用的检索实现。用于让上层区分"没检索到"与"检索不可用" */
    default boolean available() {
        return true;
    }

    /**
     * @param query    查询文本；通常由"风险类型 + 命中的原文表述"拼成，
     *                 只给类型会丢掉具体表述里最有区分度的信息
     * @param topK     最终返回条数
     * @param recallK  向量召回条数；应大于 topK，给重排留出空间
     */
    record RetrievalRequest(String query, int topK, int recallK) {
        public RetrievalRequest {
            topK = topK <= 0 ? 5 : topK;
            recallK = recallK <= 0 ? Math.max(topK * 4, 20) : recallK;
        }
    }

    /**
     * @param kbItemVersionId 知识库版本 ID，可直接写入 {@code risk_case.rule_refs}
     * @param lawTitle        法规名
     * @param chunkNo         条款序号
     * @param text            条款原文
     * @param effectiveStatus 现行 / 历史；检索结果必须能区分（AGENTS.md 第 10 条）
     * @param score           相关性得分。向量相似度与重排分数不可直接比较，
     *                        因此同时给出 {@code source} 说明这个分数是谁给的
     * @param source          VECTOR / RERANK / RULE
     */
    record RetrievedChunk(long kbItemVersionId, String lawTitle, String chunkNo, String text,
                          String effectiveStatus, double score, String source) {
    }
}
