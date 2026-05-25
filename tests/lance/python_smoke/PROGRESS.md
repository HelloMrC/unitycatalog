# Phase 1 Lance Client Smoke Tests Implementation Progress

更新日期：2026-05-23

## 1. 实现状态

| 任务 | 状态 | 说明 |
|------|------|------|
| 创建测试目录结构 | ✅ 完成 | `tests/lance/python_smoke/` |
| 创建 requirements.txt | ✅ 完成 | 包含必需和可选依赖 |
| 实现 Raw HTTP smoke 测试 | ✅ 完成 | `phase1_raw_http_smoke.py` (919 行) |
| 实现 Native Client smoke 测试 | ✅ 完成 | `phase1_native_client_smoke.py` (499 行) |
| 创建运行脚本 | ✅ 完成 | `run_tests.sh` (300 行) |
| 创建环境检查脚本 | ✅ 完成 | `check_env.sh` (222 行) |
| 创建 README 文档 | ✅ 完成 | `README.md` (126 行) |

## 2. 文件清单

```
tests/lance/python_smoke/
├── README.md                     (126 行) - 使用说明
├── requirements.txt              (26 行)  - 依赖配置
├── phase1_raw_http_smoke.py      (919 行) - Raw HTTP 测试 (必需)
├── phase1_native_client_smoke.py (499 行) - Native Client 测试 (可选)
├── run_tests.sh                  (300 行) - 测试运行脚本
└── check_env.sh                  (222 行) - 环境检查脚本

总计: 2092 行代码和文档
```

## 3. 测试覆盖

### 3.1 Raw HTTP Tests (Required)

覆盖测试设计文档中的以下测试用例：

| 用例 ID | Endpoint | 测试类 |
|---------|----------|--------|
| P1-CLIENT-002 | `/v1/namespace/{id}/exists` | `TestPhase1NamespaceRawHttp.test_p1_client_002_namespace_exists_endpoint` |
| P1-CLIENT-005 | Namespace lifecycle | `TestPhase1NamespaceRawHttp.test_p1_client_005_namespace_create_describe_list_drop` |
| P1-CLIENT-005 | HTTP contract | `TestPhase1HttpContract` |
| P1-CLIENT-006 | Table lifecycle | `TestPhase1TableRawHttp.test_p1_client_006_table_declare_describe_exists_deregister` |
| P1-CLIENT-006 | create-empty alias | `TestPhase1TableRawHttp.test_create_empty_alias_equivalence` |

附加测试：
- Namespace deep path (4 layers)
- Invalid identifier error handling
- vend_credentials behavior
- Method not allowed enforcement
- JSON content type verification
- Error response shape validation

### 3.2 Native Client Tests (Optional)

覆盖 P1-CLIENT-001:

| 测试类 | 覆盖范围 |
|--------|----------|
| `TestPhase1NamespaceNativeClient` | namespace create/list/describe/drop |
| `TestPhase1TableNativeClient` | table create/open/drop |
| `TestPhase1NativeClientCompatibility` | path format compatibility |
| `TestPhase1NativeClientErrorHandling` | error translation |

## 4. 运行方式

### 环境检查

```bash
cd tests/lance/python_smoke
./check_env.sh
```

### 运行测试

```bash
# 运行所有测试
./run_tests.sh

# 仅运行 Raw HTTP 测试 (必需)
./run_tests.sh --raw-http-only

# 仅运行 Native Client 测试 (可选)
./run_tests.sh --native-only
```

### 环境变量配置

```bash
# 配置 UC Server 地址
export UC_HOST="http://localhost:8080"
export UC_LANCE_URI="http://localhost:8080/api/2.1/unity-catalog/lance"
```

## 5. 依赖说明

### 必需依赖 (Raw HTTP Tests)

```
requests>=2.28.0    # HTTP 客户端
pytest>=7.0         # 测试框架
```

### 可选依赖 (Native Client Tests)

```
lancedb>=0.4.0      # LanceDB Python SDK
lance-namespace>=0.3.2 # REST namespace client
pyarrow>=16         # Arrow 支持
```

## 6. 下一步

1. **验证测试可运行**: 启动 UC Server，运行 `./check_env.sh` 和 `./run_tests.sh`
2. **集成到 CI/CD**: 将测试添加到 PR 门禁或 Nightly 测试
3. **补充认证测试**: 如需要，添加 Bearer token / API key 认证配置

## 7. 设计文档参考

- `docs/lance/unitycatalog-lancedb-phase-1-metadata-test-design.md` Section 7.10
- 测试用例: P1-CLIENT-001, P1-CLIENT-002, P1-CLIENT-005, P1-CLIENT-006