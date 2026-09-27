package com.community.medrecycle.domain.enums;

public enum TxnType {
    GRANT,   // 发放
    EXCHANGE,// 兑换扣减
    REVOKE,  // 扣回（异常）
    ADJUST   // 调账（称重争议等）
}
