package kr.ac.chosun.mspace.common.auth;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 컨트롤러가 {@link LoginUser}를 인자로 받을 수 있게 {@link LoginUserArgumentResolver}를 등록한다 */
@Configuration
class AuthWebConfig implements WebMvcConfigurer {

    private final LoginUserArgumentResolver resolver;

    AuthWebConfig(LoginUserArgumentResolver resolver) {
        this.resolver = resolver;
    }

    /** Spring MVC 인자 리졸버 목록에 로그인 사용자 리졸버를 더한다 */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver);
    }
}
