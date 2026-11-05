package kr.ac.chosun.mspace.photo.application;

import java.time.ZonedDateTime;

/** 사진 체크인·아웃 (FR-RPT-102) */
public interface PhotoUseCase {

    /**
     * @param kind 문자열 그대로(CHECK_IN·CHECK_OUT). 틀리면 400 PHOTO_INVALID
     * @param data 사진 파일. 없거나 JPEG·PNG가 아니면 400, 너무 크면 400 PHOTO_TOO_LARGE
     */
    Uploaded upload(long reservationId, long userId, String kind, byte[] data);

    /** 그 예약의 대표자·참여자와 관리자만. 아니면(또는 없으면) 404 PHOTO_NOT_FOUND */
    PhotoPort.StoredPhoto view(long reservationId, long userId, String kind);

    /** 보관 기간(30일)이 지난 사진을 지운다. 지운 장수 */
    int deleteExpired();

    record Uploaded(long reservationId, PhotoKind kind, ZonedDateTime uploadedAt, int sizeBytes) {
    }
}
