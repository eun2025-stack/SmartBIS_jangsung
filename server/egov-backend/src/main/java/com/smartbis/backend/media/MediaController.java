package com.smartbis.backend.media;

import java.io.IOException;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/media")
public class MediaController {
    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @GetMapping("/{contentId:.+}")
    public ResponseEntity<Resource> download(@PathVariable String contentId) throws IOException {
        MediaService.MediaFile media = mediaService.load(contentId);
        MediaType mediaType = MediaTypeFactoryCompat.detect(media.path().toString());

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(media.resource().contentLength())
                .header("Content-Disposition", "inline; filename=\"" + media.path().getFileName() + "\"")
                .body(media.resource());
    }

    private static final class MediaTypeFactoryCompat {
        private static MediaType detect(String filename) {
            String detected = org.springframework.http.MediaTypeFactory.getMediaType(filename)
                    .map(MediaType::toString)
                    .orElse(MimeTypeUtils.APPLICATION_OCTET_STREAM_VALUE);
            return MediaType.parseMediaType(detected);
        }
    }
}
