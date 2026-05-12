# Unity Catalog Lance Nightly Fixture 文档

更新日期：2026-05-12

## 1. 文档目标

本文档用于固化 Lance Phase 2 Nightly 测试的 fixture 配置、依赖安装、启动流程和运维指南。

Nightly fixture 指的是真实 LanceDB worker 进程的测试环境，用于验证 UC 与 Lance 执行引擎的端到端链路。

---

## 2. 测试分层

### 2.1 PR 测试层（无需外部依赖）

PR 层测试使用 fake/mock backend，无需安装 LanceDB Python 包：

| 测试类 | Backend 类型 | 外部依赖 |
|--------|-------------|----------|
| `LancePhase2WorkerHttpBackendRestTest` | Fake HTTP Worker | 无 |
| `LancePhase2DisabledBackendRestTest` | Disabled Backend | 无 |
| `LancePhase2ConfigurableBackendRestTest` | Echo Backend | 无 |
| `LancePhase2RequestMappingRestTest` | Echo Backend | 无 |
| `LancePhase2DataPlaneAuthorizationRestTest` | Echo Backend | 无 |
| `LancePhase2RequestValidationRestTest` | 不调用 Backend | 无 |
| `LancePhase2ArrowRequestReaderRestTest` | Echo Backend | 无 |
| `LancePhase2ArrowResponseWriterRestTest` | Echo Backend | 无 |
| `LancePhase2DataPlaneMetadataUpdateRestTest` | Echo Backend | 无 |
| `LancePhase2ReconcileRestTest` | Echo Backend | 无 |
| `LancePhase2ObservabilityRestTest` | Echo Backend | 无 |
| `LancePhase2ConcurrencyRestTest` | Echo Backend | 无 |
| `LancePhase2ScalarResponseRestTest` | Echo Backend | 无 |
| `LancePhase2ErrorResponseContractRestTest` | Echo Backend | 无 |
| `LancePhase2LocalUnsupportedRestTest` | 不调用 Backend | 无 |
| `LancePhase2StorageCredentialRestTest` | Echo Backend + Mock Credential | 无 |
| `LancePhase2LegacyReadConfigRestTest` | Echo Backend | 无 |
| `LancePhase2BackendCommittedFailureRestTest` | Echo Backend + Mock Failure | 无 |

这些测试在 CI PR workflow 中自动运行，无需人工准备。

### 2.2 Nightly 测试层（需要外部依赖）

Nightly 层测试使用真实 LanceDB worker 进程：

| 测试类 | Backend 类型 | 外部依赖 |
|--------|-------------|----------|
| `LancePhase2RealLanceDbWorkerProcessRestTest` | Real LanceDB Worker | `lancedb` + `pyarrow` |
| `LancePhase2PythonClientSmokeRestTest` | Real LanceDB Worker | `lancedb` + `pyarrow` |
| `LancePhase2JavaRustClientSmokeRestTest` | Real LanceDB Worker | `lancedb` + `pyarrow` |
| `LancePhase2RawHttpClientSmokeRestTest` | Real LanceDB Worker | `lancedb` + `pyarrow` |
| `LancePhase2RealWorkerE2ERestTest` | Minimal HTTP Worker | 无（Java fixture） |

---

## 3. 真实 LanceDB Worker 配置

### 3.1 Python Worker 脚本

位置：`server/src/test/resources/lance/real_lancedb_worker.py`

这是一个独立的 Python HTTP 服务器，使用 `lancedb` 和 `pyarrow` 包执行真实的 Lance 数据操作。

### 3.2 依赖安装

```bash
# 方式 1：系统级安装
pip3 install lancedb pyarrow

# 方式 2：虚拟环境
python3 -m venv /tmp/lance-worker-venv
source /tmp/lance-worker-venv/bin/activate
pip install lancedb pyarrow
```

依赖版本建议：
- `lancedb >= 0.4.0`
- `pyarrow >= 14.0.0`

### 3.3 环境变量配置

| 环境变量 | 说明 | 默认值 |
|----------|------|--------|
| `LANCE_REAL_WORKER_PYTHON` | Python 解释器路径 | `python3` |
| `LANCE_REAL_WORKER_PYTHONPATH` | PYTHONPATH（虚拟环境时使用） | 无 |

示例配置：

```bash
# 使用系统 Python
export LANCE_REAL_WORKER_PYTHON=python3

# 使用虚拟环境
export LANCE_REAL_WORKER_PYTHON=/tmp/lance-worker-venv/bin/python
export LANCE_REAL_WORKER_PYTHONPATH=/tmp/lance-worker-venv/lib/python3.11/site-packages
```

### 3.4 JVM 系统属性配置

可通过 JVM 系统属性覆盖环境变量：

| 系统属性 | 说明 |
|----------|------|
| `lance.real.worker.python` | Python 解释器路径 |

示例 sbt 配置：

```bash
./build/sbt testOnly "*RealLanceDb*" -Dlance.real.worker.python=/tmp/lance-worker-venv/bin/python
```

---

## 4. 测试启动流程

### 4.1 Java Fixture 类

`LanceRealLanceDbWorkerProcessFixture` 管理 Python worker 进程的生命周期：

- 启动：`worker.start(Path root)` - 在指定目录启动 worker
- 端口：动态分配，通过 stdout 输出 `READY <port>`
- Health：`/internal/lance/v1/health` endpoint
- 关闭：`worker.close()` - 优雅关闭或强制终止

### 4.2 进程启动流程

```
1. Java fixture 解析 Python 命令和 PYTHONPATH
2. 启动 Python worker 进程（real_lancedb_worker.py --port 0 --root <dir>）
3. Worker 初始化 lancedb 连接
4. Worker 输出 "READY <port>" 到 stdout
5. Java fixture 解析端口，构造 baseUrl
6. 测试执行
7. Java fixture 关闭 worker 进程
```

### 4.3 依赖检查

Worker 启动前会执行依赖检查：

```bash
python3 real_lancedb_worker.py --check
```

如果 `lancedb` 或 `pyarrow` 缺失，会返回错误：

```
missing Python modules: lancedb, pyarrow
```

测试会自动 skip（通过 `Assume.assumeTrue` 或 `@EnabledIf`）。

---

## 5. 测试数据存储

### 5.1 Worker 数据目录

Worker 使用 `--root` 参数指定的目录存储 Lance 数据文件：

```
<root>/
├── uc_<sha1(pathKey)>.lance/
│   ├── manifest.json
│   ├── data/
│   └── indices/
└── uc_<sha1(otherPathKey)>.lance/
```

### 5.2 Java 测试目录

Java 测试使用 `testDirectoryRoot` 作为基础目录：

```java
// BaseServerTest.java
protected Path testDirectoryRoot;
```

Worker fixture 在此基础上创建子目录：

```java
worker.start(testDirectoryRoot.resolve("real-lancedb-worker"));
```

### 5.3 目录清理

测试结束后：
- Java fixture 关闭 worker 进程
- 测试框架清理 `testDirectoryRoot`（根据 `BaseServerTest` 实现）
- Worker 临时目录通常会被自动清理

---

## 6. Skip 条件

### 6.1 自动 Skip 机制

测试类使用条件跳过机制：

```java
// LancePhase2RealLanceDbWorkerProcessRestTest.java
@BeforeEach
void setUp() {
  DependencyCheck check = LanceRealLanceDbWorkerProcessFixture.checkDependencies();
  org.junit.jupiter.api.Assumptions.assumeTrue(
      check.available(),
      "Real LanceDB worker dependencies not available: " + check.message());
  // ... 其他 setup
}
```

### 6.2 Skip 场景

| 场景 | Skip 原因 |
|------|----------|
| `lancedb` 包未安装 | "missing Python modules: lancedb" |
| `pyarrow` 包未安装 | "missing Python modules: pyarrow" |
| Python 解释器不存在 | "Failed to start real LanceDB worker process" |
| Worker 启动超时（15s） | "Real LanceDB worker did not become ready" |

---

## 7. 命令参考

### 7.1 本地运行 Nightly 测试

```bash
# 确保 lancedb/pyarrow 已安装
pip3 install lancedb pyarrow

# 运行真实 LanceDB worker 测试
./build/sbt testOnly "io.unitycatalog.server.service.lance.LancePhase2RealLanceDbWorkerProcessRestTest"

# 运行所有 nightly 测试（需要配置环境）
./build/sbt testOnly "*RealLanceDb*" "*PythonClient*" "*JavaRustClient*" "*RawHttpClient*"
```

### 7.2 使用虚拟环境运行

```bash
# 创建虚拟环境
python3 -m venv /tmp/lance-worker-venv
source /tmp/lance-worker-venv/bin/activate
pip install lancedb pyarrow

# 设置环境变量
export LANCE_REAL_WORKER_PYTHON=/tmp/lance-worker-venv/bin/python
export LANCE_REAL_WORKER_PYTHONPATH=/tmp/lance-worker-venv/lib/python3.11/site-packages

# 运行测试
./build/sbt testOnly "*RealLanceDb*"
```

### 7.3 检查依赖可用性

```bash
# 直接运行依赖检查
python3 server/src/test/resources/lance/real_lancedb_worker.py --check

# 成功时返回 exit code 0
# 失败时输出错误信息
```

### 7.4 手动启动 Worker（调试用）

```bash
# 启动 worker 并监听固定端口
python3 server/src/test/resources/lance/real_lancedb_worker.py --port 18080 --root /tmp/lance-debug

# Worker 输出: READY 18080

# 测试 health endpoint
curl http://127.0.0.1:18080/internal/lance/v1/health
# 返回: {"worker":"real-lancedb-worker","status":"ok"}

# Ctrl+C 终止 worker
```

---

## 8. CI/Nightly 集成

### 8.1 GitHub Actions 配置建议

```yaml
# .github/workflows/lance-nightly.yml
name: Lance Nightly Tests

on:
  schedule:
    - cron: '0 2 * * *'  # 每天凌晨 2:00 UTC
  workflow_dispatch:  # 手动触发

jobs:
  nightly:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup Python
        uses: actions/setup-python@v5
        with:
          python-version: '3.11'
      
      - name: Install LanceDB dependencies
        run: pip install lancedb pyarrow
      
      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      
      - name: Run Nightly tests
        run: |
          ./build/sbt testOnly "*RealLanceDb*" "*PythonClient*" "*JavaRustClient*"
        env:
          LANCE_REAL_WORKER_PYTHON: python3
```

### 8.2 Nightly 测试报告

Nightly 测试应输出：
- 测试执行结果（passed/failed/skipped）
- Skip 原因汇总（依赖缺失情况）
- Worker 进程日志（stdout/stderr）
- 数据目录状态（是否正确清理）

---

## 9. 故障排查

### 9.1 Worker 启动失败

| 错误信息 | 可能原因 | 解决方案 |
|----------|----------|----------|
| "missing Python modules" | lancedb/pyarrow 未安装 | `pip install lancedb pyarrow` |
| "Failed to start real LanceDB worker" | Python 路径错误 | 检查 `LANCE_REAL_WORKER_PYTHON` |
| "Real LanceDB worker did not become ready" | 启动超时（15s） | 检查 Python 版本、依赖加载速度 |

### 9.2 测试执行失败

| 错误类型 | 可能原因 | 解决方案 |
|----------|----------|----------|
| 404 NOT FOUND | 表未正确创建 | 检查 worker root 目录权限 |
| 500 WORKER_ERROR | LanceDB 执行异常 | 查看 worker stderr 输出 |
| Arrow IPC 解析失败 | pyarrow 版本不兼容 | 升级 pyarrow >= 14.0.0 |

### 9.3 日志查看

```bash
# Java 测试日志
./build/sbt testOnly "*RealLanceDb*" -- --show-times

# Worker 进程输出（测试框架会捕获）
# 查看 LanceRealLanceDbWorkerProcessFixture.output 字段
```

---

## 10. 版本兼容性

### 10.1 支持的 Python 版本

| Python 版本 | 支持状态 |
|-------------|----------|
| Python 3.8 | ✅ 支持 |
| Python 3.9 | ✅ 支持 |
| Python 3.10 | ✅ 支持 |
| Python 3.11 | ✅ 推荐 |
| Python 3.12 | ⚠️ 待验证 |

### 10.2 LanceDB 包版本

| LanceDB 版本 | 支持状态 |
|--------------|----------|
| 0.3.x | ⚠️ 功能有限 |
| 0.4.x | ✅ 推荐 |
| 0.5.x+ | ✅ 支持 |

注意：explain_plan/analyze_plan 在所有版本均返回 501（LanceDB Python SDK 无原生 API）。

---

## 11. 与 Ecosystem Smoke 的关系

Nightly fixture 是 Ecosystem Smoke（Spark/Ray/DuckDB）的基础设施：

| Ecosystem 测试 | 依赖关系 |
|----------------|----------|
| Spark connector smoke | 需要真实 worker + Spark 环境 |
| Ray connector smoke | 需要真实 worker + Ray 环境 |
| DuckDB/Pandas smoke | 需要真实 worker + DuckDB/Pandas |
| Python client smoke | 使用真实 worker（已实现） |

Ecosystem smoke 测试当前在 `LancePhase2EcosystemSmokeTest` 中 disabled，需要在后续迭代中启用。

---

## 12. 参考文件

| 文件 | 路径 |
|------|------|
| Python Worker 脚本 | `server/src/test/resources/lance/real_lancedb_worker.py` |
| Java Fixture 类 | `server/src/test/java/.../LanceRealLanceDbWorkerProcessFixture.java` |
| 真实 Worker 测试 | `server/src/test/java/.../LancePhase2RealLanceDbWorkerProcessRestTest.java` |
| Phase 2 进度文档 | `docs/lance/unitycatalog-lance-phase-2-progress.md` |
| Phase 2 设计文档 | `docs/lance/unitycatalog-lancedb-phase-2-data-plane-design.md` |