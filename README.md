# 社区药箱过期药回收称重兑换与环保交接服务

基于 **Java 17 + Spring Boot 3 + PostgreSQL** 的社区过期药品回收服务，覆盖完整链路：

```
居民预约 -> 回收员扫码登记 -> 分类称重封袋（隐私包装现场处理） -> 高风险/隐私人工复核
        -> 积分发放（暂缓的复核后补发） -> 暂存柜暂存(容量/超期预警)
        -> 环保交接(上门量/双人签收/拒收/隐私留痕) -> 批次追溯
```

预约、称重、复核、积分、暂存、交接、隐私处理与异常全部挂在**同一批次（RecycleBatch）**上，交接前每袋药品都有来源（预约/批次）与分类。

## 功能清单

| 模块 | 说明 |
| --- | --- |
| 预约 | 记录药品类型、数量、是否含处方药/液体/针剂/个人信息包装、社区点、活动日；预约时固化**积分规则快照** |
| 登记称重 | 回收员扫码登记生成批次；按 普通片剂/抗生素/精神类/注射器/外用药/液体药 六类称重，记录包装照片与封袋编号 |
| **隐私包装处理** | 药盒带姓名、身份证号、诊断信息时，回收员现场标记并引导居民**撕除/遮盖**，拍摄**处理后照片**；居民不同意则药品可封袋但**积分暂缓**，转**社区管理员复核**：通过才补发积分；不合格则社区重新联系居民补交处理；也可决定**按无积分回收**（药品收下、积分不发）。暂缓袋禁止入柜与交接，处理记录随环保交接批次留痕，防止个人信息随药品外流 |
| 人工复核 | 高风险品类（精神类、注射器）及异常触发的袋由复核员先复核；隐私包装复核单独由社区管理员处理，两类复核都通过才发积分、才准入柜 |
| 积分 | 按品类每公斤积分 × 活动日倍率发放；隐私/风险暂缓的积分在复核闭环后补发；流水可查；兑换走悲观锁应对峰值；失败落 FAILED 兑换单 |
| 暂存柜 | 入柜容量强校验（悲观锁防超卖）、使用率预警（WARN/FULL）、超期暂存检查；隐私未闭环袋禁止入柜 |
| 环保交接 | 上门量阈值校验、含高风险或超重自动要求**双人签收**、环保公司逐袋签收/拒收；交接单附**隐私处理记录** |
| 异常 | 夹带未过期药(扣回积分)、处方信息泄露(转隐私复核暂缓积分)、针剂未封装、液体泄漏、称重争议(复秤调账)、兑换失败、公司拒收 |
| 看板 | 管理员查看超期药品、是否需要双人交接、是否达上门量、容量预警、待风险复核/待隐私复核/未结异常数 |
| 追溯 | 批次时间线：预约→称重→封袋→复核→隐私处理→暂存→交接→积分流水→异常，交接单含去向/重量/照片/签收人/积分发放结果/隐私留痕 |

## 本地启动

### 方式一：Docker 一键启动（推荐）

```bash
docker compose up -d --build
# 服务：http://localhost:8080  健康检查 /api/health
# Swagger UI：http://localhost:8080/swagger-ui.html
# OpenAPI 3.x：http://localhost:8080/v3/api-docs
```

### 方式二：本地 PostgreSQL + Maven

#### 1. 准备 PostgreSQL

```bash
# 方式 A：使用 Docker（如有）
docker compose up -d db

# 方式 B：使用已有 PostgreSQL，手工建库
psql -U postgres -c "CREATE DATABASE medrecycle"
psql -U postgres -c "CREATE USER medrecycle WITH PASSWORD 'medrecycle'"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE medrecycle TO medrecycle"
psql -U postgres -d medrecycle -c "GRANT ALL ON SCHEMA public TO medrecycle"
```

#### 2. 启动服务

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
接口文档（Swagger UI）：`http://localhost:8080/swagger-ui.html`，OpenAPI 3.x 描述：`/v3/api-docs`。

## 测试账号

登录：`POST /api/auth/login` `{"username":"...","password":"..."}`，返回 `token`，后续请求带请求头 `X-Auth-Token: <token>`。

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| resident1 / resident2 | resident123 | 居民（预约、查积分、兑换、查追溯） |
| recycler1 / recycler2 | recycler123 | 回收员（登记、称重、隐私标记/补交、入柜、交接签收） |
| reviewer1 | reviewer123 | 复核员（高风险/异常复核） |
| admin1 / admin2 | admin123 | 社区管理员（看板、发起交接、**隐私包装复核**） |

演示数据：2 个社区点、3 个暂存柜、6 条品类积分规则（精神类/注射器为高风险）、3 种礼品、1 个积分 1.5 倍的活动日；resident1 预置 100 积分。

## 验证方式（均已实际执行通过）

### 1. 集成测试（PostgreSQL 实测，13 个用例）

```bash
# 测试库：默认 jdbc:postgresql://localhost:5432/medrecycle_test，可用 TEST_DB_URL 覆盖
psql -U postgres -c "CREATE DATABASE medrecycle_test OWNER medrecycle"
mvn test
```

- `FullFlowIntegrationTest`：预约→登记→称重(含高风险)→复核→积分→暂存→双人交接→完成→追溯全链路断言
- `ExceptionFlowIntegrationTest`：夹带未过期药扣回积分并复核拒绝、称重争议复秤调账、环保公司拒收回柜并记录异常
- `ConcurrencyAndCapacityTest`：20 线程并发兑换 5 件库存不超卖（成功恰为 5、余额精确）、暂存柜容量强制与 FULL 预警
- `PrivacyPackagingIntegrationTest`（7 个用例）：同意撕除即时发分且有留痕、拒绝则暂缓且禁入柜、管理员复核通过补发、不合格 REWORK 补交再复核、按无积分回收仍可交接且永不发分、高风险+隐私双重复核都过才发分、处方信息泄露异常转隐私复核扣回后补发

本次验证执行结果：`Tests run: 13, Failures: 0, Errors: 0`（PostgreSQL 15 实测）。

### 2. 端到端脚本（HTTP 全流程，73 项断言）

```bash
# 服务运行中执行（断言基于演示数据初始状态，重复执行前请重建数据库 medrecycle）
./verify.sh
```

脚本用 curl 走通：登录→预约(活动日)→登记→称重(3 类)→高风险复核→积分→入柜→看板→上门量拦截→双人签收→交接完成→追溯→称重争议复秤→夹带未过期药扣回→公司拒收→积分兑换成功/失败，以及**隐私包装三段：同意处理发分、居民拒绝暂缓+管理员复核补发、不合格重新联系补交+按无积分回收+交接留痕**，共 73 项断言。本次验证执行结果：`通过 73 项, 失败 0 项`。

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/auth/login | 登录取 token |
| POST | /api/appointments | 居民预约（含四类标识、活动日） |
| POST | /api/batches/check-in | 扫码登记生成批次 |
| POST | /api/batches/{no}/weigh | 分类称重封袋（照片、封袋编号、争议标记、**隐私包装处理字段**） |
| POST | /api/batches/{no}/finish-weighing | 称重完成 |
| GET | /api/batches/{no}/trace | 批次全链路追溯（含 privacyRecords 隐私处理记录） |
| GET/POST | /api/reviews?kind=RISK\|PRIVACY, /api/reviews/{no}/decide | 风险复核（复核员）与隐私复核任务列表、风险审批 |
| GET | /api/privacy/records[?status=] | 隐私处理记录列表/详情 |
| POST | /api/privacy/records/{no}/rework | 复核不合格后回收员重新联系居民补交处理与新照片 |
| POST | /api/privacy/reviews/{taskNo}/decide | 社区管理员隐私复核：APPROVE 发分 / REWORK 重新联系 / NO_POINTS 无积分回收 |
| GET/POST | /api/points/balance, /api/points/exchange | 积分查询与兑换 |
| POST | /api/storage/put | 入暂存柜（容量强校验；隐私未闭环禁止入柜） |
| GET | /api/storage/cabinets, /api/storage/overdue, /api/storage/alerts | 柜状态/超期/容量预警 |
| POST | /api/handovers, /{no}/sign, /{no}/complete | 交接建单/签收(双人)/完成(拒收) |
| GET | /api/handovers/{no} | 交接单详情（去向/重量/照片/签收人/积分结果/**privacyRecords 隐私留痕**） |
| POST | /api/exceptions, /{no}/reweigh, /{no}/resolve | 异常上报/复秤/办结 |
| GET | /api/admin/dashboard?siteId= | 管理员看板（pendingReviews / pendingPrivacyReviews） |

完整字段与示例可通过 Swagger UI 在线查看：`http://localhost:8080/swagger-ui.html`。

### 隐私包装称重字段（weigh 接口 items 内）

| 字段 | 说明 |
| --- | --- |
| privacyPackaging | 药盒是否带姓名、身份证号、诊断等个人信息包装 |
| residentConsent | 居民是否同意现场处理；false 时药品封袋但积分暂缓，自动生成管理员复核任务 |
| privacyMethod | 处理方式：`PEEL_OFF`（撕除）/ `COVER`（遮盖）；同意时必填 |
| privacyGuidanceNote | 现场引导说明 |
| privacyPhotoUrls | **处理后**照片（信息已撕除/遮盖后拍摄）；同意时必填 |

## 关键设计

- **批次状态机**：`CHECKED_IN → WEIGHING → IN_REVIEW → POINTS_GRANTED → STORED → (PARTIALLY_)HANDED_OVER → CLOSED`，由袋状态自动推导（`BatchRefresher`），预约随批次终态完结。
- **积分规则快照**：预约时固化 `TABLET:10,ANTIBIOTIC:15,...`，后续称重/复秤均按快照计价，规则调整不影响在途批次。
- **隐私与风险双闸（BagPointsGate）**：每袋积分同时受「高风险/异常复核」与「隐私处理闭环」两个条件约束，任一未解除都不发放（`pointsWithheld`）；解除方幂等补发。复核结论 `NO_POINTS` 置 `pointsForfeited`，药品继续流转但积分永久不发。暂缓袋禁止入柜与进入环保交接，交接单携带隐私处理记录，防止个人信息随药品外流。
- **并发安全**：积分兑换（账户+礼品悲观锁，固定加锁顺序）、暂存柜入柜（柜悲观锁）、活动日名额（乐观锁重试），保证兑换峰值不超卖、容量不超卖。
- **异常联动**：异常不是孤立记录——夹带未过期药自动扣回积分并退回复核；处方/个人信息泄露自动暂缓积分并转社区管理员隐私复核；液体泄漏/针剂未封装强制风险复核；称重争议复秤后按快照多退少补；公司拒收自动回柜并留痕。
