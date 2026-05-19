# Unity Catalog Lance REST API Phase 3 开发进度

更新日期：2026-05-16

## 1. 文档目标

本文档用于追踪 Lance Phase 3 "高级资产元数据治理 / Metadata Sync" 的开发进度。

当前进度基于：

- `docs/lance/unitycatalog-lance-phase-3-analysis.md`
- `docs/lance/unitycatalog-lancedb-phase-3-metadata-sync-design.md`
- `docs/lance/unitycatalog-lancedb-phase-3-metadata-sync-test-design.md`
- 当前代码实现，截止提交 `baf1123`

Phase 3 的目标不是让 UC 执行 Lance 物理数据操作，而是让 UC 作为 Lance Catalog 层：

- 提供可演进的 Lance 原生 metadata model
- 支持纯 metadata 能力，例如 tag CRUD
- 查询执行器同步到 UC 的 version / index / transaction / schema metadata
- 对依赖 Lance SDK / Worker 操作物理存储的能力保留协议转发和同步入口
- 对语义上不成立的 API 返回明确错误，而不是伪实现

---

## 2. 总体状态

| 能力域 | 设计目标 | 当前状态 | 说明 |
|--------|----------|----------|------|
| Version metadata store | `uc_lance_versions` 记录版本历史 | 已实现 | DAO、Repository、Hibernate 注册和持久化测试已完成 |
| Tag metadata store | `uc_lance_tags` 记录 tag -> version | 已实现 | DAO、Repository、Hibernate 注册和持久化测试已完成 |
| Data plane version sync | 写入成功后记录 version metadata | 已实现 | 当前为 UC 内部写路径同步，不是外部 `syncVersion` API |
| Version read API | `version/list`、`version/describe` | 已实现 | 查询 `uc_lance_versions` |
| Tag CRUD API | `tags/list/get/create/update/delete` | 已实现 | 纯 metadata 操作，创建/更新时校验 version 存在 |
| Version semantic errors | `createVersion`、`batchCreateVersions` 明确拒绝 | 已实现 | 返回 `INVALID_ARGUMENT` / HTTP 400 |
| `deleteVersions` | 需要 Lance SDK / Worker 执行后同步 | 部分实现 | REST endpoint 已返回 `UNIMPLEMENTED`，未执行物理删除 |
| Index metadata | index 表、查询 API、同步 API | 未实现 | 尚无 `uc_lance_indices`、Repository、REST 服务 |
| Transaction metadata | transaction 表、查询 API、同步 API | 未实现 | 尚无 `uc_lance_transactions`、Repository、REST 服务 |
| Schema history / sync | schema history 表、schema sync API | 未实现 | 当前只有 Phase 2 table 当前 schema/stats 缓存 |
| 显式 metadata sync API | `syncVersion/syncIndex/syncSchema/syncTransaction/syncTag` | 未实现 | 当前 version 只在 UC data plane write 后内部记录 |
| Phase 3 Worker forwarding | index/deleteVersions/batchCommit/schema/restore 转发 | 未实现 | `LanceExecutionBackend` 尚未扩展 Phase 3 方法 |

---

## 3. 已完成 Commit

| Commit | 内容 | 说明 |
|--------|------|------|
| `9ad95fe` | Add Lance Phase 3 API feasibility analysis | 梳理 Phase 3 API 可实现性 |
| `49ba202` | Update Lance Phase 3 analysis: support version read, reject version write | 明确 version read 可实现、version write 语义拒绝 |
| `50db470` | Add Lance Phase 3 version and tag metadata stores | 新增 version/tag DAO、Repository、Hibernate 注册和持久化测试 |
| `34b5bc2` | Record Lance write versions after data-plane commits | 写入成功后同步 version metadata |
| `baf1123` | Expose Lance Phase 3 version and tag metadata APIs | 暴露 version 查询和 tag CRUD REST API |

---

## 4. 已完成功能

### 4.1 Version 元数据表

| 功能 | 状态 | 代码位置 | 说明 |
|------|------|----------|------|
| `LanceVersionDAO` | 已实现 | `server/src/main/java/io/unitycatalog/server/persist/dao/LanceVersionDAO.java` | 映射 `uc_lance_versions` |
| `LanceVersionRepository` | 已实现 | `server/src/main/java/io/unitycatalog/server/persist/LanceVersionRepository.java` | 支持 upsert、find、exists、list |
| Hibernate 注册 | 已实现 | `HibernateConfigurator` | 注册 `LanceVersionDAO` |
| Repository 注入 | 已实现 | `Repositories` | 暴露 `getLanceVersionRepository()` |

当前字段覆盖：

- `asset_id`
- `version`
- `operation`
- `timestamp`
- `manifest_path`
- `manifest_size`
- `etag`
- `metadata_json`
- `stats_json`
- `created_by`
- `created_at`

说明：

- `version` 由 Lance 写入产生，UC 不主动生成。
- 当前写路径只记录 backend response 中可见字段；manifest path、etag 等字段取决于执行器是否返回。

### 4.2 Tag 元数据表

| 功能 | 状态 | 代码位置 | 说明 |
|------|------|----------|------|
| `LanceTagDAO` | 已实现 | `server/src/main/java/io/unitycatalog/server/persist/dao/LanceTagDAO.java` | 映射 `uc_lance_tags` |
| `LanceTagRepository` | 已实现 | `server/src/main/java/io/unitycatalog/server/persist/LanceTagRepository.java` | 支持 create、update、delete、find、list |
| Hibernate 注册 | 已实现 | `HibernateConfigurator` | 注册 `LanceTagDAO` |
| Repository 注入 | 已实现 | `Repositories` | 暴露 `getLanceTagRepository()` |

当前字段覆盖：

- `asset_id`
- `table_asset_id`
- `tag_name`
- `version`
- `metadata_json`
- `created_at`
- `created_by`
- `updated_at`
- `updated_by`

说明：

- 当前 tag 是 table-scoped metadata row。
- `createTag` / `updateTag` 会校验目标 version 已存在。
- 非 table 资产的一等治理生命周期还未完整建模。

### 4.3 写入后 Version Metadata 同步

| 功能 | 状态 | 代码位置 | 说明 |
|------|------|----------|------|
| 写入成功后记录 version | 已实现 | `LanceDataPlaneMetadataUpdater` | `afterWrite` 中调用 `LanceVersionRepository.upsertVersion` |
| 操作类型记录 | 已实现 | `LanceDataPlaneService` | 显式传入 `insert/create/update/delete/merge_insert` |
| stale version 防倒退 | 已保留 | `LanceDataPlaneMetadataUpdater` | 后端返回旧 version 时不更新 table metadata，也不记录 version row |
| declared table materialize 后记录 version | 已实现 | `LanceDataPlaneMetadataUpdater` | 首次 insert/create 推进 table ACTIVE 后记录 version |

当前同步边界：

- 只覆盖经过 UC data plane service 的写路径。
- 不覆盖外部 Lance SDK 直连存储后主动回写 UC 的场景。
- 不覆盖 `deleteVersions`、index、transaction、schema evolution 的同步。

### 4.4 Version REST API

| API | 状态 | 服务 | 说明 |
|-----|------|------|------|
| `POST /v1/table/{id}/version/list` | 已实现 | `LanceRestVersionService` | 支持 `page_size`、`page_token`、`start_version`、`end_version` |
| `POST /v1/table/{id}/version/describe` | 已实现 | `LanceRestVersionService` | 查询单个 version detail |
| `POST /v1/table/{id}/version/create` | 已实现拒绝 | `LanceRestVersionService` | 返回 400，version 不能手动创建 |
| `POST /v1/table/batch-create-versions` | 已实现拒绝 | `LanceRestVersionService` | 返回 400，version 不能批量创建 |
| `POST /v1/table/{id}/version/delete` | 已实现拒绝 | `LanceRestVersionService` | 返回 501，等待 Lance execution backend 支持 |

响应内容覆盖：

- `version`
- `operation`
- `timestamp`
- `manifest_path`
- `manifest_size`
- `etag`
- `metadata`
- `stats`
- `created_by`
- `created_at`

说明：

- legacy bridge table 不支持 version metadata。
- 当前 version API 查询的是 UC 已同步 metadata，不读取物理 Lance manifest。

### 4.5 Tag REST API

| API | 状态 | 服务 | 说明 |
|-----|------|------|------|
| `POST /v1/table/{id}/tags/list` | 已实现 | `LanceRestTagService` | 支持 `page_size`、`page_token` |
| `POST /v1/table/{id}/tags/get` | 已实现 | `LanceRestTagService` | 查询 tag -> version |
| `POST /v1/table/{id}/tags/get-version` | 已实现 | `LanceRestTagService` | 与 `tags/get` 等价 |
| `POST /v1/table/{id}/tags/version` | 已实现 | `LanceRestTagService` | 兼容别名 |
| `POST /v1/table/{id}/tags/create` | 已实现 | `LanceRestTagService` | 校验 table ACTIVE 和 version 存在 |
| `POST /v1/table/{id}/tags/update` | 已实现 | `LanceRestTagService` | 支持 `new_version/new_metadata` 与兼容 `version/metadata` |
| `POST /v1/table/{id}/tags/delete` | 已实现 | `LanceRestTagService` | 删除 tag metadata |

说明：

- tag CRUD 是纯 UC metadata 操作，不需要 LanceDB 存储。
- declared-only table 创建/更新/删除 tag 会被拒绝，需要先 materialize。
- legacy bridge table 不支持 tag metadata。

### 4.6 服务注册

| 服务 | 状态 | 代码位置 |
|------|------|----------|
| `LanceRestVersionService` | 已注册 | `UnityCatalogServer` |
| `LanceRestTagService` | 已注册 | `UnityCatalogServer` |

---

## 5. 测试进度

### 5.1 已新增/更新测试

| 测试类 | 覆盖范围 |
|--------|----------|
| `LanceVersionAndTagRepositoryTest` | version upsert/list、tag create/update/list/delete、duplicate tag |
| `LancePhase2DataPlaneMetadataUpdateRestTest` | 写入后同步 version row、版本防倒退时不记录旧 version |
| `LancePhase3MetadataRestTest` | version list/describe、tag CRUD、手动 createVersion 语义拒绝 |

### 5.2 最近验证命令

```bash
build/sbt "server/testOnly io.unitycatalog.server.service.lance.LancePhase3MetadataRestTest io.unitycatalog.server.service.lance.LancePhase2DataPlaneMetadataUpdateRestTest io.unitycatalog.server.persist.LanceVersionAndTagRepositoryTest"
```

验证结果：

- 11 个测试通过
- 覆盖 Phase 3 当前已实现最小闭环

---

## 6. 未实现项

### 6.1 Index Metadata

设计目标：

- `uc_lance_indices`
- `LanceIndexDAO`
- `LanceIndexRepository`
- `POST /v1/table/{id}/index/list`
- `POST /v1/table/{id}/index/describe`
- `syncIndex`
- `createIndex/dropIndex` Worker 执行后同步

当前状态：

- 未实现表、DAO、Repository、REST service。
- `LanceExecutionBackend` 未包含 Phase 3 index 方法。
- Worker HTTP backend 未实现 index 命令转发。

### 6.2 Transaction Metadata

设计目标：

- `uc_lance_transactions`
- `LanceTransactionDAO`
- `LanceTransactionRepository`
- `POST /v1/table/{id}/transaction/describe`
- `syncTransaction`
- `batchCommit/alterTransaction` Worker 执行后同步

当前状态：

- 未实现表、DAO、Repository、REST service。
- `LanceExecutionBackend` 未包含 transaction 方法。

### 6.3 Schema Sync / Schema History

设计目标：

- `uc_lance_schema_history`
- `syncSchema`
- `schema/update`
- `add_columns`
- `alter_columns`
- `drop_columns`
- schema evolution 后更新 table `arrow_schema_json`

当前状态：

- Phase 2 已有 table 当前 schema cache。
- Phase 3 schema history 和显式 schema sync 未实现。
- schema evolution 仍依赖后续 Worker / Lance SDK 执行能力。

### 6.4 显式 Metadata Sync API

设计目标：

- `POST /v1/table/{id}/metadata/sync/version`
- `POST /v1/table/{id}/metadata/sync/index`
- `POST /v1/table/{id}/metadata/sync/schema`
- `POST /v1/table/{id}/metadata/sync/transaction`
- `POST /v1/table/{id}/metadata/sync/tag`

当前状态：

- 未实现独立 sync service。
- 目前 version 只在 UC 内部 data plane write 成功后同步。
- 外部 Lance SDK / Worker 直连存储后的主动回写还没有入口。

### 6.5 Phase 3 Protocol Forwarding

设计目标：

- 扩展 `LanceExecutionBackend`
- 扩展 `WorkerHttpLanceExecutionBackend`
- 新增或扩展 REST data plane route
- Worker 执行成功后调用 metadata sync

待转发能力：

- `createIndex`
- `dropIndex`
- `deleteVersions`
- `batchCommit`
- `alterTransaction`
- `addColumns`
- `alterColumns`
- `dropColumns`
- `restoreTable`

当前状态：

- 未实现。
- `deleteVersions` 已有 REST endpoint，但仅返回 `UNIMPLEMENTED`。
- `restoreTable` 仍沿用 Phase 2 行为，返回 `UNIMPLEMENTED`。

### 6.6 非 Table 资产的一等治理模型

设计目标：

- index / version / tag / transaction 均可作为 Lance governed asset 参与生命周期、授权和审计。
- 不能长期塞入 UC legacy table properties。

当前状态：

- version/tag 已有专用表。
- version/tag 尚未完整接入非 table asset 的统一生命周期状态机。
- tag DAO 当前记录 `asset_id`，但 authorization hierarchy 仍以 table asset 为主。
- index/transaction 尚未建模。

---

## 7. 当前实现边界

1. UC 当前不会读取或修改物理 Lance manifest。
2. UC 当前不会主动执行 index、schema evolution、transaction、restore、deleteVersions。
3. Version 查询只依赖 UC 已同步 metadata；如果后端不返回 version，UC 不会伪造 version。
4. Tag CRUD 只修改 UC metadata；不会写入 LanceDB 物理存储中的 tag 状态。
5. Legacy bridge table 只用于兼容现有 `catalog.schema.table` 形态的只读 metadata，不支持 Phase 3 的 version/tag/index/transaction 资产。
6. 当前 Phase 3 已实现能力优先覆盖“不依赖 LanceDB 存储执行”的元数据闭环。

---

## 8. 下一步建议

### 8.1 高优先级

| 优先级 | 工作项 | 原因 |
|--------|--------|------|
| P0 | 实现显式 `syncVersion` API | 让外部 Worker / Lance SDK 直连存储后可以回写 UC |
| P0 | 补充 sync API 鉴权和幂等规则 | 避免外部同步入口成为任意 metadata 写入口 |
| P1 | 实现 `uc_lance_indices` + index 查询 API | Phase 3 设计中 version 后最核心的查询能力 |
| P1 | 实现 `uc_lance_transactions` + transaction describe | 为 batch commit / transaction 状态打基础 |

### 8.2 中优先级

| 优先级 | 工作项 | 原因 |
|--------|--------|------|
| P2 | 扩展 `LanceExecutionBackend` Phase 3 方法 | 为 Worker 协议转发做准备 |
| P2 | 实现 `deleteVersions` Worker forwarding | 需要物理 Lance manifest 操作，不能 UC 本地伪实现 |
| P2 | 实现 schema sync 和 schema history | 支持后续 schema evolution 查询与审计 |

### 8.3 低优先级

| 优先级 | 工作项 | 原因 |
|--------|--------|------|
| P3 | 统一非 table Lance asset lifecycle | 完整治理模型增强 |
| P3 | 补充 OpenAPI/generated model 对齐 | 当前 REST service 使用手写 request/response records |
| P3 | 扩展客户端 smoke 测试 | 当前 Phase 3 主要覆盖 server REST 和 repository |

---

## 9. 准入/准出状态

| 准出项 | 状态 | 说明 |
|--------|------|------|
| Tag CRUD 可用 | 通过 | `LancePhase3MetadataRestTest` 覆盖 |
| Version 查询可用 | 通过 | `LancePhase3MetadataRestTest` 覆盖 |
| 写入后 version metadata 可同步 | 通过 | `LancePhase2DataPlaneMetadataUpdateRestTest` 覆盖 |
| Version/tag repository 行为稳定 | 通过 | `LanceVersionAndTagRepositoryTest` 覆盖 |
| Index 查询可用 | 未通过 | 未实现 |
| Transaction 查询可用 | 未通过 | 未实现 |
| 显式 metadata sync API 可用 | 未通过 | 未实现 |
| Phase 3 Worker forwarding 可用 | 未通过 | 未实现 |

当前结论：

Phase 3 已完成“可本地实现且不依赖 LanceDB 物理存储执行”的第一批能力：

- version metadata persistence
- write 后 version sync
- version read API
- tag metadata CRUD
- version write semantic rejection

下一阶段应优先补齐外部执行器回写 UC 所需的显式 sync API，然后再扩展 index / transaction / schema / protocol forwarding。
