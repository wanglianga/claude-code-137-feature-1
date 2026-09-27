package com.community.medrecycle.domain.enums;

public enum HandoverStatus {
    DRAFT,            // 已建单待签收
    IN_TRANSIT,       // 已签字出库
    COMPLETED,        // 环保公司全部签收
    PARTIAL_REJECTED  // 部分袋被环保公司拒收
}
