package com.community.medrecycle.web;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.config.TokenStore;
import com.community.medrecycle.domain.Account;
import com.community.medrecycle.repository.AccountRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AccountRepository accountRepository;
    private final TokenStore tokenStore;

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        Account account = accountRepository.findByUsername(request.username())
                .filter(a -> a.getPassword().equals(request.password()))
                .orElseThrow(() -> BizException.unauthorized("用户名或密码错误"));
        String token = tokenStore.issue(account.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("account", account);
        return result;
    }
}
