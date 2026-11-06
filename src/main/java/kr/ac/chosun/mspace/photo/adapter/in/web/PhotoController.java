package kr.ac.chosun.mspace.photo.adapter.in.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZonedDateTime;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.photo.application.PhotoPort.StoredPhoto;
import kr.ac.chosun.mspace.photo.application.PhotoUseCase;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 사진 체크인·아웃 (FR-RPT-102). 형식: docs api/README.md "사진" */
@RestController
class PhotoController {

    private final PhotoUseCase photos;

    PhotoController(PhotoUseCase photos) {
        this.photos = photos;
    }

    /**
     * multipart/form-data: kind=CHECK_IN|CHECK_OUT, file=JPEG·PNG(최대 2MB)
     * → 201 {"reservationId":12,"kind":"CHECK_IN","uploadedAt":"…+09:00","sizeBytes":123456}
     * 400 PHOTO_INVALID·PHOTO_TOO_LARGE / 404 RESERVATION_NOT_FOUND / 409 PHOTO_NOT_ALLOWED_NOW·PHOTO_ALREADY_UPLOADED
     */
    @PostMapping("/api/reservations/{id}/photos")
    @ResponseStatus(HttpStatus.CREATED)
    UploadedResponse upload(LoginUser user, @PathVariable long id,
                            @RequestParam(name = "kind", required = false) String kind,
                            @RequestParam(name = "file", required = false) MultipartFile file) {
        PhotoUseCase.Uploaded u = photos.upload(id, user.id(), kind, bytes(file));
        return new UploadedResponse(u.reservationId(), u.kind().name(), u.uploadedAt(), u.sizeBytes());
    }

    /** 사진 그대로(image/jpeg·png). 그 예약 사람들과 관리자만, 아니면 404 PHOTO_NOT_FOUND */
    @GetMapping("/api/reservations/{id}/photos/{kind}")
    ResponseEntity<byte[]> view(LoginUser user, @PathVariable long id, @PathVariable String kind) {
        StoredPhoto p = photos.view(id, user.id(), kind);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(p.contentType()))
                .cacheControl(CacheControl.noStore())   // 개인 사진이라 브라우저·프록시에 남기지 않는다
                .header("X-Content-Type-Options", "nosniff")
                .body(p.data());
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record UploadedResponse(long reservationId, String kind, ZonedDateTime uploadedAt, int sizeBytes) {
    }
}
