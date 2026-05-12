# Unity Catalog Lance Phase 3 API 可行性分析

更新日期：2026-05-12

## 1. 设计约束

**核心原则：Unity Catalog 是统一元数据治理平台，不具备 LanceDB 的执行能力和存储能力。**

基于此约束，Phase 3 API 分析维度：

| 分类 | 定义 | UC 实现方式 |
|------|------|-------------|
| ✅ 纯 Metadata | 只涉及 UC 元数据表操作，不需要物理 Lance 文件 | UC 独立实现 |
| ❌ 需要 Lance 执行引擎 | 需要操作物理 Lance 文件（manifest、数据文件、索引） | 返回 501 UNIMPLEMENTED 或转发到 worker |
| ⚠️ 混合 | 部分 metadata + 部分需要执行引擎 | 分层实现，明确边界 |

---

## 2. API 详细分析

### 2.1 Index（向量索引）

| API | 操作类型 | 分析 | UC 实现可行性 |
|-----|----------|------|---------------|
| `createIndex` | 创建向量索引 | 需要 Lance 执行引擎构建索引结构（计算向量距离、构建索引树） | ❌ 需要 Lance 执行引擎 |
| `listIndices` | 列出索引 | 可以读取 UC 元数据表 `uc_lance_indices`，但索引元数据由 Lance 执行引擎生成 | ⚠️ 混合：UC 可查询元数据，但元数据来源是 Lance 执行引擎 |
| `describeIndexStats` | 索引统计信息 | 索引大小、向量数量等需要读取物理 Lance 文件 | ❌ 需要 Lance 执行引擎 |
| `dropIndex` | 删除索引 | 需要删除物理 Lance 文件中的索引数据 | ❌ 需要 Lance 执行引擎 |

**结论：Index 系列 API 全部需要 Lance 执行引擎。UC 只能维护索引元数据表，但不能创建/删除/统计索引。**

### 2.2 Version（版本管理）

| API | 操作类型 | 分析 | UC 实现可行性 |
|-----|----------|------|---------------|
| `listVersions` | 列出版本 | 可以读取 UC 元数据表 `uc_lance_versions`，但版本号由 Lance 写入时生成 | ⚠️ 混合：UC 可查询元数据，但元数据来源是 Lance 执行引擎写入 |
| `createVersion` | 创建版本 | Lance 的 version 是每次写入自动生成，不能手动创建 | ❌ 无意义：Version 由 Lance 写入自动生成 |
| `describeVersion` | 描述版本详情 | 版本详情（manifest 内容、schema 变化）在物理 Lance manifest 中 | ❌ 需要 Lance 执行引擎读取 manifest |
| `deleteVersions` | 删除版本 | 需要删除物理 Lance 数据文件和更新 manifest | ❌ 需要 Lance 执行引擎 |
| `batchCreateVersions` | 批量创建版本 | 同 createVersion，version 不能手动创建 | ❌ 无意义 |

**结论：Version 系列 API 大部分需要 Lance 执行引擎。UC 可以维护版本元数据表（作为 Lance 写入结果的缓存），但不能创建/删除版本。**

**重要说明：Lance 的 version 是物理概念，每次写入操作自动递增。UC 的 `current_version` 字段只是缓存 Lance 最新版本号。**

### 2.3 Tag（版本标签）

| API | 操作类型 | 分析 | UC 实现可行性 |
|-----|----------|------|---------------|
| `listTags` | 列出标签 | Tag 是 version 的命名别名，纯 metadata 概念 | ✅ 纯 Metadata：读取 `uc_lance_tags` 表 |
| `getTagVersion` | 获取标签对应的版本 | Tag -> Version 映射，纯 metadata | ✅ 纯 Metadata：查询元数据表 |
| `createTag` | 创建标签 | 给某个 version 创建命名别名，纯 metadata 操作 | ✅ 纯 Metadata：写入 `uc_lance_tags` 表 |
| `updateTag` | 更新标签 | 更新 Tag 指向的 Version，纯 metadata | ✅ 纯 Metadata：更新元数据表 |
| `deleteTag` | 删除标签 | 删除 Tag 元数据，纯 metadata | ✅ 纯 Metadata：删除元数据表记录 |

**结论：Tag 系列 API 全部为纯 Metadata 操作，UC 可以独立实现。**

**注意：`restore_table` 使用 Tag 恢复数据仍需要 Lance 执行引擎。Tag 的 CRUD 不影响物理数据。**

### 2.4 Transaction（事务）

| API | 操作类型 | 分析 | UC 实现可行性 |
|-----|----------|------|---------------|
| `describeTransaction` | 描述事务 | Lance 事务状态在物理 manifest 中 | ❌ 需要 Lance 执行引擎读取 manifest |
| `alterTransaction` | 修改事务 | 需要操作物理 Lance manifest | ❌ 需要 Lance 执行引擎 |

**结论：Transaction API 需要 Lance 执行引擎。UC 无法管理 Lance 物理事务状态。**

### 2.5 Batch Commit

| API | 操作类型 | 分析 | UC 实现可行性 |
|-----|----------|------|---------------|
| `batchCommit` | 批量提交 | Lance 的 batch commit 是物理操作，协调多个表的原子写入 | ❌ 需要 Lance 执行引擎 |

**结论：Batch Commit 需要 Lance 执行引擎。**

### 2.6 Schema Evolution

| API | 操作类型 | 分析 | UC 实现可行性 |
|-----|----------|------|---------------|
| `updateTableSchemaMetadata` | 更新 schema 元数据 | UC 可修改 `arrow_schema_json`，但 Lance 文件 schema 需 Lance 执行引擎验证 | ⚠️ 混合：UC 可更新 metadata，但物理 schema 变更需要 Lance 执行引擎 |
| `addColumns` | 添加列 | 同上 | ⚠️ 混合 |
| `alterColumns` | 修改列 | 同上 | ⚠️ 混合 |
| `dropColumns` | 删除列 | 同上 | ⚠️ 混合 |

**结论：Schema Evolution 是混合操作。UC 可以更新 `arrow_schema_json` 元数据，但这只是"声明"，实际 Lance 文件的 schema 变更需要 Lance 执行引擎执行。**

**潜在设计问题：如果 UC 更新了 schema metadata 但 Lance 文件 schema 未变更，会导致不一致。建议 Schema Evolution 统一通过 Lance 执行引擎完成，UC 只记录结果。**

### 2.7 Table Operations

| API | 操作类型 | 分析 | UC 实现可行性 |
|-----|----------|------|---------------|
| `restoreTable` | 恢复表到某版本 | 需要修改物理 Lance manifest，切换活跃版本 | ❌ 需要 Lance 执行引擎 |
| `renameTable` | 重命名表 | 只更新 UC metadata（path_key、name），物理 storage_location 不变 | ✅ 纯 Metadata（Phase 2 已实现） |
| `createEmptyTable` | 创建空表 | 等同于 declareTable，纯 metadata | ✅ 纯 Metadata（Phase 1 已实现） |

---

## 3. 汇总分类

### 3.1 ✅ UC 可独立实现（纯 Metadata）

| API | 说明 | 实现阶段 |
|-----|------|----------|
| `renameTable` | 更新 path_key/name/namespace_id | Phase 2 已实现 |
| `createEmptyTable` | 等同 declareTable | Phase 1 已实现 |
| `listTags` | 查询 `uc_lance_tags` 表 | Phase 3 待实现 |
| `getTagVersion` | 查询 Tag -> Version 映射 | Phase 3 待实现 |
| `createTag` | 创建 Tag 元数据 | Phase 3 待实现 |
| `updateTag` | 更新 Tag 指向的 Version | Phase 3 待实现 |
| `deleteTag` | 删除 Tag 元数据 | Phase 3 待实现 |

### 3.2 ❌ 需要 Lance 执行引擎

| API | 说明 | UC 处理方式 |
|-----|------|-------------|
| `createIndex` | 构建向量索引 | 501 UNIMPLEMENTED 或转发 worker |
| `describeIndexStats` | 索引统计 | 501 UNIMPLEMENTED 或转发 worker |
| `dropIndex` | 删除索引 | 501 UNIMPLEMENTED 或转发 worker |
| `createVersion` | Lance version 自动生成，无手动创建语义 | 返回 400 BAD_REQUEST 或 501 |
| `describeVersion` | 读取 manifest 内容 | 501 UNIMPLEMENTED 或转发 worker |
| `deleteVersions` | 删除物理数据文件 | 501 UNIMPLEMENTED 或转发 worker |
| `batchCreateVersions` | 同 createVersion | 返回 400 BAD_REQUEST 或 501 |
| `describeTransaction` | 读取 manifest 事务状态 | 501 UNIMPLEMENTED 或转发 worker |
| `alterTransaction` | 修改 manifest | 501 UNIMPLEMENTED 或转发 worker |
| `batchCommit` | 批量原子写入 | 501 UNIMPLEMENTED 或转发 worker |
| `restoreTable` | 切换活跃版本 | 501 UNIMPLEMENTED（Phase 2 已实现） |

### 3.3 ⚠️ 混合操作（需明确边界）

| API | UC Metadata 部分 | Lance 执行引擎部分 | 建议处理方式 |
|-----|------------------|-------------------|--------------|
| `listIndices` | 查询 `uc_lance_indices` | 索引元数据由 Lance 执行引擎写入 | UC 提供查询能力，但元数据来源依赖 Lance 执行引擎写入 |
| `listVersions` | 查询 `uc_lance_versions` | 版本号由 Lance 写入自动生成 | UC 提供查询能力，元数据来源依赖 Lance 执行引擎写入 |
| `updateTableSchemaMetadata` | 更新 `arrow_schema_json` | Lance 文件 schema 变更 | 建议统一通过 Lance 执行引擎完成，UC 只记录结果 |
| `addColumns` | 同上 | 同上 | 同上 |
| `alterColumns` | 同上 | 同上 | 同上 |
| `dropColumns` | 同上 | 同上 | 同上 |

---

## 4. Phase 3 实施建议

### 4.1 UC 可独立实现的能力

**Tag CRUD（5 个 API）：**

```
POST /v1/table/{id}/tags/create     -> UC 独立实现
POST /v1/table/{id}/tags/list       -> UC 独立实现  
POST /v1/table/{id}/tags/get        -> UC 独立实现
POST /v1/table/{id}/tags/update     -> UC 独立实现
POST /v1/table/{id}/tags/delete     -> UC 独立实现
```

新增元数据表：

```sql
CREATE TABLE uc_lance_tags (
  id UUID PRIMARY KEY,
  asset_id UUID NOT NULL REFERENCES uc_lance_assets(id),
  tag_name VARCHAR(255) NOT NULL,
  version BIGINT NOT NULL,
  created_at TIMESTAMP,
  created_by VARCHAR(255),
  updated_at TIMESTAMP,
  updated_by VARCHAR(255)
);
```

### 4.2 需要 Lance 执行引擎的能力

**Index、Version、Transaction、BatchCommit、Restore、Schema Evolution：**

建议策略：

| 策略 | 适用场景 |
|------|----------|
| 501 UNIMPLEMENTED | Lance 执行引擎未配置时 |
| 转发 Worker | Lance 执行引擎配置时（类似 Phase 2 的 worker-http backend） |

如果 Lance 执行引擎支持这些 API，UC 可作为协议转发层，类似 Phase 2 的数据面设计。

### 4.3 Version 元数据表的设计问题

**问题：Lance version 是物理概念，由 Lance 执行引擎自动生成。**

UC 的 `uc_lance_versions` 表存在以下问题：

1. **版本号来源**：UC 不能创建版本，版本号由 Lance 写入时自动递增
2. **元数据同步**：每次 Lance 写入后，需要同步版本号到 UC（类似 Phase 2 的 metadata update）
3. **删除语义**：UC 删除版本元数据不等于删除物理 Lance 数据

**建议：`uc_lance_versions` 表作为可选的版本历史缓存，不作为主数据源。Version 操作统一通过 Lance 执行引擎。**

---

## 5. 待讨论问题

### 5.1 Tag 是否属于 Phase 3？

Tag CRUD 是纯 metadata 操作，技术上可以在 Phase 2 或 Phase 1 之后立即实现。

建议：
- Tag 作为 Phase 2 补充或独立的 Phase 2.x
- 与 `restore_table`（需要 Lance 执行引擎）分开

### 5.2 Schema Evolution 的责任边界

Schema Evolution 是混合操作，存在两个设计选项：

**选项 A：UC 主导**
- UC 更新 `arrow_schema_json` metadata
- Lance 执行引擎读取 UC metadata 后验证/应用变更
- 问题：如果 Lance 执行引擎不支持，会导致不一致

**选项 B：Lance 执行引擎主导**
- Schema Evolution 统一通过 Lance 执行引擎完成
- Lance 执行引擎完成后写入 UC metadata
- UC 只是记录结果，不主动修改 schema
- 优势：保证一致性

建议采用 **选项 B**。

### 5.3 Index/Version/Transaction 元数据表的意义

如果 UC 不能创建/删除 Index/Version，那么 `uc_lance_indices` / `uc_lance_versions` 表的作用是什么？

选项：
- **作为缓存**：Lance 执行引擎完成后写入，UC 提供查询能力
- **不创建**：这些元数据保持在 Lance manifest 中，UC 不维护

建议：根据实际需求决定。如果 UC 需要提供 `listIndices` / `listVersions` API，则需要元数据表；否则可以省略。

---

## 6. 总结

| 分类 | API 数量 | UC 实现方式 |
|------|----------|-------------|
| ✅ 纯 Metadata | 7 | UC 独立实现 |
| ❌ 需要 Lance 执行引擎 | 12 | 501 UNIMPLEMENTED 或转发 worker |
| ⚠️ 混合 | 6 | 需明确边界，建议统一通过 Lance 执行引擎 |

**核心结论：**

- **Tag CRUD** 是 Phase 3 中唯一 UC 可完全独立实现的能力
- **Index、Version（大部分）、Transaction、BatchCommit、Restore、Schema Evolution** 需要 Lance 执行引擎
- UC 作为元数据治理平台，Phase 3 的价值在于维护 Tag 元数据和提供协议转发层（如果配置了 Lance 执行引擎）