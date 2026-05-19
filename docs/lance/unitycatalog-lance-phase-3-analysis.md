# Unity Catalog Lance Phase 3 元数据治理设计

更新日期：2026-05-15

## 1. UC 的正确定位：Catalog 层

**核心原则：Unity Catalog 是 Lance 的 Catalog 层（元数据治理），不是执行引擎。**

### 1.1 架构模式

```
┌─────────────────────────────────────────────────────────────┐
│                    执行器/客户端                              │
│  (Lance SDK / Spark / Ray / Lance Worker)                   │
├─────────────────────────────────────────────────────────────┤
│  1. 通过 UC 获取元数据 (storage_location + credential)       │
│  2. 直连存储执行操作 (query/insert/index 等)                  │
│  3. 操作成功后，同步结果到 UC                                  │
│     - version 信息                                            │
│     - index 元数据                                            │
│     - schema 变化                                             │
│     - transaction 状态                                        │
└─────────────────────────────────────────────────────────────┘
                              ↓ ↑
┌─────────────────────────────────────────────────────────────┐
│                    UC (Catalog 层)                           │
├─────────────────────────────────────────────────────────────┤
│  • 元数据发现：namespace/table 定位                           │
│  • 凭证下发：storage_options                                  │
│  • 元数据记录：接收执行器同步的结果                            │
│  • 查询 API：listVersions/listIndices/listTags 等            │
│  • Tag CRUD：纯 metadata 操作，UC 可独立管理                  │
│  • 治理能力：认证、授权、审计                                  │
└─────────────────────────────────────────────────────────────┘
                              ↓ ↑
┌─────────────────────────────────────────────────────────────┐
│                    Lance 存储                                 │
│  (对象存储上的 .lance 文件)                                   │
├─────────────────────────────────────────────────────────────┤
│  • 数据文件                                                   │
│  • Manifest (version/index 元数据)                           │
│  • 索引结构                                                   │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 UC 与执行引擎的职责分离

| 职责 | UC (Catalog 层) | 执行引擎 (Lance SDK/Worker) |
|------|-----------------|----------------------------|
| **元数据发现** | ✅ 提供 namespace/table 定位 | 通过 UC 获取 |
| **凭证下发** | ✅ 提供 storage_options | 使用 UC 下发的凭证 |
| **数据读写** | ❌ 不执行 | ✅ 直连存储执行 |
| **索引创建** | ❌ 不执行 | ✅ Lance SDK 执行，完成后同步元数据到 UC |
| **版本产生** | ❌ 不产生（自动生成） | ✅ Lance 写入自动产生，同步到 UC |
| **Tag 管理** | ✅ 可独立 CRUD | ✅ Lance SDK 也可管理 |
| **事务执行** | ❌ 不执行 | ✅ Lance SDK 执行，同步状态到 UC |
| **Schema 变更** | ❌ 不执行 | ✅ Lance SDK 执行，同步 schema 到 UC |

---

## 2. Phase 3 能力分类

### 2.1 UC 可独立实现的纯 Metadata API

| API | 说明 | UC 实现方式 |
|-----|------|-------------|
| `listTags` | 查询 `uc_lance_tags` 表 | ✅ UC 独立实现 |
| `getTagVersion` | 查询 Tag -> Version 映射 | ✅ UC 独立实现 |
| `createTag` | 创建 Tag 元数据 | ✅ UC 独立实现 |
| `updateTag` | 更新 Tag 元数据 | ✅ UC 独立实现 |
| `deleteTag` | 删除 Tag 元数据 | ✅ UC 独立实现 |

### 2.2 UC 提供查询 API，元数据由执行器同步

| API | 说明 | 数据来源 | UC 实现 |
|-----|------|----------|---------|
| `listVersions` | 查询版本历史 | 执行器写入后同步到 `uc_lance_versions` | ✅ UC 提供查询 |
| `describeVersion` | 查询版本详情 | 执行器写入后同步 | ✅ UC 提供查询 |
| `listIndices` | 查询索引列表 | 执行器创建索引后同步到 `uc_lance_indices` | ✅ UC 提供查询 |
| `describeIndexStats` | 查询索引统计 | 执行器创建索引后同步 | ✅ UC 提供查询 |
| `describeTransaction` | 查询事务状态 | 执行器执行事务后同步 | ✅ UC 提供查询 |

### 2.3 执行器执行，UC 记录结果

| 操作 | 执行方式 | UC 角色 |
|------|----------|---------|
| `createIndex` | Lance SDK 直连存储创建索引 | 接收执行器同步的索引元数据 |
| `dropIndex` | Lance SDK 删除索引 | 更新 UC 元数据表 |
| `deleteVersions` | Lance SDK 删除版本 | 更新 UC 元数据表 |
| `batchCommit` | Lance SDK 执行批量提交 | 接收执行器同步的结果 |
| `alterTransaction` | Lance SDK 操作事务 | 更新 UC 元数据表 |

### 2.4 UC 不支持的操作（语义错误）

| API | 原因 | UC 处理 |
|-----|------|---------|
| `createVersion` | Lance version 由写入自动产生，不能手动创建 | 400 BAD_REQUEST |
| `batchCreateVersions` | 同上 | 400 BAD_REQUEST |

---

## 3. 元数据表设计

### 3.1 Version 元数据表

```sql
CREATE TABLE uc_lance_versions (
  id UUID PRIMARY KEY,
  asset_id UUID NOT NULL REFERENCES uc_lance_assets(id),
  version BIGINT NOT NULL,
  operation VARCHAR(50) NOT NULL,  -- 'insert', 'update', 'delete', 'merge_insert', 'create_index'
  timestamp TIMESTAMP NOT NULL,
  manifest_path VARCHAR(500),
  manifest_size BIGINT,
  etag VARCHAR(255),
  metadata_json TEXT,
  created_by VARCHAR(255),
  UNIQUE(asset_id, version)
);
```

**元数据同步时机**：
- Lance 执行引擎写入成功后返回 version 和 manifest 信息
- 执行器调用 UC 的元数据同步 API 将 version 信息写入 `uc_lance_versions`
- UC 提供 `listVersions` / `describeVersion` 查询 API

### 3.2 Index 元数据表

```sql
CREATE TABLE uc_lance_indices (
  id UUID PRIMARY KEY,
  asset_id UUID NOT NULL REFERENCES uc_lance_assets(id),
  table_asset_id UUID NOT NULL REFERENCES uc_lance_assets(id),
  index_name VARCHAR(255) NOT NULL,
  index_type VARCHAR(50) NOT NULL,  -- 'vector', 'scalar', 'fts'
  target_columns_json TEXT NOT NULL,
  distance_type VARCHAR(50),
  build_params_json TEXT,
  stats_json TEXT,
  status VARCHAR(20) NOT NULL,  -- 'READY', 'BUILDING', 'FAILED'
  created_at TIMESTAMP NOT NULL,
  created_by VARCHAR(255),
  UNIQUE(table_asset_id, index_name)
);
```

**元数据同步时机**：
- Lance SDK 执行 `createIndex` 成功后，调用 UC API 同步索引元数据
- UC 提供 `listIndices` / `describeIndexStats` 查询 API

### 3.3 Tag 元数据表

```sql
CREATE TABLE uc_lance_tags (
  id UUID PRIMARY KEY,
  asset_id UUID NOT NULL REFERENCES uc_lance_assets(id),
  table_asset_id UUID NOT NULL REFERENCES uc_lance_assets(id),
  tag_name VARCHAR(255) NOT NULL,
  version BIGINT NOT NULL,
  metadata_json TEXT,
  created_at TIMESTAMP NOT NULL,
  created_by VARCHAR(255),
  updated_at TIMESTAMP,
  updated_by VARCHAR(255),
  UNIQUE(table_asset_id, tag_name)
);
```

**Tag 管理模式**：
- **UC 独立管理**：`createTag` / `updateTag` / `deleteTag` 为纯 metadata 操作，UC 可独立实现
- **Lance SDK 管理**：Lance SDK 也可以创建/管理 tag，完成后同步到 UC
- 两种方式结果一致：tag 作为 version 的命名别名，存储在 UC 元数据表中

### 3.4 Transaction 元数据表

```sql
CREATE TABLE uc_lance_transactions (
  id UUID PRIMARY KEY,
  asset_id UUID NOT NULL REFERENCES uc_lance_assets(id),
  transaction_key VARCHAR(255) NOT NULL,
  table_asset_id UUID NOT NULL REFERENCES uc_lance_assets(id),
  status VARCHAR(20) NOT NULL,  -- 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED'
  actions_json TEXT,
  commit_metadata_json TEXT,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP,
  UNIQUE(transaction_key)
);
```

**元数据同步时机**：
- Lance SDK 执行事务后，调用 UC API 同步事务状态
- UC 提供 `describeTransaction` 查询 API

---

## 4. 元数据同步机制

### 4.1 同步 API 设计

执行器操作成功后，需要调用 UC 的元数据同步 API：

```
POST /v1/table/{id}/metadata/sync
{
  "operation": "insert",
  "version": 5,
  "manifest_path": "...",
  "manifest_size": 1234,
  "etag": "...",
  "stats": { "num_rows": 1000, ... }
}
```

**同步 API 类型**：

| API | 调用时机 |
|-----|----------|
| `syncVersion` | Lance 写入成功后，同步 version 信息 |
| `syncIndex` | Lance 创建索引成功后，同步索引元数据 |
| `syncTransaction` | Lance 事务执行后，同步事务状态 |
| `syncSchema` | Lance schema 变化后，同步 schema 信息 |

### 4.2 同步时机

| 操作 | Lance 执行 | UC 同步 |
|------|-----------|---------|
| **写入操作** (insert/merge_insert/update/delete) | Lance SDK 执行写入，返回 version | 执行器调用 `syncVersion` |
| **创建索引** | Lance SDK 执行 createIndex | 执行器调用 `syncIndex` |
| **删除索引** | Lance SDK 执行 dropIndex | 执行器调用 `syncIndex`（更新状态或删除记录） |
| **删除版本** | Lance SDK 执行 deleteVersions | 执行器调用 `syncVersion`（更新或删除记录） |
| **批量提交** | Lance SDK 执行 batchCommit | 执行器调用 `syncVersion` / `syncTransaction` |
| **Schema 变更** | Lance SDK 执行 addColumns/alterColumns/dropColumns | 执行器调用 `syncSchema` |

### 4.3 同步协议

**方式 1：执行器主动同步**
```
执行器 ── Lance SDK 执行操作 ── 成功 ── 调用 UC sync API ── UC 记录元数据
```

**方式 2：UC 拦截并转发**
```
客户端 ── UC REST API ── UC 转发到 Lance Worker ── Worker 执行 ── Worker 同步元数据回 UC
```

方式 2 是 Phase 2 的数据面设计，UC 作为协议转发层，Worker 执行后自动同步。

---

## 5. API 详细分析

### 5.1 Index API

| API | 执行方式 | UC 实现 |
|-----|----------|---------|
| `createIndex` | Lance SDK 执行，完成后调用 `syncIndex` | UC 接收同步，记录到 `uc_lance_indices` |
| `listIndices` | UC 查询 `uc_lance_indices` | ✅ UC 提供 |
| `describeIndexStats` | UC 查询 `uc_lance_indices` | ✅ UC 提供 |
| `dropIndex` | Lance SDK 执行，完成后调用 `syncIndex` | UC 更新元数据表 |

**注意**：`createIndex` 和 `dropIndex` 需要执行引擎，但如果通过 UC 协议转发层（Phase 2 Worker），UC 可以作为协议转发层接收请求并转发到 Worker 执行。

### 5.2 Version API

| API | 执行方式 | UC 实现 |
|-----|----------|---------|
| `listVersions` | UC 查询 `uc_lance_versions` | ✅ UC 提供 |
| `describeVersion` | UC 查询 `uc_lance_versions` | ✅ UC 提供 |
| `createVersion` | ❌ 语义不支持 | 400 BAD_REQUEST |
| `deleteVersions` | Lance SDK 执行，完成后调用 `syncVersion` | UC 更新元数据表 |
| `batchCreateVersions` | ❌ 语义不支持 | 400 BAD_REQUEST |

### 5.3 Tag API

| API | 执行方式 | UC 实现 |
|-----|----------|---------|
| `listTags` | UC 查询 `uc_lance_tags` | ✅ UC 提供 |
| `getTagVersion` | UC 查询 `uc_lance_tags` | ✅ UC 提供 |
| `createTag` | UC 独立实现（纯 metadata） | ✅ UC 提供 |
| `updateTag` | UC 独立实现（纯 metadata） | ✅ UC 提供 |
| `deleteTag` | UC 独立实现（纯 metadata） | ✅ UC 提供 |

### 5.4 Transaction API

| API | 执行方式 | UC 实现 |
|-----|----------|---------|
| `describeTransaction` | UC 查询 `uc_lance_transactions` | ✅ UC 提供 |
| `alterTransaction` | Lance SDK 执行，完成后调用 `syncTransaction` | UC 更新元数据表 |

### 5.5 Batch Commit API

| API | 执行方式 | UC 实现 |
|-----|----------|---------|
| `batchCommit` | Lance SDK 执行，完成后同步 | UC 接收同步结果 |

### 5.6 Schema Evolution API

| API | 执行方式 | UC 实现 |
|-----|----------|---------|
| `updateTableSchemaMetadata` | Lance SDK 执行，完成后调用 `syncSchema` | UC 更新 `arrow_schema_json` |
| `addColumns` | Lance SDK 执行，完成后调用 `syncSchema` | UC 更新 `arrow_schema_json` |
| `alterColumns` | Lance SDK 执行，完成后调用 `syncSchema` | UC 更新 `arrow_schema_json` |
| `dropColumns` | Lance SDK 执行，完成后调用 `syncSchema` | UC 更新 `arrow_schema_json` |

---

## 6. Phase 3 实施建议

### 6.1 可优先实现（不需要执行引擎）

**Tag CRUD（5 个 API）**：纯 metadata 操作，UC 独立实现

```
POST /v1/table/{id}/tags/list       -> UC 查询 uc_lance_tags
POST /v1/table/{id}/tags/get        -> UC 查询 uc_lance_tags
POST /v1/table/{id}/tags/create     -> UC 写入 uc_lance_tags
POST /v1/table/{id}/tags/update     -> UC 更新 uc_lance_tags
POST /v1/table/{id}/tags/delete     -> UC 删除 uc_lance_tags
```

### 6.2 需要元数据同步机制支持

**Version 查询（2 个 API）**：需要执行器写入后同步 version 信息

```
POST /v1/table/{id}/version/list      -> UC 查询 uc_lance_versions
POST /v1/table/{id}/version/describe  -> UC 查询 uc_lance_versions
```

前置条件：
- Phase 2 写入成功后，执行器调用 `syncVersion` API 同步版本信息
- 或 UC 作为协议转发层，Worker 执行后自动同步

**Index 查询（2 个 API）**：需要执行器创建索引后同步

```
POST /v1/table/{id}/index/list        -> UC 查询 uc_lance_indices
POST /v1/table/{id}/index/describe    -> UC 查询 uc_lance_indices
```

前置条件：
- Lance SDK 执行 `createIndex` 后调用 `syncIndex` API
- 或 UC 作为协议转发层，Worker 执行后自动同步

### 6.3 需要执行引擎

通过 UC 协议转发层（Phase 2 Worker 模式）或 Lance SDK 直连执行：

- `createIndex` / `dropIndex`
- `deleteVersions`
- `alterTransaction`
- `batchCommit`
- Schema Evolution API

---

## 7. 与 Gravitino 对比

| 能力 | Gravitino Lance | UC Lance (本设计) |
|------|-----------------|-------------------|
| Namespace API | ✅ 已实现 | ✅ Phase 1 实现 |
| Table Metadata API | ✅ 已实现 | ✅ Phase 1 实现 |
| Schema Evolution | ✅ dropColumns/alterColumns | UC 提供协议转发，Lance 执行 |
| Index API | ❌ 未实现 | ✅ UC 提供查询 + 协议转发 |
| Version API | ❌ 未实现 | ✅ UC 提供查询 + 协议转发 |
| Tag API | ❌ 未实现 | ✅ UC 提供完整 CRUD |
| Transaction API | ❌ 未实现 | ✅ UC 提供查询 + 协议转发 |
| Batch Commit | ❌ 未实现 | ✅ UC 提供协议转发 |
| 元数据同步机制 | ❌ 未实现 | ✅ 执行器同步元数据到 UC |

---

## 8. 总结

**核心结论**：

1. **UC 是 Catalog 层**：不执行操作，但提供元数据治理和查询 API
2. **元数据来源**：执行器操作成功后同步到 UC，UC 提供查询 API
3. **Tag CRUD**：纯 metadata 操作，UC 可独立实现
4. **Version/Index 查询**：UC 提供查询 API，数据由执行器同步
5. **执行类操作**：通过 UC 协议转发层（Worker）或 Lance SDK 直连执行

**Phase 3 实施顺序**：

1. 优先实现 Tag CRUD（纯 metadata）
2. 建立元数据同步机制（syncVersion / syncIndex API）
3. 实现 Version/Index 查询 API
4. 执行类操作通过协议转发层或 Lance SDK 直连