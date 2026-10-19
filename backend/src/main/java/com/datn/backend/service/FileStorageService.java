package com.datn.backend.service;

import com.datn.backend.constants.AppConstants;
import com.datn.backend.dto.FileStorageRequest;
import com.datn.backend.dto.FileStorageResponse;
import com.datn.backend.entity.FileStorage;
import com.datn.backend.repository.FileStorageRepository;
import com.datn.backend.storage.S3StorageClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
public class FileStorageService {

    /** Phan dau URL luu trong DB. Local: http://localhost:8080/uploads ; AWS (sau ALB): /uploads */
    private final String uploadBaseUrl;

    private final FileStorageRepository fileStorageRepository;
    private final S3StorageClient s3StorageClient;

    public FileStorageService(FileStorageRepository fileStorageRepository,
                              S3StorageClient s3StorageClient,
                              @Value("${app.upload.base-url:http://localhost:8080/uploads}") String uploadBaseUrl) {
        this.fileStorageRepository = fileStorageRepository;
        this.s3StorageClient = s3StorageClient;
        this.uploadBaseUrl = uploadBaseUrl.endsWith("/")
                ? uploadBaseUrl.substring(0, uploadBaseUrl.length() - 1)
                : uploadBaseUrl;
    }

    @Transactional
    public FileStorageResponse createFile(FileStorageRequest request) {
        FileStorage fileStorage = new FileStorage();
        fileStorage.setFileName(request.getFileName());
        if (request.getFileType() != null && !request.getFileType().isBlank()) {
            fileStorage.setFileType(request.getFileType());
        }
        if (request.getStorageProvider() != null && !request.getStorageProvider().isBlank()) {
            fileStorage.setStorageProvider(request.getStorageProvider());
        }
        fileStorage.setFileUrl(request.getFileUrl());
        fileStorage.setDriveFileId(request.getDriveFileId());
        fileStorage.setMimeType(request.getMimeType());
        fileStorage.setFileSize(request.getFileSize());

        return FileStorageResponse.from(fileStorageRepository.save(fileStorage));
    }

    @Transactional
    public FileStorageResponse uploadFile(MultipartFile file, String fileType) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is required");
        }

        String normalizedFileType = normalizeFileType(fileType);
        String folderName = getFolderName(normalizedFileType);
        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename() == null
                ? "uploaded-file"
                : file.getOriginalFilename());
        String safeFileName = originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String storedFileName = UUID.randomUUID() + "-" + safeFileName;

        String key = folderName + "/" + storedFileName;
        String provider;

        try {
            if (s3StorageClient.isEnabled()) {
                // AWS: luu len S3 (container ECS khong giu file khi restart)
                s3StorageClient.upload(key, file);
                provider = AppConstants.StorageProvider.S3;
            } else {
                // Local: luu vao thu muc uploads/
                Path uploadDirectory = Paths.get("uploads", folderName).toAbsolutePath().normalize();
                Files.createDirectories(uploadDirectory);

                Path targetPath = uploadDirectory.resolve(storedFileName).normalize();
                if (!targetPath.startsWith(uploadDirectory)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file name");
                }
                file.transferTo(targetPath);
                provider = AppConstants.StorageProvider.LOCAL;
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store uploaded file");
        } catch (RuntimeException exception) {
            if (exception instanceof ResponseStatusException rse) {
                throw rse;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not upload file to S3: " + exception.getMessage());
        }

        FileStorage fileStorage = new FileStorage();
        fileStorage.setFileName(originalFileName);
        fileStorage.setFileType(normalizedFileType);
        fileStorage.setStorageProvider(provider);
        fileStorage.setFileUrl(uploadBaseUrl + "/" + key);
        fileStorage.setDriveFileId(null);
        fileStorage.setMimeType(file.getContentType());
        fileStorage.setFileSize(file.getSize());

        return FileStorageResponse.from(fileStorageRepository.save(fileStorage));
    }

    @Transactional(readOnly = true)
    public List<FileStorageResponse> getAllFiles() {
        return fileStorageRepository.findAll()
                .stream()
                .map(FileStorageResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public FileStorageResponse getFileById(Long id) {
        return fileStorageRepository.findById(id)
                .map(FileStorageResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File metadata not found"));
    }

    @Transactional
    public void deleteFile(Long id) {
        FileStorage fileStorage = fileStorageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File metadata not found"));

        String key = extractKey(fileStorage.getFileUrl());
        if (key != null) {
            if (AppConstants.StorageProvider.S3.equals(fileStorage.getStorageProvider()) && s3StorageClient.isEnabled()) {
                s3StorageClient.deleteQuietly(key);
            } else if (AppConstants.StorageProvider.LOCAL.equals(fileStorage.getStorageProvider())) {
                Path filePath = Paths.get("uploads", key).toAbsolutePath().normalize();
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException ignored) {
                }
            }
        }

        fileStorageRepository.delete(fileStorage);
    }

    /** "http://localhost:8080/uploads/videos/a.mp4" hoac "/uploads/videos/a.mp4" -> "videos/a.mp4" */
    private String extractKey(String fileUrl) {
        if (fileUrl == null) {
            return null;
        }
        int idx = fileUrl.indexOf("/uploads/");
        if (idx < 0) {
            return null;
        }
        String key = fileUrl.substring(idx + "/uploads/".length());
        return key.isBlank() || key.contains("..") ? null : key;
    }

    private String normalizeFileType(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File type is required");
        }

        String normalizedFileType = fileType.trim().toLowerCase();
        if (!normalizedFileType.equals(AppConstants.FileType.VIDEO)
                && !normalizedFileType.equals(AppConstants.FileType.IMAGE)
                && !normalizedFileType.equals(AppConstants.FileType.DOCUMENT)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File type must be video, image, or document");
        }

        return normalizedFileType;
    }

    private String getFolderName(String fileType) {
        if (AppConstants.FileType.IMAGE.equals(fileType)) {
            return AppConstants.FileFolder.IMAGES;
        }
        if (AppConstants.FileType.DOCUMENT.equals(fileType)) {
            return AppConstants.FileFolder.DOCUMENTS;
        }
        return AppConstants.FileFolder.VIDEOS;
    }
}
