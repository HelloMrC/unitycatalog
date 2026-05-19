# Unity Catalog Lance REST API 第三阶段详细测试设计

副标题：Metadata Sync & Tag CRUD 测试设计

更新日期：2026-05-16

关联文档：

- `docs/lance/unitycatalog-lancedb-rest-api-requirements.md`
- `docs/lance/unitycatalog-lancedb-rest-api-technical-design.md`
- `docs/lance/unitycatalog-lancedb-phase-3-metadata-sync-design.md`
- `docs/lance/unitycatalog-lancedb-rest-api-test-strategy.md`
- `docs/lance/unitycatalog-lancedb-rest-api-test-design.md`
- `docs/lance/unitycatalog-lancedb-phase-2-data-plane-test-design.md`

## 1. 文档目标

本文档用于把 "Phase 3：Metadata Sync & Tag CRUD" 设计展开为可执行的详细测试设计。

**文档分为两个切片：**

1. **当前已实现切片**：已在代码中实现的 endpoint，作为 PR 必跑门禁
2. **Phase 3 终态切片**：设计文档定义的完整能力，作为 Nightly/发布前门禁或未来迭代目标

Phase 3 测试的核心目标是验证 UC 作为 Lance Catalog 层的元数据治理能力。

本文回答以下问题：

- 当前哪些 endpoint 已实现，哪些作为 PR 必跑
- Phase 3 终态哪些能力作为 Nightly 或未来目标
- PR、Nightly、发布前分别跑哪些测试
- 每个测试套件的用例 ID、前置条件、步骤、主要断言和建议落位

---

## 2. 实现状态与测试分层

### 2.1 当前已实现（PR 必跑门禁）

| 能力 | 实现状态 | 测试门禁 |
|------|----------|----------|
| Tag CRUD | ✅ 已实现 | PR 必跑 |
| Tag alias（get-version/version） | ✅ 已实现 | PR 必跑 |
| Version list/describe | ✅ 已实现 | PR 必跑 |
| Version metadata（写入后记录） | ✅ 已实现（Phase 2 write 已同步 version） | PR 必跑 |
| createVersion 拒绝 | ✅ 已实现（400 BAD_REQUEST） | PR 必跑 |
| batchCreateVersions 拒绝 | ✅ 已实现（400 BAD_REQUEST） | PR 必跑 |
| deleteVersion 语义 | ✅ 已实现（501 UNIMPLEMENTED） | PR 必跑 |

### 2.2 Phase 3 后续实现（Nightly/未来门禁）

| 能力 | 实现状态 | 测试门禁 |
|------|----------|----------|
| syncVersion API | ⚠️ 待实现 | Nightly/发布前 |
| syncIndex API | ⚠️ 待实现 | Nightly/发布前 |
| syncSchema API | ⚠️ 待实现 | Nightly/发布前 |
| syncTransaction API | ⚠️ 待实现 | Nightly/发布前 |
| syncTag API | ⚠️ 待实现 | Nightly/发布前 |
| Index 查询（listIndices/describeIndexStats） | ⚠️ 待实现 | Nightly/发布前 |
| Transaction 查询（describeTransaction） | ⚠️ 待实现 | Nightly/发布前 |
| createIndex/dropIndex（协议转发） | ⚠️ 待实现 | Nightly/发布前 |
| deleteVersions（协议转发） | ⚠️ 待实现 | Nightly/发布前 |
| batchCommit（协议转发） | ⚠️ 待实现 | Nightly/发布前 |
| Schema Evolution（协议转发） | ⚠️ 待实现 | Nightly/发布前 |
| restoreTable（协议转发） | ⚠️ 待实现 | Nightly/发布前 |

### 2.3 测试分层策略

| 层级 | 执行时机 | 覆盖重点 |
|------|----------|----------|
| L0 静态评审 | PR | DDL、设计审查 |
| L1 Unit | PR | Repository、codec |
| L2 Component | PR | 已实现 endpoint（Tag CRUD、Version 查询） |
| L3 Protocol | PR | 已实现 endpoint raw HTTP、错误码固定 |
| L4 Sync API | Nightly | syncVersion/syncIndex/syncTag/syncSchema/syncTransaction |
| L5 Protocol Forward | Nightly | createIndex/dropIndex/deleteVersions/batchCommit |
| L6 Index/Transaction Query | Nightly | listIndices/describeTransaction |
| L7 Regression | PR | Phase 1/2 endpoint |
| L8 Resilience/NFR | 发布前 | 并发、权限、审计 |

---

## 3. 测试完成标准

### 3.1 当前切片完成标准（PR 门禁）

- Tag CRUD（listTags/getTagVersion/createTag/updateTag/deleteTag）作为纯 metadata 操作可用
- Tag alias（get-version/version）可用
- Version list/describe 正确返回 UC 元数据表中的数据
- createVersion/batchCreateVersions 返回 400 BAD_REQUEST
- deleteVersion 返回 501 UNIMPLEMENTED
- Tag/Version 权限正确（READ_METADATA/MODIFY/owner）
- 审计记录完整
- Phase 1/2 endpoint 回归不受影响

### 3.2 终态切片完成标准（Phase 3 完成时）

- syncVersion/syncIndex/syncSchema/syncTransaction/syncTag API 正确接收执行器同步的元数据
- Index/Transaction 查询 API 可用
- createIndex/dropIndex/deleteVersions/batchCommit 通过协议转发执行
- 所有操作通过认证、授权、审计

---

## 4. 测试基线

### 4.1 协议与客户端基线

沿用总体测试策略固定的上游基线：

| 类型 | 路径 | Commit |
|---|---|---|
| Lance REST OpenAPI | `/home/lei/data_ai/learning/codebase/lancedb-docs/docs/api-reference/rest/openapi.yml` | `6c0ccc001e6b` |
| Lance 客户端源码 | `/home/lei/data_ai/learning/codebase/lancedb` | `a92ae0ded522` |

### 4.2 服务挂载前缀

```text
/api/2.1/unity-catalog/lance
```

Phase 3 endpoint 示例：

```text
POST /api/2.1/unity-catalog/lance/v1/table/{id}/tags/list
POST /api/2.1/unity-catalog/lance/v1/table/{id}/tags/get-version
POST /api/2.1/unity-catalog/lance/v1/table/{id}/version/list
```

### 4.3 Phase 1/2 准入基线

执行 Phase 3 测试前，默认以下能力已经稳定：

- Phase 1：namespace/table metadata、identifier codec、auth decorator、legacy bridge
- Phase 2：10 个 data endpoint、Backend SPI、Worker HTTP backend、Arrow IPC、metadata update

---

## 5. 范围与非范围

### 5.1 当前切片范围内

| 能力域 | 覆盖内容 | 门禁 |
|---|---|---|
| Tag CRUD | listTags、getTagVersion、get-version、version、createTag、updateTag、deleteTag | PR 必跑 |
| Version 查询 | listVersions、describeVersion | PR 必跑 |
| 语义错误 | createVersion、batchCreateVersions 返回 400 | PR 必跑 |
| deleteVersion | 返回 501 UNIMPLEMENTED | PR 必跑 |
| 权限 | READ_METADATA/MODIFY/owner | PR 必跑 |
| DDL | `uc_lance_tags`、`uc_lance_versions` | PR 必跑 |
| Regression | Phase 1/2 endpoint | PR 必跑 |

### 5.2 终态切片范围内（Nightly/未来）

| 能力域 | 覆盖内容 | 门禁 |
|---|---|---|
| Metadata Sync | syncVersion、syncIndex、syncSchema、syncTransaction、syncTag | Nightly |
| Index 查询 | listIndices、describeIndexStats | Nightly |
| Transaction 查询 | describeTransaction | Nightly |
| 协议转发 | createIndex、dropIndex、deleteVersions、batchCommit、alterTransaction、addColumns、alterColumns、dropColumns、restoreTable | Nightly |

### 5.3 范围外

| 能力 | 处理 |
|---|---|
| 跨系统强一致性 | 只验证 sync API 写入成功 |
| Reconcile 自动修复 | Phase 2 已实现 |
| UC UI 管理页面 | 不测 |
| Lance 引擎内部算法 | 不测 |

---

## 6. 测试数据与 Fixture

### 6.1 Namespace 与 Table

| 数据 ID | 内容 | 用途 |
|---|---|---|
| `P3-NS-ROOT` | `prod` | root namespace |
| `P3-TBL-ACTIVE` | `prod$embeddings`，`ACTIVE`，已有 version | Tag/Version 测试 |
| `P3-TBL-DECLARED` | `prod$declared_only`，`DECLARED` | createTag 拒绝 |
| `P3-TBL-MULTI-VERSION` | 包含多个 version | listVersions 分页 |
| `P3-TBL-WITH-TAG` | 包含 tag | listTags/updateTag/deleteTag |

### 6.2 Backend Fixture

| Fixture | 行为 |
|---|---|
| `FakePhase3Backend` | 扩展后的 LanceExecutionBackend fake implementation，记录 command |
| `DisabledLanceExecutionBackend` | 未配置时返回 UNIMPLEMENTED |

---

## 7. 测试套件设计

### 7.1 用例编号规则

Phase 3 使用以下编号：

**当前切片（PR 必跑）：**
- `P3-TAG-*`：Tag CRUD 测试
- `P3-TAG-ALIAS-*`：Tag alias 测试
- `P3-VERSION-*`：Version 查询测试
- `P3-ERROR-*`：语义错误拒绝测试
- `P3-DDL-*`：DDL 创建与验证
- `P3-AUTH-*`：认证授权审计
- `P3-REG-*`：Phase 1/2 回归

**终态切片（Nightly/未来）：**
- `P3-SYNC-*`：元数据同步 API 测试
- `P3-INDEX-*`：Index 查询测试
- `P3-TRANS-*`：Transaction 查询测试
- `P3-PROTO-*`：协议转发测试

---

## 8. DDL 测试套件（P3-DDL）

### 8.1 `P3-DDL` 元数据表创建

| 项目 | 设计 |
|---|---|
| 目标 | 验证 Phase 3 元数据表可在 H2/PostgreSQL 中正确创建 |
| 前置条件 | Phase 1/2 DDL 已存在 |
| 关键场景 | `P3-DDL-001` `uc_lance_tags` 创建成功；`P3-DDL-002` `uc_lance_versions` 创建成功；`P3-DDL-003` 唯一约束正确；`P3-DDL-004` 外键约束正确 |
| 主要断言 | 表存在；CRUD 操作正常；约束生效 |
| 自动化级别 | PR 必跑 |

---

## 9. Tag CRUD 测试套件（P3-TAG）

### 9.1 `P3-TAG-001` listTags 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 listTags 返回正确的 tag 列表 |
| 前置条件 | 表已存在，包含多个 tag |
| 步骤 | 1. 创建 table；2. 直接写入 `uc_lance_tags` 创建多个 tag；3. 调用 `POST /v1/table/{id}/tags/list` |
| 主要断言 | 返回 tag 列表；tag_name 和 version 正确 |
| 自动化级别 | PR 必跑 |

### 9.2 `P3-TAG-002` listTags 空表

| 项目 | 设计 |
|---|---|
| 目标 | 验证无 tag 时返回空列表 |
| 前置条件 | 表存在但无 tag |
| 步骤 | 调用 `POST /v1/table/{id}/tags/list` |
| 主要断言 | 返回空数组；不报错 |
| 自动化级别 | PR 必跑 |

### 9.3 `P3-TAG-003` getTagVersion 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 getTagVersion 返回 tag 指向的 version |
| 前置条件 | tag 已存在 |
| 步骤 | 调用 `POST /v1/table/{id}/tags/get`，body 包含 `tag_name` |
| 主要断言 | 返回 version 号；返回 metadata（created_by、created_at） |
| 自动化级别 | PR 必跑 |

### 9.4 `P3-TAG-004` getTagVersion 不存在

| 项目 | 设计 |
|---|---|
| 目标 | 验证 tag 不存在返回 404 NOT_FOUND |
| 前置条件 | tag 不存在 |
| 步骤 | 调用 `POST /v1/table/{id}/tags/get` |
| 主要断言 | **返回 404 NOT_FOUND**（固定错误码） |
| 自动化级别 | PR 必跑 |

### 9.5 `P3-TAG-005` createTag 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 createTag 创建 tag 元数据 |
| 前置条件 | table ACTIVE，version 存在 |
| 步骤 | 1. 确保 version 存在；2. 调用 `POST /v1/table/{id}/tags/create`，body 包含 `tag_name`、`version` |
| 主要断言 | 返回创建的 tag；`uc_lance_tags` 写入正确；审计记录 |
| 自动化级别 | PR 必跑 |

### 9.6 `P3-TAG-006` createTag version 不存在

| 项目 | 设计 |
|---|---|
| 目标 | 验证指向不存在 version 时拒绝 |
| 前置条件 | version 不存在 |
| 步骤 | 调用 `POST /v1/table/{id}/tags/create` |
| 主要断言 | **返回 404 NOT_FOUND**（固定错误码：version 不存在） |
| 自动化级别 | PR 必跑 |

### 9.7 `P3-TAG-007` createTag 重复

| 项目 | 设计 |
|---|---|
| 目标 | 验证 tag_name 已存在返回 409 |
| 前置条件 | tag 已存在 |
| 步骤 | 再次调用 `POST /v1/table/{id}/tags/create` |
| 主要断言 | **返回 409 ABORTED**（固定错误码） |
| 自动化级别 | PR 必跑 |

### 9.8 `P3-TAG-008` createTag DECLARED 表拒绝

| 项目 | 设计 |
|---|---|
| 目标 | 验证 DECLARED 表不能创建 tag（version 不存在） |
| 前置条件 | table DECLARED，无 version |
| 步骤 | 调用 `POST /v1/table/{id}/tags/create` |
| 主要断言 | **返回 404 NOT_FOUND**（version 不存在） |
| 自动化级别 | PR 必跑 |

### 9.9 `P3-TAG-009` updateTag 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 updateTag 更新 tag 指向的 version |
| 前置条件 | tag 已存在，新 version 存在 |
| 步骤 | 调用 `POST /v1/table/{id}/tags/update`，body 包含 `tag_name`、`new_version` |
| 主要断言 | version 更新；`updated_at`、`updated_by` 更新；审计记录 |
| 自动化级别 | PR 必跑 |

### 9.10 `P3-TAG-010` updateTag 不存在

| 项目 | 设计 |
|---|---|
| 目标 | 验证 tag 不存在时拒绝 |
| 前置条件 | tag 不存在 |
| 步骤 | 调用 `POST /v1/table/{id}/tags/update` |
| 主要断言 | **返回 404 NOT_FOUND** |
| 自动化级别 | PR 必跑 |

### 9.11 `P3-TAG-011` deleteTag 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 deleteTag 删除 tag 元数据 |
| 前置条件 | tag 已存在 |
| 步骤 | 调用 `POST /v1/table/{id}/tags/delete`，body 包含 `tag_name` |
| 主要断言 | 返回成功；`uc_lance_tags` 记录删除；审计记录 |
| 自动化级别 | PR 必跑 |

### 9.12 `P3-TAG-012` deleteTag 不存在

| 项目 | 设计 |
|---|---|
| 目标 | 验证 tag 不存在返回 404 |
| 前置条件 | tag 不存在 |
| 步骤 | 调用 `POST /v1/table/{id}/tags/delete` |
| 主要断言 | **返回 404 NOT_FOUND** |
| 自动化级别 | PR 必跑 |

---

## 10. Tag Alias 测试套件（P3-TAG-ALIAS）

### 10.1 `P3-TAG-ALIAS-001` get-version 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 tags/get-version alias 可用 |
| 前置条件 | tag 已存在 |
| 步骤 | 调用 `POST /v1/table/{id}/tags/get-version`，body 包含 `tag_name` |
| 主要断言 | 返回 version 号；行为与 tags/get 一致 |
| 自动化级别 | PR 必跑 |

### 10.2 `P3-TAG-ALIAS-002` version alias 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 tags/version alias 可用 |
| 前置条件 | tag 已存在 |
| 步骤 | 调用 `POST /v1/table/{id}/tags/version`，body 包含 `tag_name` |
| 主要断言 | 返回 version 号；行为与 tags/get 一致 |
| 自动化级别 | PR 必跑 |

---

## 11. Version 查询测试套件（P3-VERSION）

### 11.1 `P3-VERSION-001` listVersions 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 listVersions 返回版本历史 |
| 前置条件 | Phase 2 write 已产生多个 version |
| 步骤 | 调用 `POST /v1/table/{id}/version/list` |
| 主要断言 | 返回 version 列表；按 version DESC 排序 |
| 自动化级别 | PR 必跑 |

### 11.2 `P3-VERSION-002` listVersions 空表

| 项目 | 设计 |
|---|---|
| 目标 | 验证无 version 时返回空列表 |
| 前置条件 | 表无 version（如 DECLARED） |
| 步骤 | 调用 `POST /v1/table/{id}/version/list` |
| 主要断言 | 返回空数组 |
| 自动化级别 | PR 必跑 |

### 11.3 `P3-VERSION-003` describeVersion 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 describeVersion 返回版本详情 |
| 前置条件 | version 存在 |
| 步骤 | 调用 `POST /v1/table/{id}/version/describe`，body 包含 `version` |
| 主要断言 | 返回 operation、timestamp、created_by |
| 自动化级别 | PR 必跑 |

### 11.4 `P3-VERSION-004` describeVersion 不存在

| 项目 | 设计 |
|---|---|
| 目标 | 验证 version 不存在返回 404 |
| 前置条件 | version 不存在 |
| 步骤 | 调用 `POST /v1/table/{id}/version/describe` |
| 主要断言 | **返回 404 NOT_FOUND** |
| 自动化级别 | PR 必跑 |

---

## 12. 语义错误拒绝测试套件（P3-ERROR）

### 12.1 `P3-ERROR-001` createVersion 拒绝

| 项目 | 设计 |
|---|---|
| 目标 | 验证 createVersion 返回 400 BAD_REQUEST |
| 前置条件 | 无 |
| 步骤 | 调用 `POST /v1/table/{id}/version/create` |
| 主要断言 | **返回 400 BAD_REQUEST**；错误说明 "Lance version 由写入自动产生，不能手动创建" |
| 自动化级别 | PR 必跑 |

### 12.2 `P3-ERROR-002` batchCreateVersions 拒绝

| 项目 | 设计 |
|---|---|
| 目标 | 验证 batchCreateVersions 返回 400 BAD_REQUEST |
| 前置条件 | 无 |
| 步骤 | 调用 `POST /v1/table/batch-create-versions` |
| 主要断言 | **返回 400 BAD_REQUEST**；错误说明同上 |
| 自动化级别 | PR 必跑 |

### 12.3 `P3-ERROR-003` deleteVersion 语义

| 项目 | 设计 |
|---|---|
| 目标 | 验证 deleteVersion 返回 501 UNIMPLEMENTED |
| 前置条件 | version 存在 |
| 步骤 | 调用 `POST /v1/table/{id}/version/delete` |
| 主要断言 | **返回 501 UNIMPLEMENTED**（协议转发待实现） |
| 自动化级别 | PR 必跑 |

---

## 13. 认证授权审计测试套件（P3-AUTH）

### 13.1 `P3-AUTH-001` Tag/Version 权限 - READ_METADATA

| 项目 | 设计 |
|---|---|
| 目标 | 验证 Tag/Version read 需要 READ_METADATA 或 owner |
| 前置条件 | 不同权限用户 |
| 步骤 | 1. 无 READ_METADATA 权限用户调用 listTags → 403；2. 有 READ_METADATA 权限用户调用 → 成功；3. Owner 调用 → 成功 |
| 主要断言 | 权限判定正确；审计记录 |
| 自动化级别 | PR 必跑 |

### 13.2 `P3-AUTH-002` Tag CRUD 权限 - MODIFY

| 项目 | 设计 |
|---|---|
| 目标 | 验证 Tag create/update/delete 需要 MODIFY 或 owner |
| 前置条件 | 不同权限用户 |
| 步骤 | 1. 无 MODIFY 权限用户调用 createTag → 403；2. 有 MODIFY 权限用户调用 → 成功；3. Owner 调用 → 成功 |
| 主要断言 | 权限判定正确 |
| 自动化级别 | PR 必跑 |

### 13.3 `P3-AUTH-003` 审计记录

| 项目 | 设计 |
|---|---|
| 目标 | 验证 Tag/Version 操作记录审计 |
| 前置条件 | 审计配置启用 |
| 步骤 | 1. 执行 Tag CRUD；2. 执行 Version 查询；检查审计日志 |
| 主要断言 | 审计包含 operation、principal、table、timestamp；无敏感信息泄露 |
| 自动化级别 | PR 必跑 |

---

## 14. Regression 测试套件（P3-REG）

### 14.1 `P3-REG-001` Phase 1 metadata endpoint 回归

| 项目 | 设计 |
|---|---|
| 目标 | 验证 Phase 3 不影响 Phase 1 |
| 前置条件 | Phase 1 测试套件 |
| 步骤 | 重跑 Phase 1 namespace/table metadata 测试 |
| 主要断言 | 全部通过 |
| 自动化级别 | PR 必跑 |

### 14.2 `P3-REG-002` Phase 2 data endpoint 回归

| 项目 | 设计 |
|---|---|
| 目标 | 验证 Phase 3 不影响 Phase 2 |
| 前置条件 | Phase 2 测试套件 |
| 步骤 | 重跑 Phase 2 query/insert/update/delete 测试 |
| 主要断言 | 全部通过 |
| 自动化级别 | PR 必跑 |

---

## 15. Metadata Sync API 测试套件（P3-SYNC）- Nightly/未来

### 15.1 `P3-SYNC-001` syncVersion 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 syncVersion 写入 version 元数据 |
| 前置条件 | table ACTIVE；sync API 已实现 |
| 步骤 | 1. 调用 `POST /v1/table/{id}/metadata/sync/version`；2. 查询 `uc_lance_versions` |
| 主要断言 | version 写入成功；字段完整 |
| 自动化级别 | **Nightly（待实现）** |

### 15.2 `P3-SYNC-002` syncIndex 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 syncIndex 写入 index 元数据 |
| 前置条件 | table ACTIVE；sync API 已实现 |
| 步骤 | 调用 `POST /v1/table/{id}/metadata/sync/index` |
| 主要断言 | index 写入成功；status=READY |
| 自动化级别 | **Nightly（待实现）** |

### 15.3 `P3-SYNC-003` syncSchema 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 syncSchema 更新 schema |
| 前置条件 | table ACTIVE；sync API 已实现 |
| 步骤 | 调用 `POST /v1/table/{id}/metadata/sync/schema` |
| 主要断言 | schema 更新成功 |
| 自动化级别 | **Nightly（待实现）** |

### 15.4 `P3-SYNC-004` syncTransaction 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 syncTransaction 写入事务状态 |
| 前置条件 | table ACTIVE；sync API 已实现 |
| 步骤 | 调用 `POST /v1/table/{id}/metadata/sync/transaction` |
| 主要断言 | transaction 写入成功 |
| 自动化级别 | **Nightly（待实现）** |

### 15.5 `P3-SYNC-005` syncTag 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 syncTag 同步 Lance SDK 创建的 tag |
| 前置条件 | table ACTIVE；sync API 已实现 |
| 步骤 | Lance SDK 创建 tag 后调用 `POST /v1/table/{id}/metadata/sync/tag` |
| 主要断言 | tag 写入 `uc_lance_tags`；与 UC createTag 结果一致 |
| 自动化级别 | **Nightly（待实现）** |

---

## 16. Index 查询测试套件（P3-INDEX）- Nightly/未来

### 16.1 `P3-INDEX-001` listIndices 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 listIndices 返回索引列表 |
| 前置条件 | 通过 syncIndex 创建多个 index |
| 步骤 | 调用 `POST /v1/table/{id}/index/list` |
| 主要断言 | 返回 index 列表 |
| 自动化级别 | **Nightly（待实现）** |

### 16.2 `P3-INDEX-002` describeIndexStats 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 describeIndexStats 返回索引详情 |
| 前置条件 | index 存在 |
| 步骤 | 调用 `POST /v1/table/{id}/index/describe` |
| 主要断言 | 返回 index_type、target_columns、stats |
| 自动化级别 | **Nightly（待实现）** |

---

## 17. Transaction 查询测试套件（P3-TRANS）- Nightly/未来

### 17.1 `P3-TRANS-001` describeTransaction 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 describeTransaction 返回事务详情 |
| 前置条件 | transaction 存在 |
| 步骤 | 调用 `POST /v1/table/{id}/transaction/describe` |
| 主要断言 | 返回 status、actions、commit_metadata |
| 自动化级别 | **Nightly（待实现）** |

---

## 18. 协议转发测试套件（P3-PROTO）- Nightly/未来

### 18.1 `P3-PROTO-001` createIndex 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 createIndex 通过 Worker 执行并同步元数据 |
| 前置条件 | Worker backend 配置；createIndex 已实现 |
| 步骤 | 调用 `POST /v1/table/{id}/index/create` |
| 主要断言 | Backend 执行；syncIndex 被调用 |
| 自动化级别 | **Nightly（待实现）** |

### 18.2 `P3-PROTO-002` dropIndex 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 dropIndex 通过 Worker 执行并同步 |
| 前置条件 | index 存在；dropIndex 已实现 |
| 步骤 | 调用 `POST /v1/table/{id}/index/drop` |
| 主要断言 | Backend 执行；syncIndex 删除记录 |
| 自动化级别 | **Nightly（待实现）** |

### 18.3 `P3-PROTO-003` deleteVersions 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 deleteVersions 通过 Worker 执行并同步 |
| 前置条件 | 多个 version 存在；deleteVersions 已实现 |
| 步骤 | 调用 `POST /v1/table/{id}/version/delete` |
| 主要断言 | Backend 执行；syncVersion 更新/删除记录 |
| 自动化级别 | **Nightly（待实现）** |

### 18.4 `P3-PROTO-004` batchCommit 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 batchCommit 通过 Worker 执行并同步 |
| 前置条件 | batchCommit 已实现 |
| 步骤 | 调用 `POST /v1/table/batch-commit` |
| 主要断言 | Backend 执行；syncVersion/syncTransaction 同步 |
| 自动化级别 | **Nightly（待实现）** |

### 18.5 `P3-PROTO-005` Schema Evolution 成功

| 项目 | 设计 |
|---|---|
| 目标 | 验证 addColumns/alterColumns/dropColumns 通过 Worker 执行并同步 |
| 前置条件 | Schema Evolution 已实现 |
| 步骤 | 调用 schema evolution endpoint |
| 主要断言 | Backend 执行；syncSchema 更新 schema |
| 自动化级别 | **Nightly（待实现）** |

---

## 19. 测试类命名建议

| 测试套件 | 建议类名 |
|---|---|
| Tag CRUD | `LancePhase3TagCrudRestTest` |
| Version 查询 | `LancePhase3VersionQueryRestTest` |
| 语义错误 | `LancePhase3SemanticErrorRestTest` |
| 认证授权 | `LancePhase3AuthGovernanceRestTest` |
| Regression | `LancePhase3RegressionRestTest` |
| Metadata Sync（未来） | `LancePhase3SyncApiRestTest` |
| Index 查询（未来） | `LancePhase3IndexQueryRestTest` |
| Transaction 查询（未来） | `LancePhase3TransactionQueryRestTest` |
| 协议转发（未来） | `LancePhase3ProtocolForwardRestTest` |

---

## 20. 测试命令分层

**当前仓库使用 sbt 构建系统，测试命令如下：**

| 命令 | 层级 | 说明 |
|---|---|---|
| `build/sbt "server/testOnly *LancePhase3*"` | PR | 所有 Phase 3 测试 |
| `build/sbt "server/testOnly io.unitycatalog.server.service.lance.LancePhase3TagCrudRestTest"` | PR | Tag CRUD 测试 |
| `build/sbt "server/testOnly io.unitycatalog.server.service.lance.LancePhase3VersionQueryRestTest"` | PR | Version 查询测试 |
| `build/sbt "server/testOnly io.unitycatalog.server.service.lance.LancePhase3SemanticErrorRestTest"` | PR | 语义错误测试 |
| `build/sbt "server/testOnly *LancePhase*"` | PR/Nightly | Phase 1+2+3 全量 |
| Nightly job | Nightly | 包含 Sync API、Protocol Forward、Index/Transaction |

---

## 21. 关键端点覆盖清单

### 21.1 当前切片（PR 必跑）

| 端点 | 覆盖套件 | 错误码 |
|---|---|---|
| `/v1/table/{id}/tags/list` | `P3-TAG-001~002` | 成功返回 |
| `/v1/table/{id}/tags/get` | `P3-TAG-003~004` | 404 NOT_FOUND（不存在） |
| `/v1/table/{id}/tags/get-version` | `P3-TAG-ALIAS-001` | 成功返回 |
| `/v1/table/{id}/tags/version` | `P3-TAG-ALIAS-002` | 成功返回 |
| `/v1/table/{id}/tags/create` | `P3-TAG-005~008` | 404 NOT_FOUND（version不存在）；409 ABORTED（重复） |
| `/v1/table/{id}/tags/update` | `P3-TAG-009~010` | 404 NOT_FOUND |
| `/v1/table/{id}/tags/delete` | `P3-TAG-011~012` | 404 NOT_FOUND |
| `/v1/table/{id}/version/list` | `P3-VERSION-001~002` | 成功返回 |
| `/v1/table/{id}/version/describe` | `P3-VERSION-003~004` | 404 NOT_FOUND |
| `/v1/table/{id}/version/create` | `P3-ERROR-001` | **400 BAD_REQUEST** |
| `/v1/table/batch-create-versions` | `P3-ERROR-002` | **400 BAD_REQUEST** |
| `/v1/table/{id}/version/delete` | `P3-ERROR-003` | **501 UNIMPLEMENTED** |

### 21.2 终态切片（Nightly/未来）

| 端点 | 覆盖套件 |
|---|---|
| `/v1/table/{id}/metadata/sync/version` | `P3-SYNC-001` |
| `/v1/table/{id}/metadata/sync/index` | `P3-SYNC-002` |
| `/v1/table/{id}/metadata/sync/schema` | `P3-SYNC-003` |
| `/v1/table/{id}/metadata/sync/transaction` | `P3-SYNC-004` |
| `/v1/table/{id}/metadata/sync/tag` | `P3-SYNC-005` |
| `/v1/table/{id}/index/list` | `P3-INDEX-001` |
| `/v1/table/{id}/index/describe` | `P3-INDEX-002` |
| `/v1/table/{id}/transaction/describe` | `P3-TRANS-001` |
| `/v1/table/{id}/index/create` | `P3-PROTO-001` |
| `/v1/table/{id}/index/drop` | `P3-PROTO-002` |
| `/v1/table/{id}/version/delete` | `P3-PROTO-003` |
| `/v1/table/batch-commit` | `P3-PROTO-004` |
| `/v1/table/{id}/schema/*` | `P3-PROTO-005` |

---

## 22. 总结

### 22.1 当前切片核心结论

**PR 必跑测试：**
1. **Tag CRUD**：纯 metadata，不依赖 Backend
2. **Tag alias**：get-version/version 与 get 一致
3. **Version 查询**：list/describe 返回 UC 元数据表数据
4. **语义错误**：createVersion/batchCreateVersions → 400；deleteVersion → 501
5. **权限**：READ_METADATA（read）/ MODIFY（write）/ owner
6. **错误码固定**：404、409、400、501

### 22.2 终态切片后续目标

**Nightly/未来：**
1. **sync API**：syncVersion/syncIndex/syncSchema/syncTransaction/syncTag
2. **Index/Transaction 查询**：listIndices/describeIndexStats/describeTransaction
3. **协议转发**：createIndex/dropIndex/deleteVersions/batchCommit/Schema Evolution

### 22.3 测试实施顺序

**当前（PR）：**
1. DDL 测试
2. Tag CRUD 测试
3. Tag Alias 测试
4. Version 查询测试
5. 语义错误测试
6. 认证授权测试
7. Regression 测试

**未来（Nightly）：**
1. Sync API 测试
2. Index 查询测试
3. Transaction 查询测试
4. 协议转发测试