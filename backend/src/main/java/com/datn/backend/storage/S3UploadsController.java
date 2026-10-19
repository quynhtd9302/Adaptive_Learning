package com.datn.backend.storage;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Khi luu file tren S3: URL trong DB van la /uploads/videos/abc.mp4 (giong khi luu local).
 * Request toi /uploads/** duoc chuyen huong (302) sang presigned URL cua S3,
 * trinh duyet tai/stream video truc tiep tu S3 -> backend khong phai gui file nang.
 */
@RestController
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3UploadsController {

    private static final String PREFIX = "/uploads/";

    private final S3StorageClient s3StorageClient;

    public S3UploadsController(S3StorageClient s3StorageClient) {
        this.s3StorageClient = s3StorageClient;
    }

    @GetMapping("/uploads/**")
    public ResponseEntity<Void> redirect(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int idx = uri.indexOf(PREFIX);
        String key = idx < 0 ? "" : URLDecoder.decode(uri.substring(idx + PREFIX.length()), StandardCharsets.UTF_8);
        if (key.isBlank() || key.contains("..")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .location(URI.create(s3StorageClient.presignedUrl(key)))
                .build();
    }
}
