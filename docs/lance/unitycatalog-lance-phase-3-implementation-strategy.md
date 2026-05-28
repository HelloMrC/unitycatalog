# Unity Catalog Lance Phase 3 实现策略总结

更新日期：2026-05-21

## 1. 核心设计原则

**UC 作为元数据唯一事实来源（Single Source of Truth）**

```
Lance SDK/Worker ── 直连 Lance 存储执行物理操作 ── 成功 ── 调用 UC sync API ── UC 存储元数据 ── 返回成功
```

- UC 是元数据存储和查询层
- UC 不从 Lance 物理存储同步数据
- UC 不执行 Lance 物理操作（除 deleteVersions forwarding）
- Lance SDK 调用 sync API 把执行结果记录到 UC

---

## 2. Phase 3 API 实现状态

### 2.1 sync API（已实现）

| API | 调用时机 | UC 行为 | 测试覆盖 |
|-----|----------|---------|----------|
| `syncVersion` | Lance SDK 写入成功后 | 存储到 `uc_lance_versions` | ✅ 11 tests |
| `syncIndex` | Lance SDK 创建/删除索引后 | 存储到 `uc_lance_indices` | ✅ 11 tests |
| `syncSchema` | Lance SDK schema 变化后 | 更新 `arrow_schema_json` | ✅ 12 tests |
| `syncTransaction` | Lance SDK 事务执行后 | 存储到 `uc_lance_transactions` | ✅ 17 tests |
| `syncTag` | Lance SDK 创建 tag 后 | 存储到 `uc_lance_tags` | ✅ (Tag CRUD) |

### 2.2 查询 API（已实现）

| API | 数据来源 | 测试覆盖 |
|-----|----------|----------|
| `version/list` | `uc_lance_versions` | ✅ |
| `version/describe` | `uc_lance_versions` | ✅ |
| `index/list` | `uc_lance_indices` | ✅ |
| `index/describe` | `uc_lance_indices` | ✅ |
| `transaction/list` | `uc_lance_transactions` | ✅ |
| `transaction/describe` | `uc_lance_transactions` | ✅ |
| `tags/list` | `uc_lance_tags` | ✅ |
| `tags/get` | `uc_lance_tags` | ✅ |

### 2.3 Tag CRUD（已实现，UC 独立）

| API | 实现方式 | 说明 |
|-----|----------|------|
| `tags/create` | UC 独立实现 | 纯 metadata 操作 |
| `tags/update` | UC 独立实现 | 纯 metadata 操作 |
| `tags/delete` | UC 独立实现 | 纯 metadata 操作 |

### 2.4 Forwarding Endpoint（仅 deleteVersions）

| API | 实现状态 | 说明 |
|-----|----------|------|
| `deleteVersions` | ✅ 已实现 | 转发到 Worker 执行物理 Lance manifest 操作 |

---

## 3. 不需要 Forwarding Endpoint 的操作

**设计决策**：以下操作 Lance SDK 直连存储执行，完成后调用 sync API，UC 不作为协议转发层。

| 操作 | Lance SDK 执行 | 完成后调用 UC |
|------|----------------|---------------|
| `createIndex` | Lance SDK 创建索引 | `syncIndex` |
| `dropIndex` | Lance SDK 删除索引 | `syncIndex` |
| `addColumns` | Lance SDK 添加列 | `syncSchema` |
| `alterColumns` | Lance SDK 修改列 | `syncSchema` |
| `dropColumns` | Lance SDK 删除列 | `syncSchema` |
| `batchCommit` | Lance SDK 执行批量提交 | `syncVersion` + `syncTransaction` |
| `alterTransaction` | Lance SDK 操作事务 | `syncTransaction` |

**原因**：
1. UC 是元数据治理层，不是执行转发层
2. Lance SDK 可直连 Lance 存储执行，无需 UC 中转
3. 执行完成后调用 UC sync API 即可完成元数据闭环
4. 减少不必要的网络跳转

---

## 4. 不支持的操作

| API | 原因 | UC 处理 |
|-----|------|---------|
| `createVersion` | Lance version 由写入自动产生，不能手动创建 | 400 BAD_REQUEST |
| `batchCreateVersions` | 同上 | 400 BAD_REQUEST |
| `restoreTable` | Lance 文件格式操作，超出 UC 元数据治理范围 | 501 UNIMPLEMENTED |

---

## 5. Phase 2 vs Phase 3 模式对比

| Phase | 模式 | 示例 |
|-------|------|------|
| Phase 2 | UC Forwarding | query/insert/update/delete → UC → Worker → Lance |
| Phase 3 (元数据) | Sync API | Lance SDK 执行 → 调用 UC sync API → UC 存储 |

**Phase 2 Forwarding 原因**：
- 数据面操作（query/DML）需要 UC 进行授权、凭证下发
- UC 作为协议适配层，提供统一治理入口

**Phase 3 Sync API 原因**：
- 元数据操作不需要 UC 中转执行
- Lance SDK 可自行执行并报告结果
- UC 作为元数据存储的唯一来源

---

## 6. 测试覆盖总结

| 测试文件 | 测试数 | 覆盖范围 |
|----------|--------|----------|
| `LancePhase3MetadataRestTest` | 18 | Tag CRUD + Version Query |
| `LancePhase3MetadataSyncRestTest` | 11 | syncVersion 边界 |
| `LancePhase3IndexRestTest` | 11 | syncIndex + Index Query |
| `LancePhase3TransactionRestTest` | 17 | syncTransaction + Transaction Query |
| `LancePhase3SchemaRestTest` | 12 | syncSchema 边界 |
| `LancePhase3BoundaryAndEdgeRestTest` | 49 | 边界值/错误处理 |
| `LancePhase3IntegrationRestTest` | 22 | E2E 集成流程 |
| **总计** | **139** | ✅ 全部通过 |

---

## 7. 后续无待实现项

Phase 3 按当前设计已全部完成：

- ✅ 所有 sync API 已实现
- ✅ 所有元数据查询 API 已实现
- ✅ Tag CRUD 已实现
- ✅ deleteVersions forwarding 已实现
- ✅ 语义错误正确拒绝
- ✅ 边界/集成测试完整覆盖

**无需额外实现 forwarding endpoint**（createIndex/dropIndex/addColumns 等）。

---

## 8. 与设计文档的关系

已更新以下设计文档以反映正确实现策略：

| 文档 | 更新内容 |
|------|----------|
| `unitycatalog-lancedb-rest-api-requirements.md` | Section 10.6：明确 UC 作为元数据唯一事实来源，sync API 调用时机，无 forwarding endpoint |
| `unitycatalog-lancedb-phase-3-metadata-sync-test-design.md` | 实现状态、测试分层、测试覆盖矩阵、设计决策说明 |

---

## 9. 结论

Phase 3 实现策略已按"UC 作为元数据唯一事实来源"原则完成：
- Lance SDK 执行物理操作 → 调用 UC sync API → UC 存储元数据
- UC 提供 sync API 和元数据查询 API
- 无需额外 forwarding endpoint
- 139 个测试全部通过，覆盖完整