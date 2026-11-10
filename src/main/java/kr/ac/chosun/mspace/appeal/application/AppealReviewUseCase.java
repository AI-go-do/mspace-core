package kr.ac.chosun.mspace.appeal.application;

/** 대기 중인 철회 요청을 판단한다. 스케줄러가 1분마다 부르고, 테스트는 직접 부른다 */
public interface AppealReviewUseCase {

    /** @return 이번에 결과를 낸 요청 수 */
    int reviewPending();
}
