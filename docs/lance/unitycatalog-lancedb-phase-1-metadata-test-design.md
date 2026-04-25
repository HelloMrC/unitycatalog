# Unity Catalog Lance REST API 第一阶段详细测试设计

副标题：Metadata Compatibility 测试设计

更新日期：2026-04-25

关联文档：

- `docs/lance/unitycatalog-lancedb-phase-1-metadata-design.md`
- `docs/lance/unitycatalog-lancedb-rest-api-technical-design.md`
- `docs/lance/unitycatalog-lancedb-rest-api-test-strategy.md`
- `docs/lance/unitycatalog-lancedb-rest-api-test-design.md`

## 1. 文档目标

本文档用于将第一阶段 `Metadata Compatibility` 设计展开为可执行的详细测试设计。

第一阶段测试只验证 Lance namespace 与 table metadata 兼容能力，不验证 query、Arrow IPC、DML、index、version、tag、transaction、batch commit 等后续阶段能力。

本阶段完成标准：

- Phase 1 endpoint 的 HTTP Method、路径、请求响应语义与上游 OpenAPI 基线一致
- namespace 与 table metadata 能在任意深度 path 下正确创建、查询、列举、删除或注销
- `DeclareTable` 主路径与 `CreateEmptyTable` deprecated alias 行为一致且可审计
- `DescribeTable(vend_credentials=true)` 能返回合法 `storage_options`，且临时凭证不落库
- legacy Unity Lance table 能被 `list/describe/exists` 桥接读取
- Lance 路由没有绕过 UC 认证、授权、审计、凭证和原有路由回归

## 2. 测试基线

### 2.1 上游协议基线

本阶段测试固定以下上游快照：

| 类型 | 路径 | Commit |
|---|---|---|
| Lance REST OpenAPI | `/home/lei/data_ai/learning/codebase/lancedb-docs/docs/api-reference/rest/openapi.yml` | `6c0ccc001e6b` |
| Lance 客户端源码 | `/home/lei/data_ai/learning/codebase/lancedb` | `a92ae0ded522` |

基线优先级：

1. endpoint 路径、HTTP Method、请求响应结构以 REST OpenAPI 快照为准。
2. Python / Java / Rust 客户端实现用于校验真实消费行为。
3. 某语言客户端未暴露便利方法时，由 raw HTTP 协议测试补齐。

### 2.2 被测服务前缀

UC 内部挂载前缀：

```text
/api/2.1/unity-catalog/lance
```

因此 Lance Phase 1 endpoint 的实际访问路径形如：

```text
/api/2.1/unity-catalog/lance/v1/namespace/{id}/create
```

## 3. 范围与非范围

### 3.1 范围内

| 能力域 | 范围 |
|---|---|
| 契约与路由 | OpenAPI diff、HTTP Method、路由挂载、DTO 映射 |
| 认证与授权 | `LanceAuthDecorator`、`x-api-key`、Bearer、context headers、`LanceResourceKeyMapper` |
| Namespace metadata | create/list/describe/drop/exists，root 与任意深度 path |
| Table metadata | register/declare/create-empty/describe/exists/drop/deregister/list |
| 持久化 | `uc_lance_namespaces`、`uc_lance_assets`、`uc_lance_tables`、`uc_lance_api_keys` |
| Storage options | `storage_options_template_json`、`vend_credentials`、临时凭证不落库 |
| Legacy bridge | `EXTERNAL + TEXT + properties.table_type=lance` 的识别与桥接读取 |
| 回归 | UC `/tables`、Iceberg REST、Delta REST、原有 `AuthDecorator` / `KeyMapper` |

### 3.2 范围外

| 能力 | 本阶段处理 |
|---|---|
| `/v1/table/{id}/create` 实际建表 | 不测，数据面阶段覆盖 |
| query / stats / count rows | 不测，Phase 2 覆盖 |
| insert / merge_insert / update / delete | 不测，Phase 2 覆盖 |
| restore / rename / schema evolution | 不测，后续阶段覆盖 |
| index / version / tag / transaction / batch commit | 不测，Phase 3 覆盖 |
| Lance UI / SDK 生成 | 不测，非 Phase 1 目标 |

## 4. Phase 1 Endpoint 覆盖矩阵

| 用例组 | Method | Endpoint | 状态 | 核心断言 |
|---|---|---|---|---|
| P1-NS-01 | `POST` | `/v1/namespace/{id}/create` | 必测 | 创建 namespace，持久化 path 与属性 |
| P1-NS-02 | `GET` | `/v1/namespace/{id}/list` | 必测 | 列举直接子 namespace |
| P1-NS-03 | `POST` | `/v1/namespace/{id}/describe` | 必测 | 返回 namespace metadata 与 properties |
| P1-NS-04 | `POST` | `/v1/namespace/{id}/drop` | 必测 | `Restrict` 语义正确 |
| P1-NS-05 | `POST` | `/v1/namespace/{id}/exists` | 必测 | 存在性判断，不依赖 Python 便利方法 |
| P1-TBL-01 | `GET` | `/v1/namespace/{id}/table/list` | 必测 | 列出 namespace 下 table，支持 declared 过滤 |
| P1-TBL-02 | `POST` | `/v1/table/{id}/register` | 必测 | 注册已有 Lance table metadata |
| P1-TBL-03 | `POST` | `/v1/table/{id}/declare` | 必测 | 声明 declared-only table |
| P1-TBL-04 | `POST` | `/v1/table/{id}/create-empty` | 必测 | deprecated alias，复用 declare 语义 |
| P1-TBL-05 | `POST` | `/v1/table/{id}/describe` | 必测 | 返回 metadata，可选下发 credentials |
| P1-TBL-06 | `POST` | `/v1/table/{id}/exists` | 必测 | 原生表与 legacy 表均可识别 |
| P1-TBL-07 | `POST` | `/v1/table/{id}/drop` | 条件测 | declared-only 可 drop；其他场景返回阶段受限错误 |
| P1-TBL-08 | `POST` | `/v1/table/{id}/deregister` | 必测 | 只注销 metadata，不删除物理数据 |

## 5. 测试环境

### 5.1 最小环境

| 维度 | 最小要求 |
|---|---|
| UC Server | 启用 Lance route 前缀 |
| Metadata DB | H2，发布前补 PostgreSQL |
| Storage | local FS，发布前补 S3 兼容对象存储 |
| Auth | UC 内部 Bearer、外部 Bearer、`x-api-key` |
| Client | raw HTTP，Python namespace client smoke |

### 5.2 推荐环境矩阵

| 环境 | 用途 | 覆盖重点 |
|---|---|---|
| Unit | PR 快速反馈 | codec、repository、service 纯逻辑 |
| H2 Component | PR / Nightly | DAO、repository、decorator、service 组合 |
| Local Integration | Nightly | HTTP endpoint、auth、storage options |
| PostgreSQL Integration | 发布前 | DDL 约束、唯一键、分页、事务一致性 |
| Client Smoke | 发布前 | Python / Java / Rust metadata 兼容 |

## 6. 测试数据设计

### 6.1 Namespace 数据

| 数据 ID | Path Segments | 默认 Identifier | 用途 |
|---|---|---|---|
| NS-ROOT | `[]` | root | root 语义 |
| NS-01 | `["prod"]` | `prod` | 单层 namespace |
| NS-02 | `["prod","team_a"]` | `prod$team_a` | 两层 namespace |
| NS-03 | `["prod","team_a","ml","embeddings"]` | `prod$team_a$ml$embeddings` | 深层 namespace |
| NS-04 | `["prod.with.dot","team/a"]` | 需编码 | 特殊字符与 canonical `path_key` |

核心断言：

- `path_key` 使用按 segment 百分号编码后以 `/` 拼接的 canonical path。
- `path_key` 不保存 `$`、`.` 等外部 delimiter 语义。
- `root_scope_id` 在 v1 固定取 UC `metastore_id`。

### 6.2 Table 数据

| 数据 ID | 类型 | 状态 | 用途 |
|---|---|---|---|
| TBL-REG-01 | registered table | `ACTIVE` | `register/describe/exists/list/deregister` |
| TBL-DEC-01 | declared-only table | `DECLARED` | `declare/create-empty/include_declared/drop` |
| TBL-LEG-01 | legacy Unity table | legacy bridge | `list/describe/exists` |
| TBL-CRED-01 | external table | `ACTIVE` | `vend_credentials=true` |

核心断言：

- `is_only_declared=true` 的表仅在 `include_declared=true` 时出现在 list 中。
- `CreateEmptyTable` 与 `DeclareTable` 进入同一业务路径，但审计中能区分 `protocol_variant`。
- legacy bridge 表不写入 `uc_lance_assets` / `uc_lance_tables`，除非后续迁移工具显式导入。

### 6.3 Auth 数据

| 数据 ID | 主体 | 权限 | 用途 |
|---|---|---|---|
| AUTH-ADMIN | metastore admin | 全量 | 正常路径 |
| AUTH-NS-OWNER | namespace owner | `CREATE_NAMESPACE`、`USE_NAMESPACE`、`READ_METADATA`、`MODIFY` | namespace/table metadata |
| AUTH-READONLY | read-only user | `READ_METADATA` | list/describe/exists |
| AUTH-SVC | service principal | API key 绑定 | `x-api-key` |
| AUTH-DENY | unauthorized user | 无 | 越权与错误语义 |

## 7. 测试套件设计

### 7.1 `P1-CONTRACT` 契约与路由

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| P1-CONTRACT-001 | OpenAPI 快照 diff | 对比当前 `api/lance-rest-upstream.yaml` 与基线快照 | 差异被记录；Phase 1 endpoint 无未解释漂移 |
| P1-CONTRACT-002 | namespace Method 校验 | 分别调用 GET/POST 版本的 namespace endpoint | 只有 OpenAPI 定义的方法成功，错误方法返回可识别错误 |
| P1-CONTRACT-003 | table Method 校验 | 调用 Phase 1 table endpoint | Method 与路径匹配设计表 |
| P1-CONTRACT-004 | 路由前缀校验 | 调用 `/api/2.1/unity-catalog/lance/v1/**` | Lance route 命中 `LanceRestNamespaceService` |
| P1-CONTRACT-005 | 非 Lance 路由隔离 | 调用 UC `/tables`、Iceberg、Delta route | 不进入 `LanceAuthDecorator` |

### 7.2 `P1-ID` Identifier 与 Path Key

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| P1-ID-001 | 默认 `$` delimiter | 创建 `prod$team_a` | 内部 path segments 为 `["prod","team_a"]` |
| P1-ID-002 | 自定义 delimiter | 以 `.` 表达同一路径 | `path_key` 与 `$` 表达一致 |
| P1-ID-003 | 深层 path | 创建 4 层 namespace | `depth`、父子关系、`path_key` 正确 |
| P1-ID-004 | 特殊字符 segment | 创建包含 `.`、`/`、空格等需编码字符的 segment | canonical `path_key` 按 segment 编码后可逆解码回原始 segment |
| P1-ID-005 | 非法 identifier | 空 segment、非法 delimiter、非法 table id | 返回 invalid request，不写入 DB |
| P1-ID-006 | canonical `path_key` 算法 | 输入 `["prod.with.dot","team/a","embeddings"]` | 每个 segment 先百分号编码，再用 `/` 拼接；delimiter 不参与持久化 |
| P1-ID-007 | 协议全路径字符串生成 | 从内部 path segments 生成 `ListTablesResponse.tables` | 返回值使用请求侧 delimiter 或协议默认 `$`，且能 round-trip 回内部 path |

### 7.3 `P1-META` 持久化模型

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| P1-META-001 | Phase 1 DDL | 检查基础表 | 只要求 `uc_lance_namespaces`、`uc_lance_assets`、`uc_lance_tables`、`uc_lance_api_keys` |
| P1-META-002 | 高级资产表不作为门禁 | Phase 1 环境不创建高级资产表 | 不判定为失败 |
| P1-META-003 | namespace 唯一键 | 顺序重复创建同级 namespace | `(root_scope_id,parent_namespace_id,name)` 防重 |
| P1-META-004 | path 唯一键 | 用不同 delimiter 创建同一路径 | `(root_scope_id,path_key)` 防重 |
| P1-META-005 | table asset 关系 | register / declare table | `uc_lance_assets` 与 `uc_lance_tables` 一致 |
| P1-META-006 | storage template | 写入 table storage template | 只持久化非敏感 `storage_options_template_json` |
| P1-META-007 | 临时凭证不落库 | describe with credentials 后查表 | STS/session token/expires 不进入 DB |
| P1-META-008 | `root_scope_id` 映射 | 创建 namespace / table 后检查 DB | `root_scope_id` 与当前 UC `metastore_id` 一致 |
| P1-META-009 | 并发创建同级 namespace | 并发提交同一个 parent/name | 仅一个成功，其余返回 already exists 或唯一约束映射错误 |
| P1-META-010 | table 状态转换 | declare -> describe -> drop 或 register -> deregister | `DECLARED/ACTIVE/DEREGISTERED/DROPPED` 状态转换符合 Phase 1 语义 |

### 7.4 `P1-AUTH` 认证、授权与审计

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| P1-AUTH-001 | UC 内部 Bearer | 使用内部 token 调用 list | principal 正确进入上下文 |
| P1-AUTH-002 | 外部 Bearer | 使用允许 issuer/audience 的 token | 复用内部 helper 完成 token exchange |
| P1-AUTH-003 | issuer/audience 拒绝 | 使用错误 issuer 或 audience | 请求被拒绝，错误语义稳定 |
| P1-AUTH-004 | API key 成功 | 使用 `x-api-key` 调用 describe | `LanceApiKeyRepository` 解析到 principal |
| P1-AUTH-005 | API key 状态 | 使用 expired / revoked key | 请求被拒绝，`last_used_at` 行为符合设计 |
| P1-AUTH-006 | API key 明文保护 | 检查 `uc_lance_api_keys` | 只保存 `key_hash`，不保存明文 |
| P1-AUTH-007 | 权限通过 | owner 执行 create / declare | `CREATE_NAMESPACE`、`USE_NAMESPACE`、`MODIFY` 判定正确 |
| P1-AUTH-008 | 权限拒绝 | read-only 执行 declare / drop | permission denied |
| P1-AUTH-009 | KeyMapper 委托 | 鉴权解析 Lance resource | `KeyMapper` 委托 `LanceResourceKeyMapper`，原有解析不受影响 |
| P1-AUTH-010 | 授权图继承 | 子 namespace/table 权限检查 | 父子关系通过授权图表达，不在 resource map 展开所有父级 |
| P1-AUTH-011 | 审计日志字段级断言 | 执行 create / declare / create-empty / deregister | 记录 principal、resource、operation；`protocol_operation=declare_table`；`protocol_variant=declare/create-empty` 可区分；`deprecated_alias_used=true/false` 正确 |
| P1-AUTH-012 | Lance context headers 透传 | 携带 `x-lance-tenant-id` 等 context header 调用 list/describe | context 进入请求上下文，并进入审计日志或后续 metadata service 调用上下文 |
| P1-AUTH-013 | 子 table 父 namespace 授权 | 在已有 namespace 下 declare/register table | 检查父 namespace 的 `USE_NAMESPACE` 与 `MODIFY` 权限，不只检查目标 table |
| P1-AUTH-014 | token exchange 实现路径 | 外部 Bearer token exchange 的代码评审或组件测试 | 不通过内部 HTTP 回环调用 `/api/1.0/unity-control/auth/tokens`，而是直接调用内部 helper/service |

### 7.5 `P1-NS` Namespace Endpoint

| 用例 ID | Endpoint | 场景 | 断言 |
|---|---|---|---|
| P1-NS-001 | `POST /v1/namespace/{id}/create` | 创建单层 namespace | 返回成功，DB 记录正确 |
| P1-NS-002 | `POST /v1/namespace/{id}/create` | 创建深层 namespace | 父 namespace 存在时成功 |
| P1-NS-003 | `POST /v1/namespace/{id}/create` | 父 namespace 不存在 | 返回 not found 或 invalid parent |
| P1-NS-004 | `GET /v1/namespace/{id}/list` | root list | 返回 root 下直接子节点 |
| P1-NS-005 | `GET /v1/namespace/{id}/list` | 深层 list | 只返回直接子 namespace |
| P1-NS-006 | `POST /v1/namespace/{id}/describe` | 读取 properties | 返回 properties 与 owner 信息 |
| P1-NS-007 | `POST /v1/namespace/{id}/exists` | 已存在 | 返回 true |
| P1-NS-008 | `POST /v1/namespace/{id}/exists` | 不存在 | 返回 false，不抛 not found |
| P1-NS-009 | `POST /v1/namespace/{id}/drop` | 空 namespace restrict | 删除成功 |
| P1-NS-010 | `POST /v1/namespace/{id}/drop` | 非空 namespace restrict | 返回冲突或 not empty |
| P1-NS-011 | `POST /v1/namespace/{id}/drop` | cascade 且包含 registered/legacy table | 返回 not supported in current phase |
| P1-NS-012 | `POST /v1/namespace/{id}/create` | 创建子 namespace | 必须校验父 namespace `USE_NAMESPACE` / `CREATE_NAMESPACE` 权限 |
| P1-NS-013 | `POST /v1/namespace/{id}/drop` | restrict 删除包含 declared-only table 的 namespace | 返回 not empty，不删除 namespace 或 declared-only table |
| P1-NS-014 | `GET /v1/namespace/{id}/list` | 分页参数 | `limit/pageToken` 语义稳定，分页不重复不丢失 |

### 7.6 `P1-TBL` Table Endpoint

| 用例 ID | Endpoint | 场景 | 断言 |
|---|---|---|---|
| P1-TBL-001 | `GET /v1/namespace/{id}/table/list` | 空 namespace | 返回空列表 |
| P1-TBL-002 | `GET /v1/namespace/{id}/table/list` | registered table | 返回 table 全路径 |
| P1-TBL-003 | `GET /v1/namespace/{id}/table/list` | `include_declared=false` | 不返回 declared-only table |
| P1-TBL-004 | `GET /v1/namespace/{id}/table/list` | `include_declared=true` | 返回 declared-only table |
| P1-TBL-005 | `POST /v1/table/{id}/register` | 注册已有 location | asset 为 `ACTIVE`，table metadata 完整 |
| P1-TBL-006 | `POST /v1/table/{id}/register` | 重复注册 | 返回 already exists |
| P1-TBL-007 | `POST /v1/table/{id}/declare` | 声明 table | asset 为 `DECLARED`，`is_only_declared=true` |
| P1-TBL-008 | `POST /v1/table/{id}/create-empty` | deprecated alias | 与 declare 结果等价，审计标记 alias |
| P1-TBL-009 | `POST /v1/table/{id}/describe` | registered table | 返回 location、schema/properties、state |
| P1-TBL-010 | `POST /v1/table/{id}/describe` | declared-only table | 返回 location、properties、`is_only_declared=true` |
| P1-TBL-011 | `POST /v1/table/{id}/exists` | registered / declared / legacy | 均返回 true |
| P1-TBL-012 | `POST /v1/table/{id}/drop` | declared-only table | metadata drop 成功 |
| P1-TBL-013 | `POST /v1/table/{id}/drop` | registered table | 返回阶段受限错误 |
| P1-TBL-014 | `POST /v1/table/{id}/deregister` | registered table | metadata 注销，物理数据不删除 |
| P1-TBL-015 | `POST /v1/table/{id}/deregister` | legacy table | 不修改 legacy UC table，按设计返回桥接限制或受控错误 |

### 7.7 `P1-CRED` Storage Options

| 用例 ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| P1-CRED-001 | `vend_credentials=false` | describe table | 不返回临时凭证 |
| P1-CRED-002 | `vend_credentials=true` | describe registered table | 返回 Lance 兼容 `storage_options` |
| P1-CRED-003 | declared-only table | describe with credentials | 可返回 location 与凭证，缺物理 metadata 不误判成功 |
| P1-CRED-004 | legacy bridge table | describe with credentials | 基于 legacy `storage_location` 返回凭证 |
| P1-CRED-005 | 模板与动态凭证合并 | 配置 `storage_options_template_json` 后 describe | 返回模板字段 + 临时凭证 |
| P1-CRED-006 | 临时凭证不落库 | 请求后检查 DB | token、session、expires 字段不写回 |
| P1-CRED-007 | 无权限凭证 | read-only 或无 external location 权限 | 不下发凭证，返回受控错误 |
| P1-CRED-008 | 模板字段级断言 | 检查 `storage_options_template_json` 内容 | 只允许 endpoint、region、path-style、provider 等非敏感字段；不包含 token、session、secret、expires 等敏感字段 |
| P1-CRED-009 | 凭证响应字段隔离 | `vend_credentials=true` 后检查响应和 DB | 响应可含临时字段，DB 模板仍保持非敏感字段集合 |

### 7.8 `P1-LEGACY` Legacy Bridge

| 用例 ID | 场景 | 前置数据 | 断言 |
|---|---|---|---|
| P1-LEGACY-001 | legacy 识别 | `table_type=EXTERNAL`、`data_source_format=TEXT`、`properties.table_type=lance` | 识别为 legacy Lance table |
| P1-LEGACY-002 | legacy list | namespace/table list | legacy table 可见 |
| P1-LEGACY-003 | legacy describe | describe table | 返回 location/properties，可带 `legacy_bridge=true` |
| P1-LEGACY-004 | legacy exists | exists table | 返回 true |
| P1-LEGACY-005 | 非 legacy 排除 | 缺任一识别条件 | 不作为 Lance table 返回 |
| P1-LEGACY-006 | 不污染主模型 | 执行 list/describe/exists 后查 DB | 不自动写入 `uc_lance_assets` / `uc_lance_tables` |

### 7.9 `P1-ERROR` 错误模型

| 用例 ID | 场景 | 断言 |
|---|---|---|
| P1-ERROR-001 | namespace 不存在 | 返回 Lance 兼容 not found |
| P1-ERROR-002 | table 不存在 | 返回 Lance 兼容 not found 或 exists=false |
| P1-ERROR-003 | 重复创建 | 返回 already exists |
| P1-ERROR-004 | 权限不足 | 返回 permission denied |
| P1-ERROR-005 | 请求体非法 | 返回 invalid request |
| P1-ERROR-006 | 当前阶段不支持 | 返回 not supported in current phase |
| P1-ERROR-007 | 错误响应可被客户端识别 | Python / Java / Rust smoke 捕获预期异常类型或错误结构 |
| P1-ERROR-008 | Lance 兼容错误响应结构 | 触发 not found / already exists / permission denied / invalid request / not supported | 响应包含稳定的 `type`、`message`、`code` 字段，必要时包含 `requestId`，不暴露内部堆栈 |
| P1-ERROR-009 | 错误状态码映射 | 分别触发 Phase 1 常见错误 | HTTP status 与错误 `code` 匹配，客户端能做稳定分支处理 |

### 7.10 `P1-CLIENT` 原生客户端 Smoke

| 用例 ID | 客户端 | 场景 | 断言 |
|---|---|---|---|
| P1-CLIENT-001 | Python | connect namespace + list/describe/drop | 不需要改业务命名方式 |
| P1-CLIENT-002 | Python + raw HTTP | namespace exists | raw HTTP 覆盖 `POST /v1/namespace/{id}/exists`，因为 Python 包装层未暴露该便利方法 |
| P1-CLIENT-003 | Java | namespace builder + metadata smoke | endpoint/auth/context 配置可用 |
| P1-CLIENT-004 | Rust | namespace client metadata smoke | endpoint/auth/context 配置可用 |
| P1-CLIENT-005 | Raw HTTP | 全 Phase 1 endpoint smoke | Method、状态码、响应体稳定 |
| P1-CLIENT-006 | raw HTTP 覆盖清单 | 执行客户端包装层未完整覆盖的 endpoint | 至少覆盖 `POST /v1/namespace/{id}/exists`、`POST /v1/table/{id}/create-empty`、阶段受限 `drop` 错误路径 |

### 7.11 `P1-REG` UC 回归

| 用例 ID | 场景 | 断言 |
|---|---|---|
| P1-REG-001 | UC `/tables` | 现有 table CRUD smoke 不受影响 |
| P1-REG-002 | UC `/schemas` | 现有 schema smoke 不受影响 |
| P1-REG-003 | Iceberg REST | 关键 metadata route 不受影响 |
| P1-REG-004 | Delta REST | 关键 route 不受影响 |
| P1-REG-005 | 原有 `AuthDecorator` | 非 Lance route 仍使用原装饰器语义 |
| P1-REG-006 | 原有 `KeyMapper` | 传统三层资源解析不受 Lance 委托影响 |
| P1-REG-007 | 装饰器链隔离 | 对非 Lance route 注入可被 `LanceAuthDecorator` 识别的 header | 非 Lance 请求不进入 `LanceAuthDecorator`，仍由原有 auth/decorator 链处理 |

## 8. 执行顺序建议

建议按以下顺序落地和执行：

1. `P1-CONTRACT`
2. `P1-ID`
3. `P1-META`
4. `P1-AUTH`
5. `P1-NS`
6. `P1-TBL`
7. `P1-CRED`
8. `P1-LEGACY`
9. `P1-ERROR`
10. `P1-CLIENT`
11. `P1-REG`

说明：

- `P1-CONTRACT`、`P1-ID`、`P1-META` 是后续用例稳定性的前置。
- `P1-AUTH` 应早于 endpoint 全量测试，避免无权限路径被误判为功能缺陷。
- `P1-CLIENT` 只作为最终兼容确认，不替代 raw HTTP 契约测试。

## 9. 自动化分层

| 层级 | 套件 | 触发建议 |
|---|---|---|
| Unit | `P1-ID`、`P1-META` 局部逻辑、`P1-CRED` 映射逻辑 | PR 必跑 |
| Component | `P1-AUTH`、repository、service with H2 | PR 必跑 |
| Protocol | `P1-CONTRACT`、`P1-NS`、`P1-TBL`、`P1-ERROR` | Nightly，发布前必跑 |
| Client Smoke | `P1-CLIENT` | 发布前必跑 |
| Regression | `P1-REG` | PR smoke，发布前全量 |

## 10. 准入与准出标准

### 10.1 准入条件

- `api/lance-rest-upstream.yaml` 已固定到基线快照
- `lance-models` 生成链可运行
- Lance route 前缀可启动
- Phase 1 基础 DDL 已存在：
  - `uc_lance_namespaces`
  - `uc_lance_assets`
  - `uc_lance_tables`
  - `uc_lance_api_keys`
- 高级资产表不作为 Phase 1 准入要求

### 10.2 准出条件

- `P1-CONTRACT` 全部通过
- namespace 与 table Phase 1 endpoint 全部通过
- `DeclareTable` 与 `CreateEmptyTable` alias 行为固化
- `DescribeTable(vend_credentials=true)` 通过，临时凭证不落库
- legacy bridge 的 `list/describe/exists` 通过
- Python / Java / Rust metadata smoke 通过，或记录明确的客户端侧限制与 raw HTTP 补充结果
- UC `/tables`、Iceberg REST、Delta REST 回归通过
- S0/S1 缺陷清零，S2 缺陷有明确风险接受记录

## 11. 风险与观察点

| 风险 | 测试观察点 |
|---|---|
| OpenAPI 与客户端便利方法不一致 | raw HTTP 覆盖 `namespace_exists` 等 endpoint |
| `drop` 语义被误实现成物理删除 | declared-only 与 registered/legacy 分开测试 |
| `CreateEmptyTable` 被当成长期主路径 | 审计标记 alias，测试和示例优先 `DeclareTable` |
| `storage_options_template_json` 泄露凭证 | DB 断言临时 token 不落库 |
| legacy bridge 污染新主模型 | bridge 读取后检查 `uc_lance_*` 不自动写入 |
| KeyMapper 改动影响现有 UC | `P1-REG-006` 固化传统解析回归 |

## 12. 交付物

第一阶段测试交付物建议包括：

- 本详细测试设计文档
- Phase 1 测试用例清单
- raw HTTP smoke 脚本或集合
- Python / Java / Rust metadata smoke 记录
- H2 component test 结果
- 发布前回归报告

## 13. 最终建议

第一阶段测试应优先把 metadata 语义、路径模型、鉴权边界和 legacy bridge 固化下来。

在这些行为稳定前，不建议把 query、Arrow IPC、DML、index/version/tag/transaction 的测试混入本阶段门禁，避免第一阶段验收边界变得模糊。
