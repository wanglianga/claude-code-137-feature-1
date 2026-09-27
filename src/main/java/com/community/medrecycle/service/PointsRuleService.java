package com.community.medrecycle.service;

import com.community.medrecycle.config.BizException;
import com.community.medrecycle.domain.PointsRule;
import com.community.medrecycle.domain.enums.DrugCategory;
import com.community.medrecycle.repository.PointsRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 积分规则：预约时快照、按品类计算积分、高风险判定 */
@Service
@RequiredArgsConstructor
public class PointsRuleService {

    private final PointsRuleRepository pointsRuleRepository;

    public List<PointsRule> activeRules() {
        return pointsRuleRepository.findByActiveTrue();
    }

    /** 预约时固化规则快照，格式 TABLET:10,ANTIBIOTIC:15 */
    public String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (PointsRule rule : activeRules()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(rule.getCategory().name()).append(':').append(rule.getPointsPerKg());
        }
        return sb.toString();
    }

    /** 解析快照为 品类->每公斤积分；快照缺失的品类回退到当前规则 */
    public int rateFor(String snapshot, DrugCategory category) {
        if (snapshot != null && !snapshot.isBlank()) {
            for (String pair : snapshot.split(",")) {
                String[] kv = pair.split(":");
                if (kv.length == 2 && kv[0].equals(category.name())) {
                    return Integer.parseInt(kv[1]);
                }
            }
        }
        return pointsRuleRepository.findByCategory(category)
                .map(PointsRule::getPointsPerKg)
                .orElseThrow(() -> BizException.badRequest("缺少品类积分规则: " + category));
    }

    public boolean isHighRisk(DrugCategory category) {
        return pointsRuleRepository.findByCategory(category)
                .map(PointsRule::isHighRisk)
                .orElse(false);
    }

    /** 单袋积分 = 重量kg * 每公斤积分 * 活动倍率，四舍五入，重量>0 时至少 1 分 */
    public int pointsFor(String snapshot, DrugCategory category, double weightKg, double multiplier) {
        if (weightKg <= 0) {
            return 0;
        }
        long points = Math.round(weightKg * rateFor(snapshot, category) * multiplier);
        return (int) Math.max(1, points);
    }

    public Map<DrugCategory, Integer> currentRateMap() {
        Map<DrugCategory, Integer> map = new EnumMap<>(DrugCategory.class);
        for (PointsRule rule : activeRules()) {
            map.put(rule.getCategory(), rule.getPointsPerKg());
        }
        return map;
    }
}
