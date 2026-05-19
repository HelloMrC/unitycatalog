# Unity Catalog Lance REST API 第二阶段详细测试设计

副标题：Data Plane Compatibility 测试设计

更新日期：2026-04-28

关联文档：

- `docs/lance/unitycatalog-lancedb-rest-api-requirements.md`
- `docs/lance/unitycatalog-lancedb-rest-api-technical-design.md`
- `docs/lance/unitycatalog-lancedb-phase-2-data-plane-design.md`
- `docs/lance/unitycatalog-lancedb-rest-api-test-strategy.md`
- `docs/lance/unitycatalog-lancedb-rest-api-test-design.md`
- `docs/lance/unitycatalog-lancedb-phase-1-metadata-test-design.md`
- `docs/lance/unitycatalog-test-mechanism-analysis.md`

## 1. 文档目标

本文档用于把 “Phase 2：Data Plane Compatibility” 设计展开为可执行的详细测试设计。

第二阶段测试的核心目标不是重新验证 Phase 1 metadata compatibility，而是在 Phase 1 namespace/table metadata、auth、storage options、legacy bridge 和 UC 回归稳定的基础上，验证 Lance REST data endpoint 能正确接入 UC 治理能力、执行后端、Arrow IPC、写后 metadata 状态推进和 connector smoke。

本文回答以下问题：

- Phase 2 哪些 endpoint 必须覆盖，哪些能力不在本阶段门禁内
- PR、Nightly、发布前分别跑哪些测试
- fake backend、disabled backend、worker HTTP backend、真实 worker 如何分层验证
- Arrow IPC request/response、JSON request/response、storage options、auth、audit、error 如何断言
- 每个测试套件的用例 ID、前置条件、步骤、主要断言和建议落位

## 2. 测试完成标准

Phase 2 完成时，测试侧必须证明：

- 10 个 Phase 2 data endpoint 的 method、path、content type、response type 与上游 OpenAPI 基线一致。
- 未配置 backend 时，所有 data endpoint 稳定返回 Lance 兼容 `UNIMPLEMENTED`，不返回 UC 内部异常。
- fake backend 下，UC data service 能正确完成 request parsing、table resolution、storage binding、command 构造、result 映射和 metadata 更新。
- 真实 worker/sidecar 下，`query` 返回 Arrow IPC 且可被客户端消费，`count_rows`、`stats`、DML 结果与表状态一致。
- `insert`、`merge_insert`、`create` 可以按设计把 declared-only table 首次物理化为 `ACTIVE`。
- `current_version`、`arrow_schema_json`、`stats_json` 的写后更新规则正确，metadata 更新失败时错误语义包含 `backend_committed=true`。
- data endpoint 认证、授权、context header、audit、metrics 均可观测，Arrow 二进制请求不能绕过权限。
- runtime storage credentials 不落库、不进日志；传给 worker 的 `storage_options` 可含临时凭证。
- legacy bridge table 默认只支持 metadata bridge，data plane 写入一律拒绝，读能力只在显式开启配置后验证。
- Phase 1 metadata endpoint 和 UC `/tables`、`/schemas`、Iceberg、Delta 回归不受影响。
- Python Lance client、Spark、Ray 至少完成 P1 数据面 smoke；DuckDB、Pandas/PyArrow 通过 UC metadata/credential resolution 完成访问 smoke。

## 3. 测试基线

### 3.1 协议与客户端基线

沿用总体测试策略固定的上游基线：

| 类型 | 路径 | Commit |
|---|---|---|
| Lance REST OpenAPI | `/home/lei/data_ai/learning/codebase/lancedb-docs/docs/api-reference/rest/openapi.yml` | `6c0ccc001e6b` |
| Lance 客户端源码 | `/home/lei/data_ai/learning/codebase/lancedb` | `a92ae0ded522` |

基线优先级：

1. Endpoint path、HTTP method、request/response schema、content type 以 OpenAPI 为准。
2. Python / Java / Rust / Spark / Ray 客户端用于验证真实消费行为。
3. 客户端包装层未暴露的能力由 raw HTTP 协议测试补齐。

### 3.2 服务挂载前缀

UC 内部 Lance REST 前缀：

```text
/api/2.1/unity-catalog/lance
```

Phase 2 endpoint 示例：

```text
POST /api/2.1/unity-catalog/lance/v1/table/{id}/query
```

### 3.3 Phase 1 准入基线

执行 Phase 2 测试前，默认以下 Phase 1 能力已经稳定：

- `LanceIdentifierCodec` 支持 `$` 默认 delimiter 和自定义 `delimiter`
- `uc_lance_namespaces`、`uc_lance_assets`、`uc_lance_tables`、`uc_lance_api_keys` 可在 H2 中正常 CRUD
- `LanceAuthDecorator` 可解析 Bearer、外部 Bearer、`x-api-key` 和 `x-lance-*` context headers
- `LanceResourceKeyMapper` 与原有 `KeyMapper` 委托关系稳定
- `LanceStorageOptionsService` 或 Phase 1 等价逻辑可生成 describe table 的 `storage_options`
- Phase 1 namespace/table metadata endpoint、legacy bridge metadata 和 UC 回归测试通过

若 Phase 1 任一基础能力不稳定，Phase 2 data endpoint 的失败不应直接归因为数据面实现。

## 4. 范围与非范围

### 4.1 范围内

| 能力域 | Phase 2 覆盖内容 |
|---|---|
| Contract | 10 个 data endpoint、method、content type、response type、disabled backend |
| Request parsing | path `id`、`delimiter`、headers、query params、JSON body、Arrow body |
| Backend SPI | command/result records、fake backend、disabled backend、worker HTTP backend |
| Arrow IPC | stream request、file/stream response、body size、streaming backpressure、media type |
| Read data | query、count rows、stats、explain plan、analyze plan |
| Write data | insert、merge insert、update、delete、create、declared-only 首次物理化 |
| Metadata update | `current_version`、`arrow_schema_json`、`stats_json`、state transition |
| Auth/Governance | Bearer、external Bearer、API key、READ_DATA/WRITE_DATA 或兼容映射、audit、metrics |
| Storage options | template + runtime credentials 合并、worker 透传、敏感字段隔离 |
| Error handling | Lance error shape、backend error envelope、timeout、metadata update failure |
| Consistency | 并发写、idempotency key、worker retry 边界、metadata version 防倒退 |
| Regression | Phase 1 metadata endpoint、UC `/tables`、Iceberg、Delta |
| Ecosystem smoke | Python Lance client、Spark、Ray、DuckDB、Pandas/PyArrow |

### 4.2 范围外

| 能力 | 本阶段处理 |
|---|---|
| index / version / transaction / batch commit | UC 不提供，不测试。客户端应通过 Lance 执行引擎或直接读取 Lance manifest 获取 |
| tag CRUD | Phase 3 实现（纯 metadata 操作），不在 Phase 2 测试 |
| schema evolution endpoint | UC 不提供，不测试。`/schema_metadata/update`、`/add_columns`、`/alter_columns`、`/drop_columns` 不挂载 |
| restore table | UC 不提供，不测试。`/v1/table/{id}/restore` 不挂载 |
| rename table | ✅ 已在 Phase 2 实现为纯 metadata 操作，需要测试 |
| 跨系统强事务 | 只验证失败暴露、audit 和 reconcile 入口，不要求自动回滚 |
| Lance 引擎内部算法正确性 | 不测，worker 或 Lance upstream 自己覆盖 |
| 全云厂商对象存储矩阵 | 发布前抽样，不作为 PR 必跑 |
| UC UI | 不测 |

### 4.3 Legacy Bridge 边界

Phase 2 默认策略：

- `describe` / `list` / `exists` 继续由 Phase 1 metadata compatibility 覆盖。
- `query` legacy bridge table 默认返回 `UNIMPLEMENTED`，除非显式开启 `lance.execution.legacy-read-enabled=true`。
- `insert` / `merge_insert` / `update` / `delete` / `create` legacy bridge table 一律拒绝。
- 错误消息必须说明 legacy bridge 只保证 metadata compatibility，需要先迁移为原生 `uc_lance_*` 记录才能使用数据面能力。

## 5. 自动化分层

| 层级 | 执行时机 | 后端 | 存储 | 覆盖重点 |
|---|---|---|---|---|
| L0 静态评审 | PR | 无 | 无 | OpenAPI diff、设计/DDL/权限审查 |
| L1 Unit | PR | fake object | 无 | codec、request mapper、command builder、error mapper、storage options 合并 |
| L2 Component | PR | fake backend / disabled backend | H2 + local FS | data service、repository、auth、metadata 状态、错误语义 |
| L3 Protocol | PR / Nightly | fake backend 或 fake worker | H2 + local FS | raw HTTP method/content type/body/headers |
| L4 Worker E2E | Nightly | real worker/sidecar | local FS 或测试对象存储 | query、count、stats、DML、Arrow IPC |
| L5 Ecosystem | Nightly / 发布前 | real worker/sidecar | local FS + S3 兼容 | Python、Spark、Ray、DuckDB、Pandas/PyArrow |
| L6 Resilience/NFR | 发布前 | fake fault backend + real worker | H2/PostgreSQL + 对象存储 | timeout、retry、并发、metadata update failure、脱敏 |

PR 必跑应优先保证 UC 协议适配层稳定，不依赖真实 worker 或真实对象存储。

## 6. 测试落位建议

| 测试域 | 建议路径 | 机制 |
|---|---|---|
| data endpoint raw HTTP | `server/src/test/java/io/unitycatalog/server/service/lance` | `BaseServerTest` + Armeria `WebClient` |
| backend SPI / command mapper | `server/src/test/java/io/unitycatalog/server/service/lance/backend` | fake backend unit/component |
| Arrow helper | `server/src/test/java/io/unitycatalog/server/service/lance/arrow` | byte fixture + media type/size tests |
| repository state update | `server/src/test/java/io/unitycatalog/server/persist` | Hibernate session + H2 |
| auth/access | `server/src/test/java/io/unitycatalog/server/sdk/access` 或 `service/lance` | `BaseAccessControlCRUDTest` 模式 |
| credential/storage options | `server/src/test/java/io/unitycatalog/server/service/lance` | `BaseCRUDTestWithMockCredentials` 模式 |
| worker HTTP backend | `server/src/test/java/io/unitycatalog/server/service/lance/backend` | fake worker HTTP server |
| integration worker smoke | `integration-tests/src/test/java` | UC + worker + local/cloud storage |
| Python client smoke | `clients/python/tests` 或单独 `tests/lance` | `run-tests.sh` + raw HTTP helper |
| Spark/Ray/DuckDB/Pandas smoke | `integration-tests` 或外部 nightly job | 官方接入方式 |

## 7. 测试数据与 Fixture

### 7.1 Namespace 与 Table

| 数据 ID | 内容 | 用途 |
|---|---|---|
| `P2-NS-ROOT` | `prod` | root namespace |
| `P2-NS-TEAM` | `prod$team_a` | 多层 namespace |
| `P2-TBL-ACTIVE` | `prod$team_a$embeddings`，`ACTIVE` | query/count/stats/DML 主路径 |
| `P2-TBL-DECLARED` | `prod$team_a$declared_only`，`DECLARED + is_only_declared=true` | 首次物理化 |
| `P2-TBL-DEREGISTERED` | `DEREGISTERED` table | data op 拒绝 |
| `P2-TBL-DROPPED` | `DROPPED` table | data op 拒绝 |
| `P2-TBL-LEGACY` | `EXTERNAL + TEXT + properties.table_type=lance` | legacy data plane 拒绝 |
| `P2-TBL-TEXT` | 非 Lance `EXTERNAL + TEXT` | legacy 误识别排除 |

### 7.2 Arrow Fixture

| Fixture | 内容 | 用途 |
|---|---|---|
| `arrow-small.stream` | 3 行，含 `id`、`text`、`vector` | create/insert/merge_insert |
| `arrow-small.file` | query fake backend 返回 | query response 可读 |
| `arrow-empty.stream` | 0 行，仅 schema | 空写入边界 |
| `arrow-large.stream` | 超过配置阈值 | 413 body too large |
| `arrow-invalid.bin` | 非 Arrow bytes | worker 或 request reader 错误 |
| `arrow-schema-v2.stream` | 新增列 schema | schema 回填 |

PR 层不要求真实解析 Arrow record batch，但必须能用 fixture 验证 media type、body size、stream pass-through 和 response content type。

### 7.3 Backend Fixture

| Fixture | 行为 |
|---|---|
| `FakeSuccessBackend` | 记录 command，返回固定成功 result |
| `FakeErrorBackend` | 返回 backend error envelope |
| `FakeTimeoutBackend` | 模拟 timeout |
| `FakeMetadataFailureBackend` | backend success 后触发 repository update failure |
| `DisabledLanceExecutionBackend` | 未配置 backend 时稳定 `UNIMPLEMENTED` |
| `FakeWorkerHttpServer` | 验证 worker HTTP path、headers、JSON body、Arrow stream |

### 7.4 用户与权限

| 用户 | 权限 | 用途 |
|---|---|---|
| `P2-ADMIN` | 全量 | 管理与准备数据 |
| `P2-READER` | `READ_DATA` 或兼容 `SELECT/READ_METADATA` | query/count/stats/explain/analyze |
| `P2-WRITER` | `WRITE_DATA` 或兼容 `MODIFY` | insert/merge/update/delete/create |
| `P2-METADATA-ONLY` | `READ_METADATA` | stats 可读、query 是否允许按设计验证 |
| `P2-DENY` | 无权限 | 403 |
| `P2-SVC` | API key 绑定 service principal | worker smoke |

## 8. Endpoint 覆盖矩阵

| 用例组 | Method | Endpoint | Request | Response | PR | Nightly |
|---|---|---|---|---|---|---|
| `P2-DATA-01` | `POST` | `/v1/table/{id}/query` | JSON | Arrow IPC file/stream | fake backend | real worker |
| `P2-DATA-02` | `POST` | `/v1/table/{id}/count_rows` | JSON | JSON integer | fake backend | real worker |
| `P2-DATA-03` | `POST` | `/v1/table/{id}/stats` | JSON | JSON object | fake backend | real worker |
| `P2-DATA-04` | `POST` | `/v1/table/{id}/insert` | Arrow stream | JSON object | fake backend | real worker |
| `P2-DATA-05` | `POST` | `/v1/table/{id}/merge_insert` | Arrow stream | JSON object | fake backend | real worker |
| `P2-DATA-06` | `POST` | `/v1/table/{id}/update` | JSON | JSON object | fake backend | real worker |
| `P2-DATA-07` | `POST` | `/v1/table/{id}/delete` | JSON | JSON object | fake backend | real worker |
| `P2-DATA-08` | `POST` | `/v1/table/{id}/explain_plan` | JSON | JSON string | fake backend | real worker |
| `P2-DATA-09` | `POST` | `/v1/table/{id}/analyze_plan` | JSON | JSON string | fake backend | real worker |
| `P2-DATA-10` | `POST` | `/v1/table/{id}/create` | Arrow stream | JSON object | fake backend | real worker |

## 9. 测试套件设计

### 9.1 `P2-CONTRACT` 契约、路由与 backend 配置

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-CONTRACT-001` | OpenAPI data endpoint diff | 比较 UC 快照与上游 OpenAPI | 10 个 endpoint method/path/content type 无未解释漂移 |
| `P2-CONTRACT-002` | data route 挂载 | 调用 `/api/2.1/unity-catalog/lance/v1/table/{id}/query` | 命中 `LanceRestTableDataService`，不是 metadata service |
| `P2-CONTRACT-003` | HTTP Method 校验 | 对每个 data endpoint 调用错误 method | 返回 405 或 Lance 兼容 method error |
| `P2-CONTRACT-004` | Content-Type 校验 | JSON endpoint 发 Arrow；Arrow endpoint 发 JSON | 返回 415/400，错误 shape 稳定 |
| `P2-CONTRACT-005` | Response content type | fake backend 返回 query Arrow bytes | `Content-Type` 为 Arrow file 或协商后的 Arrow stream |
| `P2-CONTRACT-006` | backend 未配置 | 使用 `DisabledLanceExecutionBackend` 调用所有 data endpoint | 全部返回 `UNIMPLEMENTED`，不暴露内部异常 |
| `P2-CONTRACT-007` | Phase 1 route 不受影响 | 调用 namespace/table metadata endpoint | 不进入 data backend |
| `P2-CONTRACT-008` | UC route 不被 shadow | 调用 UC `/tables`、Iceberg、Delta | 不进入 Lance data service |

### 9.2 `P2-REQ` Request Parsing 与上下文可信边界

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-REQ-001` | path id 优先 | body 中放入不同 `id`，path 为真实 table | command 使用 path id |
| `P2-REQ-002` | delimiter | 用 `delimiter=.` 调用 `prod.team_a.embeddings` | command table path_key 与 `$` 等价 |
| `P2-REQ-003` | identity 不可信 | body 内伪造 `identity/principal` | principal 仍来自 `LanceAuthDecorator` |
| `P2-REQ-004` | context merge | body context 与 `x-lance-*` 同时存在 | 不覆盖 server principal、request id、auth type |
| `P2-REQ-005` | request id | 传入 `x-request-id` | response 和 backend command 含同一 request id |
| `P2-REQ-006` | idempotency key | 写请求携带 `Idempotency-Key` | command context 含 key hash 或安全表示，不记录明文 |
| `P2-REQ-007` | JSON size limit | JSON body 超过 `max-json-request-bytes` | 返回 413 |
| `P2-REQ-008` | 非法 JSON | 字段类型错误或 body 截断 | 返回 400 `INVALID_ARGUMENT` |

### 9.3 `P2-BACKEND` Execution Backend SPI

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-BACKEND-001` | SPI 最小接口 | 编译/单测 `LanceExecutionBackend` | 仅包含 Phase 2 方法 |
| `P2-BACKEND-002` | Query command | fake backend 记录 `QueryTableCommand` | context、table、storage、querySpec、responseFormat 完整 |
| `P2-BACKEND-003` | Count/Stats command | 调用 count/stats | predicate/version/storage 正确 |
| `P2-BACKEND-004` | Insert command | Arrow insert | `inputData`、`writeMode`、`materializeDeclaredTable` 正确 |
| `P2-BACKEND-005` | MergeInsert command 全参数 | 传入 on、filters、timeout、useIndex | 8 个 MergeInsert 参数完整进入 command |
| `P2-BACKEND-006` | Update/Delete command | 调用 update/delete | predicate、updates、properties 正确 |
| `P2-BACKEND-007` | Explain/Analyze command | 调用 explain/analyze | querySpec 与 verbose/analyze 参数正确 |
| `P2-BACKEND-008` | Create command | Arrow create | mode、properties、inputData 正确 |
| `P2-BACKEND-009` | Result 映射 | fake backend 返回所有 result 字段 | response 不额外包 UC wrapper，字段符合 Lance 协议 |
| `P2-BACKEND-010` | warnings | backend result 含 warnings | response/audit 保留安全 warning |

### 9.4 `P2-RESOLVE` Table Resolver、状态与 legacy 边界

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-RESOLVE-001` | ACTIVE 可读 | query ACTIVE table | resolver 返回 native table，backend 被调用 |
| `P2-RESOLVE-002` | ACTIVE 可写 | insert/update/delete ACTIVE table | backend 被调用 |
| `P2-RESOLVE-003` | DECLARED 不能读 | query declared-only table | 返回 409 `CONFLICT` |
| `P2-RESOLVE-004` | DECLARED 可首次物理化 | insert/merge_insert/create declared-only | command `materializeDeclaredTable=true` |
| `P2-RESOLVE-005` | DEREGISTERED 拒绝 | 任意 data op | 返回 404/409，backend 不被调用 |
| `P2-RESOLVE-006` | DROPPED 拒绝 | 任意 data op | 返回 404/409，backend 不被调用 |
| `P2-RESOLVE-007` | legacy query 默认拒绝 | query legacy table，未开启 legacy read | 返回 `UNIMPLEMENTED` |
| `P2-RESOLVE-008` | legacy read 显式开启 | `legacy-read-enabled=true` 后 query legacy | 仅 query 可进入 backend，写仍拒绝 |
| `P2-RESOLVE-009` | legacy write 拒绝 | insert/update/delete legacy table | 返回 `UNIMPLEMENTED`，说明需迁移 |
| `P2-RESOLVE-010` | 非 Lance TEXT 不误识别 | query non-Lance TEXT table | not found 或 unsupported，不进入 native table |

### 9.5 `P2-ARROW` Arrow IPC 与 Streaming

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-ARROW-001` | insert media type | `Content-Type=application/vnd.apache.arrow.stream` | request accepted |
| `P2-ARROW-002` | merge_insert media type | Arrow stream request | request accepted |
| `P2-ARROW-003` | create media type | Arrow stream request | request accepted |
| `P2-ARROW-004` | 非 Arrow body | `Content-Type=application/json` 调 insert | 返回 415 |
| `P2-ARROW-005` | body size limit | body 超过 `max-arrow-request-bytes` | 返回 413，backend 不被调用 |
| `P2-ARROW-006` | 不全量缓存 | 使用大但未超限 stream | 测试 request reader 不把完整 body 读入 byte[] |
| `P2-ARROW-007` | query file response | `Accept` 默认或 file | 返回 `application/vnd.apache.arrow.file` |
| `P2-ARROW-008` | query stream response | `Accept=application/vnd.apache.arrow.stream` | 可返回 stream，headers 正确 |
| `P2-ARROW-009` | streaming 前错误 | resolver/auth 失败 | 返回 JSON error，而不是 Arrow bytes |
| `P2-ARROW-010` | schema peek off | 默认配置 | UC 不解析 Arrow schema，schema 来自 worker result |
| `P2-ARROW-011` | schema peek on | 开启 `lance.execution.arrow.peek-schema=true` 并发送带 schema 的 Arrow stream | 可从 Arrow schema message 回填；peek 只读取 schema message，不解析 record batch；peek 失败时降级使用 worker result schema 或保留原 schema，并记录 warning |

### 9.6 `P2-DATA-READ` Query、Count、Stats、Explain、Analyze

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-DATA-001` | query 基础 | JSON body 空查询 | 返回 Arrow IPC，可被 Arrow reader 打开 |
| `P2-DATA-002` | query columns | `columns=["id","text"]` | command querySpec columns 正确，结果只含目标列 |
| `P2-DATA-003` | query filter | `filter="id > 1"` | command filter 正确 |
| `P2-DATA-004` | vector query 参数全量 | 覆盖 vector、vectorColumn、distanceType、k、offset、bounds | querySpec 完整 |
| `P2-DATA-005` | ANN 参数 | 覆盖 `prefilter`、`ef`、`nprobes`、`refineFactor`、`fastSearch`、`bypassVectorIndex` | command 不丢字段 |
| `P2-DATA-006` | full text query | `fullTextQuery` + columns + filter | command 组合条件正确 |
| `P2-DATA-007` | version query | `version=1` | command table/version 正确 |
| `P2-DATA-008` | withRowId | `withRowId=true` | response schema/metadata 含 row id |
| `P2-DATA-009` | count rows | predicate + version | 返回 JSON integer，值与 fake/worker 预期一致 |
| `P2-DATA-010` | stats | 调用 stats | 返回 totalBytes、numRows、numIndices、fragmentStats/rawJson |
| `P2-DATA-011` | explain plan | 调用 explain_plan | 返回 JSON string，不是对象 wrapper |
| `P2-DATA-012` | analyze plan | 调用 analyze_plan | 返回 JSON string |
| `P2-DATA-013` | query 与 count/stats 一致 | 真实 worker 下 query 后 count/stats | 行数、stats 与预期一致 |

### 9.7 `P2-DATA-WRITE` Insert、Merge Insert、Update、Delete、Create

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-DATA-020` | insert ACTIVE | Arrow insert | 返回 transactionId/version/schema/stats，metadata 更新 |
| `P2-DATA-021` | insert DECLARED 首次物理化 | declared-only + Arrow insert | state 变 `ACTIVE`，`is_only_declared=false` |
| `P2-DATA-022` | create DECLARED 首次物理化 | declared-only + create | state 变 `ACTIVE`，location/tableUri/version 回填 |
| `P2-DATA-023` | merge insert ACTIVE | Arrow merge_insert | updated/inserted/deleted rows 正确 |
| `P2-DATA-024` | merge insert 全参数 | 覆盖 `whenMatchedUpdateAllFilt` 等 8 个参数 | command/result 不丢参数 |
| `P2-DATA-025` | update | JSON updates + predicate | updatedRows/version/stats 更新 |
| `P2-DATA-026` | delete | JSON predicate | deletedRows/version/stats 更新 |
| `P2-DATA-027` | write 不自动 retry | worker 503，body 已发送，无 idempotency key | 不重试，audit 记录 |
| `P2-DATA-028` | idempotent write | 有 `Idempotency-Key` 且 worker 支持 | key 传给 worker，audit 记录 hash |
| `P2-DATA-029` | version 防倒退 | backend 返回 version 小于 current_version | 不覆盖 current_version，记录 warning |
| `P2-DATA-030` | schema 回填优先级 | worker 返回 arrow_schema_json/schema/null | 按设计优先级回填 |

### 9.8 `P2-META` 写后 Metadata 状态与一致性

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-META-001` | Phase 2 不新增高级资产表 | 检查 DDL | 不要求 indices/versions/transactions（UC 不维护）；tags 在 Phase 3 创建 |
| `P2-META-002` | `markTableMaterialized` transaction | declared-only 首次物理化 | asset state 和 table `is_only_declared` 同事务更新 |
| `P2-META-003` | `updateTableExecutionMetadata` | write success | current_version/schema/stats 更新 |
| `P2-META-004` | schema 不被 null 覆盖 | 原 schema 非空，worker schema null | 保持原 schema |
| `P2-META-005` | stats cache | stats endpoint success | `stats_json` 更新，但不作为唯一真实来源 |
| `P2-META-006` | metadata update failure | backend 已提交，DB update 抛错 | response 含 `backend_committed=true`，记录 audit/reconcile |
| `P2-META-007` | 并发首次物理化 | 两个 insert 同时写 declared-only | 仅一个推进成功，另一个明确失败或读取 ACTIVE 后继续 |
| `P2-META-008` | runtime credentials 不落库 | data op 后查库 | token/session/secret/expires 不在 DB |
| `P2-META-009` | reconcile 入口验证 | metadata update failure 后调用 reconcile 脚本或 admin API dry-run | 能定位 `backend_committed=true` 的 table、读取 worker/physical version，并生成待回填 `current_version`、`arrow_schema_json`、`stats_json` 的安全计划 |
| `P2-META-010` | reconcile 回填成功 | 对失败表执行受控 reconcile | UC metadata 与物理 Lance table version/schema/stats 对齐，audit 记录 operator 和前后值 |

### 9.9 `P2-STORAGE` Storage Options 与 Credential Vending

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-STORAGE-001` | template 加载 | table 有 `storage_options_template_json` | backend command storage 含非敏感模板 |
| `P2-STORAGE-002` | runtime credentials 合并 | data op 需要对象存储凭证 | backend command storage 含临时凭证 |
| `P2-STORAGE-003` | runtime 覆盖模板 | 同名字段冲突 | runtime credentials 优先 |
| `P2-STORAGE-004` | 输出给 client 脱敏 | 错误/响应/audit | 不包含 secret/token/session |
| `P2-STORAGE-005` | 凭证过期 | mock expired credential | 返回受控错误，不调用 worker 或 worker 返回稳定错误 |
| `P2-STORAGE-006` | external location 权限不足 | 无对象存储访问权限 | 不下发凭证，返回 403 |
| `P2-STORAGE-007` | local FS | local table | storage binding 正确，无云凭证 |
| `P2-STORAGE-008` | S3 兼容 smoke | Nightly 对象存储 | worker 能基于 storage_options 访问 |

### 9.10 `P2-AUTH` 认证、授权、审计与指标

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-AUTH-001` | UC 内部 Bearer | query | principal/authType 进入 command |
| `P2-AUTH-002` | external Bearer | query | token exchange 后 principal 正确 |
| `P2-AUTH-003` | x-api-key | insert | service principal 正确 |
| `P2-AUTH-004` | Arrow 请求未认证 | insert 无 token/api key | 401，backend 不被调用 |
| `P2-AUTH-005` | READ_DATA allow | reader query/count/explain/analyze | 成功 |
| `P2-AUTH-006` | READ_DATA deny | deny user query | 403 |
| `P2-AUTH-007` | WRITE_DATA allow | writer insert/update/delete/create | 成功 |
| `P2-AUTH-008` | WRITE_DATA deny | reader insert/update/delete | 403 |
| `P2-AUTH-009` | 兼容权限映射 | 未定义 READ_DATA/WRITE_DATA 时 | SELECT/READ_METADATA/MODIFY 映射清晰 |
| `P2-AUTH-010` | context headers | `x-lance-*` | command/audit 包含 context，不覆盖 server 字段 |
| `P2-AUTH-011` | audit success | query/insert success | 记录 operation、backendType、version before/after、bytes、rows |
| `P2-AUTH-012` | audit failure | backend timeout/error | 记录 status/errorCode/backendRequestId |
| `P2-AUTH-013` | audit 脱敏 | storage credentials 存在 | audit/log 不含 secret/token |
| `P2-AUTH-014` | metrics | 多 endpoint 调用 | metrics labels operation/status/latency/backend error 正确 |
| `P2-AUTH-015` | LanceResourceKeyMapper 委托 | data endpoint 解析 Lance table resource | `KeyMapper` 仍为统一入口，并委托 `LanceResourceKeyMapper`；原 UC 三层 table resource 解析不受影响 |
| `P2-AUTH-016` | token exchange 不回环 | 外部 Bearer token exchange 的组件测试或代码审查 | `LanceAuthDecorator` 不通过内部 HTTP 回环调用 `/api/1.0/unity-control/auth/tokens`，而是直接调用内部 helper/service |
| `P2-AUTH-017` | LanceAuthDecorator 排除验证 | 非 Lance route 携带 `x-api-key`、`x-lance-*`、外部 Bearer | 非 Lance route 不进入 `LanceAuthDecorator`，仍由原有 auth/decorator 链处理 |

### 9.11 `P2-WORKER` Worker HTTP Backend

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-WORKER-001` | factory 配置 | `backend.type=worker-http` | 创建 `WorkerHttpLanceExecutionBackend` |
| `P2-WORKER-002` | base URL 来源 | 用户请求伪造 worker URL | backend 只用 server property |
| `P2-WORKER-003` | health path | worker health success/fail | startup 或 first call 行为明确 |
| `P2-WORKER-004` | JSON command path | query/count/stats/update/delete/explain/analyze | 调用对应 `/internal/lance/v1/commands/*` |
| `P2-WORKER-005` | Arrow command path | create/insert/merge_insert | 调用对应 `/internal/lance/v1/arrow/*` |
| `P2-WORKER-006` | header 透传 | requestId/deadline/idempotency | worker 收到正确 headers/body |
| `P2-WORKER-007` | backend error envelope | worker 返回标准错误 | UC 映射为 Lance error shape |
| `P2-WORKER-008` | worker timeout | worker 超时 | 503/504，retry 行为符合配置 |
| `P2-WORKER-009` | read retry | read 503 且允许 retry | 可重试一次，audit 记录 |
| `P2-WORKER-010` | write retry disabled | write 503 body 已发送 | 不自动 retry |

### 9.12 `P2-ERROR` 错误模型

| 用例 ID | 场景 | 断言 |
|---|---|---|
| `P2-ERROR-001` | invalid identifier | 400 `INVALID_ARGUMENT` |
| `P2-ERROR-002` | invalid JSON | 400 `INVALID_ARGUMENT` |
| `P2-ERROR-003` | unsupported media type | 415 `UNSUPPORTED_MEDIA_TYPE` |
| `P2-ERROR-004` | request too large | 413 `REQUEST_ENTITY_TOO_LARGE` |
| `P2-ERROR-005` | unauthenticated | 401 `UNAUTHENTICATED` |
| `P2-ERROR-006` | permission denied | 403 `PERMISSION_DENIED` |
| `P2-ERROR-007` | table not found | 404 `NOT_FOUND` |
| `P2-ERROR-008` | declared query conflict | 409 `CONFLICT` |
| `P2-ERROR-009` | legacy write | 406/501 `UNIMPLEMENTED` |
| `P2-ERROR-010` | backend disabled | 501 `UNIMPLEMENTED` |
| `P2-ERROR-011` | backend timeout | 503/504 `SERVICE_UNAVAILABLE` |
| `P2-ERROR-012` | worker conflict | 409 `ABORTED` 或 `CONFLICT` |
| `P2-ERROR-013` | worker internal error | 500 `INTERNAL`，隐藏 stack |
| `P2-ERROR-014` | backend committed metadata failed | 500/503，含 `backend_committed=true` |
| `P2-ERROR-015` | 错误字段结构 | 包含 `type`、`message`、`code`、`requestId`，可选 `backend_request_id` |
| `P2-ERROR-016` | restore table Phase 2 不支持 | `/v1/table/{id}/restore` 返回 `UNIMPLEMENTED` 或 404 route not mounted，但错误 shape 稳定 |
| `P2-ERROR-017` | rename table Phase 2 不支持 | `/v1/table/{id}/rename` 返回 `UNIMPLEMENTED` 或 404 route not mounted，但错误 shape 稳定 |
| `P2-ERROR-018` | schema evolution Phase 2 不支持 | `/schema_metadata/update`、`/add_columns`、`/alter_columns`、`/drop_columns` 返回 `UNIMPLEMENTED` 或 404 route not mounted，但错误 shape 稳定 |

### 9.13 `P2-CONCURRENCY` 并发、Idempotency 与 Backpressure

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| `P2-CONC-001` | 并发 query | 多线程 query 同一 ACTIVE table | 无状态污染，requestId 独立 |
| `P2-CONC-002` | 并发写 ACTIVE | 多个 insert 返回递增 version | current_version 不倒退 |
| `P2-CONC-003` | 并发首次物理化 | 两个 insert declared-only | 仅一个完成状态推进 |
| `P2-CONC-004` | idempotency key hash | 写请求携带 key | audit 不记录明文 |
| `P2-CONC-005` | Arrow backpressure | 大 stream 传给 fake worker | 不全量缓存，资源及时关闭 |
| `P2-CONC-006` | query stream 已开始后失败 | worker response 中途断开 | 不重试，客户端得到稳定 streaming failure |

### 9.14 `P2-CLIENT` Python / Java / Rust 原生客户端 Smoke

| 用例 ID | 客户端 | 场景 | 断言 |
|---|---|---|---|
| `P2-CLIENT-001` | Raw HTTP | 所有 10 个 endpoint smoke | method/content type/status 稳定 |
| `P2-CLIENT-002` | Python | connect + query + count + insert | 官方配置方式可用 |
| `P2-CLIENT-003` | Python | vector query + filter + withRowId | 结果形状可消费 |
| `P2-CLIENT-004` | Java | query/count/stats smoke | auth/header/table id 方式一致 |
| `P2-CLIENT-005` | Rust | query smoke | 错误语义可识别 |
| `P2-CLIENT-006` | 客户端错误识别 | backend disabled/permission denied | 客户端捕获预期异常结构 |

官方接入验收必须同时满足：配置方式与官方文档一致、namespace/table 标识方式一致、header/auth 透传方式一致、失败时错误语义可被官方客户端识别。

### 9.15 `P2-SPARK` Spark Connector Smoke

| 用例 ID | 场景 | 断言 |
|---|---|---|
| `P2-SPARK-001` | 官方 catalog 配置 | 不使用 UC 内部测试代理 |
| `P2-SPARK-002` | CREATE TABLE 或 declared 首次物理化 | 表变 ACTIVE |
| `P2-SPARK-003` | INSERT INTO | 数据写入成功，version/stats 更新 |
| `P2-SPARK-004` | SELECT | 结果正确 |
| `P2-SPARK-005` | UPDATE / DELETE | 若官方 connector 支持，则 smoke 通过；不支持则记录 skip 原因 |
| `P2-SPARK-006` | 多层 namespace | 不触发意外降级 |
| `P2-SPARK-007` | header/auth 透传 | Bearer/API key/context 可用 |
| `P2-SPARK-008` | 错误语义 | permission denied/backend disabled 可识别 |

### 9.16 `P2-RAY` Ray Connector Smoke

| 用例 ID | 场景 | 断言 |
|---|---|---|
| `P2-RAY-001` | read_lance | 基于 UC endpoint/metadata 可读 |
| `P2-RAY-002` | write_lance create | 首次写入成功 |
| `P2-RAY-003` | append/overwrite | version/stats 更新 |
| `P2-RAY-004` | filter/columns pushdown | 结果正确 |
| `P2-RAY-005` | 多 segment table_id | path 映射正确 |
| `P2-RAY-006` | storage_options | 对象存储访问成功 |
| `P2-RAY-007` | 并发参数 | 分布式执行不破坏 UC metadata |

### 9.17 `P2-LOCAL` DuckDB、Pandas、PyArrow

| 用例 ID | 场景 | 断言 |
|---|---|---|
| `P2-LOCAL-001` | UC resolve metadata/credentials | 获取 `storage_location` 与 `storage_options` |
| `P2-LOCAL-002` | DuckDB attach/read | 使用 Lance extension 成功读取 |
| `P2-LOCAL-003` | DuckDB SQL | `SELECT` 正常 |
| `P2-LOCAL-004` | DuckDB vector/FTS | 如果环境支持则 smoke |
| `P2-LOCAL-005` | Pandas DataFrame | Python/LanceDB/PyArrow 读取为 DataFrame |
| `P2-LOCAL-006` | PyArrow table | schema/列映射正确 |
| `P2-LOCAL-007` | 凭证过期 | 刷新或受控失败 |

这类测试不把 DuckDB/Pandas 当作 Lance REST 客户端，只验证 UC 能提供正确 metadata 与 credential resolution。

### 9.18 `P2-REG` 回归

| 用例 ID | 场景 | 断言 |
|---|---|---|
| `P2-REG-001` | Phase 1 namespace endpoint | create/list/describe/exists/drop 通过 |
| `P2-REG-002` | Phase 1 table metadata endpoint | register/declare/describe/list/drop/deregister 通过 |
| `P2-REG-003` | legacy metadata bridge | list/describe/exists 仍可用 |
| `P2-REG-004` | UC `/tables` | 原 CRUD 行为不变 |
| `P2-REG-005` | UC `/schemas` | 原 schema 行为不变 |
| `P2-REG-006` | Iceberg REST | config/namespace/table metadata route 不受影响 |
| `P2-REG-007` | Delta REST | config/commits route 不受影响 |
| `P2-REG-008` | 原有 AuthDecorator | 非 Lance route 不进入 LanceAuthDecorator |
| `P2-REG-009` | 原有 KeyMapper | 三层 UC resource 解析不受 Lance 委托影响 |
| `P2-REG-010` | LanceAuthDecorator route exclusion | 对 UC `/tables`、Iceberg、Delta 注入 Lance headers | 不触发 Lance auth side effect，不误用 API key principal，不污染审计中的 Lance operation |

## 10. 准入与准出

### 10.1 缺陷分级引用

Phase 2 缺陷分级沿用总体测试策略 `docs/lance/unitycatalog-lancedb-rest-api-test-strategy.md` Section 14 的 S0-S3 标准：

| 级别 | Phase 2 判定示例 |
|---|---|
| S0 | 越权 data access、错误凭证下发、runtime credential 泄漏、写操作导致不可恢复的数据/metadata 严重不一致 |
| S1 | query/Arrow IPC/DML 主路径不可用，或 backend disabled/error mapping 破坏客户端可识别错误语义 |
| S2 | Spark/Ray/Python smoke 部分失败、legacy bridge data 边界错误、metadata reconcile 证据不完整 |
| S3 | 非关键响应字段缺失、错误消息不友好、metrics label 细节不完整 |

Phase 2 准出必须满足总体策略 Section 13.2：对应阶段 P0 用例全部通过，高优先级缺陷全部关闭或已接受风险，回归测试无阻塞性失败。

### 10.2 准入条件

| 条件 | 验证方式 |
|---|---|
| Phase 1 metadata endpoint 通过 | 运行 Phase 1 测试 |
| Lance route / decorator 顺序明确 | `P2-CONTRACT-002/007/008` |
| backend 配置入口存在 | `P2-WORKER-001` 或 disabled backend 测试 |
| fake backend 可注入 | `P2-BACKEND-*` |
| Arrow fixture 可用 | `P2-ARROW-*` |
| H2 DDL 支持 Phase 2 字段 | `P2-META-*` |

### 10.3 PR 准出

PR 层至少通过：

- `P2-CONTRACT-*`
- `P2-REQ-*`
- `P2-BACKEND-*`
- `P2-RESOLVE-*`
- `P2-ARROW-001` 到 `P2-ARROW-009`
- `P2-DATA-READ` fake backend 用例
- `P2-DATA-WRITE` fake backend 用例
- `P2-META-*`
- `P2-STORAGE-001` 到 `P2-STORAGE-007`
- `P2-AUTH-*`
- `P2-ERROR-*`
- `P2-REG-*`

### 10.4 Nightly 准出

Nightly 层至少通过：

- 真实 worker/sidecar 下 `query/count/stats/insert/merge/update/delete/create`
- Arrow file/stream response 可被客户端读取
- local FS 和至少一个 S3 兼容对象存储 smoke
- Python Lance client data smoke
- Spark/Ray 最小数据面 smoke
- Worker timeout、backend error envelope、read retry/write no retry

### 10.5 发布前准出

发布前必须额外通过：

- PostgreSQL metadata DB
- metadata update failure + reconcile evidence，至少覆盖 dry-run 定位和受控回填成功路径
- 并发首次物理化和并发写
- storage credentials 日志/audit 脱敏检查
- DuckDB、Pandas/PyArrow local engine smoke
- 扩大 Spark/Ray/Python client 版本矩阵

## 11. 执行顺序建议

建议按以下顺序实现和执行测试：

1. `P2-CONTRACT`
2. `P2-BACKEND`
3. `P2-REQ`
4. `P2-RESOLVE`
5. `P2-ARROW`
6. `P2-DATA-READ`
7. `P2-DATA-WRITE`
8. `P2-META`
9. `P2-STORAGE`
10. `P2-AUTH`
11. `P2-ERROR`
12. `P2-WORKER`
13. `P2-CONCURRENCY`
14. `P2-REG`
15. `P2-CLIENT`
16. `P2-SPARK`
17. `P2-RAY`
18. `P2-LOCAL`

说明：

- 前 11 类可在 fake backend 下形成 PR 快速反馈。
- `P2-WORKER` 开始依赖 fake worker 或真实 worker。
- `P2-CLIENT`、`P2-SPARK`、`P2-RAY`、`P2-LOCAL` 更适合 Nightly 或发布前门禁。

## 12. 建议自动化命令

PR 层建议只跑不需要真实 LanceDB、外部对象存储或生态客户端 runtime 的测试：

```bash
bin/run-lance-phase2-pr-tests
```

该脚本展开为：

```bash
build/sbt "server/testOnly \
  io.unitycatalog.server.service.lance.LancePhase2RequestMappingRestTest \
  io.unitycatalog.server.service.lance.LancePhase2RequestValidationRestTest \
  io.unitycatalog.server.service.lance.LancePhase2ConfigurableBackendRestTest \
  io.unitycatalog.server.service.lance.LancePhase2DisabledBackendRestTest \
  io.unitycatalog.server.service.lance.LancePhase2DataPlaneAuthorizationRestTest \
  io.unitycatalog.server.service.lance.LanceDataPlaneAuthorizerTest \
  io.unitycatalog.server.service.lance.LancePhase2ArrowRequestReaderRestTest \
  io.unitycatalog.server.service.lance.LancePhase2ArrowResponseWriterRestTest \
  io.unitycatalog.server.service.lance.LancePhase2DataPlaneMetadataUpdateRestTest \
  io.unitycatalog.server.service.lance.LancePhase2BackendCommittedFailureRestTest \
  io.unitycatalog.server.service.lance.LancePhase2ReconcileRestTest \
  io.unitycatalog.server.service.lance.LancePhase2ObservabilityRestTest \
  io.unitycatalog.server.service.lance.LancePhase2ScalarResponseRestTest \
  io.unitycatalog.server.service.lance.LancePhase2ErrorResponseContractRestTest \
  io.unitycatalog.server.service.lance.LancePhase2StorageCredentialRestTest \
  io.unitycatalog.server.service.lance.LancePhase2ConcurrencyRestTest \
  io.unitycatalog.server.service.lance.LancePhase2LocalUnsupportedRestTest \
  io.unitycatalog.server.service.lance.LancePhase2WorkerHttpBackendRestTest"
build/sbt javafmtCheck server/testJavastyle
```

Nightly 层建议跑真实 worker、最小协议客户端和需要本地 toolchain 的 smoke。缺少 Python `lancedb`/`pyarrow`、`rustc` 或 Java toolchain 时，测试必须显式 skip 并输出原因：

```bash
build/sbt "server/testOnly \
  io.unitycatalog.server.service.lance.LancePhase2RealWorkerE2ERestTest \
  io.unitycatalog.server.service.lance.LancePhase2RealLanceDbWorkerProcessRestTest \
  io.unitycatalog.server.service.lance.LancePhase2RawHttpClientSmokeRestTest \
  io.unitycatalog.server.service.lance.LancePhase2PythonClientSmokeRestTest \
  io.unitycatalog.server.service.lance.LancePhase2JavaRustClientSmokeRestTest"
./clients/python/run-tests.sh
```

发布前层建议增加外部环境矩阵、生态 connector 和全量回归：

```bash
build/sbt -J-Xmx2G +jacoco
build/sbt -J-Xmx2G integrationTests/test
build/sbt "server/testOnly io.unitycatalog.server.service.lance.LancePhase2EcosystemSmokeTest"
build/sbt licenseCheck
```

发布前报告需要记录 PostgreSQL/H2、local FS/S3-compatible storage、worker backend、客户端或 connector 版本，以及所有 skip 的依赖缺失原因。

## 13. 风险与补充验证

| 风险 | 测试补偿 |
|---|---|
| OpenAPI 快照未落入仓库导致 DTO 漂移 | `P2-CONTRACT-001` 固定 diff |
| UC 主进程缓存 Arrow 大 body | `P2-ARROW-006`、`P2-CONC-005` |
| Arrow 二进制请求绕过 auth | `P2-AUTH-004` |
| body `identity` 覆盖真实 principal | `P2-REQ-003` |
| worker URL 被用户请求污染 | `P2-WORKER-002` |
| 写成功但 metadata 更新失败 | `P2-META-006`、`P2-ERROR-014` |
| metadata reconcile 入口缺失或不可审计 | `P2-META-009`、`P2-META-010` |
| legacy bridge 写入导致状态不可追踪 | `P2-RESOLVE-009` |
| READ_DATA/WRITE_DATA 未正式建模 | `P2-AUTH-009` 明确兼容映射 |
| LanceResourceKeyMapper 委托影响原 UC KeyMapper | `P2-AUTH-015`、`P2-REG-009` |
| 外部 Bearer token exchange 通过内部 HTTP 回环 | `P2-AUTH-016` |
| LanceAuthDecorator 误处理非 Lance route | `P2-AUTH-017`、`P2-REG-010` |
| Phase 2 不支持端点错误语义不稳定 | `P2-ERROR-016`、`P2-ERROR-017`、`P2-ERROR-018` |
| schema peek 读取过深导致 UC 主进程承担执行职责 | `P2-ARROW-011` |
| runtime credentials 泄漏 | `P2-STORAGE-004`、`P2-AUTH-013` |
| Spark/Ray 版本差异 | Nightly 版本矩阵，失败记录版本与错误语义 |

## 14. 验收证据

每轮 Phase 2 测试至少产出：

- Endpoint 覆盖报告，列出 10 个 data endpoint 的 method/content type/status。
- Fake backend command capture，证明 request 被正确转换为 command。
- Arrow fixture 读写证据，证明 query response 可被 Arrow reader 消费。
- Metadata DB 断言截图或日志，证明 state/current_version/schema/stats 更新正确。
- Error mapping 样例，覆盖 400/401/403/404/409/413/415/500/501/503/504。
- Audit/metrics 样例，证明成功和失败路径均可观测且脱敏。
- Worker HTTP capture，证明内部 API、headers、deadline、idempotency 透传正确。
- Connector smoke 日志，至少覆盖 Python、Spark、Ray。
- Regression 报告，证明 Phase 1 metadata endpoint 与 UC 原有路由未受影响。

## 15. 最终建议

Phase 2 测试应先把 UC data plane adapter 本身测稳，再扩大到真实 worker 和生态客户端。

推荐第一批自动化测试使用 fake backend 覆盖所有 endpoint、request parsing、auth、storage options、metadata update 和错误映射；第二批再接 fake worker HTTP 验证内部协议；第三批才用真实 worker/sidecar 验证 Arrow IPC 与数据结果。

这样可以把问题分层定位：如果 fake backend 失败，说明 UC 协议适配或治理链路有问题；如果 fake backend 成功但 worker E2E 失败，才重点排查 worker、对象存储或 Lance 执行环境。
