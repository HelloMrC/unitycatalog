# Unity Catalog Lance REST API Phase 2 开发进度

更新日期：2026-05-06

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
| LanceTableRef | ec4cee3 | Section 7.3 | id, namespacePath, tableName, pathKey, storageLocation, currentVersion, declaredOnly, legacyBridge |
| LanceStorageBinding | ec4cee3 | Section 7.3 | uri, storageOptions |
| LanceExecutionCommand 重构 | ec4cee3 | Section 7.4 | operation, context, table, storage, attributes |

**参考来源：** 设计文档 Section 7.3, Section 7.4

### 2.6 Storage Options（W2-2 部分）

| 功能 | Commit | 设计章节 | 说明 |
|------|--------|----------|------|
| LanceStorageOptionsService | ec4cee3 | Section 6.5 | 模板加载服务 |
| Template 解析 | ec4cee3 | Section 6.5 | 从 storage_options_template_json 解析 |
| 敏感字段过滤 | ec4cee3 | Section 6.5 | 过滤 token, session, secret, expires, access_key |

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

---

## 3. 待完成功能

### 3.1 高优先级（阻塞测试启用）

| 功能 | 设计章节 | 测试阻塞 | 说明 |
|------|----------|----------|------|
| **Authorization** | Section 11.2-11.3 | P2-AUTH-005-008 | READ_DATA/WRITE_DATA 权限检查，需 LanceDataPlaneAuthorizer |
| **Arrow IPC Request Reader** | Section 9.2 | P2-ARROW-001-006 | Content-Type 验证、size limit、stream handling、schema peek |
| **Arrow IPC Response Writer** | Section 4.1, 9.1 | P2-DATA-001, P2-ARROW-007-008 | Query 返回 Arrow IPC file/stream，不是 JSON |
| **Content-Type Validation** | Section 5.3 | P2-CONTRACT-004, P2-ARROW-004 | 415 拒绝错误 media type |
| **Request Size Limits** | Section 8.3 | P2-REQ-007, P2-ARROW-005 | max-arrow-request-bytes, max-json-request-bytes 配置和校验 |
| **LanceTableRepository Methods** | Section 10.3 | P2-META-002-005, P2-DATA-WRITE | markTableMaterialized, updateTableExecutionMetadata, updateTableStats |

### 3.2 中优先级（影响部分测试）

| 功能 | 设计章节 | 测试阻塞 | 说明 |
|------|----------|----------|------|
| **Runtime Credential Vending** | Section 6.5 | P2-STORAGE-002-003 | UC StorageCredentialVendor 集成，生成临时凭证 |
| **Deadline/Timeout** | Section 8.3 | P2-WORKER-006 | deadlineMs 从配置或 header 填充 |
| **ServerProperties 传入** | Section 6.2 | 影响配置读取 | LanceRestTableDataService 需要 serverProperties 参数 |
| **Audit Events** | Section 12.3 | P2-AUTH-011-013 | lance.data.* audit events |
| **Metrics** | Section 12.4 | P2-AUTH-014 | lance_data_* metrics |
| **Backend Committed Failure** | Section 10.4 | P2-ERROR-014 | backend_committed=true 错误标记 |

### 3.3 低优先级（设计一致性）

| 功能 | 设计章节 | 说明 |
|------|----------|------|
| Legacy read 配置方式 | Section 8.3 | 改为 server property `lance.execution.legacy-read-enabled` |
| LanceStorageBinding 补齐 | Section 6.5 | 添加 storageOptionsTemplate, vendCredentials, expiresAtMillis |
| LanceTableRef 补齐 | Section 7.3 | 添加 tableUri 字段 |
| Version 防倒退 | Section 13.1 | backend version < current_version 时警告 |
| Reconcile API | Section 10.4 | Admin reconcile endpoint |

### 3.4 Worker Backend（W2-5）

| 功能 | 设计章节 | 测试阻塞 | 说明 |
|------|----------|----------|------|
| WorkerHttpLanceExecutionBackend | Section 8.1-8.2 | P2-WORKER-* | HTTP worker 实现 |
| Worker 内部 API | Section 8.2 | - | JSON command path, Arrow command path |
| Timeout/Retry | Section 8.4 | P2-WORKER-008-010 | Read retry bounded, write no retry |
| Worker error envelope | Section 12.2 | P2-WORKER-007 | 映射 worker error 到 Lance error shape |

### 3.5 Ecosystem Smoke（W2-8）

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
| W2-2: Resolver + Storage | 15.3 | ⚠️ 部分 | ec4cee3（Resolver 完成，Storage 缺 credential vending） |
| W2-3: Data endpoint service | 15.4 | ⚠️ 部分 | ec4cee3（架构完成，缺 Arrow/Authorization/Metadata update） |
| W2-4: Arrow IPC | 15.5 | ❌ 未开始 | - |
| W2-5: Worker HTTP backend | 15.6 | ❌ 未开始 | - |
| W2-6: Metadata 状态推进 | 15.7 | ❌ 未开始 | - |
| W2-7: 授权、审计、观测 | 15.8 | ❌ 未开始 | - |
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
| LancePhase2AuthGovernanceRestTest | 17 | @Disabled | Authorization, audit |
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

1. **Authorization 集成** - LanceDataPlaneAuthorizer + READ_DATA/WRITE_DATA 权限
2. **Content-Type Validation** - 415 拒绝逻辑
3. **Request Size Limits** - 配置读取和校验
4. **LanceTableRepository methods** - markTableMaterialized 等
5. **Deadline 填充** - 从配置读取

### 8.2 需要测试 fixture

6. **Arrow IPC handling** - LanceArrowRequestReader/ResponseWriter
7. **Runtime credential vending** - StorageCredentialVendor mock
8. **Audit/Metrics** - 测试观察点

### 8.3 需要 real worker

9. **WorkerHttpLanceExecutionBackend** - HTTP 调用
10. **Ecosystem smoke** - Python/Spark/Ray

---

## 9. 准出标准检查

按照设计文档 Section 16：

| 标准 | 状态 |
|------|------|
| 所有 Phase 2 必做 endpoint 可用 | ❌ Query 返回 JSON 非 Arrow |
| query 返回 Arrow IPC 且可被客户端消费 | ❌ 未实现 |
| insert/merge/update/delete 通过真实 worker 执行 | ❌ 无 real worker |
| declared-only table 可首次物理化 | ⚠️ 结构就绪，缺 repository 方法 |
| stats/count 和 query/DML 结果一致 | ❌ 无 real worker |
| data endpoint 认证、授权、审计可验证 | ⚠️ 认证就绪，授权缺失 |
| runtime storage credentials 不落库、不进日志 | ⚠️ 模板过滤就绪，credential vending 缺失 |
| backend 未配置、backend 超时、worker 错误有稳定错误语义 | ⚠️ disabled backend 就绪，timeout/error 缺失 |
| Phase 1 metadata endpoint 回归通过 | ✅ Phase 1 tests passing |
| UC 原有路由回归通过 | ✅ Phase 1 regression tests passing |
| Spark/Ray/Python 至少完成 P1 smoke | ❌ Nightly 层，需要 real worker |