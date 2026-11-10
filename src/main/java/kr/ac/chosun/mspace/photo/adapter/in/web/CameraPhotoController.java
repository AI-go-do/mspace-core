package kr.ac.chosun.mspace.photo.adapter.in.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZonedDateTime;
import kr.ac.chosun.mspace.common.auth.DeviceTokenVerifier;
import kr.ac.chosun.mspace.common.auth.LoginUser;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import kr.ac.chosun.mspace.photo.application.CameraPhotoUseCase;
import kr.ac.chosun.mspace.photo.application.PhotoPort.StoredPhoto;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 방 카메라(젯슨) 사진 (FR-DEV-105, docs Q-27). 올리기는 장치 토큰, 보기는 그 예약 사람들과 관리자 */
@RestController
class CameraPhotoController {

    private final CameraPhotoUseCase photos;
    private final DeviceTokenVerifier devices;

    CameraPhotoController(CameraPhotoUseCase photos, DeviceTokenVerifier devices) {
        this.photos = photos;
        this.devices = devices;
    }

    /**
     * Authorization: Bearer 장치토큰, multipart/form-data: reservationId, phase=FIRST|RECHECK, deviceId, file=JPEG·PNG(최대 2MB)
     * → 201 {"reservationId":12,"phase":"FIRST","uploadedAt":"…+09:00","sizeBytes":123456}
     * 400 PHOTO_INVALID·PHOTO_TOO_LARGE / 401 토큰 / 404 RESERVATION_NOT_FOUND
     * / 409 TIDY_CHECK_NOT_LATEST(이 방의 마지막 퇴실 예약 아님)·PHOTO_ALREADY_UPLOADED(같은 단계 두 번째)
     */
    @PostMapping("/api/rooms/{roomId}/tidy-photos")
    @ResponseStatus(HttpStatus.CREATED)
    UploadedResponse upload(@RequestHeader(name = "Authorization", required = false) String authorization,
                            @PathVariable int roomId,
                            @RequestParam(name = "reservationId", required = false) Long reservationId,
                            @RequestParam(name = "phase", required = false) String phase,
                            @RequestParam(name = "deviceId", required = false) String deviceId,
                            @RequestParam(name = "file", required = false) MultipartFile file) {
        devices.verify(authorization, roomId, deviceId);
        if (reservationId == null) {
            throw new BusinessException(ErrorCode.PHOTO_INVALID);
        }
        CameraPhotoUseCase.Uploaded u = photos.upload(roomId, reservationId, phase, deviceId, bytes(file));
        return new UploadedResponse(u.reservationId(), u.phase(), u.uploadedAt(), u.sizeBytes());
    }

    /** 사진 그대로(image/jpeg·png). 그 예약 사람들과 관리자만, 아니면 404 PHOTO_NOT_FOUND */
    @GetMapping("/api/reservations/{id}/tidy-photos/{phase}")
    ResponseEntity<byte[]> view(LoginUser user, @PathVariable long id, @PathVariable String phase) {
        StoredPhoto p = photos.view(id, user.id(), phase);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(p.contentType()))
                .cacheControl(CacheControl.noStore())
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

    record UploadedResponse(long reservationId, String phase, ZonedDateTime uploadedAt, int sizeBytes) {
    }
}
