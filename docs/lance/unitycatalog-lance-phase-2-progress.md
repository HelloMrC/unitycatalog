# Unity Catalog Lance REST API Phase 2 开发进度

更新日期：2026-05-07

## 1. 文档目标

本文档用于追踪 Lance Phase 2 "Data Plane Compatibility" 的开发进度，记录已完成功能、待完成功能和参考来源。

---

## 2. 已完成功能

### 2.1 设计文档

| 文档 | Commit | 内容 |
|------|--------|------|
| Phase 2 详细设计 | 034837e | 数据面架构、Backend SPI、Table Resolver、Storage Options、Arrow IPC、认证授权、错误处理等完整设计 |
| Phase 2 测试设计 | 034837e | 18 个测试套件、200+ 测试用例、测试分层策略、准入准出标准 |

**参考来源：**
- `docs/lance/unitycatalog-lancedb-phase-2-data-plane-design.md`
- `docs/lance/unitycatalog-lancedb-phase-2-data-plane-test-design.md`

### 2.2 Backend SPI（W2-1）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| LanceExecutionBackend 接口 | 74fe4a4 | Section 7.2 | 10 个 Phase 2 方法定义 |
| DisabledLanceExecutionBackend | 74fe4a4 | Section 7.2 | 未配置时返回 UNIMPLEMENTED |
| LanceExecutionBackendFactory | ec3e6e0 | Section 8.3 | 根据 server property 创建 backend |
| LanceTestEchoExecutionBackend | ec3e6e0, ec4cee3 | - | 测试用的 echo backend fixture |

**参考来源：** 设计文档 Section 7.1-7.2, Section 8.3

### 2.3 Request Mapping（W2-1 部分）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| JSON body 解析 | 1fbc0ba | Section 5.3 | Jackson ObjectMapper 解析 |
| Path id 优先 | 1fbc0ba, ec4cee3 | Section 5.3 | path id 覆盖 body 中的 id |
| Spoofed identity 移除 | 1fbc0ba | Section 5.3 | 移除 id, identity, principal, authType, requestId, context |
| Request id 生成和透传 | 1fbc0ba, ec4cee3 | Section 5.3 | UUID 生成或 header 传递 |
| Principal 透传 | ec4cee3 | Section 5.3 | LanceRequestContext.currentPrincipal() |
| AuthType 识别 | 1fbc0ba | Section 5.3 | Bearer / api_key / anonymous |
| Context header 转换 | 1fbc0ba | Section 5.3 | x-lance-* → camelCase |
| Idempotency key SHA-256 哈希 | 1fbc0ba | Section 13.2 | 不记录明文 |

**参考来源：** 设计文档 Section 5.3

### 2.4 Table Resolver（W2-2 部分）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| LanceTableResolver 类 | ec4cee3 | Section 6.4 | 解析 native 和 legacy table |
| ResolvedLanceTable record | ec4cee3 | Section 6.4 | 包含 tableRef, assetDAO, tableDAO, legacyBridge |
| Native table 解析 | ec4cee3 | Section 6.4 | 通过 path_key 查询 uc_lance_assets |
| Legacy bridge 识别 | ec4cee3 | Section 6.4 | EXTERNAL + TEXT + properties.table_type=lance |
| 状态检查 | ec4cee3 | Section 6.4, 10.2 | ACTIVE/DECLARED/DEREGISTERED/DROPPED |
| LanceIdentifierCodec 集成 | ec4cee3 | Section 6.4 | 使用现有 codec 解析 identifier |

**参考来源：** 设计文档 Section 6.4

### 2.5 Command/Context 结构（W2-2 部分）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| LanceExecutionContext | ec4cee3 | Section 7.3 | requestId, principal, authType, deadlineMs, lanceContext, idempotencyKeyHash |
| LanceTableRef | ec4cee3 + 本次小步 | Section 7.3 | id, namespacePath, tableName, pathKey, storageLocation, tableUri, currentVersion, declaredOnly, legacyBridge |
| LanceStorageBinding | ec4cee3 + 本次小步 | Section 7.3 / 6.5 | uri/storageLocation, tableUri, storageOptions, storageOptionsTemplate, vendCredentials, expiresAtMillis |
| LanceExecutionCommand 重构 | ec4cee3 | Section 7.4 | operation, context, table, storage, attributes |

**参考来源：** 设计文档 Section 7.3, Section 7.4

### 2.6 Storage Options（W2-2 部分）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| LanceStorageOptionsService | ec4cee3 | Section 6.5 | 模板加载服务 |
| Template 解析 | ec4cee3 | Section 6.5 | 从 storage_options_template_json 解析 |
| 敏感字段过滤 | ec4cee3 | Section 6.5 | 过滤 token, session, secret, expires, access_key |
| Command storage binding 字段 | 本次小步 | Section 6.5 | 将清理后的模板同时填充到 storageOptions 和 storageOptionsTemplate，并暴露 tableUri/vendCredentials/expiresAtMillis |

**参考来源：** 设计文档 Section 6.5

### 2.7 Data Plane 编排（W2-3 部分）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| LanceDataPlaneService | ec4cee3 | Section 6.3 | 编排 resolver → storage → backend |
| LanceRestTableDataService 重构 | ec4cee3 | Section 6.2 | 使用 data plane service |
| 状态验证集成 | ec4cee3 | Section 4.3-4.6 | DECLARED 读写验证、legacy 验证 |
| Legacy read enable flag | ec4cee3 | Section 2.4 | x-lance-legacy-read-enabled header |

**参考来源：** 设计文档 Section 6.3

### 2.8 测试骨架（W2-3 部分）

| 功能 | Commit | 测试设计章节 | 说明 |
|------|--------|--------------|------|
| BaseLancePhase2RestTest | 0551346 | - | 基类 + fixture helpers |
| P2-CONTRACT 测试 | 0551346 | Section 9.1 | OpenAPI diff、路由挂载、Method 校验 |
| P2-REQ 测试 | 0551346 | Section 9.2 | Request parsing、identity 不可信、delimiter |
| P2-BACKEND 测试 | 0551346 | Section 9.3 | SPI 接口、command 字段完整性 |
| P2-RESOLVE 测试 | 0551346 | Section 9.4 | Table resolver、state validation、legacy 边界 |
| P2-ARROW 测试 | 0551346 | Section 9.5 | Arrow IPC request/response |
| P2-DATA-READ 测试 | 0551346 | Section 9.6 | Query、count、stats、explain、analyze |
| P2-DATA-WRITE 测试 | 0551346 | Section 9.7 | Insert、merge、update、delete、create |
| P2-META 测试 | 0551346 | Section 9.8 | Metadata 状态更新 |
| P2-STORAGE 测试 | 0551346 | Section 9.9 | Storage options、credential vending |
| P2-AUTH 测试 | 0551346 | Section 9.10 | 认证、授权、审计 |
| P2-ERROR 测试 | 0551346 | Section 9.11 | 错误模型 |
| P2-REG 测试 | 0551346 | Section 9.18 | Phase 1 + UC 回归 |
| P2-WORKER 测试 | 0551346 | Section 9.11 | Worker HTTP backend |
| P2-CONC 测试 | 0551346 | Section 9.13 | 并发、idempotency |
| P2-CLIENT 测试 | 0551346 | Section 9.14 | Python/Java/Rust client smoke |
| P2-SPARK 测试 | 0551346 | Section 9.15 | Spark connector smoke |
| P2-RAY 测试 | 0551346 | Section 9.16 | Ray connector smoke |
| P2-LOCAL 测试 | 0551346 | Section 9.17 | DuckDB/Pandas/PyArrow smoke |
| OpenAPI baseline | 0551346 | Section 3.1 | Phase 2 Lance REST OpenAPI 快照 |

**参考来源：** 测试设计文档 Section 9

### 2.9 Data Plane Authorization（W2-7 部分）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| LanceDataPlaneAuthorizer | 本次工作 | Section 11.2-11.3 | 在 data plane service 内检查 READ_DATA/WRITE_DATA 边界 |
| 兼容权限映射 | 本次工作 | Section 11.2 | READ_DATA → SELECT/READ_METADATA，WRITE_DATA → MODIFY |
| ServerProperties 注入 | 本次工作 | Section 6.2 | LanceRestTableDataService 接收 authorizer 和 serverProperties |
| 授权测试 | 本次工作 | Section 9.10 | 新增 authorizer unit test + REST owner allow/deny 集成测试 |

**参考来源：** 设计文档 Section 11.2-11.3，测试设计文档 Section 9.10

### 2.10 Request Validation（W2-3 / W2-4 部分）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| Content-Type validation | 本次工作 | Section 5.2-5.3 | JSON endpoint 仅接受 JSON，Arrow write endpoint 仅接受 Arrow stream |
| Request size limits | 本次工作 | Section 8.3, 12.1 | `lance.execution.max-json-request-bytes` 和 `max-arrow-request-bytes` 超限返回 413 |
| Lance protocol error shape | 本次工作 | Section 12.1 | 为 413/415 返回稳定 Lance error shape，避免扩散到全局 UC ErrorCode |
| 入口校验测试 | 本次工作 | Section 9.2, 9.5 | 新增 enabled REST tests 覆盖 415/413 且 backend 不被调用 |

**参考来源：** 设计文档 Section 5.2-5.3, 8.3, 12.1，测试设计文档 Section 9.2, 9.5

### 2.11 Lance Table Metadata Repository（W2-6 部分）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| markTableMaterialized | 本次工作 | Section 10.3 | 同事务推进 asset ACTIVE 和 table `is_only_declared=false`，并回填 version/schema/stats |
| updateTableExecutionMetadata | 本次工作 | Section 10.3 | 写成功后更新 current_version/schema/stats，null schema 不覆盖既有 schema |
| updateTableStats | 本次工作 | Section 10.3 | stats endpoint 成功后刷新 stats cache |
| Repository tests | 本次工作 | Section 9.8 | 新增 repository 单测覆盖 P2-META-002/003/004/005 的核心持久化规则 |
| Data plane metadata updater | 本次工作 | Section 10.2-10.4 | backend write/stats 成功后调用 repository 更新 metadata cache |
| REST metadata update tests | 本次工作 | Section 9.8 | 新增 enabled REST tests 验证 declared materialization、write metadata、stats cache |

**参考来源：** 设计文档 Section 10.2-10.4，测试设计文档 Section 9.8

### 2.12 Legacy Read Configuration（W2-3 设计一致性）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| server property 开关 | 本次小步 | Section 8.3 | 新增 `lance.execution.legacy-read-enabled`，默认 `false` |
| header 绕过移除 | 本次小步 | Section 6.3, 8.3 | legacy bridge read 不再通过 `x-lance-legacy-read-enabled` 请求头临时开启 |
| Legacy read REST tests | 本次小步 | Section 9.4 | 新增 enabled REST tests 覆盖默认拒绝和配置开启后 backend 可达 |

**参考来源：** 设计文档 Section 6.3, 8.3，测试设计文档 Section 9.4

### 2.13 LanceTableRef tableUri 补齐（W2-2 设计一致性）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| Native tableUri 透传 | 本次小步 | Section 7.3 | `LanceTableResolver` 从 `LanceTableDAO.tableUri` 填充 `LanceTableRef.tableUri` |
| Legacy tableUri 兼容 | 本次小步 | Section 7.3 | legacy bridge 使用 UC table storage location 作为兼容 tableUri |
| Command echo coverage | 本次小步 | Section 9.3-9.4 | enabled REST tests 验证 command table 中包含 tableUri |

**参考来源：** 设计文档 Section 7.3，测试设计文档 Section 9.3-9.4

### 2.14 LanceStorageBinding 字段补齐（W2-2 设计一致性）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| storageLocation/tableUri 透传 | 本次小步 | Section 6.5 / 7.3 | 后端 command storage binding 同时保留兼容 `uri` 和设计字段 `storageLocation`、`tableUri` |
| 模板字段双写 | 本次小步 | Section 6.5 | 当前未接入 runtime credential vending 前，使用清理后的 template 填充 `storageOptions` 与 `storageOptionsTemplate` |
| 凭证占位字段 | 本次小步 | Section 6.5 | 显式暴露 `vendCredentials=false`、`expiresAtMillis=0`，为后续 Runtime Credential Vending 接入预留稳定 contract |
| Command echo coverage | 本次小步 | Section 9.3 / 9.9 | enabled REST tests 验证 storage binding 字段和敏感模板过滤 |

**参考来源：** 设计文档 Section 6.5, Section 7.3，测试设计文档 Section 9.3, Section 9.9

### 2.15 Deadline/Timeout command contract（W2-5 部分）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| timeout server properties | 本次小步 | Section 8.3 | 新增 `lance.execution.request-timeout-ms`、`query-timeout-ms`、`write-timeout-ms`，均为正整数 |
| deadline header override | 本次小步 | Section 8.3 | `x-lance-deadline-ms` 优先填充 `LanceExecutionContext.deadlineMs`，非法值返回 Lance error shape |
| operation 默认 deadline | 本次小步 | Section 8.3 | query/count/plan 使用 query timeout，stats 使用 request timeout，write/create 使用 write timeout |
| Worker header echo coverage | 本次小步 | Section 9.3 / 9.11 | enabled REST tests 验证 requestId/deadline/idempotency hash contract，不透传明文 idempotency key |

**参考来源：** 设计文档 Section 8.3，测试设计文档 Section 9.3, Section 9.11

### 2.16 Backend committed metadata failure（W2-6 部分）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| metadata update failure wrapper | 本次小步 | Section 10.4 | backend write 成功后，UC metadata 更新失败时返回稳定 Lance error |
| backend_committed marker | 本次小步 | Section 10.4 | 错误响应包含 `backend_committed=true` 和 `reconcileRequired=true`，避免误导客户端重试为纯 backend 失败 |
| 写路径限定 | 本次小步 | Section 10.4 | 仅包裹 write/create/merge/update/delete 的 metadata success path，stats cache 更新不标记 backend committed |
| Failure REST coverage | 本次小步 | Section 9.8 / 9.11 | 新增 enabled REST test 使用 test backend 触发 metadata 更新失败，验证错误语义和 UC version 未推进 |

**参考来源：** 设计文档 Section 10.4，测试设计文档 Section 9.8, Section 9.11

### 2.17 Version 防倒退（W2-6 一致性）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| current_version guard | 本次小步 | Section 13.1 | backend 返回 version 小于当前 `current_version` 时，不覆盖 UC metadata |
| warning response | 本次小步 | Section 13.1 | response 标记 `metadataVersionUpdated=false` 并返回 version warning |
| stale metadata 保护 | 本次小步 | Section 13.1 | 防倒退触发时 schema/stats 也不使用旧 version 的结果回写 |
| Regression coverage | 本次小步 | Section 9.7 / 9.13 | enabled REST test 验证 current_version/schema/stats 不倒退 |

**参考来源：** 设计文档 Section 13.1，测试设计文档 Section 9.7, Section 9.13

### 2.18 Authorization skeleton 拆分启用（W2-7 高优先级）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| P2-AUTH-005 read allow | 本次小步 | Section 11.2-11.3 | enabled REST test 验证 READ_DATA 读路径允许 |
| P2-AUTH-006 read deny | 本次小步 | Section 11.2-11.3 | enabled REST test 验证非授权 principal 读路径 403 且 backend 不执行 |
| P2-AUTH-007 write allow | 本次小步 | Section 11.2-11.3 | enabled REST test 验证 WRITE_DATA 写路径允许 |
| P2-AUTH-008 write deny | 本次小步 | Section 11.2-11.3 | enabled REST test 验证 READ_DATA/非 owner principal 不能执行写路径 |

**参考来源：** 设计文档 Section 11.2-11.3，测试设计文档 Section 9.10

### 2.19 Arrow IPC Request Reader（W2-4 高优先级）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| LanceArrowRequestReader | 本次小步 | Section 9.5 | 将 Arrow write 请求解析为稳定 command metadata，而不是散落在 REST service 中 |
| stream passthrough metadata | 本次小步 | Section 9.5 | command 暴露 inputData/inputBytes/inputMediaType/requestBufferedBytes/streamPassedThrough |
| schema peek contract | 本次小步 | Section 9.5 | 默认不 peek；启用时标记 schemaPeeked=true 且 recordBatchesParsedByUc=0 |
| P2-ARROW request coverage | 本次小步 | Section 9.5 | 新增 enabled REST tests 覆盖 P2-ARROW-001~006、010~011 |

**参考来源：** 设计文档 Section 9.5，测试设计文档 Section 9.5

### 2.20 Arrow IPC Response Writer（W2-4 高优先级）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| LanceArrowResponseWriter | 本次小步 | Section 4.1 / 9.5 | query endpoint 可根据 Accept 写出 Arrow file/stream 响应 |
| Arrow file negotiation | 本次小步 | Section 9.5 | `Accept=application/vnd.apache.arrow.file` 返回 Arrow file content type |
| Arrow stream negotiation | 本次小步 | Section 9.5 | `Accept=application/vnd.apache.arrow.stream` 返回 Arrow stream content type |
| JSON fallback | 本次小步 | Section 9.2 / 9.3 | 显式 `Accept=application/json` 时保留 JSON command echo，便于 request mapping 覆盖 |
| Response coverage | 本次小步 | Section 9.5 / 9.6 | 新增 enabled REST tests 覆盖 P2-DATA-001、P2-ARROW-007~009 |

**参考来源：** 设计文档 Section 4.1, Section 9.5，测试设计文档 Section 9.5, Section 9.6

### 2.21 Reconcile API（W2-6 收口）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| Admin reconcile endpoint | 本次小步 | Section 10.4 | 新增 `/admin/reconcile`，用于 backend committed 后的 metadata 修复计划和受控回填 |
| dry-run plan | 本次小步 | Section 10.4 | `dry_run=true` 返回 current_version、arrow_schema_json、stats_json 回填计划，不修改 UC metadata |
| controlled backfill | 本次小步 | Section 10.4 | `dry_run=false` 使用请求中的 version/schema/stats 受控更新 UC metadata |
| Reconcile REST coverage | 本次小步 | Section 9.8 | 新增 enabled REST tests 覆盖 P2-META-009/010 dry-run 与 backfill |

**参考来源：** 设计文档 Section 10.4，测试设计文档 Section 9.8

### 2.22 Data Plane Audit/Metrics（W2-7 收口）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| data audit payload | 本次小步 | Section 12.3 | data plane 成功响应包含 operation、principal、table、version、bytes、rows、status 等 audit 字段 |
| backend failure audit | 本次小步 | Section 12.3 | backend timeout/error 响应包含 errorCode、backendRequestId、status 和 latency |
| audit redaction guard | 本次小步 | Section 12.3 / 14.2 | audit 只记录 storage scheme，不记录 runtime credential/header secret 原文 |
| Lance metrics endpoint | 本次小步 | Section 12.4 | 新增 `/metrics` 暴露 lance_data_* operation/status/backend label 计数和 latency/bytes 观测值 |
| Observability REST coverage | 本次小步 | Section 9.10 | 新增 enabled REST tests 覆盖 P2-AUTH-011~014 audit/metrics |

**参考来源：** 设计文档 Section 12.3, Section 12.4，测试设计文档 Section 9.10

### 2.23 JSON scalar response alignment（W2-3 协议一致性）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| count_rows scalar response | 本次小步 | Section 5.4 | `count_rows` 成功响应不再返回 command wrapper，而是直接返回 JSON integer body |
| explain/analyze scalar response | 本次小步 | Section 5.4 | `explain_plan` / `analyze_plan` 成功响应直接返回 JSON string body |
| scalar response coverage | 本次小步 | Section 9.6 | 新增 enabled REST tests 覆盖 P2-DATA-009、011、012 |
| legacy audit guard | 本次小步 | Section 12.3 | legacy bridge read 的 audit 允许 tableAssetId 为空，避免非 native Lance asset 触发 NPE |
| disabled backend contract fix | 本次小步 | Section 8.3 / 12.1 | disabled backend 覆盖先解析存在的表，并按 JSON/Arrow endpoint 的真实 media type 调用 |

**参考来源：** 设计文档 Section 5.4, Section 12.3，测试设计文档 Section 9.6, Section 9.12

### 2.24 Error response request identifiers（W2-3 协议一致性）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| error requestId body/header | 本次小步 | Section 12.1 | Lance 错误响应统一返回 `requestId`，并在响应 header 中回传 `x-request-id` |
| generated requestId for pre-context errors | 本次小步 | Section 5.3 / 12.1 | Content-Type/body size 等进入 execution context 前的错误也会生成稳定 requestId |
| backend_request_id top-level field | 本次小步 | Section 12.2 | backend timeout/error 从 audit 中提升 `backend_request_id`，便于客户端和排障系统识别 worker 请求 |
| error response REST coverage | 本次小步 | Section 9.12 | 新增 enabled REST tests 覆盖 P2-ERROR-015 requestId 与 backend_request_id 合同 |

**参考来源：** 设计文档 Section 12.1, Section 12.2，测试设计文档 Section 9.12

---

## 3. 待完成功能

### 3.1 高优先级（阻塞测试启用）

高优先级阻塞项已完成；剩余工作见中优先级、Worker Backend 和 Ecosystem Smoke。

### 3.2 中优先级（影响部分测试）

| 功能 | 设计章节 | 测试阻塞 | 说明 |
|------|----------|----------|------|
| **Runtime Credential Vending** | Section 6.5 | P2-STORAGE-002-003 | UC StorageCredentialVendor 集成，生成临时凭证 |

### 3.3 Worker Backend（W2-5）

| 功能 | 设计章节 | 测试阻塞 | 说明 |
|------|----------|----------|------|
| WorkerHttpLanceExecutionBackend | Section 8.1-8.2 | P2-WORKER-* | HTTP worker 实现 |
| Worker 内部 API | Section 8.2 | - | JSON command path, Arrow command path |
| Timeout/Retry | Section 8.4 | P2-WORKER-008-010 | Read retry bounded, write no retry |
| Worker error envelope | Section 12.2 | P2-WORKER-007 | 映射 worker error 到 Lance error shape |

### 3.4 Ecosystem Smoke（W2-8）

| 功能 | 测试设计章节 | 说明 |
|------|--------------|------|
| Python client smoke | Section 9.14 | 需要 real worker 环境 |
| Spark connector smoke | Section 9.15 | 需要 real worker 环境 |
| Ray connector smoke | Section 9.16 | 需要 real worker 环境 |
| DuckDB/Pandas/PyArrow | Section 9.17 | UC metadata/credential resolution 验证 |

---

## 4. 实施任务进度

按照设计文档 Section 15 的任务拆解：

| 任务 | 设计编号 | 状态 | Commit |
|------|----------|------|--------|
| W2-0: 准备与收口 | 15.1 | ✅ 完成 | 74fe4a4 |
| W2-1: Backend SPI | 15.2 | ✅ 完成 | 74fe4a4, ec3e6e0, 1fbc0ba |
| W2-2: Resolver + Storage | 15.3 | ⚠️ 部分 | ec4cee3 + 本次小步（Resolver/TableRef tableUri 完成，Storage 缺 credential vending） |
| W2-3: Data endpoint service | 15.4 | ✅ 高优先级完成 | ec4cee3 + 本次工作（架构/Authorization/入口校验/metadata success path/legacy read 配置/Arrow response/JSON scalar response/error requestId 完成） |
| W2-4: Arrow IPC | 15.5 | ✅ 高优先级完成 | 本次工作（media type/size limit + request reader + response writer 完成） |
| W2-5: Worker HTTP backend | 15.6 | ⚠️ 部分 | 本次小步（deadline/requestId/idempotency command contract 完成，缺 WorkerHttpLanceExecutionBackend） |
| W2-6: Metadata 状态推进 | 15.7 | ✅ 完成 | 本次工作（Repository methods + data plane success path + backend_committed marker + version 防倒退 + reconcile 完成） |
| W2-7: 授权、审计、观测 | 15.8 | ✅ 完成 | 本次工作（授权 + audit/metrics + P2-AUTH-005-008、011-014 enabled 覆盖完成） |
| W2-8: Connector 回归 | 15.9 | ❌ 未开始 | - |

---

## 5. 测试启用状态

| 测试类 | 测试数 | 状态 | 启用条件 |
|--------|--------|------|----------|
| LancePhase2ContractAndRequestRestTest | ~20 | @Disabled | Arrow handler, auth integration |
| LancePhase2BackendAndResolverRestTest | ~20 | @Disabled | Backend SPI complete（Resolver 已完成） |
| LancePhase2ArrowRestTest | 11 | @Disabled | LanceArrowRequestReader/ResponseWriter |
| LancePhase2DataReadRestTest | 13 | @Disabled | Query Arrow response |
| LancePhase2DataWriteRestTest | 11 | @Disabled | LanceTableRepository methods |
| LancePhase2MetadataAndStorageRestTest | 18 | @Disabled | Repository methods, credential vending |
| LancePhase2AuthGovernanceRestTest | 17 | @Disabled | Audit/Metrics，完整 grant 路径拆分后可部分启用 |
| LancePhase2RequestMappingRestTest | 7 | Enabled | request mapping + storage/deadline contract + header spoofing guard |
| LancePhase2DataPlaneAuthorizationRestTest | 4 | Enabled | P2-AUTH-005-008 read/write allow-deny 覆盖 |
| LanceDataPlaneAuthorizerTest | 3 | Enabled | READ_DATA/WRITE_DATA 兼容权限映射 |
| LancePhase2RequestValidationRestTest | 4 | Enabled | Content-Type 415 + JSON/Arrow size 413 |
| LancePhase2ArrowRequestReaderRestTest | 8 | Enabled | P2-ARROW-001~006、010~011 request reader 覆盖 |
| LancePhase2ArrowResponseWriterRestTest | 5 | Enabled | P2-DATA-001、P2-ARROW-007~009 response writer 覆盖 |
| LancePhase2DataPlaneMetadataUpdateRestTest | 4 | Enabled | declared materialization + write/stats metadata cache + version 防倒退 |
| LancePhase2LegacyReadConfigRestTest | 1 | Enabled | server property enables legacy bridge read |
| LancePhase2BackendCommittedFailureRestTest | 1 | Enabled | metadata update failure returns backend_committed marker |
| LancePhase2ReconcileRestTest | 2 | Enabled | P2-META-009/010 reconcile dry-run/backfill |
| LancePhase2ObservabilityRestTest | 4 | Enabled | P2-AUTH-011~014 audit success/failure/redaction + metrics |
| LancePhase2ScalarResponseRestTest | 3 | Enabled | P2-DATA-009、011、012 count/explain/analyze scalar response |
| LancePhase2ErrorResponseContractRestTest | 3 | Enabled | P2-ERROR-015 requestId/backend_request_id 合同覆盖 |
| LancePhase2ErrorAndRegressionRestTest | ~28 | @Disabled | Error handling, regression |
| LancePhase2WorkerAndResilienceRestTest | 16 | @Disabled | WorkerHttpLanceExecutionBackend |
| LancePhase2EcosystemSmokeTest | ~40 | @Disabled | Real worker + connectors |

---

## 6. Commit 历史

| Commit | 日期 | 描述 |
|--------|------|------|
| 0551346 | 2026-05-06 | Add Lance Phase 2 test skeleton with 200+ test cases |
| ec4cee3 | 2026-05-06 | Add Lance Phase 2 data plane service architecture |
| 034837e | 2026-05-06 | Add Lance Phase 2 design and test design documents |
| 1fbc0ba | 2026-04-28 | Map Lance data requests into backend commands |
| ec3e6e0 | 2026-04-28 | Make Lance execution backend configurable |
| 74fe4a4 | 2026-04-28 | Add Lance Phase 2 data plane backend gate |

---

## 7. 参考文档

| 文档 | 路径 | 用途 |
|------|------|------|
| Phase 2 详细设计 | `docs/lance/unitycatalog-lancedb-phase-2-data-plane-design.md` | 架构、模块、接口设计 |
| Phase 2 测试设计 | `docs/lance/unitycatalog-lancedb-phase-2-data-plane-test-design.md` | 测试用例、准入准出 |
| Phase 1 详细设计 | `docs/lance/unitycatalog-lancedb-phase-1-metadata-design.md` | Phase 1 基础（已完成） |
| Phase 1 测试设计 | `docs/lance/unitycatalog-lancedb-phase-1-metadata-test-design.md` | Phase 1 测试基线 |
| REST API 技术设计 | `docs/lance/unitycatalog-lancedb-rest-api-technical-design.md` | 协议总体设计 |
| 需求文档 | `docs/lance/unitycatalog-lancedb-rest-api-requirements.md` | Lance REST 需求 |

---

## 8. 下一步建议

### 8.1 立即可做（不依赖外部环境）

1. **Runtime credential vending** - StorageCredentialVendor mock

### 8.2 需要测试 fixture

2. **WorkerHttpLanceExecutionBackend** - HTTP 调用
3. **Ecosystem smoke** - Python/Spark/Ray

---

## 9. 准出标准检查

按照设计文档 Section 16：

| 标准 | 状态 |
|------|------|
| 所有 Phase 2 必做 endpoint 可用 | ⚠️ fake backend 路径可用，real worker 未接入 |
| query 返回 Arrow IPC 且可被客户端消费 | ⚠️ fake backend Arrow response writer 就绪，真实 IPC/客户端消费待 real worker |
| insert/merge/update/delete 通过真实 worker 执行 | ❌ 无 real worker |
| declared-only table 可首次物理化 | ✅ fake backend success path 已推进 ACTIVE |
| stats/count 和 query/DML 结果一致 | ⚠️ fake backend scalar 响应形状对齐，real worker 一致性待接入 |
| data endpoint 认证、授权、审计可验证 | ✅ P2-AUTH-005-008、011-014 enabled 覆盖 |
| runtime storage credentials 不落库、不进日志 | ⚠️ 模板过滤就绪，credential vending 缺失 |
| backend 未配置、backend 超时、worker 错误有稳定错误语义 | ⚠️ disabled backend + fake backend timeout/error audit 就绪，real worker retry 缺失 |
| data endpoint media type 和 request size 错误稳定 | ✅ 415/413 Lance error shape 就绪 |
| Phase 1 metadata endpoint 回归通过 | ✅ Phase 1 tests passing |
| UC 原有路由回归通过 | ✅ Phase 1 regression tests passing |
| Spark/Ray/Python 至少完成 P1 smoke | ❌ Nightly 层，需要 real worker |
