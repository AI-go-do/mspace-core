package kr.ac.chosun.mspace.photo.application;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 방 카메라(젯슨) 사진 (FR-DEV-105, docs Q-27, 11/10 지영 "core에 올려 30일 보관"). 구현: {@link CameraPhotoService}
 * 정리 확인 단계(입실 직전 ENTRY, 퇴실 뒤 FIRST, 다시 확인 RECHECK)마다 1장. 사람이 0명인 빈 방 화면만 보낸다(사람이 찍힌 사진은 장치가 보내지 않는다).
 */
public interface CameraPhotoUseCase {

    /**
     * 장치 토큰 검사는 입구가 한다. 이 방의 마지막 퇴실 예약에만 붙인다.
     * 400 PHOTO_INVALID·PHOTO_TOO_LARGE / 404 RESERVATION_NOT_FOUND / 409 TIDY_CHECK_NOT_LATEST·PHOTO_ALREADY_UPLOADED
     */
    Uploaded upload(int roomId, long reservationId, String phase, String deviceId, byte[] data);

    /** 그 예약의 대표자·참여자와 관리자만. 아니면(또는 없으면) 404 PHOTO_NOT_FOUND */
    PhotoPort.StoredPhoto view(long reservationId, long userId, String phase);

    /**
     * 관리자 화면: 방마다 단계(ENTRY·FIRST·RECHECK)별 가장 최근 사진 (11/12 지영 "관리자 페이지에 젯슨 카메라 사진이 그 방에 보이게").
     * 사진 내용은 url(GET /api/reservations/{id}/tidy-photos/{phase})로 따로 받는다. 관리자가 아니면 403 ADMIN_ONLY
     */
    RoomPhotos latestByRoom(long adminId);

    record RoomPhotos(ZonedDateTime now, List<RoomPhoto> rooms) {
    }

    /** @param photos 단계 순서 ENTRY → FIRST → RECHECK, 사진이 없는 단계는 빠진다 */
    record RoomPhoto(int roomId, String roomName, List<Photo> photos) {
    }

    /** @param takenAt 장치가 올린 시각 */
    record Photo(long reservationId, String phase, ZonedDateTime takenAt, String url) {
    }

    record Uploaded(long reservationId, String phase, ZonedDateTime uploadedAt, int sizeBytes) {
    }
}
