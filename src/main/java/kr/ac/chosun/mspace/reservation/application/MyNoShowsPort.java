package kr.ac.chosun.mspace.reservation.application;

import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.reservation.application.MyNoShowsUseCase.Item;

/** 내 노쇼 기록 읽기. 구현: JdbcMyNoShowsAdapter */
public interface MyNoShowsPort {

    /** since 뒤 대표자 노쇼·자리 비움 기록, 최근 것부터. counted는 now - windowDays 뒤 + 무르지 않음 + 지난 노쇼 정지 끝 뒤 */
    List<Item> find(long userId, Instant since, Instant now, int windowDays);
}
