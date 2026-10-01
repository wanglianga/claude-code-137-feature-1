package com.community.medrecycle.domain.enums;

/** 药盒个人信息（姓名/身份证号/诊断）隐私包装处理状态 */
public enum PrivacyStatus {
    NOT_REQUIRED,    // 药盒无个人信息，无需隐私处理
    TREATED,         // 现场已引导居民撕除/遮盖并拍摄处理后照片，处理合格
    PENDING_REVIEW,  // 居民不同意处理或处理存疑：药品可封袋，积分暂缓，待社区管理员复核
    RE_CONTACT,      // 复核：处理不合格，需社区重新联系居民处置（药品暂留、积分不发）
    NO_POINTS        // 复核：按无积分回收处理（药品继续流转，积分不予发放）
}
