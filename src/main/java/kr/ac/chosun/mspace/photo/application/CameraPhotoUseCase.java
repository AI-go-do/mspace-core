package kr.ac.chosun.mspace.photo.application;

import java.time.ZonedDateTime;

/**
 * 방 카메라(젯슨) 사진 (FR-DEV-105, docs Q-27, 11/10 지영 "core에 올려 30일 보관"). 구현: {@link CameraPhotoService}
 * 정리 확인 단계(FIRST·RECHECK)마다 1장. 사람이 0명인 빈 방 화면만 보낸다(사람이 찍힌 사진은 장치가 보내지 않는다).
 */
public interface CameraPhotoUseCase {

    /**
     * 장치 토큰 검사는 입구가 한다. 이 방의 마지막 퇴실 예약에만 붙인다.
     * 400 PHOTO_INVALID·PHOTO_TOO_LARGE / 404 RESERVATION_NOT_FOUND / 409 TIDY_CHECK_NOT_LATEST·PHOTO_ALREADY_UPLOADED
     */
    Uploaded upload(int roomId, long reservationId, String phase, String deviceId, byte[] data);

    /** 그 예약의 대표자·참여자와 관리자만. 아니면(또는 없으면) 404 PHOTO_NOT_FOUND */
    PhotoPort.StoredPhoto view(long reservationId, long userId, String phase);

    record Uploaded(long reservationId, String phase, ZonedDateTime uploadedAt, int sizeBytes) {
    }
}
