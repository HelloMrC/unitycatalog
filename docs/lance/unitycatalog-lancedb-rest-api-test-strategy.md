# Unity Catalog LanceDB REST API 测试策略

更新日期：2026-04-23

关联文档：

- `docs/unitycatalog-lancedb-rest-api-requirements.md`
- `docs/unitycatalog-lancedb-rest-api-technical-design.md`

## 1. 文档目的

本文档用于定义 Unity Catalog（以下简称 UC）新增 LanceDB REST API / Namespace 兼容能力的整体测试策略，确保实现满足以下目标：

- 对外保持 Lance 原生协议兼容，优先保证原生客户端、Spark、Ray 等接入方式可用
- 对内保证 UC 的认证、授权、审计、凭证下发和外部位置治理能力没有被绕过
- 验证“任意深度 namespace + 多资产模型 + 协议适配层 + 执行后端 SPI”的设计落地质量
- 在分阶段交付过程中建立稳定的自动化回归体系，降低后续协议演进和生态版本变化带来的回归风险

## 2. 测试目标

本项目测试的核心目标如下：

1. 验证需求文档中的一级需求 `R1` 到 `R5` 被完整覆盖。
2. 验证技术设计中的核心分层、关键模块、元数据模型、授权模型和执行链路按预期工作。
3. 验证 UC 新增 Lance 协议面后，不影响现有 UC `/tables`、`/schemas`、Iceberg、Delta 等能力。
4. 验证系统在 embedded、standalone、sidecar 三类部署模式下都具备可操作的质量保障路径。
5. 验证 legacy bridge 可识别、可迁移、可回归，而不会把旧三层模型误当成新主模型。

## 2.1 上游协议与客户端基线

为防止“只跟着某个客户端便利方法走”导致协议漂移，测试策略需要固定两层基线：

- REST 契约基线
  - `lancedb-docs/docs/api-reference/rest/openapi.yml`
  - commit：`6c0ccc001e6b`
- 客户端行为基线
  - `lancedb`
  - commit：`a92ae0ded522`

基线优先级：

1. endpoint 路径、HTTP 方法、请求响应结构以 REST OpenAPI 快照为准
2. 原生 Python / Java / Rust 客户端 smoke 用来校验真实消费行为
3. 当某语言包装层未直接暴露某个便利方法时，由原始 HTTP 协议测试补齐，不以“包装层没有方法”否定对应 REST endpoint

## 3. 测试范围

### 3.1 范围内

- Lance REST Namespace 协议兼容
- 任意深度 namespace 模型与路径编解码
- Lance 资产治理模型：
  - namespace
  - table
  - index
  - version
  - tag
  - transaction
- UC 治理能力接入：
  - 身份认证
  - 授权控制
  - 审计日志
  - 外部位置策略
  - 临时凭证发放
  - `storage_options` 生成
- 元数据持久化：
  - `uc_lance_namespaces`
  - `uc_lance_assets`
  - `uc_lance_tables`
  - `uc_lance_api_keys`
  - `uc_lance_indices`
  - `uc_lance_versions`
  - `uc_lance_tags`
  - `uc_lance_transactions`
- 协议适配关键点：
  - `$` 默认分隔符
  - 自定义 `delimiter`
  - Arrow IPC stream/file
  - header / context 透传
  - `x-api-key` / Bearer / OAuth2 token exchange
- 数据面能力：
  - query
  - stats
  - count rows
  - insert / merge insert / update / delete
  - explain / analyze
- 高级资产能力：
  - index
  - version
  - tag
  - transaction
  - batch commit
- 兼容对象：
  - Python / Java / Rust 原生 Lance client
  - Lance Spark
  - Lance Ray
  - DuckDB
  - Pandas / PyArrow
- legacy bridge 与迁移链路

### 3.1.1 分阶段持久化边界

整体测试范围仍覆盖完整 Lance 资产模型，但 DDL 与持久化验收必须遵循最新分阶段边界：

- Phase 0 / Phase 1 必须落地并验证：
  - `uc_lance_namespaces`
  - `uc_lance_assets`
  - `uc_lance_tables`
  - `uc_lance_api_keys`
- Phase 3 才要求落地并验证：
  - `uc_lance_indices`
  - `uc_lance_versions`
  - `uc_lance_tags`
  - `uc_lance_transactions`

测试报告中不得把 Phase 3 的高级资产表缺失误判为 Phase 0 / Phase 1 阻塞项。

### 3.2 范围外

- UC 主控制面 UI 的完整前端交互测试
- 非文档定义范围内的 Lance 执行引擎内部算法正确性验证
- 所有云厂商对象存储的穷尽性兼容测试
- 对未来未纳入当前 OpenAPI 快照的新 Lance 上游能力做前置验证

## 4. 测试原则

### 4.1 风险驱动

测试优先级遵循以下顺序：

1. 协议兼容正确性
2. 治理能力不被绕过
3. 元数据模型一致性
4. 数据面基本可用性
5. 生态接入兼容性
6. 性能、弹性、可运维性

### 4.2 分层验证

测试必须覆盖以下层次：

- 静态评审：需求、设计、OpenAPI 快照、授权模型、数据库模型
- 单元测试：codec、decorator、repository、service、DTO 映射
- 组件测试：service + repository + auth + DAO 的组合验证
- 协议集成测试：直接通过官方公开客户端或标准 HTTP 调用 Lance endpoint
- 端到端测试：UC + worker/sidecar + 存储 + 客户端联调
- 回归测试：UC 现有能力和各阶段兼容矩阵回归

### 4.2.1 契约回归方式

契约回归必须同时包含两类检查：

- OpenAPI diff 检查
  - 比较当前实现使用的 Lance OpenAPI 快照与上游基线快照差异
- 实际行为检查
  - 直接验证关键 endpoint 的 HTTP 方法、状态码和响应体行为

Phase 1 至少固定检查：

- `GET /v1/namespace/{id}/list`
- `POST /v1/namespace/{id}/create`
- `POST /v1/namespace/{id}/describe`
- `POST /v1/namespace/{id}/drop`
- `POST /v1/namespace/{id}/exists`
- `GET /v1/namespace/{id}/table/list`

### 4.3 官方接入面优先

Spark 与 Ray 的测试必须基于官方公开接入面，不允许通过 UC 内部 DTO、测试专用后门或非官方协议封装绕过真实协议层。

对于 Python / Java / Rust 原生客户端，需要额外区分：

- 包装层显式暴露的方法
- 客户端内部隐式依赖的协议路径
- 仅在 REST OpenAPI 中出现、但某语言包装层未单独暴露的 endpoint

例如：

- `namespace_exists` 在当前 REST OpenAPI 中存在
- 当前本地 Python namespace 包装层没有单独暴露对应高层方法
- 因此该能力在 Phase 1 由 raw HTTP 协议测试覆盖，同时用客户端可观察行为验证等价存在性语义

对于原生 Lance client、Spark、Ray 等 connector / client 的兼容验收，必须同时满足以下四项原则：

- 配置方式与官方文档一致
- namespace / table 标识方式与官方文档一致
- header / auth 透传方式与官方文档一致
- 失败时返回可被官方客户端正确识别的错误语义

### 4.4 阶段渐进

不同实施阶段采用不同测试重心：

- Phase 0 聚焦骨架、模型、授权、路由
- Phase 1 聚焦 metadata compatibility
- Phase 2 聚焦 data plane compatibility
- Phase 3 聚焦 index / version / tag / transaction
- Phase 4 聚焦生态矩阵、观测性、生产化回归

## 5. 质量风险与测试重点

| 风险编号 | 风险描述 | 影响 | 测试重点 |
|---|---|---|---|
| QR-01 | 仍以三层模型变相承载多层 namespace | 高 | 任意深度 namespace、路径查找、递归列举、权限继承 |
| QR-02 | 协议兼容只覆盖部分 table API，忽略全量资产 | 高 | index/version/tag/transaction/batch commit |
| QR-03 | Lance 路由绕过 UC 治理能力 | 高 | auth、RBAC、审计、凭证、外部位置策略 |
| QR-04 | Arrow IPC 与二进制响应不稳定 | 高 | query/stats/stream/file 响应、错误语义 |
| QR-05 | Spark / Ray 接入因 namespace 语义不完整而降级或失败 | 高 | connector 兼容矩阵、错误语义、header 透传 |
| QR-06 | legacy bridge 识别错误导致老表不可读或误迁移 | 中 | 识别规则、迁移前后可读性、一致性校验 |
| QR-07 | UC 现有 API 被新路由、装饰器、权限模型影响 | 高 | `/tables`、`/schemas`、Iceberg、Delta 回归 |
| QR-08 | 执行后端异步状态与元数据状态不一致 | 高 | index build、transaction、batch commit 状态流 |
| QR-09 | 上游客户端版本变化引发回归 | 中 | 版本矩阵、契约回归、夜间兼容测试 |

## 6. 测试级别与方法

| 层级 | 目标 | 主要对象 | 自动化优先级 |
|---|---|---|---|
| L0 静态评审 | 提前发现模型和契约缺陷 | 需求、设计、OpenAPI、DDL、权限枚举 | 高 |
| L1 单元测试 | 验证局部逻辑正确性 | `LanceIdentifierCodec`、`LanceAuthDecorator`、`LanceMetadataService`、repositories | 高 |
| L2 组件测试 | 验证模块协作与持久化 | service + repository + DAO + decorator | 高 |
| L3 协议集成测试 | 验证 HTTP 协议与 DTO 兼容 | Lance REST endpoint、Arrow IPC、错误码 | 高 |
| L4 客户端兼容测试 | 验证官方客户端/连接器可用 | Python / Java / Rust / Spark / Ray | 高 |
| L5 引擎联动测试 | 验证非 REST 原生引擎工作流 | DuckDB、Pandas、PyArrow | 中 |
| L6 非功能测试 | 验证性能、并发、可靠性、安全性 | 查询、写入、索引、token exchange、故障恢复 | 中 |
| L7 回归测试 | 防止新改动破坏现有能力 | UC 原生控制面、Iceberg、Delta | 高 |

## 7. 按阶段测试策略

| 阶段 | 功能范围 | 主要测试活动 | 准出条件 |
|---|---|---|---|
| Phase 0 | 路由、鉴权、模型、仓储、授权骨架 | DDL/DAO 评审、单元测试、组件测试、基础安全测试 | `uc_lance_namespaces`、`uc_lance_assets`、`uc_lance_tables`、`uc_lance_api_keys` 可持久化，Lance 路由不影响现有 UC 路由 |
| Phase 1 | namespace + table metadata compatibility | 协议集成测试、legacy bridge 测试、原生客户端基础兼容 | Python/Java/Rust P0 通过，`namespace exists`、`declare`、`describe`、`list`、`drop` 可用，且不要求高级资产表先行落地 |
| Phase 2 | query + stats + DML | Arrow IPC 测试、执行后端测试、数据一致性与错误路径测试 | query / stats / insert / update / delete / count 稳定可回归 |
| Phase 3 | index/version/tag/transaction/batch commit | 高级资产 DDL、异步状态流测试、资产治理测试、审计与权限测试 | `uc_lance_indices`、`uc_lance_versions`、`uc_lance_tags`、`uc_lance_transactions` 落地，高级资产元数据与物理状态一致，关键端点覆盖 |
| Phase 4 | 生态适配与生产化 | Spark/Ray/DuckDB/Pandas 全链路矩阵、性能、灰度与回滚演练 | 兼容矩阵达标，发布门禁、观测性、回滚方案完备 |

## 8. 需求覆盖策略

| 需求 | 测试关注点 | 主要测试类型 |
|---|---|---|
| R1 协议兼容 | 路由、header、delimiter、Arrow IPC、错误语义 | 协议集成、客户端兼容、契约回归 |
| R2 任意深度 namespace | path segments、递归 list、root 语义、rename/drop | 单元、组件、协议集成 |
| R3 全量 Lance 资产治理 | asset type、index/version/tag/transaction 元数据与接口 | 组件、协议集成、端到端 |
| R4 保持 UC 统一治理能力 | auth、RBAC、审计、凭证、外部位置策略 | 安全测试、组件测试、端到端 |
| R5 上层使用者尽量无感迁移 | 原生 client、Spark、Ray、DuckDB/Pandas 工作流 | 客户端兼容、迁移测试、回归测试 |

## 9. 环境与部署矩阵

### 9.1 部署模式

至少维护以下三类测试拓扑：

| 拓扑 | 用途 | 覆盖重点 |
|---|---|---|
| Embedded | 开发阶段主力回归 | 路由、decorator、repository、metadata-only 流程 |
| Standalone | 生产形态验证 | 协议面与 UC 后端调用边界、横向扩展 |
| Sidecar / Gateway | 生产增强形态 | 执行后端解耦、网络异常、上下文透传 |

### 9.2 基础依赖矩阵

| 维度 | 最小覆盖 | 扩展覆盖 |
|---|---|---|
| Metadata DB | H2、PostgreSQL | MySQL 或兼容数据库（如后续支持） |
| Storage | local FS、S3 兼容对象存储 | ADLS、GCS |
| Auth | UC 内部 Bearer、外部 Bearer token exchange、`x-api-key`；校验复用 `server.allowed-issuers`、`server.audiences` | 多 issuer、多 audience |
| Delimiter | `$` | 自定义 delimiter |
| Namespace 深度 | 1 层、2 层、4 层以上 | root + 深层混合 |
| Asset 类型 | table | index/version/tag/transaction |

### 9.3 客户端矩阵

| 客户端类型 | 门禁级别 | 目标 |
|---|---|---|
| Python namespace client | P0 | 每次主干合并必测 |
| Java namespace client | P0 | 每次里程碑发布必测 |
| Rust namespace client | P0 | 每次里程碑发布必测 |
| Spark connector | P1 | 每日或发布前完整回归 |
| Ray connector | P1 | 每日或发布前完整回归 |
| DuckDB | P2 | 每周或发布前 smoke + 关键链路 |
| Pandas / PyArrow | P2 | 每周或发布前 smoke + 关键链路 |

## 10. 测试数据策略

### 10.1 命名空间与路径数据

必须准备以下数据集：

- root namespace
- 单层 namespace
- 两层 namespace
- 四层及以上深层 namespace
- 包含自定义 `delimiter` 的路径样例
- 需要迁移的 legacy 三层表样例

路径相关数据必须额外覆盖：

- `path_key` 只保存 canonical path，不保存 delimiter 语义
- 同一组 `path_segments` 通过 `$`、`.` 等不同 delimiter 表达时，持久化后的 `path_key` 一致
- `root_scope_id` 在 v1 固定映射到 UC `metastore_id`

### 10.2 表与 schema 数据

至少包含：

- 空表
- 窄表
- 宽表
- 带向量列的表
- 带文本列的表
- 支持 schema 演进的表
- 带版本、标签、索引、事务历史的表

表元数据样例必须区分两类存储配置：

- 可长期保存的 `storage_options_template_json`
- 请求时动态下发的临时 `storage_options`

测试数据与断言需要明确：

- 模板字段只保存非敏感配置
- 短期凭证、STS token、session token、过期时间等不落库

### 10.3 安全与租户数据

至少包含：

- 普通用户
- Service principal
- 只读用户
- 拥有 namespace 管理权限但无数据修改权限的用户
- 跨租户或多上下文请求头样例

## 11. 自动化策略

### 11.1 自动化分层

- PR 阶段：
  - 静态检查
  - 单元测试
  - 核心组件测试
  - metadata 级协议 smoke test
- Nightly：
  - Python / Java / Rust 原生客户端兼容
  - Spark / Ray 兼容矩阵
  - Arrow IPC 与执行后端回归
  - legacy bridge 迁移回归
- 发布前：
  - 全量端到端回归
  - 性能与可靠性验证
  - 回滚演练

### 11.2 契约回归

必须建立两类契约回归：

- 上游 Lance OpenAPI 快照差异检查
- UC 暴露行为与官方客户端可识别错误语义的一致性检查

### 11.3 回归守护

以下能力必须纳入长期自动化守护：

- `declare table`
- `get table stats`
- `batch commit`
- version/tag 端点
- token exchange
- `storage_options` / `vend_credentials`
- Spark namespace list 语义

## 12. 非功能测试策略

### 12.1 性能

重点评估：

- namespace 深层路径查找延迟
- metadata-only 请求吞吐
- query / stats 响应时间
- token exchange 延迟
- index build 异步任务吞吐

### 12.2 并发与一致性

重点评估：

- 并发创建同级 namespace 的唯一约束
- 并发 declare / rename / drop table
- 并发 tag/version 更新
- batch commit 幂等性与原子性
- worker 状态更新与 DB 状态一致性

### 12.3 安全

重点评估：

- 未授权访问
- 越权访问
- 过期 token / 错误 issuer / audience
- `x-api-key` 滥用
- context header 篡改
- 临时凭证泄露范围与时效控制
- API key 哈希化存储与 `uc_lance_api_keys` 访问控制
- token exchange 逻辑复用内部 helper，而不是通过 HTTP 回环 `/tokens`

### 12.4 弹性与故障恢复

重点评估：

- worker 不可用
- 对象存储瞬时失败
- 数据库连接抖动
- 异步任务中断后状态恢复
- 幂等重试与错误码一致性

## 13. 准入与准出标准

### 13.1 准入条件

- 需求、设计、接口与 DDL 已冻结到本轮测试范围
- 已明确对应阶段支持的端点与非目标
- 已具备可复现实验环境和最小测试数据
- 已建立基础日志、审计和 trace 能力

### 13.2 准出条件

- 对应阶段测试计划中的 P0 用例全部通过
- 高优先级缺陷全部关闭或已接受风险
- 回归测试无阻塞性失败
- 与阶段范围对应的兼容矩阵达标
- 对外 connector / client 兼容性验收满足四项一致性标准：
  - 配置方式与官方文档一致
  - namespace / table 标识方式与官方文档一致
  - header / auth 透传方式与官方文档一致
  - 失败时返回可被官方客户端正确识别的错误语义
- 已形成可复用自动化基线并纳入持续集成

## 14. 缺陷分级建议

| 级别 | 定义 | 示例 |
|---|---|---|
| S0 | 阻塞发布或数据/安全严重问题 | 越权访问、错误凭证下发、批量提交不原子 |
| S1 | 核心功能不可用 | Python client 无法接入、Arrow IPC 响应损坏 |
| S2 | 重要功能部分异常 | Spark 降级到 `single_level_ns`、legacy bridge 迁移不完整 |
| S3 | 次要功能或体验问题 | 错误消息不友好、非关键元数据字段缺失 |

## 15. 交付物

本项目测试相关交付物至少包括：

- 测试策略文档
- 测试设计文档
- 需求与测试追踪矩阵
- 自动化用例清单
- 阶段测试报告
- 兼容矩阵报告
- 缺陷清单与风险清单

## 16. 最终建议

本项目测试不应只把重点放在“某几个 REST 端点是否返回 200”，而应围绕“协议兼容、治理闭环、模型正确、生态可接入、演进可回归”建立完整质量门禁。

建议以 Python / Java / Rust 原生客户端兼容、Spark / Ray 官方接入面兼容、UC 治理能力不绕过、legacy bridge 可迁移、Arrow IPC 与异步状态一致性五条主线作为长期测试主干。
