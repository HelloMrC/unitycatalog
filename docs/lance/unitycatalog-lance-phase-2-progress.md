# Unity Catalog Lance REST API Phase 2 开发进度

更新日期：2026-05-11

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
| Command reserved field 防伪造 | 本次小步 | Section 5.3 / 8.2 | 用户 JSON/attributes 不能覆盖 UC 生成的 operation、requestId、tableId、pathKey、workerBaseUrl 等 command 保留字段 |
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
| buffered boundary metadata | Add Lance Arrow buffered boundary metadata | Section 9.5 / 13.3 | 当前 UC 入口仍为聚合请求，command 明确暴露 requestBufferedBytes、streamPassedThrough=false、ucRequestMode=aggregated，避免误判为真实 streaming |
| worker-http streaming pass-through | 本次小步 | Section 9.5 / 13.3 | worker-http Arrow 写入路径使用 Armeria `HttpRequest` publisher 透传 body，metadata 暴露 requestBufferedBytes=0、streamPassedThrough=true、ucRequestMode=streaming；非 worker 兼容路径仍保留聚合模式 |
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
| failure backend labels | Add Lance backend failure labels | Section 12.3 / 12.4 | backend failure audit/metrics 使用异常来源 backendType，避免 worker-http 失败被误标为 test-echo 或 unknown |
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

### 2.25 Runtime Credential Vending mock（W2-2 Storage 收口）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| runtime credential merge | 本次小步 | Section 6.5 | `x-lance-fake-runtime-credential` 模拟 StorageCredentialVendor 输出，并合并到 data-plane `storageOptions` |
| runtime overrides template | 本次小步 | Section 6.5 | runtime storage options 覆盖同名 template 字段，template 原值仍保留在 `storageOptionsTemplate` |
| credential expiry/denied errors | 本次小步 | Section 12.1 | mock expired/denied credential 在 backend 前返回受控 Lance error shape |
| no persistence/redaction guard | 本次小步 | Section 9.9 / 14.2 | runtime credential 不写回 describe/template，scalar client response 不暴露 token/session |
| storage credential REST coverage | 本次小步 | Section 9.9 | 新增 enabled REST tests 覆盖 P2-STORAGE-001~007 与 P2-META-008 |

**参考来源：** 设计文档 Section 6.5, Section 12.1，测试设计文档 Section 9.9

### 2.26 Worker HTTP backend 首批接入（W2-5 协议层）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| WorkerHttpLanceExecutionBackend | Add Lance worker HTTP backend | Section 8.1-8.3 | 新增 `worker-http` backend type，通过 server property 配置 worker base URL |
| internal worker path routing | Add Lance worker HTTP backend | Section 8.2 | JSON command 转发到 `/internal/lance/v1/commands/*`，Arrow command 转发到 `/internal/lance/v1/arrow/*` |
| worker header propagation | Add Lance worker HTTP backend | Section 8.3 / 13.2 | 转发 `x-request-id`、deadline 与 idempotency hash，不转发明文 idempotency key |
| worker error envelope mapping | Add Lance worker HTTP backend | Section 12.2 | worker error JSON 映射为 Lance error shape，并提升 `backend_request_id` |
| worker non-JSON error fallback | Add Lance worker error fallback | Section 12.2 | worker 返回非 JSON 错误体时保留 worker HTTP 状态，回退为稳定 `worker_error` Lance error shape |
| fake worker HTTP coverage | Add Lance worker HTTP backend | Section 9.11 | 新增 enabled REST tests 覆盖 P2-WORKER-001/002/004/005/006/007/008 |
| worker retry semantics | Add Lance worker retry semantics | Section 8.4 | read 503 最多自动重试一次并写入 retry audit；write 503-after-body 不自动重试 |
| worker client timeout | Add Lance worker client timeout | Section 8.4 / 12.2 | worker HTTP 调用使用 command deadlineMs 作为 Armeria response/write timeout，客户端侧超时映射为 `backend_timeout` 504 |
| worker health probe | Add Lance worker health probe + 本次小步 | Section 8.3 / 8.4 | `/admin/worker/health` 按 server property 配置的 worker health path 探测 success/failure，并覆盖自定义 health path |
| worker command reserved field guard | 本次小步 | Section 8.2 / 13.2 | Worker JSON command payload 由 UC 生成字段最终覆盖用户 attributes，防止伪造 operation/table/requestId/workerBaseUrl |
| Arrow body handoff | Add Lance worker Arrow body handoff + 本次小步 | Section 8.2 / 13.3 | Arrow command 使用 metadata headers + streaming Arrow body 转发到 worker；metadata 明确传递 ucRequestMode=streaming 且不包含 Arrow body，worker 已验证收到原始 body bytes |
| Arrow response handoff | Add Lance worker Arrow response handoff | Section 4.1 / 13.3 | worker query 返回 Arrow IPC body 时，UC 保留 media type/body 并交给 `LanceArrowResponseWriter` 返回客户端 |
| worker unavailable mapping | Add Lance worker unavailable mapping | Section 12.2 | worker 连接失败/无响应时收敛为稳定 `worker_unavailable` 503 Lance error shape，并写入 failure audit |
| minimal real worker E2E fixture | 本次小步 | Section 8.1-8.4 / 10.3 | 新增有状态 HTTP worker fixture，跑通 UC worker-http → declared insert 物理化 → metadata 回写 → query Arrow → count/stats 一致的最小闭环 |
| unknown-length Arrow runtime limit | 8c9f6b9 | Section 8.3 / 13.3 | 不带 Content-Length 的 chunked Arrow 写入超过 `lance.execution.max-arrow-request-bytes` 时，UC 中断 stream 并返回稳定 413/受控失败语义 |
| streaming worker failure cleanup | 888afae | Section 8.4 / 13.3 | worker 在 streaming body 期间返回 503、断连或超时时，写请求不重试，上游 body 被关闭，audit/metrics 稳定 |
| stateful worker write-chain E2E | c5086fa | Section 10.3 | 最小 HTTP worker fixture 覆盖 active insert、merge_insert、update、delete 的 metadata version/stats 推进 |
| real LanceDB worker process | db9fb41, 496291d | Section 8.1-8.4 / 10.3 | 新增 Python HTTP worker 进程，底层使用真实 `lancedb` + `pyarrow`，覆盖 query/count/stats/insert/create/merge_insert/update/delete 与 metadata 回写 |
| protocol client smokes | 56fe8f6, 2495350, 975c3b2 | Section 9.14 | raw HTTP 覆盖 10 个 data endpoint；Python/Java/Rust 最小协议客户端可消费真实 worker Arrow/JSON 响应 |

**当前边界：** 已打通 UC 到 HTTP worker 的命令协议，并覆盖 health、read retry / write no-retry、非 JSON 错误 fallback、连接失败 503、Arrow request streaming body handoff、Arrow query response handoff、unknown-length runtime limit、streaming worker failure cleanup、有状态 worker write 链路，以及真实 LanceDB worker process 的 query/count/stats/insert/create/merge_insert/update/delete。剩余重点已经从 UC 协议层转向官方客户端/生态 connector、真实 sidecar/nightly 环境和发布前矩阵。

**参考来源：** 设计文档 Section 8.1-8.4, Section 12.2，测试设计文档 Section 9.11

---

## 3. 剩余任务（按当前代码状态）

### 3.1 Worker / Sidecar Nightly 收口

| 任务 | 对应设计/测试 | 当前状态 | 剩余工作 | 完成标准 |
|------|---------------|----------|----------|----------|
| 真实 LanceDB worker sidecar 化 | Section 8.1-8.4；P2-WORKER-E2E | 测试资源中已有 `real_lancedb_worker.py`，可通过 `LanceRealLanceDbWorkerProcessFixture` 启动真实 `lancedb` 进程 | 把当前 test-only Python 进程沉淀为可重复 nightly fixture/sidecar：依赖安装、启动参数、health path、临时目录清理、日志输出和 skip 条件都要文档化 | CI/nightly 能在无人工准备的环境中启动 worker，稳定跑过真实 worker E2E |
| real worker explain/analyze 决策 | P2-DATA-08/09；P2-CLIENT-001 | raw HTTP smoke 已通过最小有状态 worker 覆盖 10 个 endpoint；真实 LanceDB worker process 目前覆盖 query/count/stats/insert/create/merge_insert/update/delete | 明确 explain_plan/analyze_plan 在真实 LanceDB worker 的 Phase 2 行为：实现真实响应、代理为 worker 支持能力，或返回稳定 `UNIMPLEMENTED` 并补对应测试 | 10 个 data endpoint 在真实 worker/sidecar 下均有明确、可回归的成功或受控不支持语义 |
| 真实 worker resilience/backpressure | P2-ARROW-012；P2-WORKER-011~013；P2-CONC-005 | fake worker 已覆盖 unknown-length chunked 超限、503/断连/超时期间关闭 upstream body、不重试写请求 | 将同类压力路径补到真实 worker/sidecar 或 nightly fault worker，覆盖大 Arrow stream、慢消费者、worker 中途失败和 audit/metrics 证据 | 真实 worker/nightly 层证明 UC 不聚合大 body、不泄漏 upstream、失败语义稳定 |
| 并发与幂等完整回归 | P2-CONC-001~006；P2-META-007 | 已有 version 防倒退、write no-retry、runtime limit 等局部覆盖；早期并发 skeleton 仍禁用 | 启用或重写并发 query、active 并发写、declared 首次物理化并发、idempotency hash 和 response-stream 中断测试 | 并发测试可在 PR 或 nightly 稳定运行，UC metadata version 不倒退，重复/竞争写有明确语义 |
| 发布前数据库/环境矩阵 | Section 10.5 | H2 + local FS 为主；PostgreSQL/S3 兼容路径尚未形成可重复证据 | 增加 PostgreSQL metadata DB、local FS + 至少一个 S3-compatible object store、credential expiry/denied 的发布前执行记录 | 发布前报告含 DB/storage/backend 组合矩阵与失败排查日志 |

### 3.2 Client / SDK 兼容性

| 任务 | 对应测试 | 当前状态 | 剩余工作 | 完成标准 |
|------|----------|----------|----------|----------|
| 官方 Python LanceDB client 接入 | P2-CLIENT-002 | 已有 `python_client_smoke.py`，但它是 test-only `urllib` + `pyarrow` 最小协议客户端，不是官方 LanceDB SDK/UC Client | 调研并接入官方 Python LanceDB remote/client 配置；若 upstream client 不能直接指向 UC Lance REST，需要实现兼容 adapter 或明确 UC Client wrapper 方案 | 使用官方/目标 Python client 形态完成 connect/query/count/insert，且能消费 UC 返回的 Arrow/JSON |
| Python vector/filter/withRowId | P2-CLIENT-003 | 仍在 `LancePhase2EcosystemSmokeTest` 中禁用 | 准备含 vector 字段的真实 Lance table，覆盖 vector query、filter、columns、withRowId/row id 和结果 shape | Python 客户端可读取距离/row id/列选择等结果，错误语义可识别 |
| Java 官方客户端或 UC generated client | P2-CLIENT-004 | 已有 JDK `HttpClient` 最小协议 smoke，可 query/count/stats；不是官方 LanceDB Java SDK，也不是 UC generated client data API | 决定 Java 面向对象：补 UC OpenAPI/generated client 的 Lance data endpoint，或接入官方 LanceDB Java remote/namespace client 的兼容配置 | Java 官方/目标客户端能通过 UC Lance REST 完成 query/count/stats，auth/header/table id 方式稳定 |
| Rust 官方客户端或 UC wrapper | P2-CLIENT-005 | 已有 std `TcpStream` 最小协议 smoke，可验证 Arrow query；不是官方 Rust SDK | 调研 LanceDB Rust remote client 对 UC endpoint 的支持；必要时实现 adapter/wrapper，并保留 `rustc`/crate 依赖的 skip 语义 | Rust 官方/目标客户端 query smoke 通过，错误响应能映射为预期异常结构 |
| 客户端错误识别 | P2-CLIENT-006 | 服务端错误 shape/requestId/backend_request_id 已覆盖；官方客户端消费层仍禁用 | 用 Python/Java/Rust 目标客户端覆盖 backend disabled、permission denied、worker timeout/conflict/internal error | 客户端侧能稳定识别 401/403/409/413/415/500/501/503/504，不误判 backend committed 失败 |
| UC Client/OpenAPI 暴露策略 | P2-CONTRACT + P2-CLIENT | raw HTTP entrypoint 已可用；当前客户端 smoke 主要绕过 generated UC client | 确认 Lance data endpoint 是否进入 UC OpenAPI/Java/Python generated clients；若进入，补生成、编译和最小调用测试 | 协议入口、生成客户端和文档示例一致，不再只有 test-only raw HTTP 客户端 |

### 3.3 Ecosystem Smoke（Nightly / Release Gate）

| 任务 | 对应测试 | 当前状态 | 剩余工作 | 完成标准 |
|------|----------|----------|----------|----------|
| Spark connector smoke | P2-SPARK-001~008 | `LancePhase2EcosystemSmokeTest` 中全部禁用 | 使用官方 Spark/catalog 配置覆盖 create/materialize、insert、select、update/delete skip 语义、多层 namespace、auth/context、错误识别 | Spark 最小数据面 smoke 在 nightly 通过或带清晰 skip 原因 |
| Ray connector smoke | P2-RAY-001~007 | 全部禁用 | 覆盖 read_lance、write_lance create、append/overwrite、filter/columns pushdown、多 segment table id、storage_options、分布式执行 metadata 稳定性 | Ray smoke 在 nightly 通过，分布式写不破坏 UC metadata |
| DuckDB local engine smoke | P2-LOCAL-001~004 | 全部禁用 | 基于 UC metadata/credential resolution 做 attach/read、SQL、vector/FTS 能力验证；不要求 UC 充当查询引擎 | DuckDB 可通过 UC 暴露的 Lance table uri/storage options 读取真实数据 |
| Pandas/PyArrow smoke | P2-LOCAL-005~006 | 全部禁用 | 通过 UC 解析出的 location/credential 读取 Lance/Arrow 数据，验证 DataFrame/Table shape | Pandas/PyArrow 可消费真实 table 数据和 schema |
| credential refresh/expired smoke | P2-LOCAL-007；P2-STORAGE-005 | mock credential path 已有 PR 覆盖 | 在 ecosystem/local engine 层验证过期凭证 refresh 或受控失败，不泄漏 token/session | local engine 失败语义可控，日志/audit 脱敏 |
| S3-compatible storage smoke | P2-STORAGE-008；Nightly 准出 | 尚无真实对象存储环境证据 | 接入 MinIO 或兼容 S3 测试桶，确认 worker/local engine 能基于 storage_options 访问 | nightly 至少覆盖 local FS + 一个 S3-compatible backend |

### 3.4 测试套件整理和文档自动化

| 任务 | 当前状态 | 剩余工作 | 完成标准 |
|------|----------|----------|----------|
| 早期 skeleton 归并 | 多个 `LancePhase2*RestTest` 仍 `@Disabled`，其中不少场景已由拆分后的 enabled tests 覆盖 | 将 skeleton 中仍有价值的断言迁移到已启用测试；已重复或过期的 skeleton 标注/删除，避免进度误读 | `@Disabled` 只保留真实外部依赖或 nightly/release gate，不再保留已完成能力的旧占位 |
| 测试命令分层 | 当前已有 PR 级 enabled tests、真实 worker process tests、ecosystem disabled skeleton | 在文档和 CI 中拆清 PR、nightly、release gate 命令；记录 Python/lancedb/pyarrow/rustc/Spark/Ray/DuckDB 等依赖 skip 语义 | 开发者能按一条命令跑 PR 层，nightly 能输出外部依赖缺失原因 |
| 进度文档维护 | 本文档已刷新到 2026-05-11 状态 | 每次完成 P2-CLIENT/P2-SPARK/P2-RAY/P2-LOCAL 或 sidecar 化时同步更新 Section 3/5/9 | 文档、代码测试和准出状态保持一致 |

---

## 4. 实施任务进度

按照设计文档 Section 15 的任务拆解：

| 任务 | 设计编号 | 状态 | Commit |
|------|----------|------|--------|
| W2-0: 准备与收口 | 15.1 | ✅ 完成 | 74fe4a4 |
| W2-1: Backend SPI | 15.2 | ✅ 完成 | 74fe4a4, ec3e6e0, 1fbc0ba |
| W2-2: Resolver + Storage | 15.3 | ✅ 高/中优先级完成 | ec4cee3 + 本次小步（Resolver/TableRef tableUri + sanitized template + runtime credential mock/contract 完成） |
| W2-3: Data endpoint service | 15.4 | ✅ 高优先级完成 | ec4cee3 + 本次工作（架构/Authorization/入口校验/metadata success path/legacy read 配置/Arrow response/JSON scalar response/error requestId 完成） |
| W2-4: Arrow IPC | 15.5 | ✅ 完成 | media type/size limit + request reader + response writer + worker-http streaming pass-through + unknown-length runtime limit 已完成 |
| W2-5: Worker HTTP backend | 15.6 | ✅ PR 完成 / ⚠️ Nightly 扩展 | WorkerHttpLanceExecutionBackend、fake worker contract、retry/no-retry、timeout、health、Arrow request/response handoff、unavailable/error fallback、streaming failure cleanup、有状态 worker E2E、真实 LanceDB worker process query/count/stats/insert/create/merge/update/delete 已完成；sidecar 化、real worker explain/analyze 决策、真实环境压力矩阵待续 |
| W2-6: Metadata 状态推进 | 15.7 | ✅ 完成 | 本次工作（Repository methods + data plane success path + backend_committed marker + version 防倒退 + reconcile 完成） |
| W2-7: 授权、审计、观测 | 15.8 | ✅ 完成 | 本次工作 + Add Lance backend failure labels（授权 + audit/metrics + failure backend labels + P2-AUTH-005-008、011-014 enabled 覆盖完成） |
| W2-8: Connector 回归 | 15.9 | ⚠️ 协议 smoke 部分完成 | raw HTTP 10 endpoint、Python urllib+pyarrow、Java JDK HTTP、Rust std HTTP 最小协议 smoke 已完成；官方 LanceDB/UC Client、Spark、Ray、DuckDB、Pandas/PyArrow 仍待接入 |

---

## 5. 测试启用状态

| 测试类 | 测试数 | 状态 | 启用条件 |
|--------|--------|------|----------|
| LancePhase2ContractAndRequestRestTest | 16 | @Disabled | 早期 skeleton；route/request/validation 已由拆分 enabled tests 部分覆盖，待归并 |
| LancePhase2BackendAndResolverRestTest | 20 | @Disabled | 早期 skeleton；backend/resolver command coverage 已由拆分 enabled tests 部分覆盖，待归并 |
| LancePhase2ArrowRestTest | 11 | @Disabled | 早期 skeleton；request/response reader-writer 已由 enabled tests 覆盖，待归并 |
| LancePhase2DataReadRestTest | 13 | @Disabled | 早期 skeleton；基础 read/scalar/Arrow 已覆盖，复杂 query 参数与 real worker explain/analyze 待整理 |
| LancePhase2DataWriteRestTest | 11 | @Disabled | 早期 skeleton；write metadata 与 real worker write 链路已拆分覆盖，待归并 |
| LancePhase2MetadataAndStorageRestTest | 18 | @Disabled | 早期 skeleton；metadata/reconcile/storage mock 已拆分覆盖，S3-compatible smoke 仍待 nightly |
| LancePhase2AuthGovernanceRestTest | 17 | @Disabled | 早期 skeleton；READ/WRITE allow-deny 与 audit/metrics 已拆分覆盖，Bearer/API key 细项待归并 |
| LancePhase2RequestMappingRestTest | 7 | Enabled | request mapping + storage/deadline contract + header/body reserved field spoofing guard |
| LancePhase2DataPlaneAuthorizationRestTest | 4 | Enabled | P2-AUTH-005-008 read/write allow-deny 覆盖 |
| LanceDataPlaneAuthorizerTest | 3 | Enabled | READ_DATA/WRITE_DATA 兼容权限映射 |
| LancePhase2ConfigurableBackendRestTest | 1 | Enabled | backend type/property 配置入口 |
| LancePhase2DisabledBackendRestTest | 1 | Enabled | disabled backend 返回 Lance-compatible `UNIMPLEMENTED` |
| LancePhase2RequestValidationRestTest | 4 | Enabled | Content-Type 415 + JSON/Arrow size 413 |
| LancePhase2ArrowRequestReaderRestTest | 8 | Enabled | P2-ARROW-001~006、010~011 request reader 覆盖 |
| LancePhase2ArrowResponseWriterRestTest | 5 | Enabled | P2-DATA-001、P2-ARROW-007~009 response writer 覆盖 |
| LancePhase2DataPlaneMetadataUpdateRestTest | 4 | Enabled | declared materialization + write/stats metadata cache + version 防倒退 |
| LancePhase2LegacyReadConfigRestTest | 1 | Enabled | server property enables legacy bridge read |
| LancePhase2BackendCommittedFailureRestTest | 1 | Enabled | metadata update failure returns backend_committed marker |
| LancePhase2ReconcileRestTest | 2 | Enabled | P2-META-009/010 reconcile dry-run/backfill |
| LancePhase2ObservabilityRestTest | 4 | Enabled | P2-AUTH-011~014 audit success/failure/redaction + metrics，含失败 backend label 对齐断言 |
| LancePhase2ScalarResponseRestTest | 3 | Enabled | P2-DATA-009、011、012 count/explain/analyze scalar response |
| LancePhase2ErrorResponseContractRestTest | 3 | Enabled | P2-ERROR-015 requestId/backend_request_id 合同覆盖 |
| LancePhase2StorageCredentialRestTest | 8 | Enabled | P2-STORAGE-001~007 + P2-META-008 runtime credential mock/contract 覆盖 |
| LancePhase2WorkerHttpBackendRestTest | 18 | Enabled | P2-WORKER-001~013 + P2-ARROW-012 fake HTTP worker 合同覆盖，含 health、headers、retry/no-retry、timeout、unavailable、non-JSON fallback、streaming limit 与 upstream close |
| LancePhase2RealWorkerE2ERestTest | 2 | Enabled | 有状态 HTTP worker fixture E2E：declared insert 物理化、active write metadata、Arrow query、count/stats 一致 |
| LancePhase2RealLanceDbWorkerProcessRestTest | 3 | Enabled | 真实 `lancedb` worker process 覆盖 query/count/stats/insert/create/merge_insert/update/delete 与 metadata 回写 |
| LancePhase2RawHttpClientSmokeRestTest | 1 | Enabled | P2-CLIENT-001 raw HTTP 覆盖 10 个 data endpoint |
| LancePhase2PythonClientSmokeRestTest | 1 | Enabled | P2-CLIENT-002 最小 Python 协议客户端；依赖 `lancedb`/`pyarrow`，不是官方 SDK |
| LancePhase2JavaRustClientSmokeRestTest | 2 | Enabled | P2-CLIENT-004/005 最小 Java JDK HTTP 与 Rust std HTTP 协议客户端；不是官方 SDK |
| LancePhase2ErrorAndRegressionRestTest | 28 | @Disabled | 早期 skeleton；错误/回归已有拆分覆盖，未支持 endpoint 与全量 UC/Iceberg/Delta 回归待整理 |
| LancePhase2WorkerAndResilienceRestTest | 16 | @Disabled | 早期 skeleton；worker resilience 已由 `LancePhase2WorkerHttpBackendRestTest` 覆盖大部分，待归并 |
| LancePhase2EcosystemSmokeTest | 26 | @Disabled | P2-CLIENT-003/006、P2-SPARK、P2-RAY、P2-LOCAL 官方 connector/nightly smoke 待接入 |

---

## 6. Commit 历史

| Commit | 日期 | 描述 |
|--------|------|------|
| 975c3b2 | 2026-05-09 | Add Java and Rust Lance protocol smoke tests |
| 496291d | 2026-05-09 | Extend real LanceDB worker write coverage |
| 2495350 | 2026-05-09 | Add Python Lance client smoke |
| 56fe8f6 | 2026-05-09 | Enable raw HTTP Lance data endpoint smoke |
| db9fb41 | 2026-05-09 | Add minimal real LanceDB worker process |
| c5086fa | 2026-05-09 | Extend minimal Lance worker write-chain coverage |
| 888afae | 2026-05-09 | Cover Lance streaming worker failures during body upload |
| 8c9f6b9 | 2026-05-09 | Stop oversized chunked Lance Arrow streams at runtime |
| 80822a1 | 2026-05-09 | Stream Lance Arrow writes to HTTP workers |
| 9f59f55 | 2026-05-09 | Add Lance minimal real worker E2E fixture |
| 2dc1b5a | 2026-05-09 | Cover Lance worker custom health path |
| 9251f6f | 2026-05-09 | Fix legacy read header bypass per design |
| c263465 | 2026-05-09 | Prevent Lance command reserved field spoofing |
| 1068b67 | 2026-05-08 | Add Lance worker client timeout |
| 304c5b4 | 2026-05-08 | Cover Lance worker Arrow metadata boundary |
| 9c4c931 | 2026-05-08 | Add Lance Arrow buffered boundary metadata |
| 0407702 | 2026-05-08 | Add Lance backend failure labels |
| 1652684 | 2026-05-08 | Add Lance worker error fallback |
| 10a34bc | 2026-05-08 | Add Lance worker unavailable mapping |
| 7cf8374 | 2026-05-08 | Add Lance worker Arrow response handoff |
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

### 8.1 立即可做（不引入重型生态环境）

1. **官方 Python client 兼容性调研与最小 smoke** - 先确认 upstream LanceDB Python client 能否配置到 UC Lance REST；若不能，明确 adapter/UC Client wrapper 方案。当前 `python_client_smoke.py` 只能证明协议可消费，不能作为官方 SDK 验收。
2. **P2-CLIENT-003 vector/filter/withRowId** - 在真实 LanceDB worker fixture 下补 vector table 和 query shape，优先验证 Arrow 返回能被目标 Python 侧消费。
3. **P2-CLIENT-006 client error recognition** - 用目标客户端覆盖 backend disabled、permission denied、worker timeout/error，证明 Lance error shape 在客户端侧可识别。
4. **整理 disabled skeleton** - 把已经由拆分 enabled tests 覆盖的旧 `@Disabled` 场景归并或删除，只保留真实外部依赖/nightly gate。

### 8.2 需要 nightly fixture 或外部依赖

1. **真实 LanceDB worker sidecar 化** - 固化依赖安装、启动、health、日志、临时目录和 skip 语义，减少 `LANCE_REAL_WORKER_PYTHONPATH` 这类手动准备。
2. **real worker explain/analyze 行为** - 对 P2-DATA-08/09 给出真实 worker 支持或稳定不支持语义，并补回归。
3. **官方 Java/Rust client 或 UC generated client** - 当前 Java/Rust 只是最小 HTTP 协议客户端，下一步需要决定并接入官方 SDK 或生成客户端路径。
4. **Spark/Ray/DuckDB/Pandas/PyArrow smoke** - 逐步启用 `LancePhase2EcosystemSmokeTest` 中的 P2-SPARK、P2-RAY、P2-LOCAL。
5. **S3-compatible + PostgreSQL 发布前矩阵** - 接入 MinIO/兼容 S3 和 PostgreSQL metadata DB，形成 release gate 证据。

---

## 9. 准出标准检查

按照设计文档 Section 16：

| 标准 | 状态 |
|------|------|
| 所有 Phase 2 必做 endpoint 可用 | ⚠️ raw HTTP + 最小 worker fixture 已覆盖 10 个 endpoint；真实 LanceDB worker process 覆盖 query/count/stats/insert/create/merge_insert/update/delete，explain/analyze 真实 worker 语义待定 |
| query 返回 Arrow IPC 且可被客户端消费 | ✅ fake worker、真实 LanceDB worker process、Python/Java/Rust 最小协议客户端均已覆盖 Arrow 消费 |
| insert/merge/update/delete 通过真实 worker 执行 | ✅ 真实 LanceDB worker process 已覆盖 insert/create/merge_insert/update/delete 并验证 metadata version/stats 回写 |
| declared-only table 可首次物理化 | ✅ fake backend、最小 worker fixture、真实 LanceDB worker process 均已有覆盖 |
| stats/count 和 query/DML 结果一致 | ✅ 基础 real worker query/count/stats/insert/create/write 链路已覆盖；复杂 query/vector/filter 仍待 P2-CLIENT-003/生态 smoke |
| data endpoint 认证、授权、审计可验证 | ✅ P2-AUTH-005-008、011-014 enabled 覆盖 |
| runtime storage credentials 不落库、不进日志 | ✅ runtime credential mock/merge/redaction 覆盖就绪；真实 credential vendor/S3 仍待 nightly |
| backend 未配置、backend 超时、worker 错误有稳定错误语义 | ✅ disabled backend + fake/HTTP worker health/timeout/error/fallback/retry/unavailable/streaming failure cleanup 已覆盖；真实 sidecar 压力矩阵仍待 nightly |
| data endpoint media type 和 request size 错误稳定 | ✅ 415/413 Lance error shape、unknown-length chunked runtime limit 已覆盖 |
| Phase 1 metadata endpoint 回归通过 | ✅ 已有 Phase 1 回归覆盖；发布前仍需全量重跑 |
| UC 原有路由回归通过 | ✅ 已有 UC/Iceberg/Delta 回归覆盖；发布前仍需全量重跑 |
| Python client smoke | ⚠️ 最小 `urllib` + `pyarrow` 协议 smoke 已完成；官方 LanceDB Python client 形态未完成 |
| Java/Rust client smoke | ⚠️ Java JDK HTTP 与 Rust std HTTP 协议 smoke 已完成；官方 SDK/UC Client 形态未完成 |
| Spark/Ray/local engine smoke | ❌ `P2-SPARK`、`P2-RAY`、`P2-LOCAL` 仍在 disabled ecosystem skeleton 中，需 nightly/发布前补齐 |
