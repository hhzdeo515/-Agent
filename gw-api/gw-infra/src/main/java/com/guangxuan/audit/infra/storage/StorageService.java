package com.guangxuan.audit.infra.storage;

import com.guangxuan.audit.common.error.DomainException;
import com.guangxuan.audit.common.error.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 对象存储服务。
 *
 * <p>对象键规则：{@code case/{caseId}/material/{materialId}/{versionLabel}/{sha256前12位}-{文件名}}。
 * 把 sha256 写进对象键，使对象本身天然不可变——同名不同内容的覆盖在物理上不可能发生，
 * 这比"靠代码不覆盖"可靠（AGENTS.md 第 7 条要求版本不可覆盖）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final MinioClient minioClient;

    @Value("${gw.storage.bucket}")
    private String bucket;

    /**
     * 启动时确保桶存在（幂等）。
     *
     * <p>放在启动阶段而不是首次上传时：上传路径上做基础设施初始化会把
     * "配置错了"和"用户文件有问题"两类错误混在一起，排查代价高。
     * 启动即失败更早、更清晰。
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        ensureBucket();
    }

    /** 确保桶存在（幂等） */
    public void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建存储桶: {}", bucket);
            }
        } catch (Exception e) {
            throw new DomainException(ErrorCode.STORAGE_ERROR, "存储桶初始化失败: " + e.getMessage());
        }
    }

    /**
     * 上传字节内容。
     *
     * @return 计算得到的 sha256（用于幂等去重与版本校验）
     */
    public UploadedFile put(Long caseId, Long materialId, String versionLabel,
                            String originalName, byte[] content, String contentType) {
        String sha256 = sha256(content);
        String objectKey = buildKey(caseId, materialId, versionLabel, sha256, originalName);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
            log.info("物料已入库: {} ({} bytes, sha256={})", objectKey, content.length, sha256.substring(0, 12));
            return new UploadedFile(objectKey, sha256, content.length);
        } catch (Exception e) {
            throw new DomainException(ErrorCode.STORAGE_ERROR, "文件上传失败: " + e.getMessage());
        }
    }

    public InputStream get(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new DomainException(ErrorCode.STORAGE_ERROR, "读取文件失败: " + e.getMessage());
        }
    }

    public byte[] getBytes(String objectKey) {
        try (InputStream in = get(objectKey)) {
            return in.readAllBytes();
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw new DomainException(ErrorCode.STORAGE_ERROR, "读取文件失败: " + e.getMessage());
        }
    }

    public boolean exists(String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            // 删除失败不阻断主流程，但必须留日志——这可能意味着有敏感文件未按预期清理
            log.warn("删除对象失败（需人工核查）: {} - {}", objectKey, e.getMessage());
        }
    }

    private String buildKey(Long caseId, Long materialId, String versionLabel,
                            String sha256, String originalName) {
        String safeName = originalName == null ? "unnamed"
                : originalName.replaceAll("[\\\\/:*?\"<>|]", "_");
        return String.format("case/%d/material/%d/%s/%s-%s",
                caseId, materialId, versionLabel, sha256.substring(0, 12), safeName);
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 上传结果 */
    public record UploadedFile(String objectKey, String sha256, long size) {
    }
}
