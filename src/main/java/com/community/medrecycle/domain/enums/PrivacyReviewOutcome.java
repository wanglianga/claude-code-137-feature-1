package com.community.medrecycle.domain.enums;

/** 社区管理员对隐私包装暂缓积分袋的复核结论 */
public enum PrivacyReviewOutcome {
    APPROVE,     // 隐私处理合格，接受回收，补发居民积分
    RE_CONTACT,  // 隐私处理不合格，社区重新联系居民处理（药品暂留，积分仍暂缓）
    NO_POINTS    // 无法整改，按无积分回收处理（药品保留流转，积分不予发放）
}
