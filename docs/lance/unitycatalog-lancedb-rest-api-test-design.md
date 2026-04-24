# Unity Catalog LanceDB REST API 测试设计

更新日期：2026-04-23

关联文档：

- `docs/unitycatalog-lancedb-rest-api-requirements.md`
- `docs/unitycatalog-lancedb-rest-api-technical-design.md`
- `docs/unitycatalog-lancedb-rest-api-test-strategy.md`

## 1. 文档目的

本文档用于把测试策略进一步细化为可执行的测试设计，明确：

- 需求、设计与测试套件之间的追踪关系
- 各测试主题的目标、前置条件、关键场景与主要断言
- 覆盖原生 Lance client、Spark、Ray、DuckDB、Pandas / PyArrow 的具体验证方式
- 覆盖认证、授权、审计、凭证、legacy bridge、Arrow IPC、异步状态等高风险链路

## 2. 测试对象

本轮测试对象包括：

- 路由与服务注册：
  - `UnityCatalogServer.addLanceApiServices(...)`
  - Lance 路由挂载与 decorator 链
- 协议适配层：
  - `LanceRestNamespaceService`
  - `LanceIdentifierCodec`
  - `LanceAuthDecorator`
  - `LanceStorageOptionsService`
  - Arrow request / response adapters
- 元数据层：
  - `LanceMetadataService`
  - `uc_lance_namespaces`
  - `uc_lance_assets`
  - `uc_lance_tables`
  - `uc_lance_api_keys`
  - `uc_lance_indices`
  - `uc_lance_versions`
  - `uc_lance_tags`
  - `uc_lance_transactions`
- 授权模型：
  - `SecurableType`
  - `Privileges`
  - `KeyMapper`
  - `LanceResourceKeyMapper`
- API key 映射：
  - `LanceApiKeyRepository`
- 执行后端：
  - `LanceExecutionBackend`
  - `WorkerHttpLanceExecutionBackend`
- 兼容桥接：
  - legacy Unity table 识别
  - migration/import 逻辑

## 3. 设计假设

1. 第一阶段优先保证 metadata compatibility，数据面和高级资产能力按阶段渐进补齐。
2. Lance 协议模型以 `api/lance-rest-upstream.yaml` 的快照为准，路由与服务实现仍为手写。
3. 执行后端首选 worker / sidecar 方式，因此测试需要覆盖 metadata-only 与 metadata + execution 两类链路。
4. 官方原生客户端与连接器是兼容验收主体，不能用 UC 内部测试代理代替。
5. 上游基线固定为：
   - `lancedb-docs/docs/api-reference/rest/openapi.yml`：`6c0ccc001e6b`
   - `lancedb`：`a92ae0ded522`
6. OpenAPI 契约优先，客户端实现用于行为校验；当便利方法缺失时，由 raw HTTP 协议测试补齐。

## 4. 用例编号规则

建议统一使用以下编号：

- `TD-ARCH-*`：架构、路由、装饰器、注册链路
- `TD-ID-*`：identifier、delimiter、namespace path
- `TD-AUTH-*`：认证、上下文、授权、审计
- `TD-META-*`：元数据模型与持久化
- `TD-NS-*`：namespace 能力
- `TD-TBL-*`：table 生命周期与 legacy bridge
- `TD-SCHEMA-*`：schema 与列演进
- `TD-DATA-*`：query、stats、Arrow IPC、DML
- `TD-ADV-*`：index、version、tag、transaction、batch commit
- `TD-CRED-*`：`storage_options` 与 credential vending
- `TD-CLI-*`：Python / Java / Rust 原生客户端
- `TD-SPARK-*`：Spark connector
- `TD-RAY-*`：Ray connector
- `TD-LOCAL-*`：DuckDB、Pandas、PyArrow
- `TD-REG-*`：UC 原生能力回归
- `TD-NFR-*`：性能、并发、安全、弹性

## 5. 需求与测试套件追踪矩阵

| 需求/设计点 | 关键验证内容 | 对应测试套件 |
|---|---|---|
| R1 协议兼容 | Lance 路由、header、delimiter、Arrow IPC、错误语义 | `TD-ARCH`、`TD-ID`、`TD-DATA`、`TD-CLI`、`TD-SPARK`、`TD-RAY` |
| R2 任意深度 namespace | 多层路径、root、递归 list/drop、父子关系 | `TD-ID`、`TD-META`、`TD-NS`、`TD-AUTH` |
| R3 全量 Lance 资产治理 | asset type、index/version/tag/transaction/batch commit | `TD-META`、`TD-ADV` |
| R4 保持 UC 统一治理能力 | auth、RBAC、审计、凭证、外部位置策略 | `TD-AUTH`、`TD-CRED`、`TD-REG`、`TD-NFR` |
| R5 上层使用者尽量无感迁移 | 原生 client、Spark、Ray、DuckDB/Pandas | `TD-CLI`、`TD-SPARK`、`TD-RAY`、`TD-LOCAL` |
| 设计 4 层架构 | metadata-only 与 execution path 分离 | `TD-ARCH`、`TD-DATA`、`TD-ADV`、`TD-NFR` |
| 设计 6.x 元数据模型 | namespace / asset / detail table 一致性 | `TD-META` |
| 设计 7.x 授权模型 | 新资源类型、新权限、层级继承、KeyMapper | `TD-AUTH` |
| 设计 8.2 legacy bridge | 识别、只读桥接、迁移导入 | `TD-TBL` |
| 设计 8.3 凭证与 `storage_options` | vend credentials、对象存储访问 | `TD-CRED`、`TD-LOCAL` |
| 设计 8.4 执行后端 SPI | query / stats / DML / async 状态 | `TD-DATA`、`TD-ADV`、`TD-NFR` |

## 6. 共享前置条件

### 6.1 环境前置

- UC 服务可按 embedded、standalone、sidecar 三种拓扑启动
- Metadata DB 至少支持 H2 和 PostgreSQL 两种测试环境
- 至少准备 local FS 和 S3 兼容对象存储环境
- 可切换三类认证方式：
  - UC 内部 Bearer
  - 外部 Bearer + token exchange
  - `x-api-key`

### 6.2 数据前置

需要预置如下基础数据：

- Namespace：
  - `root`
  - `prod`
  - `prod/team_a`
  - `prod/team_a/ml`
  - `prod/team_a/ml/embeddings`
- Table：
  - 空表
  - 向量表
  - 文本检索表
  - 具备 schema 演进历史的表
  - 具备 version/tag/index/transaction 历史的表
- 用户：
  - metastore admin
  - namespace owner
  - table maintainer
  - read-only user
  - service principal

### 6.3 分阶段 DDL 前置

测试执行必须遵循当前设计中的 DDL 时序：

- Phase 0 / Phase 1 预期存在：
  - `uc_lance_namespaces`
  - `uc_lance_assets`
  - `uc_lance_tables`
  - `uc_lance_api_keys`
- Phase 3 才预期存在：
  - `uc_lance_indices`
  - `uc_lance_versions`
  - `uc_lance_tags`
  - `uc_lance_transactions`

因此：

- Phase 0 / Phase 1 的 `TD-META`、`TD-AUTH`、`TD-CRED` 不应把高级资产表缺失判定为失败
- Phase 3 的 `TD-ADV` 必须补齐对应 DDL、DAO 和数据一致性验证

## 7. 测试套件设计

### 7.1 `TD-ARCH` 路由与装饰器链

| 项目 | 设计 |
|---|---|
| 目标 | 验证 Lance 路由被正确挂载，且 `LanceAuthDecorator`、`UnityAccessDecorator`、service 入口顺序正确 |
| 前置条件 | UC 启动时启用 Lance 路由；保留现有 UC `/tables`、Iceberg、Delta 路由 |
| 关键场景 | `TD-ARCH-001` 路由挂载成功；`TD-ARCH-002` Lance 路由走 `LanceAuthDecorator`；`TD-ARCH-003` 非 Lance 路由不误用 `LanceAuthDecorator`；`TD-ARCH-004` metadata-only 请求不访问执行后端；`TD-ARCH-005` 数据面请求进入 `LanceExecutionBackend`；`TD-ARCH-006` Phase 1 namespace endpoint 的 HTTP Method 与 OpenAPI 一致，覆盖 `GET /v1/namespace/{id}/list`、`POST /v1/namespace/{id}/create|describe|drop|exists`、`GET /v1/namespace/{id}/table/list` |
| 主要断言 | 路由前缀正确；decorator 生效顺序正确；请求上下文被传递到 service；metadata-only 与 execution path 分离；endpoint 方法签名以 OpenAPI 契约为准 |
| 自动化级别 | PR 必跑 |

### 7.2 `TD-ID` Identifier 与 delimiter 编解码

| 项目 | 设计 |
|---|---|
| 目标 | 验证 `LanceIdentifierCodec` 能正确处理任意深度路径、默认 `$` 与自定义 `delimiter` |
| 前置条件 | 启用 namespace path 相关接口 |
| 关键场景 | `TD-ID-001` list-style 转 string-style；`TD-ID-002` string-style 转 path segments；`TD-ID-003` root namespace 编码；`TD-ID-004` 自定义 delimiter；`TD-ID-005` 非法 path/空 segment/保留字符；`TD-ID-006` table id 与 namespace id 区分；`TD-ID-007` 深层路径 round-trip 不丢失 |
| 主要断言 | 转换可逆；路径深度正确；非法输入返回稳定错误；不会回退到三层拼接模型 |
| 自动化级别 | PR 必跑 |

### 7.3 `TD-AUTH` 认证、上下文、授权与审计

| 项目 | 设计 |
|---|---|
| 目标 | 验证 Lance 协议面没有绕过 UC 治理能力 |
| 前置条件 | 已配置 RBAC、审计与 token exchange |
| 关键场景 | `TD-AUTH-001` UC 内部 Bearer 直通；`TD-AUTH-002` 外部 Bearer token exchange 成功；`TD-AUTH-003` 错误 issuer/audience 被拒绝，且校验参数直接复用 `server.allowed-issuers`、`server.audiences`；`TD-AUTH-004` `LanceAuthDecorator` 不通过内部 HTTP 回环调用 `/tokens`，而是直接复用内部 helper/service；`TD-AUTH-005` `x-api-key` 通过 `LanceApiKeyRepository` / `uc_lance_api_keys` 映射 principal；`TD-AUTH-006` API key 只保存哈希，不保存明文；`TD-AUTH-007` context headers 入上下文并进入审计；`TD-AUTH-008` `CREATE_NAMESPACE`/`USE_NAMESPACE`/`READ_METADATA` 等权限控制；`TD-AUTH-009` `KeyMapper` 保持统一入口并委托 `LanceResourceKeyMapper`；`TD-AUTH-010` 任意深度 namespace 的父子继承通过授权图关系表达，而不是在 resource map 中展开所有父 namespace；`TD-AUTH-011` 越权访问被拦截；`TD-AUTH-012` 审计日志记录主体、资源、操作、前后状态 |
| 主要断言 | 主体映射正确；权限判定准确；审计字段完整；issuer/audience 与服务配置一致；API key 哈希化落库；KeyMapper 委托关系正确；Lance 路由与 UC 原有授权体系一致 |
| 自动化级别 | PR 必跑，发布前全量 |

### 7.4 `TD-META` 元数据模型与持久化

| 项目 | 设计 |
|---|---|
| 目标 | 验证 Lance 专用元数据模型能够正确承载 namespace 与多资产对象 |
| 前置条件 | Lance DDL 已执行，repository/DAO 已接入 |
| 关键场景 | `TD-META-001` `uc_lance_namespaces` 父子关系与唯一约束；`TD-META-002` `(root_scope_id, path_key)` 唯一；`TD-META-003` `path_key` 使用 canonical path，不保存 delimiter 语义；`TD-META-004` `root_scope_id` 在 v1 固定取 UC `metastore_id`；`TD-META-005` 资产主表与 detail 表一对一/一对多关系；`TD-META-006` `TABLE/INDEX/VERSION/TAG/TRANSACTION` 类型持久化；`TD-META-007` `arrow_schema_json` 原样保存；`TD-META-008` `storage_options_template_json` 只保存非敏感模板；`TD-META-009` 临时凭证、STS token、过期时间等不落库；`TD-META-010` properties 复用 `uc_properties`；`TD-META-011` Phase 0 / Phase 1 只要求 `uc_lance_namespaces`、`uc_lance_assets`、`uc_lance_tables`、`uc_lance_api_keys`；`TD-META-012` Phase 3 才验证 `uc_lance_indices`、`uc_lance_versions`、`uc_lance_tags`、`uc_lance_transactions` DDL；`TD-META-013` 删除/迁移时引用完整性；`TD-META-014` 异步状态字段更新一致 |
| 主要断言 | 关系模型不依赖 UC 三层表结构；`path_key` 与 delimiter 解耦；`root_scope_id` 与 metastore 锚点一致；模板配置与临时凭证分离；高级资产表的验收按阶段推进；状态变更可审计、可查询 |
| 自动化级别 | PR 必跑 |

### 7.5 `TD-NS` Namespace 能力

| 项目 | 设计 |
|---|---|
| 目标 | 验证 namespace 的 create/list/describe/exists/drop 在任意深度下都正确 |
| 前置条件 | 已完成 identifier、repository 与 auth 基础能力 |
| 关键场景 | `TD-NS-001` 创建单层 namespace；`TD-NS-002` 创建深层 namespace；`TD-NS-003` 列举子 namespace；`TD-NS-004` root namespace 语义；`TD-NS-005` exists；`TD-NS-006` `RESTRICT` 删除；`TD-NS-007` `CASCADE` 或递归删除策略；`TD-NS-008` namespace properties 保存与读取；`TD-NS-009` 同级重名冲突；`TD-NS-010` 跨租户上下文隔离 |
| 主要断言 | 多层路径语义正确；递归列举稳定；删除策略符合设计；错误码可被客户端识别 |
| 自动化级别 | PR 必跑，原生客户端回归必跑 |

### 7.6 `TD-TBL` Table 生命周期与 legacy bridge

| 项目 | 设计 |
|---|---|
| 目标 | 验证 table 生命周期接口、legacy bridge 识别与迁移链路 |
| 前置条件 | namespace 已存在；legacy 样例表已预置 |
| 关键场景 | `TD-TBL-001` list tables；`TD-TBL-002` register table；`TD-TBL-003` `declare table`；`TD-TBL-004` create empty table；`TD-TBL-005` create table；`TD-TBL-006` describe table；`TD-TBL-007` rename table；`TD-TBL-008` restore table；`TD-TBL-009` drop table；`TD-TBL-010` deregister table；`TD-TBL-011` table exists；`TD-TBL-012` legacy `EXTERNAL + table_type=lance + TEXT` 识别；`TD-TBL-013` legacy 表只读桥接；`TD-TBL-014` 迁移导入后新旧记录一致；`TD-TBL-015` 迁移后官方客户端可继续读表 |
| 主要断言 | `declare` 与 metadata-only 路径可用；legacy bridge 不影响新主模型；迁移具备可核对性与回归能力 |
| 自动化级别 | PR 必跑，发布前全量 |

### 7.7 `TD-SCHEMA` Schema 与列演进

| 项目 | 设计 |
|---|---|
| 目标 | 验证 schema metadata update、add/alter/drop columns 与 Arrow schema JSON 保真 |
| 前置条件 | 已有具备演进能力的测试表 |
| 关键场景 | `TD-SCHEMA-001` 更新 schema metadata；`TD-SCHEMA-002` add columns；`TD-SCHEMA-003` alter columns；`TD-SCHEMA-004` drop columns；`TD-SCHEMA-005` Arrow schema JSON 原样存储与读取；`TD-SCHEMA-006` 与 UC 原列模型不一致时保持 Lance schema 为准；`TD-SCHEMA-007` schema 演进后原生客户端仍可读 |
| 主要断言 | 演进前后 schema 一致；不会被压缩丢失 Lance 原生信息；错误演进能返回明确失败语义 |
| 自动化级别 | Phase 1 起必跑 |

### 7.8 `TD-DATA` Query、stats、Arrow IPC 与 DML

| 项目 | 设计 |
|---|---|
| 目标 | 验证执行后端 SPI、Arrow IPC、query/stats/count 与数据修改能力 |
| 前置条件 | worker/sidecar 可用；对象存储配置正确 |
| 关键场景 | `TD-DATA-001` `/v1/table/{id}/query` 返回 Arrow IPC stream；`TD-DATA-002` Arrow IPC file 模式；`TD-DATA-003` `/v1/table/{id}/stats`；`TD-DATA-004` count rows；`TD-DATA-005` insert；`TD-DATA-006` merge insert，覆盖 `whenMatchedUpdateAllFilt`、`whenNotMatchedBySourceDelete`、`whenNotMatchedBySourceDeleteFilt`、`timeout`、`useIndex`；`TD-DATA-007` update；`TD-DATA-008` delete；`TD-DATA-009` explain；`TD-DATA-010` analyze；`TD-DATA-011` 向量查询参数，覆盖 `vector`、`vectorColumn`、`distanceType`、`k`、`offset`、`filter`、`prefilter`、`ef`、`nprobes`、`lowerBound`、`upperBound`、`refineFactor`、`fastSearch`、`bypassVectorIndex`；`TD-DATA-012` full-text 查询参数，覆盖 `fullTextQuery`、`columns` 与组合过滤；`TD-DATA-013` 按 `version` 查询；`TD-DATA-014` `withRowId` 输出与结果形状校验；`TD-DATA-015` worker 超时/异常；`TD-DATA-016` metadata 成功但执行失败时错误回滚策略 |
| 主要断言 | 数据面请求进入执行后端；二进制响应可被客户端消费；查询结果与 stats 一致；异常路径错误语义稳定 |
| 自动化级别 | Nightly 必跑，发布前全量 |

### 7.9 `TD-ADV` Index、Version、Tag、Transaction 与 Batch Commit

| 项目 | 设计 |
|---|---|
| 目标 | 验证高级资产端点、异步状态流和治理映射 |
| 前置条件 | 执行后端支持对应命令；异步任务轮询能力可用 |
| 关键场景 | `TD-ADV-001` create index；`TD-ADV-002` create scalar index；`TD-ADV-003` list index；`TD-ADV-004` describe index stats；`TD-ADV-005` drop index；`TD-ADV-006` index `QUEUED/BUILDING/READY/FAILED/CANCELED` 状态流，覆盖 `QUEUED -> BUILDING` 超时处理、`FAILED` 状态下 `failure_reason` 字段、主动取消进入 `CANCELED`；`TD-ADV-007` list versions；`TD-ADV-008` create version；`TD-ADV-009` describe version；`TD-ADV-010` delete version；`TD-ADV-011` batch create versions；`TD-ADV-012` list/create/update/delete tag；`TD-ADV-013` describe transaction；`TD-ADV-014` alter transaction；`TD-ADV-015` `/v1/table/batch-commit`；`TD-ADV-016` batch commit 幂等键；`TD-ADV-017` 原子性与失败回滚；`TD-ADV-018` 审计记录状态前后值 |
| 主要断言 | 高级资产既可通过协议访问，也能作为治理对象被授权和审计；异步状态与物理状态一致；超时、失败原因、主动取消等状态细节可观测；批处理操作不与版本批量创建混淆 |
| 自动化级别 | Phase 3 起必跑 |

### 7.10 `TD-CRED` `storage_options` 与 credential vending

| 项目 | 设计 |
|---|---|
| 目标 | 验证 `LanceStorageOptionsService` 能把 UC 凭证能力正确转换给 Lance 客户端或执行后端 |
| 前置条件 | 已配置 `StorageCredentialVendor`；对象存储测试环境可用 |
| 关键场景 | `TD-CRED-001` `DescribeTable(vend_credentials=true)`；`TD-CRED-002` DeclareTable 透传存储参数；`TD-CRED-003` query/insert 透传 `storage_options`；`TD-CRED-004` `storage_options_template_json` 与请求时动态凭证合并；`TD-CRED-005` 模板字段仅包含 endpoint、region、path-style、provider 等非敏感配置；`TD-CRED-006` 凭证过期；`TD-CRED-007` 未授权对象存储访问被拒绝；`TD-CRED-008` 返回字段与客户端预期键名兼容；`TD-CRED-009` 动态 `storage_options` 不被持久化回模板字段 |
| 主要断言 | 凭证最小授权；时效正确；模板配置与临时凭证职责分离；对象存储可以按返回参数成功访问；错误配置时能明确失败 |
| 自动化级别 | Nightly 必跑 |

### 7.11 `TD-CLI` 原生 Lance Client 兼容

| 项目 | 设计 |
|---|---|
| 目标 | 验证 Python、Java、Rust 原生 namespace client 只调整 endpoint / auth / context 即可接入 |
| 前置条件 | 官方客户端版本固定到兼容矩阵；测试环境可访问 UC Lance endpoint |
| 关键场景 | `TD-CLI-001` Python connect namespace；`TD-CLI-002` Java builder 接入；`TD-CLI-003` Rust namespace client 接入；`TD-CLI-004` namespace create/list/describe/drop；`TD-CLI-005` table declare/list/describe/deregister；`TD-CLI-006` query 或 metadata 读取；`TD-CLI-007` 错误 token / 无权限；`TD-CLI-008` 自定义 delimiter；`TD-CLI-009` context header 透传 |
| 主要断言 | 客户端无需改业务层资源命名；返回语义与客户端解析期望一致；P0 客户端可作为发布门禁 |
| 自动化级别 | Python PR/Nightly，Java/Rust Nightly/发布前 |

### 7.12 `TD-SPARK` Spark connector 兼容

| 项目 | 设计 |
|---|---|
| 目标 | 基于官方 `org.lance.spark.LanceNamespaceSparkCatalog` 验证 Spark REST 接入兼容性 |
| 前置条件 | 已安装官方 Spark 连接器；配置 `impl=rest`、`uri`、认证头 |
| 关键场景 | `TD-SPARK-001` `CREATE NAMESPACE`；`TD-SPARK-002` `SHOW NAMESPACES`；`TD-SPARK-003` `DESCRIBE NAMESPACE`；`TD-SPARK-004` `DROP NAMESPACE`；`TD-SPARK-005` `CREATE TABLE`；`TD-SPARK-006` `SHOW TABLES`；`TD-SPARK-007` `DESCRIBE TABLE`；`TD-SPARK-008` `INSERT INTO`；`TD-SPARK-009` `SELECT`；`TD-SPARK-010` `UPDATE`；`TD-SPARK-011` `DELETE`；`TD-SPARK-012` `CREATE INDEX`；`TD-SPARK-013` `SHOW INDEXES`；`TD-SPARK-014` 默认三层 namespace；`TD-SPARK-015` `single_level_ns` 场景；`TD-SPARK-016` `parent + parent_delimiter` 场景；`TD-SPARK-017` namespace list 异常时不误触发降级；`TD-SPARK-018` header 透传与错误语义 |
| 主要断言 | 使用官方配置方式即可接入；namespace 语义稳定；不会因多层路径问题意外触发 Spark 降级行为 |
| 自动化级别 | Nightly 必跑，发布前全量 |

### 7.13 `TD-RAY` Ray connector 兼容

| 项目 | 设计 |
|---|---|
| 目标 | 按 namespace-backed workflow 验证 Ray 读写与演进链路 |
| 前置条件 | 已固定 Ray 与相关 Lance 版本 |
| 关键场景 | `TD-RAY-001` `read_lance`；`TD-RAY-002` `write_lance` create；`TD-RAY-003` `write_lance` append/overwrite；`TD-RAY-004` `add_columns`；`TD-RAY-005` filter pushdown；`TD-RAY-006` columns pushdown；`TD-RAY-007` 多 segment `table_id`；`TD-RAY-008` 附带 `storage_options` 访问对象存储；`TD-RAY-009` 分布式并发参数稳定性；`TD-RAY-010` 若版本支持则增加 index/compaction smoke test |
| 主要断言 | Ray 能以 namespace 对象为入口完成读写；路径映射和存储配置正确；并发下行为稳定 |
| 自动化级别 | Nightly 必跑 |

### 7.14 `TD-LOCAL` DuckDB、Pandas、PyArrow

| 项目 | 设计 |
|---|---|
| 目标 | 验证非 REST 原生引擎通过 UC 提供 metadata / credential resolution 仍可工作 |
| 前置条件 | DuckDB Lance 扩展与 Python 环境可用 |
| 关键场景 | `TD-LOCAL-001` 通过 UC 获取 `storage_location` 与 `storage_options`；`TD-LOCAL-002` DuckDB attach / read；`TD-LOCAL-003` DuckDB `SELECT`；`TD-LOCAL-004` `lance_vector_search`；`TD-LOCAL-005` `lance_fts`；`TD-LOCAL-006` Pandas DataFrame 读取；`TD-LOCAL-007` PyArrow 读取；`TD-LOCAL-008` schema 演进后列映射；`TD-LOCAL-009` 凭证过期后刷新或失败语义 |
| 主要断言 | 重点验证 UC 作为治理与元数据后端是否正确下发位置和凭证，而不是把 DuckDB/Pandas 当作 REST 客户端 |
| 自动化级别 | 发布前必跑，Nightly 可做 smoke |

### 7.15 `TD-REG` UC 原生能力回归

| 项目 | 设计 |
|---|---|
| 目标 | 验证引入 Lance 路由与模型后不影响现有 UC 能力 |
| 前置条件 | 保留现有 UC 控制面与 Iceberg/Delta 回归基线 |
| 关键场景 | `TD-REG-001` `/tables` 现有行为回归；`TD-REG-002` `/schemas` 回归；`TD-REG-003` Iceberg REST 回归；`TD-REG-004` Delta 协议回归；`TD-REG-005` 现有 `AuthDecorator` 行为回归；`TD-REG-006` 原有 `KeyMapper` 三层解析回归；`TD-REG-007` 不同路由前缀下装饰器不串扰 |
| 主要断言 | 新增 Lance 协议面是“并行扩展”，不是对现有控制面的破坏性替换 |
| 自动化级别 | PR 必跑 |

### 7.16 `TD-NFR` 性能、并发、安全与弹性

| 项目 | 设计 |
|---|---|
| 目标 | 验证系统具备生产化基本质量属性 |
| 前置条件 | 有稳定的环境监控与日志采集 |
| 关键场景 | `TD-NFR-001` 深层 namespace 查找性能；`TD-NFR-002` metadata-only 吞吐；`TD-NFR-003` query/stats 延迟；`TD-NFR-004` 并发创建同级 namespace；`TD-NFR-005` 并发 batch commit；`TD-NFR-006` worker 故障；`TD-NFR-007` DB 抖动；`TD-NFR-008` 对象存储短暂失败；`TD-NFR-009` token exchange 峰值；`TD-NFR-010` 越权、伪造 header、错误 api key 攻击面；`TD-NFR-011` 异步状态恢复与幂等重试 |
| 主要断言 | 性能指标满足阶段目标；并发和故障场景下数据与状态保持一致；安全边界没有旁路 |
| 自动化级别 | Nightly/发布前 |

## 8. 关键端点覆盖清单

| 端点或能力 | 最低覆盖套件 |
|---|---|
| `GET /v1/namespace/{id}/list` | `TD-ARCH`、`TD-NS`、`TD-CLI` |
| `POST /v1/namespace/{id}/create` | `TD-ARCH`、`TD-NS`、`TD-CLI` |
| `POST /v1/namespace/{id}/describe` | `TD-ARCH`、`TD-NS`、`TD-CLI` |
| `POST /v1/namespace/{id}/drop` | `TD-ARCH`、`TD-NS`、`TD-CLI` |
| `POST /v1/namespace/{id}/exists` | `TD-ARCH`、`TD-NS` |
| `GET /v1/namespace/{id}/table/list` | `TD-ARCH`、`TD-TBL`、`TD-CLI` |
| `/v1/table/{id}/declare` | `TD-TBL`、`TD-CLI` |
| table list/register/create-empty/create/describe/drop/deregister/exists | `TD-TBL` |
| rename table | `TD-TBL` |
| restore table | `TD-TBL` |
| schema metadata update | `TD-SCHEMA` |
| add columns | `TD-SCHEMA` |
| alter columns | `TD-SCHEMA` |
| drop columns | `TD-SCHEMA` |
| `/v1/table/{id}/query` | `TD-DATA`、`TD-CLI` |
| `/v1/table/{id}/stats` | `TD-DATA` |
| count rows | `TD-DATA` |
| insert | `TD-DATA` |
| merge insert | `TD-DATA` |
| update | `TD-DATA` |
| delete | `TD-DATA` |
| explain plan | `TD-DATA` |
| analyze plan | `TD-DATA` |
| create/list/describe/drop index | `TD-ADV` |
| `/v1/table/{id}/version/list` 等 version 端点 | `TD-ADV` |
| `/v1/table/{id}/tags/*` | `TD-ADV` |
| transaction describe/alter | `TD-ADV` |
| `/v1/table/batch-commit` | `TD-ADV` |
| `DescribeTable(vend_credentials=true)` | `TD-CRED`、`TD-LOCAL` |

## 9. 测试数据设计

### 9.1 数据集分类

| 数据集 | 用途 | 说明 |
|---|---|---|
| DS-NS-01 | 多层 namespace | 覆盖 1 层、2 层、4 层以上路径 |
| DS-TBL-01 | 空表 | 验证 declare/create-empty/metadata-only |
| DS-TBL-02 | 向量表 | 验证 vector query、index |
| DS-TBL-03 | 文本表 | 验证 FTS、hybrid 查询 |
| DS-TBL-04 | 演进表 | 验证 schema metadata 与列变更 |
| DS-TBL-05 | 版本表 | 验证 version/tag/transaction/batch commit |
| DS-LEG-01 | legacy Unity Lance 表 | 验证识别与迁移 |
| DS-SEC-01 | 多主体权限数据 | 验证授权与审计 |
| DS-SEC-02 | API key 数据 | 验证 `uc_lance_api_keys` 哈希存储、主体映射与吊销 |

### 9.2 数据生成原则

- 向量表至少包含可验证距离排序的样本
- 文本表至少包含可验证全文检索结果的样本
- 版本表至少包含多个 manifest 与 tag 指向
- legacy 表必须同时保留：
  - `table_type=EXTERNAL`
  - `properties.table_type=lance`
  - `data_source_format=TEXT`
- 安全数据需要覆盖不同 namespace 层级的继承与拒绝场景
- `path_key` 样例必须覆盖同一路径使用不同 delimiter 的等价表达
- `storage_options_template_json` 样例必须与动态下发的临时凭证样例分离生成

## 10. 执行顺序建议

建议按以下顺序实施测试：

1. `TD-ARCH`
2. `TD-ID`
3. `TD-AUTH`
4. `TD-META`
5. `TD-NS`
6. `TD-TBL`
7. `TD-SCHEMA`
8. `TD-CRED`
9. `TD-DATA`
10. `TD-ADV`
11. `TD-CLI`
12. `TD-SPARK`
13. `TD-RAY`
14. `TD-LOCAL`
15. `TD-REG`
16. `TD-NFR`

说明：

- 前 8 类更适合作为阶段开发中的持续回归基线。
- `TD-CLI`、`TD-SPARK`、`TD-RAY`、`TD-LOCAL` 更适合作为联调与发布门禁。
- `TD-REG` 需要贯穿所有阶段。

## 11. 验收证据

每个测试套件至少应产出以下证据：

- 用例执行记录
- 请求与响应样例
- 日志 / 审计截取
- 关键数据库状态核对结果
- 对象存储或 worker 侧结果核对
- 缺陷与风险结论

## 12. 最终建议

本测试设计建议采用“需求驱动 + 风险驱动 + 客户端驱动”的组合方式执行：

- 需求驱动保证 `R1` 到 `R5` 不漏项
- 风险驱动保证治理、安全、异步状态、一致性优先
- 客户端驱动保证官方接入面真正可用，而不是只在 UC 内部测试中看起来可用

其中最关键的发布门禁建议固定为：

1. Python 原生客户端可接入并完成 namespace + table 基础链路。
2. Spark 官方 catalog 不触发非预期降级。
3. `query`、`stats`、`batch-commit`、`vend_credentials` 四条高风险路径稳定。
4. UC 现有 `/tables`、Iceberg、Delta 回归通过。
