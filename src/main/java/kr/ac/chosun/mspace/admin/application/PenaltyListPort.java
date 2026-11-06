package kr.ac.chosun.mspace.admin.application;

import java.time.Instant;
import java.util.List;
import kr.ac.chosun.mspace.admin.application.PenaltyListUseCase.Suspension;
import kr.ac.chosun.mspace.admin.application.PenaltyListUseCase.Warning;

/** 경고·정지 목록 저장소. 구현: JdbcPenaltyListAdapter */
public interface PenaltyListPort {

    boolean isAdmin(long userId);

    /** since 뒤에 받은 경고, 최근 것부터 */
    List<Warning> warnings(Instant since);

    /** since 뒤에 끝나는(진행 중 포함) 이용 정지·신고 정지, 시작이 늦은 것부터 */
    List<Suspension> suspensions(Instant since, Instant now);
}
