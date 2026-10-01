# 社区药箱过期药回收称重兑换与环保交接服务

基于 **Java 17 + Spring Boot 3 + PostgreSQL** 的社区过期药品回收服务，覆盖完整链路：

```
居民预约 -> 回收员扫码登记 -> 分类称重封袋(含隐私包装现场处理) -> 高风险/隐私人工复核 -> 积分发放
        -> 暂存柜暂存(容量/超期预警) -> 环保交接(隐私留痕/上门量/双人签收/拒收) -> 批次追溯
```

预约、称重、复核、积分、暂存、交接与异常全部挂在**同一批次（RecycleBatch）**上，交接前每袋药品都有来源（预约/批次）与分类。

## 功能清单

| 模块 | 说明 |
| --- | --- |
| 预约 | 记录药品类型、数量、是否含处方药/液体/针剂/个人信息包装、社区点、活动日；预约时固化**积分规则快照** |
| 登记称重 | 回收员扫码登记生成批次；按 普通片剂/抗生素/精神类/注射器/外用药/液体药 六类称重，记录包装照片与封袋编号 |
| 隐私包装 | 药盒保留姓名/身份证号/诊断时，回收员现场标记隐私包装、引导居民**撕除/遮盖**并拍摄处理后照片；处理合格即时发积分，居民不同意则**药品可封袋、积分暂缓**，由社区管理员复核 |
| 人工复核 | 高风险品类（精神类、注射器）由复核员复核；隐私包装暂缓袋由**社区管理员**复核（合格补发积分 / 不合格重新联系居民 / 按无积分回收） |
| 积分 | 按品类每公斤积分 × 活动日倍率发放，隐私暂缓积分复核通过后补发；流水可查；兑换走悲观锁应对峰值；失败落 FAILED 兑换单 |
| 暂存柜 | 入柜容量强校验（悲观锁防超卖）、使用率预警（WARN/FULL）、超期暂存检查；待隐私复核袋禁止入柜 |
| 环保交接 | 上门量阈值校验、含高风险或超重自动要求**双人签收**、环保公司逐袋签收/拒收；**隐私处理记录随交接批次留痕，未处理合格袋禁止出库** |
| 异常 | 夹带未过期药(扣回积分)、处方信息泄露、针剂未封装、液体泄漏、称重争议(复秤调账)、兑换失败、公司拒收 |
| 看板 | 管理员查看超期药品、是否需要双人交接、是否达上门量、容量预警、待高风险/隐私复核数、待重新联系居民数、未结异常数 |
| 追溯 | 批次时间线：预约→称重→封袋→隐私处理→复核→暂存→交接→积分流水→异常，交接单含去向/重量/照片/签收人/积分结果/隐私处理结果 |

## 隐私包装处理流程

1. **现场处理**：回收员称重时对带个人信息的药盒标记 `personalInfoPackaging=true`，引导居民撕除（TEAR_OFF）或遮盖（COVERED）信息，居民同意（`residentConsent=true`）并上传**处理后照片** `treatedPhotoUrls` → 状态 `TREATED`，袋即时验收、积分正常发放。
2. **居民不同意/处理存疑**：药品仍可进入封袋，但积分**暂缓**（袋 `pointsWithheld=true`、隐私状态 `PENDING_REVIEW`），自动生成社区管理员隐私复核任务，袋**不可入柜、不可交接**。
3. **社区管理员复核**（`POST /api/privacy/reviews/{taskNo}/decide`）：
   - `APPROVE` 处理合格 → 接受回收、**补发暂缓积分**；
   - `RE_CONTACT` 处理不合格 → 社区重新联系居民，药品暂留、积分仍暂缓（可在 `/api/privacy/re-contact` 跟踪）；
   - `NO_POINTS` 无法整改 → **按无积分回收**：药品保留继续入柜/交接，积分不予发放。
4. **重新联系居民后登记处置**（`POST /api/privacy/treatments/{no}/followup`）：居民已配合处理须补拍处理后照片 → 合格补发积分；仍不配合 → 转无积分回收。
5. **交接防外流**：发起环保交接时逐袋快照隐私处理状态，`PENDING_REVIEW`/`RE_CONTACT` 的袋**禁止出库**；交接单详情与批次追溯均可查隐私处理与复核留痕。

## 本地启动

### 1. 准备 PostgreSQL

```bash
# 方式 A：使用 Docker（如有）
docker compose up -d db

# 方式 B：使用已有 PostgreSQL，手工建库
psql -U postgres -c "CREATE DATABASE medrecycle"
psql -U postgres -c "CREATE USER medrecycle WITH PASSWORD 'medrecycle'"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE medrecycle TO medrecycle"
psql -U postgres -d medrecycle -c "GRANT ALL ON SCHEMA public TO medrecycle"
```

### 2. 启动服务

```bash
# 默认连接 jdbc:postgresql://localhost:5432/medrecycle，可用环境变量覆盖
DB_URL=jdbc:postgresql://localhost:5432/medrecycle \
DB_USER=medrecycle \
DB_PASSWORD=medrecycle \
mvn spring-boot:run

# 或打包后运行
mvn package -DskipTests
java -jar target/med-recycle-1.0.0.jar
```

启动后表结构自动创建（`ddl-auto: update`），首次启动写入演示数据（站点/暂存柜/积分规则/礼品/测试账号/活动日，可用 `SEED_DEMO_DATA=false` 关闭）。

健康检查：`curl http://localhost:8080/api/health`

## 测试账号

登录：`POST /api/auth/login` `{"username":"...","password":"..."}`，返回 `token`，后续请求带请求头 `X-Auth-Token: <token>`。

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| resident1 / resident2 | resident123 | 居民（预约、查积分、兑换、查追溯） |
| recycler1 / recycler2 | recycler123 | 回收员（登记、称重、入柜、交接签收） |
| reviewer1 | reviewer123 | 复核员（高风险复核） |
| admin1 / admin2 | admin123 | 社区管理员（看板、发起交接） |

演示数据：2 个社区点、3 个暂存柜、6 条品类积分规则（精神类/注射器为高风险）、3 种礼品、1 个积分 1.5 倍的活动日；resident1 预置 100 积分。

## 验证方式（均已实际执行通过）

### 1. 集成测试（PostgreSQL 实测，10 个用例）

```bash
# 测试库：默认 jdbc:postgresql://localhost:5432/medrecycle_test，可用 TEST_DB_URL 覆盖
psql -U postgres -c "CREATE DATABASE medrecycle_test OWNER medrecycle"
mvn test
```

- `FullFlowIntegrationTest`：预约→登记→称重(含高风险)→复核→积分→暂存→双人交接→完成→追溯全链路断言
- `ExceptionFlowIntegrationTest`：夹带未过期药扣回积分并复核拒绝、称重争议复秤调账、环保公司拒收回柜并记录异常
- `ConcurrencyAndCapacityTest`：20 线程并发兑换 5 件库存不超卖（成功恰为 5、余额精确）、暂存柜容量强制与 FULL 预警
- `PrivacyFlowIntegrationTest`：隐私包装现场撕除即发积分并随交接留痕、居民拒绝积分暂缓待管理员复核、不合格重新联系居民后补发、按无积分回收保留药品不发积分（4 个用例）

本次验证执行结果：`Tests run: 10, Failures: 0, Errors: 0`（PostgreSQL 15 实测）。

### 2. 端到端脚本（HTTP 全流程，75 项断言）

```bash
# 服务运行中执行（断言基于演示数据初始状态，重复执行前请重建数据库 medrecycle）
./verify.sh
```

脚本用 curl 走通：登录→预约(活动日)→登记→称重(3 类)→高风险复核→积分→入柜→看板→上门量拦截→双人签收→交接完成→追溯→称重争议复秤→夹带未过期药扣回→公司拒收→积分兑换成功/失败，以及**隐私包装现场处理即时积分、居民拒绝暂缓入隐私复核、管理员合格补发/重新联系居民/无积分回收、隐私记录随环保交接批次留痕**，共 75 项断言。本次验证执行结果：`通过 75 项, 失败 0 项`（退出码 0，需在全新 `medrecycle` 库上运行）。

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/auth/login | 登录取 token |
| POST | /api/appointments | 居民预约（含四类标识、活动日） |
| POST | /api/batches/check-in | 扫码登记生成批次 |
| POST | /api/batches/{no}/weigh | 分类称重封袋（照片、封袋编号、争议标记、隐私包装处理标记） |
| POST | /api/batches/{no}/finish-weighing | 称重完成 |
| GET | /api/batches/{no}/trace | 批次全链路追溯（含隐私处理记录） |
| GET/POST | /api/reviews, /api/reviews/{no}/decide | 高风险复核列表与审批（复核员/管理员） |
| GET/POST | /api/privacy/reviews, /api/privacy/reviews/{no}/decide | 隐私包装复核列表与社区管理员审批（APPROVE/RE_CONTACT/NO_POINTS） |
| GET | /api/privacy/re-contact | 待社区重新联系居民的隐私处理记录 |
| POST | /api/privacy/treatments/{no}/followup | 登记重新联系居民后的处置（配合处理补发/否则无积分回收） |
| GET/POST | /api/points/balance, /api/points/exchange | 积分查询与兑换 |
| POST | /api/storage/put | 入暂存柜（容量强校验） |
| GET | /api/storage/cabinets, /api/storage/overdue, /api/storage/alerts | 柜状态/超期/容量预警 |
| POST | /api/handovers, /{no}/sign, /{no}/complete | 交接建单/签收(双人)/完成(拒收) |
| GET | /api/handovers/{no} | 交接单详情（去向/重量/照片/签收人/积分结果） |
| POST | /api/exceptions, /{no}/reweigh, /{no}/resolve | 异常上报/复秤/办结 |
| GET | /api/admin/dashboard?siteId= | 管理员看板 |

## 关键设计

- **批次状态机**：`CHECKED_IN → WEIGHING → IN_REVIEW → POINTS_GRANTED → STORED → (PARTIALLY_)HANDED_OVER → CLOSED`，由袋状态自动推导（`BatchRefresher`），预约随批次终态完结。
- **积分规则快照**：预约时固化 `TABLET:10,ANTIBIOTIC:15,...`，后续称重/复秤均按快照计价，规则调整不影响在途批次。
- **隐私包装闸门**：称重时统一评估"高风险品类"与"隐私包装"两道入库闸门（`applyIntakeGates`/`settleAfterReview`）。任一未过则袋 `PENDING_REVIEW`、积分暂缓且禁止入柜/交接；两道全解才验收。隐私复核由**社区管理员**独立于复核员处理，结论分合格补发、重新联系居民、无积分回收三种；隐私处理状态逐袋快照进环保交接明细，未处理合格袋建交接单时直接拦截，防止姓名/身份证号/诊断随药品外流。
- **并发安全**：积分兑换（账户+礼品悲观锁，固定加锁顺序）、暂存柜入柜（柜悲观锁）、活动日名额（乐观锁重试），保证兑换峰值不超卖、容量不超卖。
- **异常联动**：异常不是孤立记录——夹带未过期药自动扣回积分并退回复核；液体泄漏/针剂未封装/处方信息泄露强制人工复核；称重争议复秤后按快照多退少补；公司拒收自动回柜并留痕。
