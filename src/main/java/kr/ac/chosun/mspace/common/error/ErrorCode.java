package kr.ac.chosun.mspace.common.error;

import org.springframework.http.HttpStatus;

/** 오류 코드 한곳 모음. 응답은 {"code": name(), "message": message} 모양 (NFR-COM-005). */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    ROOM_DATE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "오늘부터 예약 가능한 기간 안의 날짜만 조회할 수 있습니다"),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "없거나 지금 사용할 수 없는 방입니다"),
    ROOM_BLOCKED(HttpStatus.CONFLICT, "관리자가 막아 둔 시간이라 예약할 수 없습니다"),
    ROOM_BLOCK_INVALID(HttpStatus.BAD_REQUEST, "막을 기간이 올바르지 않습니다"),
    ROOM_BLOCK_OVERLAP(HttpStatus.CONFLICT, "이 방은 이미 그 시간에 막혀 있습니다. 먼저 풀거나 시간을 바꿔 주세요"),
    ROOM_BLOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "없는 기간 막기입니다"),
    ROOM_WATCH_FREE(HttpStatus.CONFLICT, "지금 비어 있어요. 바로 예약해 주세요"),
    RELAY_WAIT_LIMIT(HttpStatus.CONFLICT, "기다리기는 한 사람당 동시에 3개까지예요"),
    RELAY_TOO_LATE(HttpStatus.CONFLICT, "시작 30분 전부터는 기다리기를 걸 수 없어요"),
    RELAY_SLOT_FREE(HttpStatus.CONFLICT, "그 시간은 비어 있어요. 바로 예약해 주세요"),
    RELAY_WAIT_NOT_FOUND(HttpStatus.NOT_FOUND, "없는 기다리기예요"),
    RELAY_OFFER_NOT_FOUND(HttpStatus.NOT_FOUND, "받을 수 있는 자리가 아니에요"),
    RELAY_OFFER_INVALID(HttpStatus.CONFLICT, "넘겨받은 자리의 받을 시간이 지났거나 구간이 달라요"),
    RELAY_HELD(HttpStatus.CONFLICT, "기다리던 학생에게 먼저 넘겨진 자리예요"),

    // 참여자 학번 검색 (FR-RSV-004). 가입 안 한 학번과 학생 인증 전 회원은 같은 404 (어느 쪽인지 알려 주지 않으려고)
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "학생 인증을 마친 회원 중 그 학번이 없습니다"),

    // 친구 (FR-RSV-004, 11/6 지영 결정: 서로 수락, 삭제 가능)
    FRIEND_SELF(HttpStatus.BAD_REQUEST, "나에게는 친구 요청을 보낼 수 없습니다"),
    FRIEND_ALREADY(HttpStatus.CONFLICT, "이미 친구입니다"),
    FRIEND_REQUEST_ALREADY_SENT(HttpStatus.CONFLICT, "이미 친구 요청을 보냈습니다"),
    FRIEND_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없는 친구 요청입니다"),
    FRIEND_NOT_FOUND(HttpStatus.NOT_FOUND, "친구 목록에 없는 학번입니다"),

    // 예약 생성 (FR-RSV-001~005)
    RESERVATION_NOT_ON_SLOT(HttpStatus.BAD_REQUEST, "시작·종료 시각은 정각 또는 30분이어야 합니다"),
    RESERVATION_INVALID_LENGTH(HttpStatus.BAD_REQUEST, "종료는 시작보다 뒤여야 하고, 1회 최대 2시간입니다"),
    RESERVATION_IN_PAST(HttpStatus.BAD_REQUEST, "지난 시각은 예약할 수 없습니다"),
    RESERVATION_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "오늘부터 7일 안에 시작하는 예약만 할 수 있습니다"),
    PARTICIPANT_DUPLICATE(HttpStatus.BAD_REQUEST, "같은 학번을 두 번 넣을 수 없습니다"),
    PARTICIPANT_NOT_FOUND(HttpStatus.BAD_REQUEST, "학생 인증을 마친 회원의 학번만 넣을 수 있습니다"),
    // 친구만 참여자로 (FR-RSV-004, 11/6 지영). 누가 친구가 아닌지는 알려 주지 않는다
    PARTICIPANT_NOT_FRIEND(HttpStatus.FORBIDDEN, "서로 친구인 회원만 참여자로 넣을 수 있습니다"),
    HEADCOUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "방의 이용 인원을 벗어났습니다"),
    RESERVATION_TIME_TAKEN(HttpStatus.CONFLICT, "이미 예약된 시간입니다"),
    // FR-RSV-003 (11/5): 대표자든 참여자든 끝나지 않은 예약은 1건. 나(대표자)가 걸리면 이 코드
    OWNER_HAS_ACTIVE_RESERVATION(HttpStatus.CONFLICT, "끝나지 않은 예약이 이미 있습니다"),
    // 넣으려는 참여자 중 누가 다른 끝나지 않은 예약에 들어가 있음. 누구인지는 알려 주지 않는다
    PARTICIPANT_HAS_ACTIVE_RESERVATION(HttpStatus.CONFLICT, "참여자 중 끝나지 않은 예약이 이미 있는 사람이 있습니다"),
    PARTICIPANT_TIME_TAKEN(HttpStatus.CONFLICT, "참여자 중 같은 시간에 다른 방 예약이 있는 사람이 있습니다"),
    // 노쇼 벌칙 (FR-RSV-106): 30일 안 노쇼 2번 → 14일 정지. 참여자는 누구인지 알려 주지 않는다(남의 제재 기록이라)
    USER_SUSPENDED(HttpStatus.FORBIDDEN, "이용이 정지되어 예약할 수 없습니다"),
    PARTICIPANT_SUSPENDED(HttpStatus.FORBIDDEN, "참여자 중 이용 정지 중인 사람이 있어 예약할 수 없습니다"),
    // 동시 요청끼리 DB에서 서로 기다리는 교착이 다시 해도 계속 날 때 (드묾). 예약은 저장되지 않았다
    RESERVATION_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요"),
    // 같은 Idempotency-Key를 다른 내용(방·시각·참여자)에 다시 씀 (#10)
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "이미 다른 예약에 쓴 Idempotency-Key입니다"),

    // 사진 체크인·아웃 (FR-RPT-102). 선택 기능이라 실패해도 입실·퇴실은 그대로
    PHOTO_INVALID(HttpStatus.BAD_REQUEST, "사진 종류(CHECK_IN·CHECK_OUT)와 JPEG·PNG 파일이 필요합니다"),
    PHOTO_TOO_LARGE(HttpStatus.BAD_REQUEST, "사진은 1장 최대 2MB입니다. 줄여서 다시 올려 주세요"),
    PHOTO_NOT_ALLOWED_NOW(HttpStatus.CONFLICT, "입실 사진은 사용 중에, 퇴실 사진은 사용 중이거나 퇴실 후 30분 안에 올릴 수 있습니다"),
    PHOTO_ALREADY_UPLOADED(HttpStatus.CONFLICT, "이 예약의 같은 종류 사진이 이미 있습니다"),
    PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없는 사진입니다"),

    // 예약 취소 (FR-RSV-007). 이름은 영준 비서(mspace-assistant #30)와 맞춤
    // 없는 예약과 남의 예약(참여자도 아님)은 둘 다 404: 남의 예약이 있는지 없는지 알려 주지 않으려고
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없는 예약입니다"),
    NOT_RESERVATION_OWNER(HttpStatus.FORBIDDEN, "대표자만 할 수 있습니다"),
    ADMIN_ONLY(HttpStatus.FORBIDDEN, "관리자만 볼 수 있습니다"),
    RESERVATION_NOT_CANCELABLE(HttpStatus.CONFLICT, "시작 전인 예약만 취소할 수 있습니다"),

    // 퇴실 (FR-RSV-008). 이름은 영준 비서(mspace-assistant #30)와 맞춤
    RESERVATION_NOT_IN_USE(HttpStatus.CONFLICT, "입실한(사용 중) 예약만 퇴실할 수 있습니다"),

    // "아직 있어요" (FR-DEV-103). 기본으로 켜져 있고, 설정으로 끄면 없는 기능처럼 404
    STILL_HERE_DISABLED(HttpStatus.NOT_FOUND, "지금은 쓸 수 없는 기능입니다"),
    STILL_HERE_ALREADY_USED(HttpStatus.CONFLICT, "'아직 있어요'는 예약당 1번만 쓸 수 있습니다"),

    // 연장하기 (FR-RSV-105). 409 RESERVATION_EXTENSION_LIMIT은 11/3 지영·영준 합의(#44)
    RESERVATION_EXTENSION_LIMIT(HttpStatus.CONFLICT, "연장은 1회만 할 수 있습니다"),
    RESERVATION_EXTENSION_NOT_OPEN(HttpStatus.CONFLICT, "연장은 종료 10분 전부터 종료 전까지만 할 수 있습니다"),

    // 신고 (FR-RPT-101)
    REPORT_NOISE_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "소음 신고는 \"정말 소란인가요?\"를 체크해야 보낼 수 있습니다"),
    REPORT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "지금 그 방이나 옆 방을 사용 중인 예약자만 신고할 수 있습니다"),
    REPORT_MESSY_OWN_ROOM_ONLY(HttpStatus.FORBIDDEN, "어질러짐은 지금 쓰고 있는 방만 신고할 수 있습니다"),
    REPORT_MESSY_TOO_LATE(HttpStatus.CONFLICT, "어질러짐은 입실한 뒤 바로만 신고할 수 있습니다"),
    REPORT_CHECKIN_PHOTO_REQUIRED(HttpStatus.CONFLICT, "어질러짐을 신고하려면 입실 사진을 먼저 올려 주세요"),
    // 관리자 판정 (FR-RPT-103)
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없는 신고입니다"),
    REPORT_NOT_JUDGEABLE(HttpStatus.CONFLICT, "소음 신고는 판정 대상이 아닙니다"),
    REPORT_ALREADY_JUDGED(HttpStatus.CONFLICT, "이미 판정한 신고입니다"),
    RESERVATION_NOT_ENDED(HttpStatus.CONFLICT, "아직 끝나지 않았거나 입실하지 않은 예약입니다"),
    TIDY_CHECK_EXISTS(HttpStatus.CONFLICT, "이 예약의 정리 확인 결과가 이미 있습니다"),
    TIDY_CHECK_NOT_LATEST(HttpStatus.CONFLICT, "이 방에서 방금 끝난 예약이 아닙니다"),
    TIDY_RECHECK_NOT_NEEDED(HttpStatus.CONFLICT, "이 예약은 다시 확인할 것이 없습니다"),
    TIDY_RECHECK_TOO_EARLY(HttpStatus.CONFLICT, "아직 다시 확인할 시각이 아닙니다"),
    // 찾으러 갈게요 10분 QR (11/10 지영)
    RETRIEVE_NOT_NEEDED(HttpStatus.CONFLICT, "방 카메라가 두고 간 물건을 찾지 않은 예약입니다"),
    RETRIEVE_TOO_LATE(HttpStatus.CONFLICT, "찾으러 올 수 있는 시간이 지났습니다"),
    // 허위 신고 경고가 쌓이면 (FR-RPT-103 벌칙, 11/6)
    REPORT_SUSPENDED(HttpStatus.FORBIDDEN, "허위 신고 경고가 쌓여 신고가 정지되었습니다"),

    // 알림함 (FR-NTF-103). 남의 알림은 있는지도 알려 주지 않으려고 403 대신 404
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없는 알림입니다"),

    // 키패드 (FR-ACC-001)
    DEVICE_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "장치 토큰이 없거나 맞지 않습니다"),
    DEVICE_ROOM_MISMATCH(HttpStatus.FORBIDDEN, "이 장치 토큰은 다른 방에 등록되어 있습니다"),
    DEVICE_ID_MISMATCH(HttpStatus.FORBIDDEN, "이 장치 토큰은 다른 장치 이름(deviceId)으로 등록되어 있습니다"),
    // 내부용: 새로 뽑은 번호가 같은 방 다른 예약 번호와 겹침 → 서비스가 새 번호로 다시 한다 (응답으로 나가지 않음)
    ACCESS_CODE_TAKEN(HttpStatus.CONFLICT, "키패드 번호가 겹쳤습니다"),

    // 로그인 (FR-AUTH-001)
    SCHOOL_EMAIL_ONLY(HttpStatus.BAD_REQUEST, "학교 메일(@chosun.ac.kr)만 사용할 수 있습니다"),
    DEVICE_FAULT_TOO_MANY(HttpStatus.TOO_MANY_REQUESTS, "이 방 장치 오류 번호를 하루에 너무 많이 보냈어요"),
    EMAIL_CODE_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "인증번호는 1분에 한 번만 보낼 수 있습니다"),
    EMAIL_CODE_NOT_FOUND(HttpStatus.BAD_REQUEST, "인증번호를 먼저 받아 주세요"),
    EMAIL_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 만료되었습니다. 다시 받아 주세요"),
    EMAIL_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증번호가 맞지 않습니다"),
    EMAIL_CODE_TOO_MANY_ATTEMPTS(HttpStatus.BAD_REQUEST, "5번 틀려서 이 인증번호는 더 쓸 수 없습니다. 다시 받아 주세요"),
    SIGNUP_INFO_REQUIRED(HttpStatus.BAD_REQUEST, "처음 로그인이라 이름과 학번이 필요합니다"),
    INVALID_SIGNUP_INFO(HttpStatus.BAD_REQUEST, "이름(50자 이내)과 학번(숫자 20자 이내)을 확인해 주세요"),
    STUDENT_ID_TAKEN(HttpStatus.CONFLICT, "이미 가입된 학번입니다"),
    EMAIL_ALREADY_SIGNED_UP(HttpStatus.CONFLICT, "이 메일로 방금 가입되었습니다. 인증번호를 다시 받아 로그인해 주세요"),
    MAIL_SEND_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "인증번호 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요"),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
