package com.community.medrecycle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 社区药箱过期药回收称重兑换与环保交接服务。
 *
 * 主链路：居民预约 -> 回收员扫码登记/分类称重封袋 -> 高风险人工复核 -> 积分发放 ->
 * 暂存柜暂存（容量/超期预警） -> 环保公司交接（双人签收/拒收） -> 批次追溯。
 */
@SpringBootApplication
public class MedRecycleApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedRecycleApplication.class, args);
    }
}
