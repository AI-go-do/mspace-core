package kr.ac.chosun.mspace.common.auth;

import java.util.Optional;

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
 * 요청을 보낸 회원을 정한다.
 * 1) Authorization: Bearer 액세스 토큰(JWT, FR-AUTH-001·002)이 있으면 그것만 본다. 틀리면 401.
 * 2) 토큰이 없을 때만, mspace.auth.dev-header=true(로컬·테스트)이면 임시 X-User-Id 헤더를 받는다.
 *    기본값은 꺼짐이라 운영에서는 토큰 없이 401.
 */
@Component
class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    static final String HEADER = "X-User-Id";
    private static final String BEARER = "Bearer ";

    private final JwtTokens tokens;
    private final boolean devHeaderEnabled;

    LoginUserArgumentResolver(JwtTokens tokens, @Value("${mspace.auth.dev-header:false}") boolean devHeaderEnabled) {
        this.tokens = tokens;
        this.devHeaderEnabled = devHeaderEnabled;
    }

    /** 컨트롤러 메서드 인자 중 {@link LoginUser} 타입만 이 리졸버가 채운다 */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == LoginUser.class;
    }

    /**
     * 액세스 토큰(없으면 임시 헤더)을 읽어 {@link LoginUser}를 만든다.
     *
     * @return 회원 id를 담은 LoginUser
     * @throws BusinessException 토큰이 틀렸거나 user 범위가 없거나, 토큰도 임시 헤더도 없으면 401(UNAUTHORIZED)
     */
    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            if (!authorization.startsWith(BEARER)) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            Optional<JwtTokens.Verified> verified = tokens.verify(authorization.substring(BEARER.length()).trim());
            return verified
                    .filter(v -> v.scopes().contains(JwtTokens.Scopes.USER))
                    .map(v -> new LoginUser(v.memberId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        }
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
