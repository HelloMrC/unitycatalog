# Lance REST OpenAPI 暴露策略

更新日期：2026-05-11

## 1. 当前状态

### 1.1 UC OpenAPI Spec

Unity Catalog 主 OpenAPI spec (`api/all.yaml`) 目前不包含 Lance REST endpoints。

| API 路径 | 状态 | 说明 |
|----------|------|------|
| `/api/2.1/unity-catalog/catalogs` | ✅ 在 UC spec | UC 核心功能 |
| `/api/2.1/unity-catalog/tables` | ✅ 在 UC spec | UC 核心功能 |
| `/api/2.1/unity-catalog/lance/v1/namespaces/*` | ❌ 不在 UC spec | Lance Phase 1 metadata |
| `/api/2.1/unity-catalog/lance/v1/table/{id}/query` | ❌ 不在 UC spec | Lance Phase 2 data |

### 1.2 Lance REST OpenAPI Baseline

Phase 2 使用独立的 Lance REST OpenAPI baseline：

- 路径：`server/src/test/resources/lance/lance-rest-phase2-openapi-baseline.yml`
- 来源：上游 Lance REST OpenAPI 快照
- 用途：测试 contract verification

---

## 2. 暴露策略决策

### 2.1 短期策略（Phase 2）

**决策：Lance endpoints 不纳入 UC generated clients**

理由：
- Lance REST 是 experimental 功能，协议仍在演进
- Lance data endpoints 需要专用 backend（worker/sidecar），不是所有 UC 部署都有
- 客户端形态复杂：官方 LanceDB client、raw HTTP、UC wrapper 多种方式并存

### 2.2 推荐客户端形态

| 场景 | 推荐客户端 | 说明 |
|------|------------|------|
| **LanceDB Python 用户** | 官方 LanceDB Python client + UC adapter | LanceDB client 已有 remote/namespace 配置，需要 UC 兼容 adapter |
| **UC Java 用户** | raw HTTP + Armeria/JDK HttpClient | 当前最小协议 smoke 已验证 |
| **UC Python 用户（非 LanceDB）** | `urllib` + `pyarrow` | 最小协议客户端，已验证 |
| **Rust 用户** | raw HTTP + `reqwest`/std TcpStream | 最小协议 smoke 已验证 |

### 2.3 长期策略（Phase 3+）

待 Lance REST 协议稳定后：
1. 评估是否将 Lance endpoints 纳入 UC OpenAPI spec
2. 生成 UC Java/Python Lance data client
3. 统一 UC client 和 LanceDB client 的认证/授权路径

---

## 3. Lance Phase 2 OpenAPI Baseline

### 3.1 Data Endpoints

| Endpoint | Method | Content-Type | Response |
|----------|--------|--------------|----------|
| `/v1/table/{id}/query` | POST | JSON | Arrow IPC file/stream |
| `/v1/table/{id}/count_rows` | POST | JSON | integer (scalar) |
| `/v1/table/{id}/stats` | POST | JSON | JSON object |
| `/v1/table/{id}/insert` | POST | Arrow stream | JSON |
| `/v1/table/{id}/merge_insert` | POST | Arrow stream | JSON |
| `/v1/table/{id}/update` | POST | JSON | JSON |
| `/v1/table/{id}/delete` | POST | JSON | JSON |
| `/v1/table/{id}/explain_plan` | POST | JSON | string (scalar) |
| `/v1/table/{id}/analyze_plan` | POST | JSON | JSON (scalar) |
| `/v1/table/{id}/create` | POST | Arrow stream | JSON |

### 3.2 Admin Endpoints

| Endpoint | Method | Content-Type | Response |
|----------|--------|--------------|----------|
| `/admin/reconcile` | POST | JSON | JSON |
| `/admin/worker/health` | POST | JSON | JSON |

---

## 4. Generated Client 状态

### 4.1 UC Generated Clients

| 语言 | Lance Metadata | Lance Data | 说明 |
|------|----------------|------------|------|
| Java | ❌ 不包含 | ❌ 不包含 | UC Java client 不生成 Lance APIs |
| Python | ❌ 不包含 | ❌ 不包含 | UC Python client 不生成 Lance APIs |

### 4.2 Lance 专用 Clients

| 语言 | Client | 状态 |
|------|--------|------|
| Python | LanceDB Python (`lancedb`) | 官方支持，需 UC adapter |
| Java | LanceDB Java | 官方支持，需 UC adapter |
| Rust | LanceDB Rust | 官方支持，需 UC adapter |

---

## 5. 实施计划

### 5.1 Phase 2 剩余工作

- [x] real worker explain_plan/analyze_plan 支持（mock 实现）
- [x] Lance OpenAPI 暴露策略文档
- [ ] Python LanceDB client UC adapter
- [ ] Java LanceDB client UC adapter
- [ ] Rust LanceDB client UC adapter

### 5.2 Phase 3+ 考虑

- Lance endpoints 纳入 UC OpenAPI spec
- UC generated Lance data client
- 统一 UC/LanceDB client 认证/授权

---

## 6. 参考文档

- `docs/lance/unitycatalog-lancedb-phase-2-data-plane-design.md`
- `docs/lance/unitycatalog-lancedb-phase-2-progress.md`
- `server/src/test/resources/lance/lance-rest-phase2-openapi-baseline.yml`
- `api/all.yaml` (UC OpenAPI spec)