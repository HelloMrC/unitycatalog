# Unity Catalog 面向 LanceDB 全量资产与多层路径的兼容需求文档

更新日期：2026-04-22

对应技术设计文档：`docs/unitycatalog-lancedb-rest-api-technical-design.md`

## 1. 文档目标

本文档用于重构 Unity Catalog（以下简称 UC）支持 LanceDB 的需求定义。重构后的目标不再是“让 UC 勉强兼容部分 Lance table API”，而是：

- 让 UC 能作为 Lance 原生生态可接入的治理与元数据后端
- 让 Lance 原生 Client / Spark / Ray 等上层使用者尽量不改业务语义
- 让 UC 能管理 LanceDB 的全量资产，而不仅仅是 table
- 让 UC 支持任意深度的 namespace / path，而不是被固定在 `catalog.schema.table`

这意味着，后续设计应当参考 Gravitino 的“统一治理内核 + 协议适配层”思路，但不能照搬其当前公开实现中的固定层级限制。

## 2. 核心结论

### 2.1 结论摘要

如果目标只是“对接 Lance 的部分表接口”，可以在 UC 现有 `catalog -> schema -> table` 模型上做较薄适配。

如果目标是“完整管理 LanceDB 的所有资产，并支持多层路径”，则必须：

- 将 Lance 视为一种独立协议与资产模型，而不是仅仅一种 `data_source_format`
- 在 UC 之上新增 `Lance REST Protocol Adapter`
- 在 UC 内部增加“任意深度 namespace + 多资产类型”的元数据模型
- 将 UC 现有的三层对象模型降级为兼容视图，而不是唯一真相

### 2.2 对 Gravitino 设计的判断

Gravitino 的设计有两点值得借鉴：

- 统一元数据治理内核与协议适配服务分离
- Iceberg REST / Lance REST 作为独立协议面暴露，而不是强行塞进统一 API

但 Gravitino 当前公开文档也明确暴露出限制：

- 其 Lance REST 后端仍以 `catalog -> schema -> table` 为中心
- 当前只支持 table 前两级 namespace
- 不支持 `catalog/schema/sub_schema`
- 表主要仍围绕 namespace / table 管理展开

因此，Gravitino 适合作为“架构分层参考”，不适合作为“对象模型模板”。

## 3. 重构后的目标定义

### 3.1 产品目标

UC 应提供一个面向 Lance 生态的协议服务，使外部 Lance 客户端可把 UC 当作 Lance Namespace / Lance REST 服务端来使用，同时由 UC 负责：

- 元数据管理
- 认证与授权
- 凭证下发
- 审计与治理
- 存储位置与访问策略控制

### 3.2 成功标准

满足以下条件时，视为该需求达到目标：

- UC 的 Lance 协议面以完整兼容 Lance API 为长期目标，而不是只挑选部分 endpoint
- Lance 原生 namespace client 可以直接连接到 UC 暴露的 Lance REST endpoint
- 多层 namespace 路径可以被原生表达、列举、描述、删除和迁移
- 不仅 table，可治理对象还包括 index、version、tag、transaction 等 Lance 资产
- UC 保持现有统一治理能力，不要求上层调用方必须改用 UC 自定义 API

### 3.3 非目标

以下事项不是本阶段核心目标：

- 让 UC 主 REST API 自身完全重命名成 Lance API
- 在 UC 核心进程中重写整个 Lance 执行引擎
- 在 UC 内部直接实现依赖 LanceDB 物理存储或 Lance 文件格式执行的能力
- 追求“所有非 Lance 原生引擎都零改造”

说明：

- “本阶段不实现”不等于“长期不支持”。凡是 Lance API 中只涉及 metadata / control plane 的能力，即使当前 UC 公共模型没有对应对象，也必须在 Lance 专用架构中预留模型、路由、状态和权限扩展点。
- 对于必须依赖 LanceDB 存储、Lance 文件格式或查询执行引擎的能力，UC 可以不在本进程内实现，但协议层应保留 endpoint、错误语义和后端 SPI，以便后续通过 worker / sidecar / external backend 承接。

### 3.4 API 兼容目标与阶段边界

需求层将 Lance API 能力分为三类：

| 类别 | 定义 | 长期要求 | 当前阶段要求 |
|---|---|---|---|
| Metadata-only / control-plane | 只需要 UC 自身 metadata、权限、属性、状态机即可表达，不要求读写 Lance 物理数据 | 必须纳入目标架构并最终支持 | 可以暂不实现具体 endpoint，但必须预留模型、路由、权限和迁移路径 |
| Hybrid metadata + storage metadata | 需要记录 Lance 文件、version、manifest、schema、stats 等指针或摘要，但不要求 UC 执行数据读写 | 必须有可演进的 metadata model，避免被 UC 当前 table/schema 模型锁死 | 可先返回 `UNIMPLEMENTED` 或只返回基础 metadata，但不能把字段设计成无法扩展 |
| Storage / execution dependent | 需要 LanceDB 存储、Lance 文件格式操作、Arrow 数据读写、query / DML / index build 执行 | 协议应兼容，执行可委托给 Lance backend | 当前阶段可以不实现本地执行，但必须有稳定错误语义和 backend 接入边界 |

这三个类别只影响实施顺序，不影响“长期完整兼容 Lance API”的目标。

## 4. 分析基线

### 4.1 本地代码与文档路径

- LanceDB 仓库：`/home/lei/data_ai/learning/codebase/lancedb`
- LanceDB 文档仓库：`/home/lei/data_ai/learning/codebase/lancedb-docs`
- Unity Catalog 仓库：`/home/lei/data_ai/learning/codebase/unitycatalog`

### 4.2 本次分析基于的快照

- LanceDB：`a92ae0de`
- LanceDB docs：`6c0ccc0`
- Unity Catalog：`75a750a`

### 4.3 重点参考的本地源码与规范

LanceDB 侧：

- `lancedb/docs/openapi.yml`
- `lancedb/python/python/lancedb/namespace.py`
- `lancedb/rust/lancedb/src/database/namespace.rs`
- `lancedb/rust/lancedb/src/connection.rs`
- `lancedb/java/lancedb-core/src/main/java/com/lancedb/LanceDbNamespaceClientBuilder.java`
- `lancedb-docs/docs/api-reference/rest/openapi.yml`
- `lancedb-docs/docs/namespaces/index.mdx`

Unity Catalog 侧：

- `unitycatalog/api/all.yaml`
- `unitycatalog/api/README.md`
- `unitycatalog/docs/unitycatalog-project-analysis.md`

## 5. LanceDB 的真实目标模型

### 5.1 Lance Namespace 不是固定三层模型

Lance Namespace 规范把 namespace 定义为一种可递归容纳 namespace 与 table 的层级容器。它关心的是“从 root 到对象的路径标识”，而不是要求所有后端都必须长成 `catalog.schema.table`。

因此，Lance 语义天然支持：

- 任意深度路径
- list-style identifier：`["prod", "team_a", "ml", "embeddings"]`
- string-style identifier：默认使用 `$` 连接
- 根 namespace 与多层子 namespace 的递归管理

这与 UC 当前固定三层模型存在本质差异。

### 5.2 Lance 资产不止 table

从 LanceDB 官方 REST API 与 namespace 规范来看，至少需要考虑以下资产类别：

- namespace
- table
- schema metadata
- column evolution
- data mutation
- query plan / query result
- index
- version
- tag
- transaction
- batch version commit

因此，“支持 LanceDB”如果只落在 table 元数据对象上，会在需求定义阶段就把目标做窄。

### 5.3 Lance 客户端的真实接入方式

Lance 原生客户端并不是面向某个抽象统一 catalog API 编码的，而是面向 Lance Namespace / Lance REST 协议接入。典型形式包括：

- Python / Rust：`connect_namespace("rest", {...})`
- Java：使用 namespace REST builder
- Spark / Ray：使用 Lance REST namespace URI + parent / delimiter 等配置

因此，若希望上层调用者尽量无感迁移，UC 必须提供原生 Lance 协议面，而不是仅暴露 UC 自己的统一 API。

## 6. UC 当前模型的约束

根据当前 UC 的 OpenAPI 与对象定义，UC 当前更偏向传统 control plane：

- 核心对象以 catalog / schema / table 为中心
- `CreateTable` 主要围绕传统 table metadata
- `DataSourceFormat` 中没有 `LANCE`
- 当前公共 API 主要覆盖元数据、权限、凭证、外部位置、临时凭证等

这说明 UC 有良好的治理底座，但缺少 Lance 所需的三类关键能力：

- 协议兼容层
- 任意深度 namespace 模型
- 面向 Lance 全量资产的元数据与数据面语义

### 6.1 原生 Lance API 目标与 UC legacy bridge 的差异边界

需求定义必须区分两类能力：

- **原生 Lance API 目标**：UC 对外提供 Lance Namespace / Lance REST 协议面，并以完整兼容 Lance API 为长期目标；内部用 Lance 专用元数据模型承载任意深度 namespace、多资产类型、metadata 状态和可委托的数据面语义。
- **UC legacy bridge**：为了兼容官方当前 Unity 集成规范和已有 UC 表记录，将满足特定条件的 UC `TableInfo` 识别为 legacy Lance table，并提供只读/可迁移的桥接视图。

legacy bridge 不是原生 Lance 目标模型的替代品，也不能反向决定长期对象模型。它只用于兼容已有 `catalog.schema.table` 形态的 Unity 表，并为后续迁移到 `uc_lance_*` 原生模型提供入口。

| 维度 | 原生 Lance API 长期目标 | UC legacy bridge 约束 | 需求处理方式 |
|---|---|---|---|
| Namespace 深度 | namespace 是递归路径容器，可表达任意深度路径 | 官方 Unity 集成只支持两级 namespace：`[catalog, schema]` | 原生 Lance metadata 必须支持任意深度；legacy lookup 只在可映射为 UC `catalog.schema` 时执行 |
| Table 标识 | table 标识为 namespace path + table name，string identifier 可通过 `$` 或 `delimiter` 表达 | legacy table 只支持三级标识：`catalog.schema.table` | 对外协议继续使用 Lance identifier；legacy table lookup 只接受可解析成三段的路径 |
| Table list | 在任意 Lance namespace 下列举该 namespace 的 table | UC legacy 表只能通过 `catalog + schema` 调 `listTables` | native 表列表与 legacy 表列表必须分清来源；分页时不得把两个来源交错成不可重复顺序 |
| 资产范围 | 不止 table，还包括 index、version、tag、transaction、batch commit 等资产 | legacy bridge 只能从 UC `TableInfo` 桥接 table metadata | 非 table 资产必须走原生 Lance metadata model，不能塞入 legacy UC table properties 作为长期方案 |
| Lance table 识别 | 原生模型应有明确 Lance asset/table 类型与状态 | UC 当前没有 `DataSourceFormat=LANCE`，legacy 通过 `EXTERNAL + TEXT + properties.table_type=lance` 识别 | `TEXT + table_type=lance` 只能作为兼容标记，不能作为最终主模型 |
| Metadata 完整性 | 可保存 Lance table URI、Arrow schema、current version、stats、storage options template 等专有元数据 | legacy `TableInfo` 主要能提供 `storage_location`、`properties`、UC columns，其他 Lance 字段可能为空 | response 必须显式处理缺失字段，不得伪造 version / stats / detailed metadata |
| Metadata-only API | namespace、table metadata、index/version/tag/transaction 等 metadata endpoint 应纳入 Lance 专用模型 | legacy bridge 不能表达非 table 资产，也不能表达完整 Lance 状态机 | 当前阶段可暂不实现，但架构必须预留 route、model、state、authorization 和 migration path |
| 数据面能力 | 支持 query、stats、count rows、insert、merge insert、update、delete、explain、analyze 等 Lance REST data endpoint | legacy bridge 默认只保证 metadata compatibility | 依赖 LanceDB 存储或执行的能力可暂不实现；协议层保留 endpoint 和 backend SPI，legacy 写操作仍应拒绝并提示迁移 |
| 写入与状态推进 | 写操作成功后推进原生 Lance metadata 状态、version、schema、stats | legacy 写入会绕过 `uc_lance_assets + uc_lance_tables` 状态模型 | legacy bridge 不支持 insert / merge_insert / update / delete / create；需要数据面能力时必须迁移为原生记录 |
| 凭证与 storage options | runtime credentials 按请求下发，不持久化短期凭证 | legacy 可基于 UC table/path credential vending 生成有限 storage options | storage options 可以桥接返回，但 token/session/secret 等短期凭证不得落库 |
| rename / restore 等表管理 | metadata-only rename 应进入原生 metadata model；restore 等物理格式相关能力属于 storage-dependent | legacy rename/restore 无法可靠维护 UC 表与 Lance 文件状态一致性 | metadata-only 能力必须可演进；restore 这类需要 Lance 文件格式操作的能力可暂不实现但不得伪成功 |

### 6.2 legacy bridge 的硬性需求约束

为避免把兼容视图误认为完整 Lance 支持，需求层必须明确以下约束：

- legacy bridge 只识别满足全部条件的 UC table：
  - `table_type = EXTERNAL`
  - `data_source_format = TEXT`
  - `properties.table_type = lance`
- legacy namespace list 只在路径能映射为 UC `catalog.schema` 时读取 UC table list；更深层 Lance namespace 不应回退到 UC legacy 表扫描。
- legacy table describe / exists 只在路径能映射为 UC `catalog.schema.table` 时读取 UC `TableInfo`；少于或多于三段的路径不属于 legacy Unity table。
- 当同一路径同时存在原生 `uc_lance_*` 记录和 legacy UC table 时，必须优先使用原生 Lance metadata。
- legacy bridge 响应必须能够被调用方识别，例如返回 `legacy_bridge=true` 或等价元数据，避免调用方误以为该表具备完整数据面和资产治理能力。
- legacy bridge 不自动写入 `uc_lance_assets` / `uc_lance_tables`；迁移必须通过显式迁移工具、管理 API 或后台任务完成。
- legacy bridge 的错误响应必须清晰说明能力边界：metadata bridge 可用，但完整 Lance API 能力需要迁移到原生 Lance metadata model；依赖存储或执行的 endpoint 可通过后端实现补齐。

## 7. 重构后的需求范围

### 7.1 总体产品定义

建议将需求正式命名为：

**“Unity Catalog 增加 Lance 协议适配与全量 Lance 资产治理能力”**

而不是：

**“Unity Catalog 支持 LanceDB 的若干 REST API”**

前者面向长期演进，后者容易把设计约束死在接口兼容的表层。

### 7.2 必须满足的一级需求

#### R1. 协议兼容

UC 必须提供 Lance 原生客户端可直接使用的协议服务。

至少包括：

- Lance REST Namespace 路由与语义兼容
- 默认 `$` 分隔符支持
- `delimiter` 参数兼容
- 兼容 Lance 的 header / context 传递模型
- 兼容 Arrow IPC 请求与响应语义

#### R2. 任意深度 namespace

UC 必须支持任意深度 namespace 路径，不能将多层路径通过字符串拼接后塞入单个 schema 名称来模拟。

必须满足：

- namespace 可递归嵌套
- 任意层可 `create / list / describe / exists / drop`
- table 可以挂载在任意合法父 namespace 下
- 支持从 list-style identifier 到 string-style identifier 的双向转换

#### R3. Lance 资产治理范围

UC 作为 Catalog 层，负责治理以下对象：

- `LanceNamespace`
- `LanceTable`
- `LanceIndex`：UC 记录索引元数据，提供查询 API，元数据由执行器同步
- `LanceVersion`：UC 记录版本历史，提供查询 API，元数据由执行器同步
- `LanceTag`：UC 提供完整 CRUD（纯 metadata 操作），或接收执行器同步
- `LanceTransaction`：UC 记录事务状态，提供查询 API，元数据由执行器同步

**UC 与执行引擎的职责分离**：

- UC 不执行 Index/Version/Transaction 操作
- Lance SDK 或 Worker 执行这些操作，完成后将元数据同步到 UC
- UC 提供 `listIndices` / `listVersions` / `describeTransaction` 等查询 API

#### R4. 保持 UC 统一治理能力

Lance 协议面不能绕过 UC 的治理能力。所有 Lance 资产都应纳入：

- 身份认证
- 授权控制
- 审计日志
- 外部位置策略
- 临时凭证发放
- 资源级标签或属性管理

#### R5. 上层使用者尽量无感迁移

至少应支持以下迁移路径：

- 原生 Lance namespace client 直接改 endpoint 后可用
- Spark / Ray 等已支持 Lance REST 的上层可直接接入
- 不原生支持 Lance REST 的引擎可通过 location resolution + credential vending 访问

#### R6. legacy Unity bridge 只作为兼容与迁移入口

UC 必须支持已有 Unity 集成形态的 Lance 表被发现、描述和迁移，但该能力必须被明确限定为 legacy bridge：

- legacy bridge 必须服从 `catalog.schema.table` 的官方 Unity 集成约束
- legacy bridge 不得限制原生 Lance namespace 的任意深度目标
- legacy bridge 不得承载 index / version / tag / transaction 等长期资产模型
- legacy bridge 数据面写入必须拒绝，避免绕过原生 Lance metadata 状态机
- legacy bridge 的迁移路径必须明确，包括迁移后如何生成 namespace、asset、table 记录

#### R7. 完整 Lance API 兼容以 capability matrix 管理

需求和设计必须维护一份 Lance API capability matrix，至少区分：

- 已实现
- 当前阶段暂不实现但 metadata model 已预留
- 当前阶段暂不实现且依赖 LanceDB storage / execution backend
- legacy bridge 不适用，必须迁移到原生 Lance metadata model

任何 endpoint 被标为暂不实现时，都必须说明暂不实现原因、未来承接模块和对外错误语义，不能只因为 UC 当前公共对象模型缺失就从目标范围删除。

### 7.3 metadata-only API 的最低架构要求

凡是只涉及 metadata / control plane 的 Lance API，即使当前阶段不实现，也必须满足以下架构要求：

- OpenAPI / route 层保留可对齐 Lance API 的 operation 名称与路径规划。
- Service 层预留独立 handler 或 dispatcher，避免未来把能力塞进通用 UC table API。
- Persist 层预留 Lance 专用 asset / relation / property / state 结构，不依赖 UC 固定三层对象表达全部语义。
- Auth 层预留对应资源类型与权限映射，不把所有操作粗暴折叠成 table owner。
- Error 层使用稳定的 Lance-compatible `UNIMPLEMENTED` / `NOT_SUPPORTED` 响应，并说明该 endpoint 是阶段未实现，不是协议不支持。
- 测试层至少保留 disabled / skeleton / capability matrix 覆盖，防止后续 OpenAPI 漂移。

## 8. 推荐架构

### 8.1 总体分层

推荐采用四层结构：

1. UC Unified Governance Core
2. Lance Metadata Model
3. Lance REST Protocol Adapter
4. Lance Execution / Storage Adapter

职责划分如下：

- UC Unified Governance Core
  - 认证、授权、审计、凭证、策略、外部位置
- Lance Metadata Model
  - namespace 树、资产对象、Lance 专有元数据
- Lance REST Protocol Adapter
  - 暴露 Lance REST 端点，做请求解析与协议兼容
- Lance Execution / Storage Adapter
  - 处理 Arrow IPC、query、index、version、事务等执行语义

### 8.2 为什么不能只扩 `DataSourceFormat=LANCE`

将 Lance 简化成一种 `data_source_format` 会带来三个问题：

- 它无法表达多层 namespace 的递归对象关系
- 它无法表达 index / version / tag / transaction 等非 table 资产
- 它会让协议适配、治理语义和 table metadata 紧耦合，后续难以扩展到 Iceberg / Hudi / 其他开放协议

因此，`DataSourceFormat=LANCE` 最多只能作为兼容字段，不能作为主设计。

### 8.3 推荐部署模式

应明确支持以下部署模式：

- Embedded mode
  - Lance 协议服务随 UC 主进程启动
- Standalone mode
  - 独立的 Lance REST service，后端调用 UC
- Sidecar / gateway mode
  - 作为协议适配网关部署在 UC 前方

默认建议：

- 开发阶段优先 `embedded`
- 生产阶段优先 `standalone` 或 `sidecar`

## 9. 推荐对象模型

### 9.1 NamespaceNode

建议新增通用 namespace 节点模型：

- `namespace_id`
- `parent_namespace_id`
- `path_segments: List<String>`
- `depth`
- `properties`
- `display_name`
- `state`

关键要求：

- `path_segments` 为一等字段
- 不通过 `catalog_name + schema_name` 的固定列组表达全部路径
- 支持按父子关系递归列举

### 9.2 LanceAsset

建议增加通用 Lance 资产抽象：

- `asset_id`
- `asset_type`
- `namespace_id`
- `canonical_identifier`
- `properties`
- `owner`
- `created_at`
- `updated_at`

其中 `asset_type` 至少支持：

- `TABLE`
- `INDEX`
- `VERSION`
- `TAG`
- `TRANSACTION`

### 9.3 LanceTableRecord

面向 Lance table 的专用元数据应至少包括：

- table identifier
- namespace path
- storage location
- storage options
- Arrow schema JSON
- managed versioning 标记
- current version pointer
- table URI
- credential vending policy

### 9.4 LanceIndexRecord

至少覆盖：

- index name
- index type
- target columns
- distance type
- status
- build parameters
- statistics pointer

### 9.5 LanceVersionRecord

至少覆盖：

- table identity
- version number
- manifest path
- manifest size
- eTag
- timestamp
- metadata

### 9.6 LanceTagRecord

至少覆盖：

- tag name
- target version
- metadata

### 9.7 LanceTransactionRecord

至少覆盖：

- transaction id
- related tables
- operation list
- state
- commit metadata

## 10. 关键功能需求

### 10.1 Namespace 能力

必须支持：

- create namespace
- list namespaces
- describe namespace
- namespace exists
- drop namespace

并满足：

- 支持任意深度
- 支持递归删除策略
- 支持 namespace properties
- 支持 root namespace 语义

### 10.2 Table 生命周期能力

必须支持：

- list tables
- register table
- declare table
- create empty table
- create table
- describe table
- rename table
- restore table
- drop table
- deregister table
- table exists

其中应显式覆盖 Lance 的 `declare table` 端点：

- `/v1/table/{id}/declare`

该能力应归入 Phase 1，而不应只作为 table metadata 的隐含能力存在。

### 10.3 Schema 与列演进能力

必须支持：

- schema metadata update
- add columns
- alter columns
- drop columns

并要求：

- 原生保存 JSON Arrow schema
- 不只依赖 UC 当前列模型进行信息压缩

### 10.4 数据面能力

若目标是“完整支持 LanceDB”，则必须纳入以下数据面能力：

- query
- get table stats
- count rows
- insert
- merge insert
- update
- delete
- explain plan
- analyze plan

关键要求：

- 支持 Arrow IPC stream/file
- 支持向量查询参数
- 支持 full-text 查询参数
- 支持按 version 查询
- 支持 query result 的二进制返回

其中 `get table stats` 应显式对应 Lance 端点：

- `/v1/table/{id}/stats`

### 10.5 索引能力

必须支持：

- create index
- create scalar index
- list index
- describe index stats
- drop index

并要求支持：

- vector index
- scalar index
- FTS index
- async index build 状态跟踪

### 10.6 版本、标签、索引、事务能力

UC 作为元数据唯一事实来源（Single Source of Truth），提供元数据存储与查询 API。

**设计原则**：
- Lance SDK/Worker 直连 Lance 物理存储执行操作
- 执行成功后调用 UC sync API 将元数据存储到 UC
- UC 不从 Lance 物理存储同步数据，只接收执行器传入的元数据
- UC 作为元数据查询的唯一来源

**Version 能力**：

- `version/list`：查询版本历史 - ✅ UC 提供，查询 `uc_lance_versions`
- `version/describe`：查询版本详情 - ✅ UC 提供
- `version/create`、`version/batch-create`：❌ 语义不支持（Lance version 由写入自动生成），返回 400 BAD_REQUEST
- `version/delete`：✅ UC 提供 forwarding endpoint（转发到 Worker 执行物理删除）
- `syncVersion`：✅ Lance SDK 写入成功后调用，存储 version 元数据到 UC

**Index 能力**：

- `index/list`：查询索引列表 - ✅ UC 提供，查询 `uc_lance_indices`
- `index/describe`：查询索引统计 - ✅ UC 提供
- `syncIndex`：✅ Lance SDK 创建/删除索引后调用，存储 index 元数据到 UC
- **无 forwarding endpoint**：Lance SDK 直连存储执行，完成后调用 syncIndex

**Tag 能力**：

- `tags/list`：列出 table 下所有 tag - ✅ UC 提供
- `tags/get-version`：获取 tag 对应的 version - ✅ UC 提供
- `tags/create`：创建 tag 到 version 的映射 - ✅ UC 独立实现（纯 metadata）
- `tags/update`：更新 tag 指向的 version - ✅ UC 独立实现
- `tags/delete`：删除 tag - ✅ UC 独立实现
- `syncTag`：✅ Lance SDK 创建 tag 后调用，存储到 UC（与 UC createTag 结果一致）

Tag CRUD 是纯 metadata 操作，UC 可独立实现，不影响 Lance 物理数据。

**Transaction 能力**：

- `transaction/list`：查询事务列表 - ✅ UC 提供，查询 `uc_lance_transactions`
- `transaction/describe`：查询事务状态 - ✅ UC 提供
- `syncTransaction`：✅ Lance SDK 事务执行后调用，存储 transaction 元数据到 UC
- **无 forwarding endpoint**：Lance SDK 执行事务，完成后调用 syncTransaction

**Batch Commit**：

- `syncVersion` + `syncTransaction`：✅ Lance SDK 执行 batch commit 后调用
- **无 forwarding endpoint**：Lance SDK 执行，完成后调用 sync API

**Schema Evolution**：

- `syncSchema`：✅ Lance SDK schema 变化后调用，更新 `arrow_schema_json`
- **无 forwarding endpoint**：Lance SDK 执行 addColumns/alterColumns/dropColumns，完成后调用 syncSchema

**Restore Table**：

- ❌ 不提供 forwarding endpoint：Lance 文件格式操作超出 UC 元数据治理范围
- Lance SDK 可自行执行 restore，但 UC 不作为协议转发层

**元数据同步机制总结**：

| API | 调用时机 | UC 行为 |
|-----|----------|---------|
| `syncVersion` | Lance SDK 写入成功后 | 存储 version 元数据到 `uc_lance_versions` |
| `syncIndex` | Lance SDK 创建/删除索引后 | 存储/更新 index 元数据到 `uc_lance_indices` |
| `syncSchema` | Lance SDK schema 变化后 | 更新 `arrow_schema_json`、`current_version` |
| `syncTransaction` | Lance SDK 事务执行后 | 存储 transaction 状态到 `uc_lance_transactions` |
| `syncTag` | Lance SDK 创建 tag 后 | 存储 tag 元数据到 `uc_lance_tags` |

### 10.7 身份、认证与上下文透传

必须支持：

- `Authorization`
- `x-api-key`
- Lance context header
- 多租户或数据库上下文 header

并要求：

- 兼容 Lance 规范中的 `OAuth2`、`BearerAuth`、`ApiKeyAuth` 安全模型
- 外部请求身份能够映射到 UC 主体
- 凭证可按 namespace / table / asset 下发
- 可记录请求链路与上下文到审计日志

认证兼容与映射规则至少应明确：

- OAuth2 token exchange 与 UC `/tokens` 的映射关系
- OAuth2 token exchange 与 UC `AuthService.grantToken()` 的承接边界
- Bearer token 的校验、透传、交换与 principal 归属规则
- `x-api-key` 到 UC 主体或服务账号的映射规则
- Lance context headers 到 tenant / catalog / namespace 上下文的映射规则
- issuer / audience / subject token type 的兼容约束

## 11. 客户端兼容性要求

### 11.1 原生 Lance Client

必须把原生 Lance Client 作为 P0 兼容目标，而不是“后续考虑”：

- Python namespace client
- Rust namespace client
- Java namespace client

成功标准：

- 用户只改 endpoint / auth / context 配置即可接入
- 不需要改业务层资源命名方式

### 11.2 Spark / Ray / 生态接入

应把以下类型视作重点兼容对象：

- Lance Spark
- Lance Ray

并提供：

- 明确版本兼容矩阵
- 示例配置
- parent / delimiter / namespace path 的映射说明

### 11.3 非原生 REST 引擎

对 DuckDB、Pandas、DataFusion 等未原生支持 Lance REST 的引擎，应提供：

- table location resolution
- storage options resolution
- credential vending
- 明确的迁移说明

## 12. 分阶段实施建议

### Phase 0：基础模型重构

目标：

- 在 UC 内引入任意深度 namespace 模型
- 建立 Lance 专有资产模型
- 设计治理对象与授权粒度

交付：

- 元数据模型设计
- 存储表设计
- OpenAPI / 协议映射设计
- Lance API capability matrix，明确每个 endpoint 属于 metadata-only、hybrid metadata、还是 storage / execution dependent
- metadata-only endpoint 的 route、model、authorization、error contract 预留设计，即使当前阶段不落地实现

### Phase 1：Lance Metadata Compatibility

目标：

- 跑通 namespace 与 table metadata 兼容
- 支持原生 Lance namespace client 基础接入
- 建立 metadata-only API 后续扩展的最小架构骨架

范围：

- namespace CRUD
- table list / describe / register / declare / create-empty / exists / drop / deregister
- 显式覆盖 `/v1/table/{id}/declare`
- Arrow schema JSON 持久化
- legacy bridge 的 `catalog.schema.table` 识别、只读 metadata 响应和迁移边界
- Tag CRUD 作为 Phase 1 或 Phase 2.x 可提前实现

### Phase 2：Lance Data Plane Compatibility

目标：

- 跑通 query 与基础数据修改语义
- 建立依赖 LanceDB 存储或执行能力的 backend SPI，不要求 UC 主进程实现物理执行

范围：

- query
- get table stats
- insert
- merge insert
- update
- delete
- count rows
- explain / analyze

其中应显式覆盖：

- `/v1/table/{id}/stats`

### Phase 3：高级资产元数据治理

目标：

- 提供 Index、Version、Tag、Transaction 等元数据查询 API
- 建立元数据同步机制，接收执行器同步的元数据

范围：

- **Tag CRUD**（UC 独立实现）：
  - `tags/list`
  - `tags/get-version`
  - `tags/create`
  - `tags/update`
  - `tags/delete`
  
- **Version 查询**（需要执行器同步）：
  - `version/list`
  - `version/describe`
  
- **Index 查询**（需要执行器同步）：
  - `index/list`
  - `index/describe`
  
- **Transaction 查询**（需要执行器同步）：
  - `transaction/describe`
  
- **元数据同步 API**：
  - `syncVersion`：执行器写入成功后同步 version 信息
  - `syncIndex`：执行器创建/删除索引后同步索引元数据
  - `syncSchema`：执行器 schema 变化后同步 schema 信息
  - `syncTransaction`：执行器事务执行后同步事务状态

**执行类操作**（通过 UC 协议转发层或 Lance SDK 直连执行）：

- `index/create`、`index/drop`
- `version/delete`
- `transaction/alter`
- `table/batch-commit`
- `schema/update`、`add_columns`、`alter_columns`、`drop_columns`
- `restore table`

执行器执行成功后需要调用同步 API 将元数据同步到 UC。

### Phase 4：生态适配与生产化

目标：

- 提供完整迁移路径与生产部署建议

范围：

- Spark / Ray 兼容矩阵
- 观测性
- 审计
- 灰度发布与回滚策略

## 13. 当前已识别的主要风险

### 13.1 现有 UC 三层模型约束过强

若继续以 `catalog_name / schema_name / table_name` 作为所有对象的唯一主键，后续多层路径支持会非常别扭，且会导致：

- 路径 flatten
- list / describe 语义异常
- 权限继承难以定义
- 资产迁移成本高

### 13.2 当前 UC 公共对象模型偏传统表目录

这会导致：

- 无法自然表达 index / version / tag / transaction
- Lance 专有 metadata 被迫塞入 `properties`

### 13.3 数据面与治理面的耦合风险

如果在 UC 核心进程中直接堆叠 Arrow IPC、向量查询和索引执行逻辑，后续演进成本会很高。

因此应尽早分离：

- governance core
- protocol adapter
- execution adapter

### 13.4 上层生态版本变化快

Lance 生态仍在快速变化，后续必须维护：

- 客户端兼容矩阵
- API 变更跟踪
- 协议回归测试

## 14. 最终建议

如果最终目标是“完整治理 LanceDB 的所有资产，并支持多层路径”，那么推荐路线是：

- 参考 Gravitino 的协议适配思路
- 不复制 Gravitino 当前固定层级对象模型
- 将 UC 扩展为“统一治理内核 + Lance 协议服务 + Lance 专用元数据模型”

这一路线的核心原则只有三条：

1. 对外保持 Lance 原生协议兼容。
2. 对内建立任意深度 namespace 与多资产模型。
3. 让 UC 的认证、授权、审计与凭证能力覆盖 Lance 全量资产。

## 15. 参考资料

Lance / LanceDB：

- [Lance Namespace Client Spec](https://lance.org/format/namespace/client/)
- [Lance Namespace Objects & Relationships](https://lance.org/format/namespace/client/object-relationship/)
- [Lance REST Namespace Catalog Spec](https://lance.org/format/namespace/rest/catalog-spec/)
- [LanceDB Namespaces and Catalog Model](https://docs.lancedb.com/namespaces/index)
- [LanceDB REST API Reference](https://docs.lancedb.com/api-reference/rest)
- [Lance Unity Integration Spec](https://lance.org/format/namespace/integrations/unity/)

Gravitino：

- [Gravitino Lance REST service](https://gravitino.apache.org/docs/next/lance-rest-service/)
- [Gravitino Lance REST integration](https://gravitino.apache.org/docs/1.2.0/lance-rest-integration/)
- [Generic Lakehouse Catalog](https://gravitino.apache.org/docs/1.1.0/lakehouse-generic-catalog/)

Unity Catalog：

- [Unity Catalog GitHub](https://github.com/unitycatalog/unitycatalog)
