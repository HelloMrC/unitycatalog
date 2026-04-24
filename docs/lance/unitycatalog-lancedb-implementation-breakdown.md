# Unity Catalog Lance REST API 实施任务拆解

更新日期：2026-04-23

关联文档：

- `docs/unitycatalog-lancedb-rest-api-requirements.md`
- `docs/unitycatalog-lancedb-rest-api-technical-design.md`
- `docs/unitycatalog-lancedb-rest-api-test-strategy.md`
- `docs/unitycatalog-lancedb-phase-1-metadata-design.md`

## 1. 文档目标

本文件用于把现有需求文档和技术设计文档转成可执行的实施任务拆解，明确：

- 每个阶段的目标、边界、交付件与准出条件
- 每个阶段涉及的新增模块、修改模块与复用模块
- 阶段之间的依赖关系与推荐实施顺序

说明：

- 本文将技术设计文档中的 `Phase 0` 视为“准备阶段”
- 本文中的“第一阶段”对应技术设计文档中的 `Phase 1：Metadata Compatibility`

## 2. 阶段映射

| 实施阶段 | 对应技术设计 | 核心目标 | 主要交付件 |
|---|---|---|---|
| 准备阶段 | Phase 0 | 搭起 Lance 协议层的路由、鉴权、模型生成、元数据骨架 | 路由骨架、`lance-models`、基础表结构、授权骨架 |
| 第一阶段 | Phase 1 | 完成 namespace 和 table metadata compatibility | namespace/table metadata endpoint、legacy bridge、`storage_options` |
| 第二阶段 | Phase 2 | 完成 query / stats / DML 的最小数据面 | `LanceExecutionBackend`、Arrow IPC、query/DML endpoint |
| 第三阶段 | Phase 3 | 完成 index / version / tag / transaction / batch commit | 高级资产元数据、状态机、异步构建和事务能力 |
| 发布准备 | 测试与生产化收尾 | 完成生态兼容、观测、灰度与迁移方案 | connector 回归、运维手册、迁移与回滚方案 |

## 3. 总体拆解

## 3.1 准备阶段

目标：

- 在不破坏 UC 现有 `/catalogs`、`/schemas`、`/tables`、Iceberg、Delta 路由的前提下，引入独立的 Lance 协议面骨架
- 完成模型生成、路由挂载、权限扩展、元数据基础表与 repository 骨架

任务包：

- `W0-1` 协议契约与代码生成
  - `[新增]` `api/lance-rest-upstream.yaml`
  - `[修改]` `build.sbt`
  - 生成 `server/target/lance-models`
  - 明确阶段 0/1 只生成 server models，不生成 UC 自带 Lance SDK
- `W0-2` 服务端路由与装饰器骨架
  - `[修改]` `server/src/main/java/io/unitycatalog/server/UnityCatalogServer.java`
  - 新增 `addLanceApiServices(...)`
  - 新增 `/api/2.1/unity-catalog/lance/**` 路由前缀
  - 将 Lance 路由从现有 `AuthDecorator` 中剥离，接入 `LanceAuthDecorator`
- `W0-3` 元数据主模型与持久化骨架
  - `[新增]` `uc_lance_namespaces`
  - `[新增]` `uc_lance_assets`
  - `[新增]` `uc_lance_tables`
  - `[新增]` `LanceNamespaceDAO` / `LanceAssetDAO` / `LanceTableDAO`
  - `[新增]` `LanceNamespaceRepository` / `LanceTableRepository`
  - 明确第一阶段只落三张基础表，`uc_lance_indices` / `uc_lance_versions` / `uc_lance_tags` / `uc_lance_transactions` 延后到第三阶段
- `W0-4` 鉴权与授权骨架
  - `[修改]` `api/control.yaml`
  - 扩展 `SecurableType` / `Privilege`
  - `[新增]` `LanceResourceKeyMapper`
  - `[新增]` `uc_lance_api_keys` / `LanceApiKeyRepository`
  - `[修改]` `Repositories.java`，把 Lance repository 纳入统一装配
- `W0-5` 基础服务骨架
  - `[新增]` `server/src/main/java/io/unitycatalog/server/service/lance/LanceRestNamespaceService.java`
  - `[新增]` `LanceMetadataService.java`
  - `[新增]` `LanceIdentifierCodec.java`
  - `[新增]` `LanceStorageOptionsService.java`
  - `[新增]` `LanceLegacyBridgeService.java`
- `W0-6` 测试基座
  - `[新增]` Lance endpoint 的 unit/component test 目录
  - 建立 H2 下的 repository/component 测试
  - 建立官方 OpenAPI 快照回归检查

准出条件：

- `/api/2.1/unity-catalog/lance/**` 路由可被挂载并经过 Lance 专用装饰器链
- `lance-models` 可稳定生成
- Lance 基础 DAO / Repository 可在 H2 上完成最小 CRUD
- 不影响现有 UC `/tables`、Iceberg、Delta 路由和授权链

## 3.2 第一阶段

目标：

- 完成 Lance namespace 和 table metadata compatibility
- 对外提供可被原生 Lance namespace client 识别的 metadata-only REST 能力
- 完成 legacy Unity bridge 的识别与桥接读取

任务包：

- `W1-1` namespace metadata endpoint
  - `/v1/namespace/{id}/create`
  - `/v1/namespace/{id}/list`
  - `/v1/namespace/{id}/describe`
  - `/v1/namespace/{id}/drop`
  - `/v1/namespace/{id}/exists`
- `W1-2` namespace 下 table metadata endpoint
  - `/v1/namespace/{id}/table/list`
- `W1-3` table metadata lifecycle endpoint
  - `/v1/table/{id}/register`
  - `/v1/table/{id}/declare`
  - `/v1/table/{id}/create-empty`
  - `/v1/table/{id}/describe`
  - `/v1/table/{id}/exists`
  - `/v1/table/{id}/drop`
  - `/v1/table/{id}/deregister`
- `W1-4` deprecated alias 与协议兼容
  - `create-empty` 视为 `declare` 的兼容别名
  - 保留协议兼容，不再作为主实现路径
- `W1-5` `storage_options` 与凭证下发
  - `DescribeTable(vend_credentials=true)`
  - `DeclareTable(vend_credentials=true)`
  - `CreateEmptyTable(vend_credentials=true)`
  - 运行时凭证只动态生成，不持久化到 `uc_lance_tables`
- `W1-6` legacy bridge
  - 识别 `EXTERNAL + TEXT + properties.table_type=lance`
  - 让 legacy 表可被 `list/describe` 读到
  - 补充“可迁移”标记与桥接限制说明
- `W1-7` 第一阶段测试
  - unit：codec、service、repository、legacy bridge
  - component：H2 + repository + decorator
  - protocol：curl / Python client / Java client / Rust client metadata smoke
  - regression：UC 原有 `/tables`、Iceberg、Delta 不受影响

准出条件：

- namespace CRUD 与 table metadata 生命周期端点可用
- `DeclareTable` 与 `CreateEmptyTable` 兼容逻辑稳定
- `DescribeTable(vend_credentials=true)` 能返回可消费的 `storage_options`
- legacy Lance table 能被桥接读取
- 原生 Lance client 的 metadata smoke 用例通过

## 3.3 第二阶段

目标：

- 引入最小数据面，完成 query / stats / DML

任务包：

- `W2-1` `LanceExecutionBackend` SPI 落地
- `W2-2` `WorkerHttpLanceExecutionBackend` 实现
- `W2-3` Arrow IPC request/response converter
- `W2-4` query / count_rows / stats endpoint
- `W2-5` insert / merge_insert / update / delete endpoint
- `W2-6` explain_plan / analyze_plan
- `W2-7` Spark / Ray connector data plane 回归

准出条件：

- query / stats / DML 能通过真实后端执行
- Arrow IPC 协议路径稳定
- Spark / Ray 至少完成 P1 级别兼容验证

## 3.4 第三阶段

目标：

- 完成 Lance 高级资产与异步状态流

任务包：

- `W3-1` index metadata 与异步状态管理
- `W3-2` version / tag endpoint 全量覆盖
- `W3-3` transaction endpoint 与状态机
- `W3-4` `batch-commit` 原子提交
- `W3-5` 审计事件、失败恢复与幂等策略

准出条件：

- index / version / tag / transaction / batch commit 全量打通
- 元数据状态与物理状态一致
- 异步状态与错误恢复可观测

## 3.5 发布准备

目标：

- 把“能跑”推进到“可发布、可迁移、可运维”

任务包：

- `W4-1` Spark / Ray / DuckDB / Pandas / PyArrow 兼容矩阵
- `W4-2` 迁移工具与回滚策略
- `W4-3` 观测性与告警
- `W4-4` 生产部署模式验证：embedded / standalone / sidecar
- `W4-5` 文档完善：安装、配置、灰度、故障排查

准出条件：

- 兼容矩阵与发布门禁明确
- 有完整迁移、灰度、回滚方案
- 有独立运维手册

## 4. 模块级任务矩阵

| 模块或路径 | 动作 | 准备阶段 | 第一阶段 | 第二阶段 | 第三阶段 | 说明 |
|---|---|---|---|---|---|---|
| `api/lance-rest-upstream.yaml` | `[新增]` | 是 | 调整 | 调整 | 调整 | Lance 协议快照与 server models 输入 |
| `api/control.yaml` | `[修改]` | 是 | 可能补充 | 否 | 可能补充 | 扩展 `SecurableType` / `Privilege` |
| `build.sbt` | `[修改]` | 是 | 可能补充 | 可能补充 | 可能补充 | 新增 `lance-models` 生成链 |
| `server/src/main/java/io/unitycatalog/server/UnityCatalogServer.java` | `[修改]` | 是 | 是 | 是 | 是 | 新增 Lance 路由注册与 decorator 装配 |
| `server/src/main/java/io/unitycatalog/server/service/lance/**` | `[新增]` | 是 | 是 | 是 | 是 | Lance 协议层服务和适配逻辑 |
| `server/src/main/java/io/unitycatalog/server/service/LanceAuthDecorator.java` | `[新增]` | 是 | 稳定化 | 稳定化 | 稳定化 | Lance 专用认证入口 |
| `server/src/main/java/io/unitycatalog/server/auth/decorator/LanceResourceKeyMapper.java` | `[新增]` | 是 | 是 | 是 | 是 | Lance 资源键与授权映射 |
| `server/src/main/java/io/unitycatalog/server/persist/Repositories.java` | `[修改]` | 是 | 是 | 是 | 是 | 统一装配 Lance repository |
| `server/src/main/java/io/unitycatalog/server/persist/dao/Lance*.java` | `[新增]` | 是 | 是 | 是 | 是 | Lance DAO |
| `server/src/main/java/io/unitycatalog/server/persist/Lance*.java` | `[新增]` | 是 | 是 | 是 | 是 | Lance repository |
| `server/src/main/java/io/unitycatalog/server/exception/Lance*.java` | `[新增]` | 可选 | 是 | 是 | 是 | Lance 协议错误映射 |
| `server/src/test/**/lance/**` | `[新增]` | 是 | 是 | 是 | 是 | Lance 单元、组件、协议回归 |
| `clients/java` / `clients/python` | `[复用]` | 否 | 否 | 否 | 否 | 阶段 0/1 不并入 UC 主 SDK 生成链 |
| `docs/*.md` | `[修改/新增]` | 是 | 是 | 是 | 是 | 需求、设计、测试与运维文档同步维护 |

## 5. 推荐实施顺序

推荐顺序如下：

1. 先完成准备阶段中的 `W0-1`、`W0-2`、`W0-3`，把“协议模型、路由入口、元数据骨架”稳定下来。
2. 再进入第一阶段中的 `W1-1`、`W1-2`、`W1-3`，优先打通 namespace 与 table metadata 主链路。
3. 第一阶段稳定后，再做 `W1-5` 和 `W1-6`，把 `storage_options` 与 legacy bridge 收口。
4. 第一阶段全部通过后，再引入第二阶段的数据面 SPI，避免过早把 metadata 与执行链路耦合在一起。

## 6. 第一阶段设计入口

第一阶段详细设计见：

- `docs/unitycatalog-lancedb-phase-1-metadata-design.md`
