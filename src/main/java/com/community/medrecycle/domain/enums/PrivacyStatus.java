package com.community.medrecycle.domain.enums;

/**
 * 隐私包装处理记录状态：
 * PROCESSED      居民同意，现场已撕除/遮盖并拍摄处理后照片
 * PENDING_REVIEW 居民不同意现场处理，药品封袋但积分暂缓，待社区管理员复核
 * APPROVED       管理员复核通过，接受回收并补发积分
 * REWORK         管理员认定处理不合格，社区需重新联系居民重新处理
 * NO_POINTS      管理员决定按无积分回收（药品收下，不再发放积分）
 */
public enum PrivacyStatus {
    PROCESSED,
    PENDING_REVIEW,
    APPROVED,
    REWORK,
    NO_POINTS
}
