# 普通业务场景 Debug 入口

## 场景定义（覆盖面广）

用户在电商订阅系统中的常见路径：

1. 创建月付订阅（带 trial）
2. trial 期间改套餐（立即生效）
3. 进入折扣期（phase 迁移）
4. 设置 CTD 后做到期变更（EOT）
5. 再次变更覆盖前一次变更
6. 时间推进到 CTD，触发计划切换
7. 进入 evergreen
8. 取消订阅

这个场景会命中：

- `catalog`（套餐/phase/规则）
- `entitlement`（订阅状态机、事件处理、计划切换、取消）
- `util/eventbus`（事件分发）
- `api`（跨模块接口）

## 直接可跑的现成测试

测试类：

- `entitlement/src/test/java/com/ning/billing/entitlement/api/user/TestUserApiDemos.java`

核心用例：

- `testDemo1`

推荐运行命令（覆盖该类里的 demo 组）：

```bash
mvn -pl entitlement -am -Dgroups=demos test
```

## 第一轮断点（建议）

按调用链从外到内打断点：

1. `TestUserApiDemos.testDemo1`
2. `entitlement/src/main/java/com/ning/billing/entitlement/api/user/DefaultEntitlementUserApi.java`
3. `entitlement/src/main/java/com/ning/billing/entitlement/api/user/SubscriptionApiService.java`
4. `entitlement/src/main/java/com/ning/billing/entitlement/engine/core/Engine.java`
5. `entitlement/src/main/java/com/ning/billing/entitlement/engine/core/DefaultApiEventProcessor.java`
6. `entitlement/src/main/java/com/ning/billing/entitlement/engine/dao/EntitlementSqlDao.java`
7. `catalog/src/main/java/com/ning/billing/catalog/`

## 第二轮（补充 invoice 侧）

如果你想把“订阅变化 -> 出账计算”一起串起来，再加这些断点：

1. `invoice/src/main/java/com/ning/billing/invoice/api/DefaultInvoiceService.java`
2. `invoice/src/main/java/com/ning/billing/invoice/model/DefaultInvoiceGenerator.java`
3. `invoice/src/main/java/com/ning/billing/invoice/model/InAdvanceBillingMode.java`
4. `api/src/main/java/com/ning/billing/invoice/api/DefaultBillingEvent.java`

## 调试技巧

- 第一次先只看一次 `testDemo1`，不要并行跑所有 test。
- 重点观察对象：
  - `SubscriptionData`（state/plan/phase）
  - `EntitlementEvent`（effectiveDate/type）
  - `clock`（时间推进触发 phase/change）
- 如果你想看“某一步前后差异”，在 `displayState(...)` 前后分别停一下。
