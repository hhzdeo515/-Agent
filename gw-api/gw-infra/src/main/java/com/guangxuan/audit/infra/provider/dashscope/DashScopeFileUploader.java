package com.guangxuan.audit.infra.provider.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import com.guangxuan.audit.infra.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 百炼临时文件上传：把本项目的物料送到模型服务能取到的位置。
 *
 * <h3>为什么需要它</h3>
 * ASR 的录音文件识别只接受<b>公网可访问的 URL</b>，明确不支持 base64 与本地直传；
 * 而本项目的物料存在内网 MinIO 里，模型服务取不到。没有这一步，
 * dashscope 供应商下的视频口播链路会稳定失败——这是实测前就能预判的必然失败，
 * 不是"连不上再排查"的问题。
 *
 * <h3>官方流程（已核实）</h3>
 * <ol>
 *   <li>{@code GET /api/v1/uploads?action=getPolicy&model=<model>} → 拿到 OSS 上传凭证；</li>
 *   <li>用凭证向 {@code upload_host} 发 multipart 表单 → 得到 {@code oss://...} 临时 URL；</li>
 *   <li>调用模型时<b>必须</b>带请求头 {@code X-DashScope-OssResourceResolve: enable}，
 *       否则服务端无法解析 {@code oss://} 链接（这一步漏了会得到一个很像网络问题的报错）。</li>
 * </ol>
 *
 * <h3>官方明确的限制（必须让使用者知道）</h3>
 * <ul>
 *   <li>文件与<b>模型名绑定</b>：上传时指定 qwen3-asr-flash-filetrans 的临时 URL
 *       不能拿去调别的模型；</li>
 *   <li>与<b>主账号绑定</b>：上传与调用的 API Key 必须同属一个阿里云主账号；</li>
 *   <li>有效期 <b>48 小时</b>，单文件 ≤ <b>1GB</b>；</li>
 *   <li>凭证接口限流 <b>100 QPS</b> 且不支持扩容，官方写明"请勿用于生产环境"。</li>
 * </ul>
 * 因此本类定位为<b>联调与中小批量的可用路径</b>：接口可用、行为真实，
 * 但生产环境应换成 OSS 等稳定存储（{@code AudioUrlResolver} 扩展点仍保留，
 * 部署侧可注册自己的实现覆盖本类）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gw.ai.provider", havingValue = "dashscope")
public class DashScopeFileUploader {

    private final DashScopeClient client;
    private final StorageService storage;

    /** 上传凭证与临时 URL 的复用：同一物料在同一模型下的临时 URL 48 小时内有效 */
    private final Map<String, String> urlCache = new ConcurrentHashMap<>();

    private static final long MAX_UPLOAD_BYTES = 1024L * 1024 * 1024; // 官方上限 1GB

    /**
     * 把对象存储里的文件送到模型服务，返回可用的 {@code oss://} 临时 URL。
     *
     * @param objectKey MinIO 对象键
     * @param fileName  文件名（影响临时 URL 的最后一段，仅用于可读性）
     * @param model     该文件将要用于哪个模型；<b>必须与后续调用的模型完全一致</b>
     */
    public String upload(String objectKey, String fileName, String model) {
        String cacheKey = model + "|" + objectKey;
        String cached = urlCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        byte[] bytes = storage.getBytes(objectKey);
        if (bytes == null || bytes.length == 0) {
            throw new DomainException(ErrorCode.STORAGE_ERROR,
                    "音频文件为空或无法读取：" + objectKey);
        }
        if (bytes.length > MAX_UPLOAD_BYTES) {
            throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "文件超过百炼临时存储 1GB 上限（当前 " + (bytes.length / 1024 / 1024)
                            + "MB）：请压缩后重传，或改用 OSS 中转");
        }

        JsonNode policy = client.get(
                client.props().getDashscope().getBaseUrl()
                        + "/api/v1/uploads?action=getPolicy&model=" + model,
                "uploads.getPolicy");
        JsonNode data = policy.path("data");
        if (data.isMissingNode() || data.path("upload_host").isMissingNode()) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "获取上传凭证失败，响应缺少 upload_host：" + brief(policy));
        }

        String safeName = sanitize(fileName);
        String key = data.path("upload_dir").asText("") + "/" + safeName;

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("OSSAccessKeyId", data.path("oss_access_key_id").asText(""));
        form.add("Signature", data.path("signature").asText(""));
        form.add("policy", data.path("policy").asText(""));
        form.add("x-oss-object-acl", data.path("x_oss_object_acl").asText(""));
        form.add("x-oss-forbid-overwrite", data.path("x_oss_forbid_overwrite").asText(""));
        form.add("key", key);
        form.add("success_action_status", "200");
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return safeName;
            }
        });

        postForm(data.path("upload_host").asText(), form);

        String url = "oss://" + key;
        urlCache.put(cacheKey, url);
        log.info("[dashscope] 物料已上传至百炼临时存储：{} → {}（48 小时有效，模型={}）",
                objectKey, url, model);
        return url;
    }

    /**
     * 上传表单 POST。
     *
     * <p>单独写而不复用 {@link DashScopeClient}：这一步打的是 OSS 的 upload_host，
     * <b>不能带 Authorization</b>（凭证已经在表单里，多带一个 Bearer 只会把 API Key
     * 暴露给存储侧），而客户端的统一出口默认会带。
     */
    private void postForm(String uploadHost, MultiValueMap<String, Object> form) {
        try {
            HttpClient jdk = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(
                            client.props().getHttp().getConnectTimeoutMs()))
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
            factory.setReadTimeout(Duration.ofMillis(
                    client.props().getHttp().getReadTimeoutMs()));
            RestClient http = RestClient.builder().requestFactory(factory).build();

            http.post().uri(URI.create(uploadHost))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new DomainException(ErrorCode.AI_CALL_FAILED,
                    "上传到百炼临时存储失败：" + e.getMessage()
                            + "（检查 API Key 是否与所选区域一致、主账号是否一致）");
        }
    }

    /**
     * 文件名净化。
     *
     * <p>临时 URL 的最后一段会拼进对象键，原始文件名里的斜杠、空格、中文
     * 都会让对象键变得不可预期（甚至越出 upload_dir）。这里只保留安全字符。
     */
    private static String sanitize(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "material" : fileName;
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (name.length() > 80) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                ext = name.substring(dot);
            }
            name = name.substring(0, 60) + ext;
        }
        return name;
    }

    private static String brief(JsonNode node) {
        String s = node == null ? "null" : node.toString();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /** 便于诊断：当前缓存了多少个临时 URL */
    public int cachedUrlCount() {
        return urlCache.size();
    }
}
