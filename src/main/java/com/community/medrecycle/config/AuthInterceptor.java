package com.community.medrecycle.config;

import com.community.medrecycle.repository.AccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 认证拦截器：校验 X-Auth-Token 并装载当前用户 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenStore tokenStore;
    private final AccountRepository accountRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("X-Auth-Token");
        if (token == null || token.isBlank()) {
            throw BizException.unauthorized("缺少请求头 X-Auth-Token");
        }
        Long accountId = tokenStore.resolve(token)
                .orElseThrow(() -> BizException.unauthorized("令牌无效或已过期"));
        AccountRepository repo = accountRepository;
        var account = repo.findById(accountId)
                .orElseThrow(() -> BizException.unauthorized("账号不存在"));
        AuthContext.set(account);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
