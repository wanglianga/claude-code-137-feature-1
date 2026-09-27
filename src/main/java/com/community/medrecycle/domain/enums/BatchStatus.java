package com.community.medrecycle.domain.enums;

/** 批次状态：把预约、称重、复核、积分、暂存、交接串在同一批次里 */
public enum BatchStatus {
    CHECKED_IN,           // 已扫码登记
    WEIGHING,             // 称重封袋中
    IN_REVIEW,            // 存在待人工复核的高风险袋
    POINTS_GRANTED,       // 积分已发放
    STORED,               // 已全部入暂存柜
    PARTIALLY_HANDED_OVER,// 部分袋已交接
    HANDED_OVER,          // 全部交接完成
    CLOSED                // 批次关闭（含全部袋被复核拒绝等终态）
}
