package kr.ac.chosun.mspace.reservation.adapter.in.web;

/**
 * 취소·퇴실처럼 상태만 바뀌는 요청의 응답: {"id": 12, "status": "CANCELED"}.
 * 영준 비서(mspace-assistant #30)가 이 모양을 읽는다.
 */
record StatusChangeResponse(long id, String status) {
}
