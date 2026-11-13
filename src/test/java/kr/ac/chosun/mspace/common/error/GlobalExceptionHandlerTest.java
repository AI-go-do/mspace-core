package kr.ac.chosun.mspace.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사진처럼 JSON이 아닌 것을 돌려주는 API에서도 오류는 팀 형식 JSON과 원래 상태 코드로 나간다 */
class GlobalExceptionHandlerTest {

    @RestController
    static class PhotoLikeController {

        @GetMapping("/photo")
        ResponseEntity<byte[]> photo() {
            throw new BusinessException(ErrorCode.PHOTO_NOT_FOUND);
        }
    }

    // standalone(DB·컨테이너 없이)으로 컨트롤러 하나와 전역 오류 처리만 붙인다
    private final MockMvcTester mvc = MockMvcTester.create(MockMvcBuilders.standaloneSetup(new PhotoLikeController())
            .setControllerAdvice(new GlobalExceptionHandler()).build());

    @Test
    @DisplayName("화면이 Accept: image/*로 사진을 받으려다 없으면 500이 아니라 404 PHOTO_NOT_FOUND JSON")
    void imageAcceptGetsJsonError() {
        // given: 화면(Alerts·관리 화면)은 사진을 Blob으로 받으려고 Accept를 image/*로 보낸다
        // when
        var result = mvc.get().uri("/photo").accept(MediaType.parseMediaType("image/*")).exchange();
        // then
        assertThat(result).hasStatus(404).hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PHOTO_NOT_FOUND");
    }

    @Test
    @DisplayName("Accept를 안 보내도 지금처럼 404 JSON")
    void noAcceptStillJson() {
        var result = mvc.get().uri("/photo").exchange();
        assertThat(result).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("PHOTO_NOT_FOUND");
    }
}
