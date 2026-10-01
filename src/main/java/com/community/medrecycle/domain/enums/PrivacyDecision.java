package com.community.medrecycle.domain.enums;

/** 社区管理员对隐私包装复核的决定 */
public enum PrivacyDecision {
    APPROVE,  // 复核通过：接受回收并发放（暂缓的）积分
    REWORK,   // 处理不合格：社区重新联系居民重新撕除/遮盖后再提交
    NO_POINTS // 按无积分回收：药品收下继续流转，该袋积分不再发放
}
