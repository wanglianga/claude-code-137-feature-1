package com.community.medrecycle.domain.enums;

public enum ReviewStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SUPERSEDED // 被新的复核任务替代（如隐私重新处理后提交）
}
