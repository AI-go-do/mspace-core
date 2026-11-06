package kr.ac.chosun.mspace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 사진 체크인·아웃 숫자 (FR-RPT-102, 11/5 지영 "추천대로"). 코드에 박지 않고 application.yml에서 읽는다.
 *
 * @param maxBytes             1장 최대 크기 (2MB). 화면이 줄여서 보낸다
 * @param checkoutGraceMinutes 퇴실 사진은 퇴실한 뒤 이만큼 안까지 올릴 수 있다 (30)
 * @param retentionDays        올린 지 이만큼 지나면 지운다 (30)
 */
@ConfigurationProperties("mspace.photo")
public record PhotoPolicy(int maxBytes, int checkoutGraceMinutes, int retentionDays) {
}
