# Phase 2 测试遗漏分析报告

分析日期：2026-05-26

## 1. 设计偏差

### 1.1 P2-SPARK 用例定义偏差

| 设计文档定义 | 当前覆盖率分析标注 | 问题 |
|--------------|-------------------|------|
| P2-SPARK-006: 多层 namespace | vector column + vector index | 用例场景不一致 |
| P2-SPARK-007: header/auth 透传 | partition column filter + aggregation | 用例场景不一致 |
| P2-SPARK-005: UPDATE/DELETE | write with rollback | UPDATE/DELETE 在 Spark lance connector 中可能不支持 |

**修正建议：**
- P2-SPARK-006: 测试 Spark 读取多层 namespace 如 `prod$team_a$embeddings`
- P2-SPARK-007: 测试 Bearer/API key 通过 Spark context 透传到 UC

### 1.2 Spark write 测试设计偏差

设计文档定义 P2-SPARK-002/003 为 Spark 直接写入 Lance 表：
- P2-SPARK-002: CREATE TABLE 或 declared 首次物理化
- P2-SPARK-003: INSERT INTO

当前 Python smoke 测试使用 **LanceDB 写入 S3 + UC 注册元数据** 模式，而非 Spark 直接写入。

**原因：** lance-spark-4.0_2.13 在 PySpark 4.1.2 下 write 路径有 CatalogNotFoundException 兼容性问题。

**建议：** 标注为 lance-spark 版本兼容性缺口，而非测试缺口。

---

## 2. 测试设计遗漏分析

### 2.1 设计文档定义但未实现的测试

#### 2.1.1 发布前准出条件缺口 (Section 10.5)

| 要求 | 当前状态 | 缺口分析 |
|------|----------|----------|
| PostgreSQL metadata DB | ❌ 未测试 | Java 测试使用 H2，PostgreSQL 仅在 integration-tests |
| metadata update failure + reconcile evidence | ⚠️ Skeleton | LancePhase2ReconcileRestTest 存在但部分 skeleton |
| 并发首次物理化 | ✅ LancePhase2ConcurrencyRestTest | 已覆盖 |
| storage credentials 日志/audit 脱敏检查 | ⚠️ 部分 | P2-STORAGE-004/P2-AUTH-013 存在，但需人工审查日志 |
| DuckDB/Pandas/PyArrow smoke | ❌ Disabled | LancePhase2EcosystemSmokeTest disabled |
| Spark/Ray/Python client 版本矩阵 | ❌ 未实现 | 仅测试 PySpark 4.1.2 + lance-spark 0.0.15 |

#### 2.1.2 Vector Query 边界值缺口 (Section 9.6)

| 用例 ID | 场景 | 当前状态 |
|---------|------|----------|
| P2-DATA-004 | vector query 参数全量 | Skeleton (P2-DATA-READ) |
| P2-DATA-005 | ANN 参数 (prefilter/ef/nprobes/refineFactor) | Skeleton |
| P2-DATA-006 | full text query | Skeleton |

**需准备：**
- 含 vector index 的 Lance table (≥256 rows)
- 含 FTS index 的 Lance table

#### 2.1.3 MergeInsert 全参数缺口 (Section 9.7)

| 用例 ID | 场景 | 当前状态 |
|---------|------|----------|
| P2-DATA-024 | merge_insert 8 个参数组合 | Skeleton (P2-DATA-WRITE) |

**需覆盖参数：**
- on, whenMatchedUpdateAll, whenMatchedUpdateAllFilt
- whenNotMatchedInsertAll, whenNotMatchedInsertAllFilt
- whenNotMatchedBySourceDeleteAll, whenNotMatchedBySourceDeleteAllFilt
- whenNotMatchedBySourceUpdateAll

---

## 3. 边界值覆盖完整性分析

### 3.1 已覆盖的边界值

| 边界场景 | 测试覆盖 | 设计文档参考 |
|----------|----------|--------------|
| Request Size 边界 | P2-REQ-007, P2-ARROW-005, P2-ARROW-012 | ✅ JSON/Arrow size limit |
| Content-Type 边界 | P2-REQ-008, P2-ARROW-004 | ✅ invalid JSON/Arrow |
| Table State 边界 | P2-RESOLVE-001~010 | ✅ ACTIVE/DECLARED/DEREGISTERED/DROPPED |
| Metadata Version 边界 | P2-META-004, P2-DATA-029 | ✅ version 防倒退 + null schema |
| Credential 边界 | P2-STORAGE-005~006 | ✅ expired/denied credential |
| Worker 边界 | P2-WORKER-003, P2-WORKER-008~013 | ✅ health/timeout/streaming failure |
| 并发边界 | P2-CONC-001~006 | ✅ concurrent query/write/materialization |
| Error 边界 | P2-ERROR-001~018 | ✅ 400/401/403/404/409/413/415/500/501/503/504 |

### 3.2 边界值缺口

| 边界场景 | 设计要求 | 当前状态 |
|----------|----------|----------|
| **Schema peek 边界** | P2-ARROW-011: peek 失败降级 + warning | ⚠️ 存在测试但需验证 warning 记录 |
| **Legacy read 开关边界** | P2-RESOLVE-007/008: 默认拒绝 + 显式开启后可读 | ✅ LancePhase2LegacyReadConfigRestTest |
| **restore/rename/schema evolution 边界** | P2-ERROR-016/017/018: 返回稳定 UNIMPLEMENTED | ✅ LancePhase2LocalUnsupportedRestTest |
| **Token exchange 不回环** | P2-AUTH-016: 不通过内部 HTTP 回环 | ⚠️ 需代码审查而非 runtime test |

---

## 4. 测试设计本身是否完备

### 4.1 设计文档覆盖范围评估

| 设计域 | 设计文档覆盖 | 评估 |
|--------|--------------|------|
| Endpoint 契约 | 10 个 data endpoint + method/content type | ✅ 完备 |
| Request parsing | path/header/body/context/delimiter | ✅ 完备 |
| Backend SPI | command/result + fake/disabled/worker backend | ✅ 完备 |
| Arrow IPC | stream/file + size limit + media type + backpressure | ✅ 完备 |
| Read data | query/count/stats/explain/analyze + vector/FTS | ⚠️ vector/FTS 为 skeleton |
| Write data | insert/merge/update/delete/create + idempotency | ✅ 完备 |
| Metadata update | state transition + version/schema/stats + reconcile | ✅ 完备 |
| Auth/Governance | Bearer/API key + permissions + audit/metrics | ✅ 完备 |
| Storage options | template + runtime credentials + 脱敏 | ✅ 完备 |
| Error handling | Lance error shape + 所有 HTTP status | ✅ 完备 |
| Consistency | 并发 + idempotency + metadata version 防倒退 | ✅ 完备 |
| Regression | Phase 1 + UC/Iceberg/Delta 路由隔离 | ✅ 完备 |
| Ecosystem smoke | Python/Spark/Ray/DuckDB/Pandas | ⚠️ Ray/DuckDB/Pandas disabled |

### 4.2 设计文档潜在遗漏

| 遗漏项 | 说明 | 建议 |
|--------|------|------|
| **S3 写失败回滚** | 设计文档未明确定义 LanceDB 写 S3 失败后的回滚机制 | 当前 Python smoke 已覆盖，建议补充到设计文档 |
| **Spark multi-version 矩阵** | 设计仅提 Spark smoke，未定义版本矩阵 | 发布前需补充 PySpark 3.x + lance-spark 0.0.x 测试矩阵 |
| **Worker sidecar 真实部署** | 设计未定义 sidecar 部署方式测试 | Nightly 需补充真实 sidecar 进程测试 |

---

## 5. 优先修复项

### 5.1 高优先级（影响测试完整性）

| 修复项 | 工作量 | 说明 |
|--------|--------|------|
| P2-SPARK-006 多层 namespace 测试 | 小 | 修正当前错误标注 |
| P2-SPARK-007 header/auth 透传测试 | 中 | 需 Spark context 配置 |
| Vector query skeleton 启用 | 中 | 准备含 vector index 的 fixture table |
| DuckDB/Pandas smoke 启用 | 中 | 配置环境 fixture |

### 5.2 中优先级（影响发布前准出）

| 修复项 | 工作量 | 说明 |
|--------|--------|------|
| PostgreSQL integration tests | 大 | 需配置 PostgreSQL 环境 |
| Reconcile evidence 补充 | 中 | 补充 dry-run + 受控回填测试 |
| Storage credentials 脱敏审查 | 小 | 人工审查日志 + 补充自动化断言 |

### 5.3 低优先级（设计偏差修正）

| 修复项 | 工作量 | 说明 |
|--------|--------|------|
| P2-SPARK 用例标注修正 | 小 | 更新 PHASE2_COVERAGE_ANALYSIS.md |
| lance-spark 版本兼容性文档 | 小 | 标注 write 路径兼容性缺口 |
| S3 写失败回滚补充到设计 | 小 | 更新测试设计文档 |

---

## 6. 结论

### 6.1 测试设计完备性评估

设计文档 **基本完备**，覆盖了 Phase 2 所有核心能力域。主要缺口在于：
1. Ecosystem smoke (Ray/DuckDB/Pandas) 需环境配置
2. Vector/FTS query 边界值需 fixture table 准备
3. 发布前准出条件需额外环境 (PostgreSQL, Spark 版本矩阵)

### 6.2 当前实现完整性评估

当前实现 **协议层 100% 覆盖**，**生态层 31% 覆盖**。主要缺口：
1. P2-SPARK-006/007 用例定义偏差（需修正）
2. P2-SPARK-008 Spark read via UC credentials 缺口（需 lance-spark 功能支持）
3. Vector/FTS query skeleton 未启用（需 fixture）
4. Ray/DuckDB/Pandas 未实现（需环境配置）

### 6.3 下一步行动

1. **立即修复：** P2-SPARK 用例标注偏差
2. **短期补充：** 多层 namespace + header/auth 透传测试
3. **中期补充：** Vector query + DuckDB/Pandas smoke
4. **发布前补充：** PostgreSQL + Spark 版本矩阵 + reconcile evidence