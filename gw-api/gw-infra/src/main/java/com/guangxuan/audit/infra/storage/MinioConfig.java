package com.guangxuan.audit.infra.storage;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置。
 *
 * <p>物料原件与派生图（页面渲染图、视频帧）的唯一权威副本存放在这里。
 * 云端模型只接收临时引用（且有有效期），归档时必须清理，见 docs/00 §2.3。
 */
@Slf4j
@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(
            @Value("${gw.storage.endpoint}") String endpoint,
            @Value("${gw.storage.access-key}") String accessKey,
            @Value("${gw.storage.secret-key}") String secretKey) {
        log.info("初始化 MinIO 客户端: {}", endpoint);
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
