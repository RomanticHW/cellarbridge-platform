# 业务流程设计

## 1. 流程建模约定

本文使用接近 BPMN 的泳道和事件语义描述流程，但不依赖专用流程引擎。P1 使用领域状态机、应用服务和事件驱动编排实现；是否引入工作流引擎需要独立 ADR。

符号约定：

- 圆角节点：开始/结束事件；
- 矩形：人工或系统任务；
- 菱形：排他判断；
- 虚线：异步事件或通知；
- 补偿以显式业务命令实现，不采用分布式事务。

## 2. 客户准入流程

```mermaid
flowchart LR
    subgraph Sales[销售]
      A([开始]) --> B[创建客户草稿]
      B --> C[补充联系人/地址/付款条款/路径申请]
      C --> D[提交审核]
    end
    subgraph System[系统]
      D --> E{重复和完整性检查}
      E -->|失败| B
      E -->|通过| F[创建审核工作项]
    end
    subgraph Manager[经理/管理员]
      F --> G{决定}
      G -->|退回| H[填写修改意见]
      G -->|拒绝| I[记录拒绝]
      G -->|批准| J[确认资格与信用配置]
    end
    H -.通知.-> B
    I --> K([结束: REJECTED])
    J --> L[激活客户并发布事件]
    L --> M([结束: ACTIVE])
```

关键规则：提交人和审核人分离；客户停用立即阻止新报价，但不重写历史订单。

## 3. 报价到订单主流程

```mermaid
sequenceDiagram
    actor Sales as 销售代表
    participant UI as React 控制台
    participant Quote as 报价模块
    participant Plan as 贸易规划模块
    participant Approval as 审批策略
    actor Manager as 销售经理
    actor Buyer as 客户采购
    participant Order as 订单模块
    participant Inventory as 库存模块
    participant Fulfillment as 履约模块

    Sales->>UI: 选择客户和商品
    UI->>Quote: 保存报价草稿
    Quote->>Plan: 评估候选路径
    Plan-->>Quote: 有效/拒绝路径、评分、策略版本
    Sales->>Quote: 提交审批
    Quote->>Approval: 评估审批规则
    alt 需要审批
      Approval-->>Manager: 创建审批工作项
      Manager->>Quote: 批准/退回/拒绝
    else 无需审批
      Quote->>Quote: 自动批准
    end
    Sales->>Quote: 发送报价
    Buyer->>Quote: 接受报价（幂等）
    Quote-->>Order: QuotationAcceptedV1
    Order->>Order: 唯一 quote_id 创建订单
    Order-->>Inventory: TradeOrderCreatedV1
    Inventory->>Inventory: 原子全量预占
    alt 预占成功
      Inventory-->>Fulfillment: InventoryReservationConfirmedV1
      Fulfillment->>Fulfillment: 生成路径履约计划
    else 预占失败
      Inventory-->>Order: InventoryReservationFailedV1
      Inventory-->>Fulfillment: 不创建计划
    end
```

## 4. 贸易路径评估流程

```mermaid
flowchart TD
    A[构造评估输入快照] --> B[加载已启用路径与策略版本]
    B --> C{对每条路径执行硬约束}
    C -->|不满足| D[记录拒绝码、参数和公开解释]
    C -->|满足| E[计算成本评分]
    E --> F[计算时效评分]
    F --> G[计算供给置信度]
    G --> H[计算操作复杂度]
    H --> I[应用权重并归一化]
    D --> J{还有路径?}
    I --> J
    J -->|是| C
    J -->|否| K{至少一条有效?}
    K -->|否| L[报价不可提交，返回全部拒绝原因]
    K -->|是| M[按总分和稳定次序排序]
    M --> N[保存评估结果与策略版本]
    N --> O[返回推荐路径]
```

### 4.1 硬约束示例

| 规则 ID | 规则 | 拒绝码 |
|---|---|---|
| TR-HARD-001 | 客户未获得路径资格 | `PARTNER_NOT_ELIGIBLE` |
| TR-HARD-002 | 目的地区域不被路径服务 | `DESTINATION_NOT_SUPPORTED` |
| TR-HARD-003 | 供给类型不匹配路径 | `SUPPLY_TYPE_NOT_SUPPORTED` |
| TR-HARD-004 | 预计交付晚于客户要求 | `DELIVERY_DATE_UNACHIEVABLE` |
| TR-HARD-005 | 数量低于路径最小起订 | `MOQ_NOT_MET` |
| TR-HARD-006 | 付款条款不被路径/客户策略允许 | `PAYMENT_TERM_NOT_ALLOWED` |

### 4.2 评分公式

P1 默认权重：

```text
总分 = 成本得分 × 0.40
     + 时效得分 × 0.30
     + 供给置信度 × 0.20
     + 操作简易度 × 0.10
```

所有子分数范围为 0~100；权重之和必须为 1；评分结果使用固定精度和稳定排序。改变权重创建新策略版本，不修改历史评估。

## 5. 报价审批流程

```mermaid
flowchart TD
    A[提交报价] --> B[冻结候选版本]
    B --> C[运行审批策略]
    C --> D{有触发规则?}
    D -->|否| E[自动批准]
    D -->|是| F[生成审批任务]
    F --> G{审批决定}
    G -->|批准| H[记录决定并批准]
    G -->|退回| I[状态变 CHANGES_REQUESTED]
    G -->|拒绝| J[状态变 REJECTED]
    I --> K[销售创建新版本并重新提交]
    K --> C
    E --> L([可发送])
    H --> L
    J --> M([终止])
```

审批触发项包括：折扣超过阈值、预计毛利低于阈值、付款账期超出客户默认、选择非推荐路径、人工改价、特殊有效期。

## 6. 库存预占事务流程

```mermaid
flowchart TD
    A[接收订单预占命令] --> B{幂等记录存在?}
    B -->|是且成功| C[返回既有预占]
    B -->|是且失败终态| D[返回既有失败结果]
    B -->|否| E[按订单行稳定排序]
    E --> F[开启数据库事务]
    F --> G[查询候选批次并锁定/条件更新]
    G --> H{当前行足量?}
    H -->|否| I[抛出库存不足]
    H -->|是| J[写入分配明细]
    J --> K{还有订单行?}
    K -->|是| G
    K -->|否| L[写预占单和可靠事件]
    L --> M[提交事务]
    I --> N[回滚全部行]
    N --> O[保存可重试的失败事实/缺口摘要]
```

P1 采用全量预占；不得在失败后保留部分行的预占。

## 7. 履约计划与异常流程

```mermaid
stateDiagram-v2
    [*] --> Planned
    Planned --> Ready: 前置条件满足
    Ready --> InProgress: 领取/开始
    InProgress --> Completed: 完成验证通过
    InProgress --> Failed: 操作或适配器失败
    Ready --> Overdue: SLA 到期
    InProgress --> Overdue: SLA 到期
    Failed --> ExceptionOpen: 自动创建异常
    Overdue --> ExceptionOpen: 自动创建异常
    ExceptionOpen --> RecoveryPending: 选择恢复方案
    RecoveryPending --> Ready: 重试/重新安排
    RecoveryPending --> Cancelled: 终止履约
    Completed --> [*]
    Cancelled --> [*]
```

异常恢复动作必须具备：动作类型、参数、操作者、原因、前置状态、执行结果、重试计数和关联追踪 ID。

## 8. 应收与付款流程

```mermaid
flowchart LR
    A[订单达到应收触发点] --> B[创建应收]
    B --> C{到期日已过?}
    C -->|是且有余额| D[标记逾期]
    C -->|否| E[等待付款]
    E --> F[登记付款]
    F --> G{金额 <= 剩余?}
    G -->|否| H[拒绝并返回余额]
    G -->|是| I[写付款记录]
    I --> J{剩余为零?}
    J -->|否| K[PARTIALLY_PAID]
    J -->|是| L[PAID]
    I --> M{登记错误?}
    M -->|是| N[创建冲正记录]
    N --> E
```

## 9. 跨流程一致性边界

- 报价接受与订单创建不使用分布式事务；通过可靠事件、幂等键和重放实现最终一致；
- 订单创建与其可靠事件记录在同一本地事务；
- 库存预占的所有行在一个库存模块事务中全成或全败；
- 预占成功与履约计划创建最终一致；失败事件可重试，履约消费者幂等；
- 报表和审计投影允许短暂延迟，但必须暴露延迟指标；
- 任何流程失败都不得通过直接修改其他模块数据库恢复。
