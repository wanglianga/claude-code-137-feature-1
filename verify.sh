#!/usr/bin/env bash
# 端到端验证脚本：对运行中的服务走通 预约->登记->称重->复核->积分->暂存->交接->追溯 全链路
# 含隐私包装处理：现场标记/撕除遮盖拍照、居民拒绝暂缓积分、管理员复核、交接留痕
# 用法: ./verify.sh  （默认 BASE_URL=http://localhost:8080，可用 BASE_URL 环境变量覆盖）
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

check() { # $1=描述 $2=实际 $3=期望
  if [ "$2" == "$3" ]; then
    PASS=$((PASS+1)); echo "  ✔ $1"
  else
    FAIL=$((FAIL+1)); echo "  ✘ $1  期望=[$3] 实际=[$2]"
  fi
}

login() {
  curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | jq -r '.token'
}

echo "== 0. 健康检查 =="
HEALTH=$(curl -s "$BASE/api/health" | jq -r '.status')
check "服务健康" "$HEALTH" "UP"

echo "== 1. 四类角色登录 =="
T_RESIDENT=$(login resident1 resident123)
T_RESIDENT2=$(login resident2 resident123)
T_RECYCLER=$(login recycler1 recycler123)
T_RECYCLER2=$(login recycler2 recycler123)
T_REVIEWER=$(login reviewer1 reviewer123)
T_ADMIN=$(login admin1 admin123)
check "居民登录" "$([ -n "$T_RESIDENT" ] && [ "$T_RESIDENT" != null ] && echo ok)" "ok"
check "居民2登录" "$([ -n "$T_RESIDENT2" ] && [ "$T_RESIDENT2" != null ] && echo ok)" "ok"
check "回收员登录" "$([ -n "$T_RECYCLER" ] && [ "$T_RECYCLER" != null ] && echo ok)" "ok"
check "复核员登录" "$([ -n "$T_REVIEWER" ] && [ "$T_REVIEWER" != null ] && echo ok)" "ok"
check "管理员登录" "$([ -n "$T_ADMIN" ] && [ "$T_ADMIN" != null ] && echo ok)" "ok"

AUTH_R=(-H "X-Auth-Token: $T_RESIDENT")
AUTH_R2=(-H "X-Auth-Token: $T_RESIDENT2")
AUTH_W=(-H "X-Auth-Token: $T_RECYCLER")
AUTH_V=(-H "X-Auth-Token: $T_REVIEWER")
AUTH_A=(-H "X-Auth-Token: $T_ADMIN")
JSON=(-H 'Content-Type: application/json')

echo "== 2. 基础目录 =="
SITE_ID=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/sites" | jq -r '.[0].id')
ACT_ID=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/activity-days?siteId=$SITE_ID" | jq -r '.[0].id')
check "药品分类 6 类" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/categories" | jq 'length')" "6"
check "积分规则含高风险标记" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/points-rules" | jq '[.[] | select(.highRisk==true)] | length')" "2"
check "活动日倍率 1.5" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/activity-days?siteId=$SITE_ID" | jq -r '.[0].pointsMultiplier')" "1.5"

echo "== 3. 居民预约（活动日，含处方/液体/个人信息标识） =="
APPT=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"感冒药、安眠药、口服液\", \"estimatedQty\": 5,
  \"hasPrescription\": true, \"hasLiquid\": true, \"hasInjection\": true,
  \"hasPersonalInfo\": true, \"activityDayId\": $ACT_ID}")
APPT_NO=$(echo "$APPT" | jq -r '.appointmentNo')
check "预约单号生成" "$(echo "$APPT_NO" | grep -c '^AP')" "1"
check "积分规则快照已固化" "$(echo "$APPT" | jq -r '.pointsRuleSnapshot' | grep -c 'TABLET:10')" "1"
check "活动日名额 +1" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/activity-days?siteId=$SITE_ID" | jq '.[0].bookedCount')" "1"

echo "== 4. 回收员扫码登记 =="
BATCH=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" -d "{\"appointmentNo\": \"$APPT_NO\"}")
BATCH_NO=$(echo "$BATCH" | jq -r '.batchNo')
check "批次状态 CHECKED_IN" "$(echo "$BATCH" | jq -r '.status')" "CHECKED_IN"
check "批次倍率继承活动日" "$(echo "$BATCH" | jq -r '.pointsMultiplier')" "1.5"

echo "== 5. 分类称重封袋（片剂2kg + 注射器0.5kg高风险 + 液体1kg） =="
BATCH=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH_NO/weigh" -d '{
  "items": [
    {"category": "TABLET",  "weightKg": 2.0, "sealNo": "SEAL-T-1001", "photoUrls": "photo://pkg-t,photo://bag-t"},
    {"category": "SYRINGE", "weightKg": 0.5, "sealNo": "SEAL-S-1001", "photoUrls": "photo://pkg-s,photo://bag-s"},
    {"category": "LIQUID",  "weightKg": 1.0, "sealNo": "SEAL-L-1001", "photoUrls": "photo://pkg-l,photo://bag-l"}
  ]}')
STATUS=$(curl -s -X POST "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO/finish-weighing" | jq -r '.status')
check "含高风险袋 -> 批次进入复核" "$STATUS" "IN_REVIEW"
# 片剂 2*10*1.5=30，液体 1*12*1.5=18，注射器待复核
check "非高风险即时积分 30+18" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "148"

echo "== 6. 人工复核高风险注射器 =="
TASK_NO=$(curl -s "${AUTH_V[@]}" "$BASE/api/reviews?status=PENDING" | jq -r '.[0].taskNo')
curl -s -X POST "${AUTH_V[@]}" "${JSON[@]}" "$BASE/api/reviews/$TASK_NO/decide" \
  -d '{"approve": true, "note": "针剂已安全封装，核对无误"}' > /dev/null
check "复核通过发放 0.5*8*1.5=6" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "154"
check "批次积分已发放" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "POINTS_GRANTED"

echo "== 7. 入暂存柜 =="
BAG_NOS=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO/trace" | jq -r '[.bags[].bag.bagNo] | @json')
CABINET_ID=$(curl -s "${AUTH_W[@]}" "$BASE/api/storage/cabinets?siteId=$SITE_ID" | jq -r '.[0].cabinet.id')
PUT=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": $BAG_NOS}")
check "入柜 3 袋" "$(echo "$PUT" | jq '.storedBags')" "3"
check "批次已入柜" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "STORED"
check "容量预警接口可用" "$(curl -s "${AUTH_A[@]}" "$BASE/api/storage/alerts?siteId=$SITE_ID" | jq 'type')" "\"array\""

echo "== 8. 管理员看板 =="
DASH=$(curl -s "${AUTH_A[@]}" "$BASE/api/admin/dashboard?siteId=$SITE_ID")
check "看板-暂存 3 袋" "$(echo "$DASH" | jq '.pickup.storedBags')" "3"
check "看板-未达上门量" "$(echo "$DASH" | jq '.pickup.thresholdReached')" "false"
check "看板-含高风险需双人" "$(echo "$DASH" | jq '.handover.requiresDualIfHandoverNow')" "true"
check "看板-超期统计存在" "$(echo "$DASH" | jq '.overdue.count')" "0"

echo "== 9. 环保交接（上门量校验 -> 双人签收 -> 完成） =="
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\"}")
check "未达上门量拒绝建单(409)" "$CODE" "409"
ORDER=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"force\": true}")
ORDER_NO=$(echo "$ORDER" | jq -r '.orderNo')
check "强制建单成功" "$(echo "$ORDER_NO" | grep -c '^HO')" "1"
check "含高风险需双人交接" "$(echo "$ORDER" | jq -r '.requiresDual')" "true"
check "第一人签收后仍未出库" "$(curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER_NO/sign" | jq -r '.status')" "DRAFT"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER_NO/sign")
check "同一人重复签收被拒(409)" "$CODE" "409"
check "第二人签收后出库" "$(curl -s -X POST "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER_NO/sign" | jq -r '.status')" "IN_TRANSIT"
DONE=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER_NO/complete" \
  -d '{"receiverName": "绿源-张司机", "photoUrls": "photo://handover-1"}')
check "交接完成" "$(echo "$DONE" | jq -r '.status')" "COMPLETED"
check "批次已交接" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "HANDED_OVER"
DETAIL=$(curl -s "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER_NO")
check "交接单含签收人" "$(echo "$DETAIL" | jq -r '.order.receiverName')" "绿源-张司机"
check "交接单含积分发放结果" "$(echo "$DETAIL" | jq '.pointsResult | length')" "1"
check "交接积分 54 分" "$(echo "$DETAIL" | jq '.pointsResult[0].totalPoints')" "54"

echo "== 10. 批次全链路追溯 =="
TRACE=$(curl -s "${AUTH_R[@]}" "$BASE/api/batches/$BATCH_NO/trace")
check "追溯-称重记录 3 条" "$(echo "$TRACE" | jq '.weighRecords | length')" "3"
check "追溯-每袋有来源与分类" "$(echo "$TRACE" | jq '[.bags[].bag.category] | length')" "3"
check "追溯-高风险袋有复核记录" "$(echo "$TRACE" | jq '[.bags[] | select(.bag.category=="SYRINGE") | .reviewTasks[]] | length')" "1"
check "追溯-封袋编号在案" "$(echo "$TRACE" | jq -r '[.bags[].bag.sealNo] | join(",")' | grep -c 'SEAL-S-1001')" "1"

echo "== 11. 称重争议复秤 =="
APPT2=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"阿莫西林\", \"estimatedQty\": 2,
  \"hasPrescription\": true, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}")
APPT2_NO=$(echo "$APPT2" | jq -r '.appointmentNo')
BATCH2_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT2_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH2_NO/weigh" \
  -d '{"items": [{"category": "ANTIBIOTIC", "weightKg": 2.0, "dispute": true, "photoUrls": "photo://pkg-a"}]}' > /dev/null
check "争议自动积分 2*15=30" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "184"
EX_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions?status=OPEN" | jq -r '[.[] | select(.type=="WEIGHT_DISPUTE")][0].exceptionNo')
check "称重争议异常自动生成" "$(echo "$EX_NO" | grep -c '^EX')" "1"
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/exceptions/$EX_NO/reweigh" -d '{"newWeightKg": 2.6}' > /dev/null
check "复秤补差 2.6*15=39" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "193"
check "复秤后异常已办结" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions/$EX_NO" | jq -r '.status')" "RESOLVED"

echo "== 12. 夹带未过期药 -> 扣回积分并复核拒绝 =="
BAG2_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH2_NO/trace" | jq -r '.bags[0].bag.bagNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/exceptions" \
  -d "{\"batchNo\": \"$BATCH2_NO\", \"bagNo\": \"$BAG2_NO\", \"type\": \"NON_EXPIRED_DRUG\", \"description\": \"发现未过期药品\"}" > /dev/null
check "积分已扣回" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "154"
TASK2_NO=$(curl -s "${AUTH_V[@]}" "$BASE/api/reviews?status=PENDING" | jq -r '.[0].taskNo')
curl -s -X POST "${AUTH_V[@]}" "${JSON[@]}" "$BASE/api/reviews/$TASK2_NO/decide" \
  -d '{"approve": false, "note": "未过期药品退回居民"}' > /dev/null
check "批次关闭" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH2_NO" | jq -r '.status')" "CLOSED"

echo "== 13. 环保公司拒收 -> 回柜 + 异常 =="
APPT3_NO=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"软膏\", \"estimatedQty\": 1,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH3_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT3_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH3_NO/weigh" \
  -d '{"items": [{"category": "EXTERNAL", "weightKg": 1.0}]}' > /dev/null
BAG3_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH3_NO/trace" | jq -r '.bags[0].bag.bagNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$BAG3_NO\"]}" > /dev/null
ORDER3_NO=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"force\": true}" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER3_NO/sign" > /dev/null
REJ=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER3_NO/complete" \
  -d "{\"receiverName\": \"绿源-张司机\", \"items\": [{\"bagNo\": \"$BAG3_NO\", \"accepted\": false, \"rejectReason\": \"管状包装不在接收范围\"}]}")
check "部分拒收状态" "$(echo "$REJ" | jq -r '.status')" "PARTIAL_REJECTED"
check "拒收异常已记录" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions" | jq '[.[] | select(.type=="COMPANY_REJECT")] | length' | grep -c '^[1-9]')" "1"
check "拒收袋退回暂存" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH3_NO/trace" | jq -r '.bags[0].bag.status')" "STORED"

echo "== 14. 积分兑换（成功 + 余额不足失败） =="
GIFT_OK=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/gifts" | jq -r '[.[] | select(.costPoints<=154)][0].id')
EX1=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/points/exchange" -d "{\"giftId\": $GIFT_OK}")
check "兑换成功" "$(echo "$EX1" | jq -r '.status')" "SUCCESS"
# 余额 154-120=34，兑换 500 分体检券应失败
GIFT_EXPENSIVE=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/gifts" | jq -r '[.[] | select(.costPoints==500)][0].id')
CODE=$(curl -s -o /tmp/ex_fail.json -w '%{http_code}' -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/points/exchange" -d "{\"giftId\": $GIFT_EXPENSIVE}")
check "余额不足兑换失败(409)" "$CODE" "409"
check "失败单已记录" "$(jq -r '.status' /tmp/ex_fail.json)" "FAILED"

echo "== 15. 隐私包装：同意处理正常发积分 =="
APPT_P1=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"带姓名标签的降压药\", \"estimatedQty\": 2,
  \"hasPrescription\": true, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": true}")
APPT_P1_NO=$(echo "$APPT_P1" | jq -r '.appointmentNo')
BATCH_P1_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT_P1_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH_P1_NO/weigh" -d '{
  "items": [{
    "category": "TABLET", "weightKg": 1.0, "sealNo": "SEAL-PRV-001",
    "privacyPackaging": true, "residentConsent": true, "privacyMethod": "PEEL_OFF",
    "privacyGuidanceNote": "现场引导居民撕除药盒姓名、身份证号与诊断标签",
    "privacyPhotoUrls": "photo://privacy-processed-1"}]}' > /dev/null
check "同意撕除后积分即时发放 1*10=10" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "10"
TRACE_P1=$(curl -s "${AUTH_R2[@]}" "$BASE/api/batches/$BATCH_P1_NO/trace")
check "追溯含隐私处理记录" "$(echo "$TRACE_P1" | jq '.privacyRecords | length')" "1"
check "隐私记录状态 PROCESSED" "$(echo "$TRACE_P1" | jq -r '.privacyRecords[0].status')" "PROCESSED"
check "处理方式为撕除" "$(echo "$TRACE_P1" | jq -r '.privacyRecords[0].method')" "PEEL_OFF"

echo "== 16. 隐私包装：居民拒绝 -> 积分暂缓 -> 管理员复核通过补发 =="
APPT_P2_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"带身份证号的药盒\", \"estimatedQty\": 1,
  \"hasPrescription\": true, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": true}" | jq -r '.appointmentNo')
BATCH_P2_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT_P2_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH_P2_NO/weigh" -d '{
  "items": [{
    "category": "TABLET", "weightKg": 1.0, "sealNo": "SEAL-PRV-002",
    "privacyPackaging": true, "residentConsent": false}]}' > /dev/null
BAG_P2_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_P2_NO/trace" | jq -r '.bags[0].bag.bagNo')
check "拒绝处理后积分暂缓(余额仍10)" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "10"
check "暂缓袋状态 PENDING_REVIEW" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_P2_NO/trace" | jq -r '.bags[0].bag.status')" "PENDING_REVIEW"
check "隐私记录状态 PENDING_REVIEW" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_P2_NO/trace" | jq -r '.privacyRecords[0].status')" "PENDING_REVIEW"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$BAG_P2_NO\"]}")
check "暂缓袋禁止入柜(409)" "$CODE" "409"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH_V[@]}" "$BASE/api/reviews?kind=PRIVACY&status=PENDING")
check "复核员无权查看隐私复核(403)" "$CODE" "403"
check "看板出现待隐私复核" "$(curl -s "${AUTH_A[@]}" "$BASE/api/admin/dashboard?siteId=$SITE_ID" | jq '.pendingPrivacyReviews')" "1"
PTASK=$(curl -s "${AUTH_A[@]}" "$BASE/api/reviews?kind=PRIVACY&status=PENDING" \
  | jq -r --arg b "$BAG_P2_NO" '[.[] | select(.bagNo==$b)][0].taskNo')
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/privacy/reviews/$PTASK/decide" \
  -d '{"decision": "APPROVE", "note": "复核照片，个人信息已遮盖，接受回收"}' > /dev/null
check "复核通过补发积分 20" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "20"
check "隐私记录状态 APPROVED" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_P2_NO/trace" | jq -r '.privacyRecords[0].status')" "APPROVED"

echo "== 17. 隐私包装：复核不合格重新联系 -> 补交后按无积分回收 -> 交接留痕 =="
APPT_P3_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"带诊断信息的安定药盒\", \"estimatedQty\": 1,
  \"hasPrescription\": true, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": true}" | jq -r '.appointmentNo')
BATCH_P3_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT_P3_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH_P3_NO/weigh" -d '{
  "items": [{
    "category": "TABLET", "weightKg": 1.0, "sealNo": "SEAL-PRV-003",
    "privacyPackaging": true, "residentConsent": false,
    "privacyPhotoUrls": "photo://privacy-unclear"}]}' > /dev/null
BAG_P3_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_P3_NO/trace" | jq -r '.bags[0].bag.bagNo')
REC_P3_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_P3_NO/trace" | jq -r '.privacyRecords[0].recordNo')
PTASK1=$(curl -s "${AUTH_A[@]}" "$BASE/api/reviews?kind=PRIVACY&status=PENDING" \
  | jq -r --arg b "$BAG_P3_NO" '[.[] | select(.bagNo==$b)][0].taskNo')
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/privacy/reviews/$PTASK1/decide" \
  -d '{"decision": "REWORK", "note": "身份证号仍可见，处理不合格，重新联系居民"}' > /dev/null
check "判定不合格状态 REWORK" "$(curl -s "${AUTH_W[@]}" "$BASE/api/privacy/records/$REC_P3_NO" | jq -r '.status')" "REWORK"
check "不合格期间积分仍暂缓(20)" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "20"
# 回收员重新联系居民后补交处理，居民仍拒绝，再次进入管理员复核
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/privacy/records/$REC_P3_NO/rework" \
  -d '{"privacyPackaging": true, "residentConsent": false}' > /dev/null
check "补交后重新进入待复核" "$(curl -s "${AUTH_W[@]}" "$BASE/api/privacy/records/$REC_P3_NO" | jq -r '.status')" "PENDING_REVIEW"
PTASK2=$(curl -s "${AUTH_A[@]}" "$BASE/api/reviews?kind=PRIVACY&status=PENDING" \
  | jq -r --arg b "$BAG_P3_NO" '[.[] | select(.bagNo==$b)][0].taskNo')
check "重新生成复核任务" "$([ "$PTASK2" != "$PTASK1" ] && [ "$PTASK2" != null ] && echo ok)" "ok"
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/privacy/reviews/$PTASK2/decide" \
  -d '{"decision": "NO_POINTS", "note": "居民坚持不处理，药品按无积分回收"}' > /dev/null
check "无积分回收不发分(余额仍20)" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "20"
check "无积分袋可入柜" "$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$BAG_P3_NO\"]}" | jq '.storedBags')" "1"
# 发起交接（含此前拒收回柜的袋与无积分袋），隐私处理记录随批次进入交接单
ORDER_P_NO=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"force\": true}" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER_P_NO/sign" > /dev/null
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER_P_NO/complete" \
  -d '{"receiverName": "绿源-李司机", "photoUrls": "photo://handover-privacy"}' > /dev/null
check "无积分袋已交接" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_P3_NO/trace" | jq -r '.bags[0].bag.status')" "HANDED_OVER"
check "交接单含隐私处理留痕" "$(curl -s "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER_P_NO" | jq '[.privacyRecords[] | select(.bagNo=="'"$BAG_P3_NO"'")] | length')" "1"
check "交接单隐私结论 NO_POINTS" "$(curl -s "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER_P_NO" | jq -r '.privacyRecords[0].status')" "NO_POINTS"

echo ""
echo "==================================="
echo "验证完成: 通过 $PASS 项, 失败 $FAIL 项"
echo "==================================="
[ "$FAIL" -eq 0 ]
