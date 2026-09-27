package com.community.medrecycle.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 简单令牌存储（内存实现，演示用） */
@Component
public class TokenStore {

    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    public String issue(Long accountId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, accountId);
        return token;
    }

    public Optional<Long> resolve(String token) {
        return Optional.ofNullable(tokens.get(token));
    }
}
