package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import kr.ac.chosun.mspace.reservation.domain.CreatedVia;
import kr.ac.chosun.mspace.reservation.domain.ReservationStatus;

/**
 * 예약 만들기 입력 포트. 웹 어댑터(컨트롤러)는 구현 클래스가 아니라 이 인터페이스만 안다.
 * 구현: {@link CreateReservationService}
 */
public interface CreateReservationUseCase {

    /**
     * 미리 예약을 만든다.
     *
     * @param ownerId 대표자 회원 id (로그인 사용자)
     * @param command 방·시각·참여자 학번 (대표자 학번은 빼고)
     * @return 만든 예약과 키패드 번호
     */
    Created create(long ownerId, Command command);

    /**
     * 즉시 시작 (FR-RSV-103): 지금 빈 방을 버튼 누른 시각부터 쓴다. 끝은 고른 30분 칸(최대 지금 + 2시간을 올린 칸).
     * 10분 안에 키패드로 입실하지 않으면 노쇼. 인원·참여자 규칙은 미리 예약과 같다.
     *
     * @param command startAt은 무시한다 (서버 시계가 시작)
     */
    Created startNow(long ownerId, Command command);

    /**
     * 예약 만들기 입력. 시각은 Instant(UTC 기준 한 순간)로 받는다.
     *
     * @param idempotencyKey 멱등 키(Idempotency-Key 헤더, 64자 이하). 없으면 null (웹 화면)
     * @param createdVia     어디서 만들었나 (X-Client 헤더). 통계용이고 권한과는 상관없다
     */
    record Command(int roomId, Instant startAt, Instant endAt, List<String> participantStudentIds,
                   String idempotencyKey, CreatedVia createdVia) {

        /** 웹 화면에서 */
        public Command(int roomId, Instant startAt, Instant endAt, List<String> participantStudentIds,
                       String idempotencyKey) {
            this(roomId, startAt, endAt, participantStudentIds, idempotencyKey, CreatedVia.WEB);
        }

        /** 멱등 키 없이 */
        public Command(int roomId, Instant startAt, Instant endAt, List<String> participantStudentIds) {
            this(roomId, startAt, endAt, participantStudentIds, null);
        }
    }

    /**
     * 예약 만들기 결과. 시각은 서울 시간대. API 응답 모양은 웹 어댑터의 CreateReservationResponse가 정한다.
     *
     * @param replayed true면 같은 멱등 키로 다시 온 요청이라 새로 만들지 않고 처음 예약을 돌려준 것 (201 대신 200)
     */
    record Created(long id, int roomId, ZonedDateTime startAt, ZonedDateTime endAt,
                   ReservationStatus status, int headcount, String accessCode, boolean replayed) {
    }
}
