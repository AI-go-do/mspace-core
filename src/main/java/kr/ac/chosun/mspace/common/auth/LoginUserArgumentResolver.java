package kr.ac.chosun.mspace.common.auth;

import kr.ac.chosun.mspace.common.error.BusinessException;
import kr.ac.chosun.mspace.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 임시: 로그인 기능이 붙기 전까지 X-User-Id 헤더로 회원 id를 받는다.
 * mspace.auth.dev-header=true(로컬·테스트)일 때만 켜지고, 기본값은 꺼짐이라 운영에서는 항상 401.
 */
@Component
class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    static final String HEADER = "X-User-Id";

    private final boolean devHeaderEnabled;

    LoginUserArgumentResolver(@Value("${mspace.auth.dev-header:false}") boolean devHeaderEnabled) {
        this.devHeaderEnabled = devHeaderEnabled;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == LoginUser.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String header = request.getHeader(HEADER);
        if (!devHeaderEnabled || header == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return new LoginUser(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
