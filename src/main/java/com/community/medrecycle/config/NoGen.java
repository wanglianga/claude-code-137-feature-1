package com.community.medrecycle.config;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 业务单号生成器 */
public final class NoGen {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private NoGen() {
    }

    public static String next(String prefix) {
        StringBuilder sb = new StringBuilder(prefix);
        sb.append(LocalDateTime.now().format(DATE)).append('-');
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
