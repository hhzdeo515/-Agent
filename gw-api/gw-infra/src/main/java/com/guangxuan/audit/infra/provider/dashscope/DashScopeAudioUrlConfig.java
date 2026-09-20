package com.guangxuan.audit.infra.provider.dashscope;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link DashScopeFileUploader} 接成 ASR 的默认取件方式。
 *
 * <p>背景：录音文件识别只接受公网可访问的 URL，而本项目物料在内网 MinIO。
 * 没有这一步，dashscope 下的视频口播链路必然失败。百炼自带的临时文件上传
 * 让它在<b>不引入 OSS 账号</b>的前提下就能跑通，因此作为默认实现。
 *
 * <p>部署侧若已有阿里云 OSS 或 MinIO 公网网关，注册一个自己的
 * {@code AudioUrlResolver} Bean 并标 {@code @Primary} 即可覆盖本实现，
 * 不必修改本模块代码——官方也明确临时存储"请勿用于生产环境"
 * （48 小时有效、凭证接口 100 QPS 且不支持扩容）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gw.ai.provider", havingValue = "dashscope")
public class DashScopeAudioUrlConfig {

    private final DashScopeFileUploader uploader;
    private final DashScopeProperties props;

    @Bean
    DashScopeAsrClient.AudioUrlResolver dashScopeAudioUrlResolver() {
        return (objectKey, mimeType) -> {
            String fileName = fileNameOf(objectKey);
            // 文件与模型名绑定：必须用 ASR 模型名上传，否则调用时取不到
            String url = uploader.upload(objectKey, fileName, props.getModels().getAsr());
            log.debug("[dashscope] ASR 音频已换为临时 URL：{}", url);
            return url;
        };
    }

    /** 从对象键尾段取文件名；取不到就给一个中性名字，反正它只影响可读性 */
    private static String fileNameOf(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return "audio";
        }
        int slash = objectKey.lastIndexOf('/');
        String name = slash >= 0 ? objectKey.substring(slash + 1) : objectKey;
        // 对象键形如 {sha12}-{原始文件名}，去掉哈希前缀更像个人样
        int dash = name.indexOf('-');
        if (dash > 0 && dash < 20) {
            name = name.substring(dash + 1);
        }
        return name.isBlank() ? "audio" : name;
    }
}
