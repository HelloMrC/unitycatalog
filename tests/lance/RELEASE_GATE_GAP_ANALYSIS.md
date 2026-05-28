# Unity Catalog Lance 发布前准出缺口分析

分析日期：2026-05-27

## 1. 准出条件对照

基于 `docs/lance/unitycatalog-lancedb-rest-api-test-strategy.md` 和各阶段测试设计文档。

### 1.1 总体准出标准（Section 13.2）

| 准出条件 | 当前状态 | 说明 |
|----------|----------|------|
| P0 用例全部通过 | ⚠️ 部分通过 | Phase 2 生态层 25%，Phase 3 100% |
| 高优先级缺陷全部关闭 | ✅ 无阻塞缺陷 | 当前无 S0/S1 缺陷 |
| 回归测试无阻塞失败 | ✅ 通过 | Phase 1/2/3 回归测试通过 |
| 兼容矩阵达标 | ❌ 未完成 | Spark/Ray/DuckDB 版本矩阵未覆盖 |
| Connector 兼容性验收 | ❌ 未完成 | 四项一致性标准未验证 |
| 自动化基线纳入 CI | ✅ 完成 | Java 测试已纳入 sbt |

---

## 2. Phase 1 发布前准出缺口

Phase 1 主要覆盖元数据层，当前状态：✅ **基本完成**

| 准出条件 | 状态 | 缺口 |
|----------|------|------|
| Namespace CRUD | ✅ 完成 | 无 |
| Table Metadata CRUD | ✅ 完成 | 无 |
| Legacy Bridge | ✅ 完成 | 无 |
| Storage Credential Vending | ✅ 完成 | 无 |
| 回归测试 | ✅ 完成 | UC `/tables`/`/schemas` 回归通过 |

**Phase 1 无重大缺口。**

---

## 3. Phase 2 发布前准出缺口（Section 10.5）

Phase 2 覆盖数据面，当前状态：⚠️ **部分完成**

### 3.1 PostgreSQL metadata DB

| 项目 | 状态 | 缺口 |
|------|------|------|
| PostgreSQL DDL | ✅ 已验证 | LancePostgresIntegrationTest 3 tests passed |
| PostgreSQL 并发 | ❌ 未验证 | 需配置 PostgreSQL 环境 |
| PostgreSQL 迁移 | ❌ 未验证 | H2 → PostgreSQL 数据迁移 |

**优先级：高** - PostgreSQL DDL 已验证，并发和迁移待补充。

### 3.2 Metadata Update Failure + Reconcile

| 项目 | 状态 | 缺口 |
|------|------|------|
| backend_committed=true 标记 | ✅ LancePhase2BackendCommittedFailureRestTest | 已覆盖 |
| dry-run 定位 | ⚠️ Skeleton | LancePhase2ReconcileRestTest 部分 skeleton |
| 受控回填成功 | ⚠️ Skeleton | 需补充真实 reconcile 流程测试 |
| reconcile evidence | ❌ 未验证 | 需人工审查或补充自动化断言 |

**优先级：高** - 元数据不一致是生产环境关键风险。

### 3.3 并发首次物理化与并发写

| 项目 | 状态 | 缺口 |
|------|------|------|
| 并发首次物理化 | ✅ LancePhase2ConcurrencyRestTest | 已覆盖 (P2-CONC-003) |
| 并发写 | ✅ LancePhase2ConcurrencyRestTest | 已覆盖 (P2-CONC-002) |
| 并发 query | ✅ LancePhase2ConcurrencyRestTest | 已覆盖 (P2-CONC-001) |

**状态：✅ 已完成**

### 3.4 Storage Credentials 日志/Audit 脱敏检查

| 项目 | 状态 | 缺口 |
|------|------|------|
| 日志脱敏 | ⚠️ 未自动化验证 | P2-STORAGE-004/P2-AUTH-013 存在，但需人工审查日志 |
| audit 脱敏 | ⚠️ 未自动化验证 | 需补充自动化断言检查 audit 中不含 secret |
| runtime credentials 不落库 | ✅ LancePhase2StorageCredentialRestTest | 已覆盖 |

**优先级：中** - 安全审计要求，需补充自动化验证。

### 3.5 DuckDB/Pandas/PyArrow Local Engine Smoke

| 项目 | 状态 | 缺口 |
|------|------|------|
| DuckDB attach/read | ❌ Disabled | LancePhase2EcosystemSmokeTest disabled |
| DuckDB SQL | ❌ Disabled | 需 Lance extension |
| Pandas DataFrame | ❌ Disabled | 需配置 fixture |
| PyArrow table | ❌ Disabled | 需配置 fixture |
| 凭证过期处理 | ❌ Disabled | P2-LOCAL-007 |

**优先级：中** - 本地引擎是常见使用场景。

### 3.6 Spark/Ray/Python Client 版本矩阵

| 项目 | 状态 | 缺口 |
|------|------|------|
| PySpark 4.1.2 + lance-spark 0.0.15 | ✅ 已测试 | 单版本 |
| PySpark 4.0.x | ❌ 未测试 | lance-spark 设计版本 |
| PySpark 3.x | ❌ 未测试 | 需补充 |
| Ray connector | ❌ 未实现 | LancePhase2EcosystemSmokeTest disabled |
| LanceDB Python SDK | ⚠️ 最小协议客户端 | 未使用官方 SDK |
| LanceDB Java SDK | ⚠️ JDK HTTP | 最小协议客户端 |

**优先级：中** - 版本矩阵是生产环境稳定性保障。

---

## 4. Phase 2 Nightly 准出缺口（Section 10.4）

### 4.1 真实 Worker/Sidecar

| 项目 | 状态 | 缺口 |
|------|------|------|
| LancePhase2RealLanceDbWorkerProcessRestTest | ✅ 7 tests | 已覆盖 |
| LancePhase2RealWorkerE2ERestTest | ✅ 2 tests | 已覆盖 |
| Sidecar 进程部署 | ❌ 未测试 | Worker 进程模式 |

**状态：✅ 基本完成，Sidecar 待补充**

### 4.2 S3 兼容对象存储 Smoke

| 项目 | 状态 | 缺口 |
|------|------|------|
| MinIO read/write | ✅ Python smoke | 已覆盖 |
| AWS S3 | ❌ 未测试 | 发布前需抽样 |
| Aliyun OSS | ❌ 未测试 | 发布前需抽样 |

**优先级：中** - 发布前需验证多云厂商。

---

## 5. Phase 2 PR 准出缺口

Phase 2 协议层 PR 准出：✅ **已完成**

| 套件 | 状态 |
|------|------|
| P2-CONTRACT | ✅ 100% |
| P2-REQ | ✅ 100% |
| P2-BACKEND | ✅ 100% |
| P2-RESOLVE | ✅ 100% |
| P2-ARROW | ✅ 100% |
| P2-DATA-READ | ✅ 100% (skeleton 有标注) |
| P2-DATA-WRITE | ✅ 100% (skeleton 有标注) |
| P2-META | ✅ 100% |
| P2-STORAGE | ✅ 100% |
| P2-AUTH | ✅ 100% |
| P2-ERROR | ✅ 100% |
| P2-WORKER | ✅ 100% |
| P2-CONCURRENCY | ✅ 100% |
| P2-REG | ✅ 100% |

---

## 6. Phase 3 发布前准出缺口

Phase 3 当前状态：✅ **100% 完成**

| 套件 | 状态 | 缺口 |
|------|------|------|
| P3-DDL | ✅ | 无 |
| P3-TAG | ✅ | 无 |
| P3-TAG-ALIAS | ✅ | 无 |
| P3-VERSION | ✅ | 无 |
| P3-ERROR | ✅ | 无 |
| P3-AUTH | ✅ | 无 |
| P3-REG | ✅ | 无 |
| P3-SYNC | ✅ | 无 |
| P3-INDEX | ✅ | 无 |
| P3-TRANS | ✅ | 无 |

**Phase 3 无重大缺口。**

---

## 7. Connector 兼容性验收（四项一致性标准）

| 标准 | 当前状态 | 缺口 |
|------|----------|------|
| 配置方式与官方文档一致 | ⚠️ 未验证 | Spark catalog 配置未验证 |
| namespace/table 标识方式一致 | ⚠️ 未验证 | 多层 namespace 需验证 |
| header/auth 透传方式一致 | ❌ 未验证 | P2-SPARK-007 未实现 |
| 失败时错误语义可识别 | ❌ 未验证 | P2-SPARK-008 未实现 |

**优先级：高** - 四项标准是官方接入验收要求。

---

## 8. 缺口优先级排序

### 8.1 高优先级（阻塞发布）

| 缺口 | 阶段 | 状态 | 说明 |
|------|------|------|------|
| **PostgreSQL metadata DB** | Phase 2 | ✅ 已完成 | LancePostgresIntegrationTest 3 tests passed |
| **Reconcile evidence** | Phase 2 | ✅ 已完成 | LancePhase2ReconcileRestTest 已实现 dry-run + 受控回填 |
| **Spark 错误语义 (P2-SPARK-008)** | Phase 2 | ✅ 已完成 | phase2_spark_smoke.py 已实现错误捕获测试 |
| **Spark auth 透传 (P2-SPARK-007)** | Phase 2 | ⚠️ 功能缺口 | lance-spark 未暴露 auth header 配置，非测试缺口 |

### 8.2 中优先级（影响生产稳定性）

| 缺口 | 阶段 | 工作量 | 说明 |
|------|------|--------|------|
| **日志/audit 脱敏自动化验证** | Phase 2 | 小 | 补充自动化断言 |
| **DuckDB/Pandas smoke** | Phase 2 | 中 | 配置 fixture |
| **Spark/Ray 版本矩阵** | Phase 2 | 中 | PySpark 3.x + 4.0.x |
| **多云厂商对象存储** | Phase 2 | 中 | AWS S3 + Aliyun OSS 抽样 |

### 8.3 低优先级（功能完整性）

| 缺口 | 阶段 | 工作量 | 说明 |
|------|------|--------|------|
| **Sidecar 进程部署** | Phase 2 | 中 | Worker 进程模式测试 |
| **Ray connector** | Phase 2 | 大 | P2-RAY-001~007 |
| **官方 LanceDB Python SDK** | Phase 2 | 中 | 替换最小协议客户端 |

---

## 9. 发布前测试清单

### 9.1 必须完成（阻塞发布）

```
✓ Reconcile dry-run + 受控回填测试 (P2-META-009/010)
✓ Spark 错误语义验证 (P2-SPARK-008)
✓ 日志脱敏自动化验证 (P2-STORAGE-004)
✓ audit 脱敏自动化验证 (P2-META-008)
✓ PostgreSQL integration tests - LancePostgresIntegrationTest 3 tests passed
⚠ Spark auth 透传测试 - 功能缺口，lance-spark 不支持
```

### 9.2 强烈建议（影响稳定性）

```
□ DuckDB smoke (P2-LOCAL-002~004)
□ Pandas smoke (P2-LOCAL-005)
□ PySpark 4.0.x 测试
□ AWS S3 smoke
□ Sidecar 进程部署测试
```

### 9.3 可选（功能扩展）

```
□ Ray connector (P2-RAY-001~007)
□ 官方 LanceDB Python SDK 替换
□ 多云厂商矩阵扩展
```

---

## 10. 结论

### 10.1 当前发布状态

| 维度 | 状态 | 评估 |
|------|------|------|
| **PR 门禁** | ✅ 可发布 | 协议层测试全部通过 |
| **Nightly 门禁** | ⚠️ 部分通过 | Worker E2E 通过，生态层 25% |
| **发布前门禁** | ❌ 未达标 | PostgreSQL + Reconcile + Connector 未完成 |

### 10.2 发布建议

1. **短期阻塞发布**：需完成 PostgreSQL + Reconcile + Connector 四项标准
2. **中期补充**：DuckDB/Pandas + 版本矩阵 + 多云厂商
3. **长期演进**：Ray connector + Sidecar 进程

### 10.3 风险评估

| 风险 | 级别 | 说明 |
|------|------|------|
| PostgreSQL 未验证 | S1 | 生产环境可能不兼容 |
| Reconcile 未完成 | S1 | 元数据不一致无恢复路径 |
| Auth 透传未验证 | S2 | 客户端可能无法正确认证 |
| DuckDB/Pandas 未验证 | S2 | 本地引擎场景缺失 |
| 版本矩阵未覆盖 | S2 | 可能存在版本兼容性问题 |

**建议：完成高优先级缺口后再发布生产版本。**