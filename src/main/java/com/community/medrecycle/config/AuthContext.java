package com.community.medrecycle.config;

import com.community.medrecycle.domain.Account;
import com.community.medrecycle.domain.enums.Role;

import java.util.Arrays;

/** 当前登录用户上下文（基于请求头 X-Auth-Token） */
public final class AuthContext {

    private static final ThreadLocal<Account> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(Account account) {
        CURRENT.set(account);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Account current() {
        return CURRENT.get();
    }

    public static Account require() {
        Account account = CURRENT.get();
        if (account == null) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        return account;
    }

    public static Account requireRole(Role... roles) {
        Account account = require();
        boolean ok = Arrays.asList(roles).contains(account.getRole());
        if (!ok) {
            throw BizException.forbidden("当前角色无权执行该操作，需要角色: " + Arrays.toString(roles));
        }
        return account;
    }
}
