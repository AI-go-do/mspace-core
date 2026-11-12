package kr.ac.chosun.mspace.appeal.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import kr.ac.chosun.mspace.common.config.AppealPolicy;
import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 경고 목록과 철회 요청 (FR-RPT-105). 요청은 저장만 하고, 판단은 AppealReviewService가 따로 한다
 * (AI 호출을 요청 트랜잭션 안에 두지 않으려고). 요청 중에도 경고는 센다 (Q-24 ①).
 */
@Service
public class WarningAppealService implements WarningAppealUseCase {

    static final int TEXT_MIN = 10;
    static final int TEXT_MAX = 500;
    static final Set<String> EVIDENCE = Set.of("CHECKIN_PHOTO", "CHECKOUT_PHOTO", "TIDY_CHECK");
    /** 숫자 7개 이상이 이어지면(띄어쓰기·하이픈 허용) 학번·전화번호로 본다. 사유는 판단기(AI)에게 넘어간다 */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d(?:[\\s-]?+\\d){6,}+");
    private static final int MAX_DAYS = 90;

    private final WarningAppealPort port;
    private final AppealPolicy policy;
    private final Clock clock;

    public WarningAppealService(WarningAppealPort port, AppealPolicy policy, Clock clock) {
        this.port = port;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public List<WarningItem> mine(long userId, int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "days는 1–%d입니다".formatted(MAX_DAYS));
        }
        return port.mine(userId, clock.instant().minus(Duration.ofDays(days)), policy.days());
    }

    @Override
    @Transactional
    public WarningItem appeal(long userId, long warningId, String text, List<String> evidence) {
        String reason = text == null ? "" : text.strip();
        int length = reason.codePointCount(0, reason.length()); // DB char_length와 같게 글자(코드 포인트)로 센다
        if (length < TEXT_MIN || length > TEXT_MAX) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "사유는 %d–%d자입니다".formatted(TEXT_MIN, TEXT_MAX));
        }
        if (LONG_NUMBER.matcher(reason).find()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "사유에 학번·전화번호 같은 긴 숫자는 넣을 수 없습니다");
        }
        Set<String> asked = new LinkedHashSet<>(evidence == null ? List.of() : evidence);
        if (asked.contains(null) || !EVIDENCE.containsAll(asked)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "evidence는 CHECKIN_PHOTO, CHECKOUT_PHOTO, TIDY_CHECK 중에서 고릅니다");
        }
        List<String> picked = List.copyOf(asked);
        WarningAppealPort.Mine mine = port.lockMine(userId, warningId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WARNING_NOT_FOUND));
        // 관리자가 이미 철회한 경고에 요청이 들어가면 판단기가 다시 보고 "유지"라고 답할 수 있다 (재웅 #123)
        if (mine.withdrawn()) {
            throw new BusinessException(ErrorCode.WARNING_ALREADY_WITHDRAWN);
        }
        Instant warnedAt = mine.warnedAt();
        Instant now = clock.instant();
        if (!now.isBefore(warnedAt.plus(Duration.ofDays(policy.days())))) {
            throw new BusinessException(ErrorCode.WARNING_APPEAL_EXPIRED);
        }
        if (!port.insertIfAbsent(userId, warningId, reason, picked, now)) {
            throw new BusinessException(ErrorCode.WARNING_APPEAL_DUPLICATE);
        }
        return port.one(userId, warningId, policy.days()).orElseThrow();
    }
}
