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

- Lance 原生 namespace client 可以直接连接到 UC 暴露的 Lance REST endpoint
- 多层 namespace 路径可以被原生表达、列举、描述、删除和迁移
- 不仅 table，可治理对象还包括 index、version、tag、transaction 等 Lance 资产
- UC 保持现有统一治理能力，不要求上层调用方必须改用 UC 自定义 API

### 3.3 非目标

以下事项不是本阶段核心目标：

- 让 UC 主 REST API 自身完全重命名成 Lance API
- 在 UC 核心进程中重写整个 Lance 执行引擎
- 追求“所有非 Lance 原生引擎都零改造”

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

#### R3. 全量 Lance 资产治理

UC 必须把 Lance 资产抽象成一组可治理对象，而不是只有 table。

至少包括：

- `LanceNamespace`
- `LanceTable`
- `LanceIndex`
- `LanceVersion`
- `LanceTag`
- `LanceTransaction`

并预留面向未来资产类型扩展的能力。

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

### 10.6 版本、标签与事务能力

完整目标下必须支持：

- list versions
- create version
- describe version
- delete version
- batch create versions
- table batch commit
- create / list / delete tags
- transaction describe / alter / commit 相关语义

其中 `table batch commit` 应显式对应 Lance 端点：

- `/v1/table/batch-commit`

并且需要与版本侧的 `batch create versions` 明确区分，避免在后续设计与 OpenAPI 映射阶段将两者混为同一能力。

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

### Phase 1：Lance Metadata Compatibility

目标：

- 跑通 namespace 与 table metadata 兼容
- 支持原生 Lance namespace client 基础接入

范围：

- namespace CRUD
- table list / describe / register / declare / create-empty / exists / drop / deregister
- 显式覆盖 `/v1/table/{id}/declare`
- Arrow schema JSON 持久化

### Phase 2：Lance Data Plane Compatibility

目标：

- 跑通 query 与基础数据修改语义

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

### Phase 3：Lance Index / Version / Tag / Transaction

目标：

- 覆盖 Lance 高价值高级资产

范围：

- index
- version
- tag
- table batch commit
- transaction

其中应显式覆盖：

- `/v1/table/batch-commit`

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
