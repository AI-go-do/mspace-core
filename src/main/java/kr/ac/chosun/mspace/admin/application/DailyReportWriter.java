package kr.ac.chosun.mspace.admin.application;

import java.time.LocalDate;
import java.util.List;
import kr.ac.chosun.mspace.admin.application.DailyReportUseCase.Facts;

/**
 * 하루 숫자를 3–5줄 글로 쓰는 쪽 (외부 AI 어댑터 또는 정해진 틀). 판정기·철회 판단기처럼 포트 뒤에 둔다.
 * 숫자와 방 이름만 받는다. 이름·학번은 넘기지 않는다.
 */
public interface DailyReportWriter {

    Written write(LocalDate date, Facts facts);

    /** @param writer gemini:모델 또는 template */
    record Written(List<String> lines, String writer, String promptVersion) {
    }
}
