package com.guangxuan.audit.infra.provider.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.domain.port.RetrievalPort;
import com.guangxuan.audit.infra.persistence.mapper.LegalBasisMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 千问语义检索：embedding 召回 + rerank 精排。
 *
 * <h3>两段式，缺一不可</h3>
 * <ol>
 *   <li><b>召回</b>：把知识库条款与查询都转向量，取余弦相似度最高的 recallK 条。
 *       这一步保证"说法不同但意思相同"的条款能进来；</li>
 *   <li><b>精排</b>：把召回的候选交给 rerank 模型，按与查询的真实相关性重排，取前 topK。
 *       向量相似度高不等于"能回答这个问题"，精排补的正是这一层。</li>
 * </ol>
 *
 * <h3>向量存哪里</h3>
 * 存 Redis 而不是新建表。理由：MySQL 没有向量类型，为了存一个 float 数组去加表
 * 会让迁移与备份都变复杂；而 Redis 本来就是本项目的依赖，条款数量级也在其舒适区内。
 * 键里带模型名与维度，换模型时不会读到旧向量（否则会得到"看起来算出来了、
 * 实际相似度全是噪声"的结果，比报错难查得多）。
 *
 * <h3>失败时的行为</h3>
 * 检索失败返回<b>空列表</b>并由 {@link #available()} 让上层区分
 * "没检索到"与"检索不可用"——前者意味着"确实没有依据"，后者意味着"这次没查成"。
 * 把两者混为一谈，会让一次网络抖动变成一批风险被判定为"无依据"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gw.ai.provider", havingValue = "dashscope")
public class DashScopeRetrievalAdapter implements RetrievalPort {

    private final DashScopeClient client;
    private final LegalBasisMapper legalBasisMapper;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    /** 向量缓存前缀；含模型名与维度，避免换模型后读到不兼容的旧向量 */
    private static final String CACHE_PREFIX = "gw:kb:emb:";

    /** text-embedding-v4 单次请求的输入条数上限（官方为 10） */
    private static final int EMBED_BATCH = 10;

    /** 进程内缓存：一次初审会对同一批条款反复取用，没必要每次都走 Redis */
    private final Map<String, float[]> localVectors = new HashMap<>();

    private volatile boolean lastCallFailed = false;

    @Override
    public boolean available() {
        return !lastCallFailed;
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalRequest request) {
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty()) {
            return List.of();
        }
        try {
            List<Indexed> index = loadIndex();
            if (index.isEmpty()) {
                log.warn("[retrieval] 知识库中没有已发布的条款可检索");
                return List.of();
            }

            float[] queryVector = embedOne(query);
            // 召回：余弦相似度。维度不一致说明缓存里混进了别的模型，直接跳过该条
            List<Scored> recalled = new ArrayList<>();
            for (Indexed item : index) {
                if (item.vector() == null || item.vector().length != queryVector.length) {
                    continue;
                }
                recalled.add(new Scored(item, cosine(queryVector, item.vector())));
            }
            recalled.sort(Comparator.comparingDouble(Scored::score).reversed());
            List<Scored> candidates = recalled.subList(0, Math.min(request.recallK(), recalled.size()));
            if (candidates.isEmpty()) {
                return List.of();
            }

            // 精排：拿不到重排结果就用向量序，但必须把 source 标成 VECTOR，
            // 让上层知道这份排序没有经过精排
            List<Scored> ranked = rerank(query, candidates, request.topK());
            lastCallFailed = false;
            return ranked.stream().map(s -> new RetrievedChunk(
                    s.item().kbVersionId(), s.item().lawTitle(), s.item().chunkNo(),
                    s.item().text(), s.item().effectiveStatus(), s.score(), s.stage())).toList();
        } catch (Exception e) {
            lastCallFailed = true;
            // 只记类型与消息：查询文本可能来自物料原文，不进日志（AGENTS.md 第 12 条）
            log.warn("[retrieval] 语义检索失败，将回落确定性查询（err={}）", e.getMessage());
            return List.of();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 索引
    // ════════════════════════════════════════════════════════════════════

    private record Indexed(long kbVersionId, String lawTitle, String chunkNo, String text,
                           String effectiveStatus, float[] vector) {
    }

    private record Scored(Indexed item, double score, String stage) {
        Scored(Indexed item, double score) {
            this(item, score, "VECTOR");
        }
    }

    /**
     * 载入全部已发布条款及其向量。
     *
     * <p>只索引 {@code PUBLISHED} 的知识库：历史规则与被下架的版本不该出现在
     * 初审依据里——界面必须能区分现行规则与历史规则（AGENTS.md 第 10 条）。
     */
    private List<Indexed> loadIndex() {
        List<LegalBasisMapper.LegalBasisRow> rows = legalBasisMapper.listAllPublished();
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<Indexed> out = new ArrayList<>();
        List<LegalBasisMapper.LegalBasisRow> missing = new ArrayList<>();
        Map<Long, float[]> fetched = new HashMap<>();

        for (LegalBasisMapper.LegalBasisRow r : rows) {
            if (r.getKbVersionId() == null || r.getChunkText() == null || r.getChunkText().isBlank()) {
                continue;
            }
            String key = cacheKey(r.getChunkText());
            float[] vec = localVectors.get(key);
            if (vec == null) {
                vec = readFromRedis(key);
            }
            if (vec == null) {
                missing.add(r);
                continue;
            }
            localVectors.put(key, vec);
            out.add(toIndexed(r, vec));
        }

        if (!missing.isEmpty()) {
            log.info("[retrieval] 需要新建向量 {} 条（其余已缓存）", missing.size());
            for (int i = 0; i < missing.size(); i += EMBED_BATCH) {
                List<LegalBasisMapper.LegalBasisRow> batch =
                        missing.subList(i, Math.min(i + EMBED_BATCH, missing.size()));
                List<String> texts = batch.stream()
                        .map(LegalBasisMapper.LegalBasisRow::getChunkText).toList();
                List<float[]> vectors = embed(texts);
                for (int j = 0; j < batch.size() && j < vectors.size(); j++) {
                    LegalBasisMapper.LegalBasisRow r = batch.get(j);
                    float[] v = vectors.get(j);
                    String key = cacheKey(r.getChunkText());
                    localVectors.put(key, v);
                    writeToRedis(key, v);
                    fetched.put(r.getKbVersionId(), v);
                    out.add(toIndexed(r, v));
                }
            }
        }
        return out;
    }

    private Indexed toIndexed(LegalBasisMapper.LegalBasisRow r, float[] vector) {
        return new Indexed(r.getKbVersionId(), r.getLawTitle(),
                r.getChunkNo() == null ? null : String.valueOf(r.getChunkNo()),
                r.getChunkText(), "CURRENT", vector);
    }

    // ════════════════════════════════════════════════════════════════════
    // Embedding
    // ════════════════════════════════════════════════════════════════════

    private float[] embedOne(String text) {
        List<float[]> v = embed(List.of(text));
        if (v.isEmpty()) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED, "查询向量化失败");
        }
        return v.get(0);
    }

    /**
     * 批量向量化。
     *
     * <p>走 OpenAI 兼容接口：请求/响应结构与通用规范一致，且本项目只用到
     * "给文本、拿向量"这一件事，不需要原生接口的额外参数。
     */
    private List<float[]> embed(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", client.props().getModels().getEmbedding());
        body.put("input", texts);
        body.put("encoding_format", "float");

        JsonNode resp = client.nativePostOnCompatible("/embeddings", body,
                client.props().getModels().getEmbedding(), "embeddings");
        JsonNode data = resp.path("data");
        if (!data.isArray()) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "向量化响应缺少 data 数组：" + resp.toString().substring(0,
                            Math.min(200, resp.toString().length())));
        }
        // 按 index 回填，不假设返回顺序与输入一致
        float[][] ordered = new float[texts.size()][];
        for (JsonNode item : data) {
            int idx = item.path("index").asInt(-1);
            JsonNode emb = item.path("embedding");
            if (idx < 0 || idx >= texts.size() || !emb.isArray()) {
                continue;
            }
            float[] v = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                v[i] = (float) emb.get(i).asDouble();
            }
            ordered[idx] = v;
        }
        List<float[]> out = new ArrayList<>();
        for (float[] v : ordered) {
            if (v != null) {
                out.add(v);
            }
        }
        if (out.size() != texts.size()) {
            log.warn("[retrieval] 向量条数与输入不一致：期望 {} 实际 {}", texts.size(), out.size());
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // Rerank
    // ════════════════════════════════════════════════════════════════════

    /**
     * 精排。
     *
     * <p>⚠️ {@code qwen3-rerank} 的接口与其它 rerank 模型<b>不同</b>：路径是
     * {@code /compatible-api/v1/reranks}（不是 {@code /compatible-mode}），
     * 请求体是<b>扁平</b>的（query/documents/top_n 与 model 同级，没有 input/parameters），
     * 响应也在顶层（没有 output 包裹）。官方文档特意提示过这个差异，
     * 按其它模型的写法会直接 400。
     *
     * <p>重排失败不抛异常：召回结果仍然可用，只是顺序未经精排，
     * 把 source 标成 VECTOR 让上层知道这件事。
     */
    private List<Scored> rerank(String query, List<Scored> candidates, int topK) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", client.props().getModels().getRerank());
            body.put("query", query);
            body.put("documents", candidates.stream().map(c -> c.item().text()).toList());
            body.put("top_n", Math.min(topK, candidates.size()));
            body.put("instruct",
                    "Given a Chinese advertising-compliance review query, "
                            + "retrieve the legal provisions that apply to it.");

            JsonNode resp = client.nativePostOnCompatible("/reranks", body,
                    client.props().getModels().getRerank(), "rerank");
            JsonNode results = resp.path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.warn("[retrieval] 重排未返回结果，改用向量顺序");
                return candidates.subList(0, Math.min(topK, candidates.size()));
            }
            List<Scored> out = new ArrayList<>();
            for (JsonNode r : results) {
                int idx = r.path("index").asInt(-1);
                if (idx < 0 || idx >= candidates.size()) {
                    continue;
                }
                out.add(new Scored(candidates.get(idx).item(),
                        r.path("relevance_score").asDouble(0), "RERANK"));
            }
            return out.isEmpty()
                    ? candidates.subList(0, Math.min(topK, candidates.size())) : out;
        } catch (Exception e) {
            log.warn("[retrieval] 重排失败，改用向量顺序（err={}）", e.getMessage());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 工具
    // ════════════════════════════════════════════════════════════════════

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 缓存键按"模型 + 文本哈希"：文本改了自然失效，不必手工清缓存 */
    private String cacheKey(String text) {
        return CACHE_PREFIX + client.props().getModels().getEmbedding() + ":" + sha256(text);
    }

    private float[] readFromRedis(String key) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            String[] parts = json.split(",");
            float[] v = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                v[i] = Float.parseFloat(parts[i]);
            }
            return v;
        } catch (Exception e) {
            // 缓存不可用不能影响检索本身，只是会多花一次 embedding 调用
            log.debug("[retrieval] 读取向量缓存失败（将重新计算）：{}", e.getMessage());
            return null;
        }
    }

    private void writeToRedis(String key, float[] vector) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder(vector.length * 8);
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(vector[i]);
            }
            redis.opsForValue().set(key, sb.toString());
        } catch (Exception e) {
            log.debug("[retrieval] 写入向量缓存失败（不影响本次检索）：{}", e.getMessage());
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
