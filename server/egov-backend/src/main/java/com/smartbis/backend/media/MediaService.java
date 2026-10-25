package com.smartbis.backend.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.smartbis.backend.content.ContentAssetSummary;

import java.util.List;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class MediaService {
    private final JdbcTemplate jdbcTemplate;
    private final Path contentRoot;

    public MediaService(
            JdbcTemplate jdbcTemplate,
            @Value("${content.root:/app/content-store}") String contentRoot) {
        this.jdbcTemplate = jdbcTemplate;
        this.contentRoot = Path.of(contentRoot).toAbsolutePath().normalize();
    }

    @PostConstruct
    void validateRoot() throws IOException {
        Files.createDirectories(contentRoot);
    }

    public MediaFile load(String contentId) {
        String relativePath;
        try {
            relativePath = jdbcTemplate.queryForObject(
                    "SELECT source_path FROM contents WHERE content_id = ?",
                    String.class,
                    contentId);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "콘텐츠를 찾을 수 없습니다.", ex);
        }

        if (relativePath == null || relativePath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "연결된 미디어 파일이 없습니다.");
        }

        Path mediaPath = resolveContentPath(relativePath);
        return resourceFor(mediaPath);
    }

    public MediaFile loadAsset(String contentId, int sequence) {
        String filePath;
        try {
            filePath = jdbcTemplate.queryForObject(
                    "SELECT file_path FROM content_assets WHERE content_id = ? AND sequence_no = ?",
                    String.class,
                    contentId,
                    sequence);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "첨부 미디어를 찾을 수 없습니다.", ex);
        }
        if (filePath == null || filePath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "첨부 미디어 경로가 없습니다.");
        }
        return resourceFor(resolveContentPath(filePath));
    }

    public List<ContentAssetSummary> listAssets(String contentId) {
        return jdbcTemplate.query("""
                SELECT asset_role, file_name, sequence_no, mime_type,
                       file_size_bytes, sha256
                  FROM content_assets
                 WHERE content_id = ?
                 ORDER BY sequence_no
                """, (rs, rowNum) -> new ContentAssetSummary(
                rs.getString("asset_role"),
                rs.getString("file_name"),
                rs.getInt("sequence_no"),
                rs.getString("mime_type"),
                (Long) rs.getObject("file_size_bytes"),
                rs.getString("sha256"),
                "/api/v1/media/" + contentId + "/assets/" + rs.getInt("sequence_no")
        ), contentId);
    }

    private Path resolveContentPath(String storedPath) {
        Path stored = Path.of(storedPath);
        Path mediaPath = (stored.isAbsolute() ? stored : contentRoot.resolve(stored))
                .toAbsolutePath().normalize();
        if (!mediaPath.startsWith(contentRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 미디어 경로입니다.");
        }
        return mediaPath;
    }

    private MediaFile resourceFor(Path mediaPath) {
        FileSystemResource resource = new FileSystemResource(mediaPath);
        if (!resource.exists() || !resource.isReadable()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "미디어 파일을 찾을 수 없습니다.");
        }

        return new MediaFile(resource, mediaPath);
    }

    public record MediaFile(Resource resource, Path path) {
    }
}
