package com.datn.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.util.Arrays;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** Doc tu APP_CORS_ALLOWED_ORIGINS, nhieu gia tri phan cach bang dau phay. */
    @Value("${app.cors.allowed-origins:}")
    private String[] allowedOrigins;

    @Value("${app.storage.type:local}")
    private String storageType;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins)
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .toArray(String[]::new);
        if (origins.length == 0) {
            return; // prod: frontend va backend chung domain qua ALB, khong can CORS
        }
        allowedOrigins = origins;
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
        registry.addMapping("/uploads/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Chi phuc vu file tu o dia khi luu local. Khi luu S3, /uploads/** do S3UploadsController xu ly.
        if (!"local".equalsIgnoreCase(storageType)) {
            return;
        }
        String uploadPath = Paths.get("uploads").toAbsolutePath().normalize().toUri().toString();
        if (!uploadPath.endsWith("/")) {
            uploadPath = uploadPath + "/";
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadPath);
    }
}
