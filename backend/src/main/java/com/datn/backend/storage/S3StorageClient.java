package com.datn.backend.storage;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * Lam viec voi Amazon S3.
 * - Chi khoi tao khi app.storage.type=s3.
 * - Tren AWS, quyen truy cap lay tu ECS Task Role (KHONG de access key trong code/config).
 * - Bucket de private; trinh duyet xem file qua presigned URL co han.
 */
@Component
public class S3StorageClient {

    private static final Logger log = LoggerFactory.getLogger(S3StorageClient.class);

    private final boolean enabled;
    private final String bucket;
    private final Duration presignDuration;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3StorageClient(
            @Value("${app.storage.type:local}") String storageType,
            @Value("${app.s3.bucket:}") String bucket,
            @Value("${app.s3.region:ap-southeast-2}") String region,
            @Value("${app.s3.endpoint:}") String endpoint,
            @Value("${app.s3.public-endpoint:}") String publicEndpoint,
            @Value("${app.s3.path-style:false}") boolean pathStyle,
            @Value("${app.s3.create-bucket:false}") boolean createBucket,
            @Value("${app.s3.presign-minutes:60}") long presignMinutes) {

        this.enabled = "s3".equalsIgnoreCase(storageType);
        this.bucket = bucket;
        this.presignDuration = Duration.ofMinutes(presignMinutes);

        if (!enabled) {
            this.s3 = null;
            this.presigner = null;
            return;
        }
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("STORAGE_TYPE=s3 nhung chua dat bien S3_BUCKET");
        }

        S3Configuration s3Config = S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build();

        S3ClientBuilder clientBuilder = S3Client.builder()
                .region(Region.of(region))
                .serviceConfiguration(s3Config);
        if (StringUtils.hasText(endpoint)) {
            clientBuilder.endpointOverride(URI.create(endpoint)); // MinIO khi chay local
        }
        this.s3 = clientBuilder.build();

        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .region(Region.of(region))
                .serviceConfiguration(s3Config);
        String presignEndpoint = StringUtils.hasText(publicEndpoint) ? publicEndpoint : endpoint;
        if (StringUtils.hasText(presignEndpoint)) {
            presignerBuilder.endpointOverride(URI.create(presignEndpoint));
        }
        this.presigner = presignerBuilder.build();

        if (createBucket) {
            createBucketIfMissing();
        }
        log.info("Luu file tren S3: bucket={}, region={}", bucket, region);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void upload(String key, MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(in, file.getSize()));
        }
        log.info("Da upload s3://{}/{}", bucket, key);
    }

    public String presignedUrl(String key) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(presignDuration)
                .getObjectRequest(b -> b.bucket(bucket).key(key))
                .build();
        return presigner.presignGetObject(request).url().toString();
    }

    public void deleteQuietly(String key) {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (RuntimeException e) {
            log.warn("Khong xoa duoc s3://{}/{}: {}", bucket, key, e.getMessage());
        }
    }

    private void createBucketIfMissing() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (RuntimeException notFound) {
            try {
                s3.createBucket(b -> b.bucket(bucket));
                log.info("Da tao bucket {}", bucket);
            } catch (RuntimeException e) {
                log.warn("Khong tao duoc bucket {}: {}", bucket, e.getMessage());
            }
        }
    }

    @PreDestroy
    void close() {
        if (s3 != null) s3.close();
        if (presigner != null) presigner.close();
    }
}
