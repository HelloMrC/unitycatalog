# Unity Catalog 测试机制分析

更新日期：2026-04-25

关联文档：

- `docs/lance/unitycatalog-lancedb-phase-1-metadata-design.md`
- `docs/lance/unitycatalog-lancedb-phase-1-metadata-test-design.md`
- `docs/lance/unitycatalog-lancedb-rest-api-test-strategy.md`
- `docs/lance/unitycatalog-lancedb-rest-api-test-design.md`

## 1. 文档目标

本文档用于说明 Unity Catalog 当前仓库中的测试机制、测试分层、常用基类、执行命令和 CI 门禁方式，为后续基于 Lance Phase 1 测试设计开发自动化用例提供落地参考。

本文档不定义新的业务测试场景。业务覆盖范围以 Lance 测试策略和详细测试设计为准；本文档只回答“这些用例应该放在哪里、复用什么机制、怎么运行、怎么验收”。

后续用例开发的完成标准：

- 每个新增用例能映射到明确测试层级，例如 server component、raw HTTP protocol、auth/RBAC、credential、client smoke 或 integration。
- 每个新增测试类复用现有基类、断言工具和启动机制，避免自建重复测试框架。
- 每个新增 endpoint 用例至少能通过窄范围 `testOnly` 或对应 project test 命令单独执行。
- 涉及 Lance 新持久化表的测试需要明确清理策略，避免污染同 JVM 内其他用例。
- 涉及协议兼容的测试需要优先验证 HTTP method、path、请求响应结构、状态码和错误语义。

## 2. 仓库测试入口总览

| 测试入口 | 路径 | 主要用途 | 常用命令 |
|---|---|---|---|
| Server Java tests | `server/src/test/java` | UC server 服务层、SDK CRUD、权限、凭证、REST 协议适配测试 | `build/sbt server/test` |
| Java client tests | `clients/java/src/test/java` | Java OpenAPI client、ApiClient、鉴权 provider、重试等客户端行为 | `build/sbt client/test` |
| Spark connector tests | `connectors/spark/src/test` | Spark connector 单元和组件测试 | `build/sbt spark/test` |
| Integration tests | `integration-tests/src/test/java` | Spark + UC server + 存储路径的集成验证 | `build/sbt integrationTests/test` |
| Python client tests | `clients/python/tests` | 生成版 Python client 异步 API smoke | `./clients/python/run-tests.sh` |
| Top-level Python tests | `tests` | 教程、tarball、MLflow 等仓库级验证 | 按对应 workflow 或 pytest 命令执行 |

CI 当前主要执行：

| Workflow | 关键动作 |
|---|---|
| `.github/workflows/unit-tests.yml` | 编译 `integrationTests/test:compile`，执行 `+jacoco` 或 `spark/jacoco`，执行 `licenseCheck` |
| `.github/workflows/python-client-tests.yml` | 安装 Python 3.9，执行 `./clients/python/run-tests.sh` |
| `.github/workflows/lint-check.yml` | 执行 UI format 检查和 `build/sbt javafmtCheck` |

注意：`integrationTests` 不在 root aggregate 中。普通 `build/sbt test` 或 `build/sbt +jacoco` 不等价于执行完整 integration tests。

## 3. SBT 与 JUnit 机制

Unity Catalog 使用 SBT 管理 Java/Scala project，Java 测试主要基于 JUnit 5。

全局测试配置要点：

| 配置点 | 当前机制 | 对用例开发的影响 |
|---|---|---|
| JUnit runner | `jupiter-interface` + JUnit Jupiter | 新 Java 测试应使用 JUnit 5 注解 |
| Java 版本 | Java 17 | 测试代码可以使用 Java 17 语法，但应匹配项目风格 |
| Fork JVM | `fork := true` | 测试在独立 JVM 中执行，便于隔离 server 进程和系统属性 |
| 输出 | `StdoutOutput` | 测试日志会直接输出到 SBT 控制台 |
| Base 测试过滤 | `Tests.Filter(name => !(name startsWith s"$orgName.server.base"))` | `io.unitycatalog.server.base.*` 下的基类不会被直接作为测试执行 |
| Server 工作目录 | `server/Test/javaOptions += -Duser.dir=<repo root>` | Server 测试可以按 repo root 相对路径读取 `etc/conf` 等资源 |
| Integration 工作目录 | `integrationTests/Test/javaOptions += -Duser.dir=<repo>/integration-tests` | 集成测试默认从 `integration-tests` 目录解析配置 |

常用命令：

```bash
build/sbt server/test
build/sbt "server/testOnly io.unitycatalog.server.service.IcebergRestCatalogTest"
build/sbt client/test
build/sbt spark/test
build/sbt integrationTests/test:compile
build/sbt integrationTests/test
build/sbt javafmtCheck
build/sbt licenseCheck
```

开发 Lance Phase 1 用例时，优先使用 `server/testOnly` 跑单个测试类，只有在涉及跨 project 回归时再扩大到 `server/test`、`spark/test` 或 `+jacoco`。

## 4. Server 测试机制

### 4.1 `BaseServerTest`

`server/src/test/java/io/unitycatalog/server/base/BaseServerTest.java` 是 server 测试最核心的基类。

它提供：

| 能力 | 说明 |
|---|---|
| 随机端口启动 | 当 `serverConfig` 指向 localhost 时，每个测试启动一个随机端口的 `UnityCatalogServer` |
| 临时目录 | 通过 JUnit `@TempDir` 创建测试目录，并设置 managed table storage root |
| Server properties | 默认设置 `SERVER_ENV=test`、`INCLUDE_STACK_TRACE_IN_ERROR=true`、`MANAGED_TABLE_ENABLED=true` |
| Hibernate | 为测试 server 创建 `HibernateConfigurator`，可直接打开 session 做持久化断言 |
| 凭证扩展点 | `setUpCredentialOperations` 可注入 mock credential vendor |
| 清理 | `tearDown` 手动删除现有 UC DAO 表数据并停止 server |

对 Lance 用例的影响：

- Raw HTTP protocol、namespace/table metadata、legacy bridge、error response 等测试可直接继承 `BaseServerTest`。
- 如果 Lance 新增 `uc_lance_*` DAO 表，测试清理需要同步覆盖这些表，或在 Lance 专用测试类中自行清理。
- `serverConfig` 是静态对象且在启动后会被写入随机端口，测试不要依赖固定端口。

### 4.2 CRUD 基类与 Operations 包装

现有 CRUD 测试大量使用 `BaseCRUDTest` 和各资源的 `Base*CRUDTest`。

典型模式：

| 组件 | 作用 |
|---|---|
| `BaseCRUDTest` | 继承 `BaseServerTest`，并创建 `catalogOperations` |
| `BaseTableCRUDTest` | 抽象表 CRUD 通用用例，供 SDK/CLI 具体实现复用 |
| `Sdk*Operations` | 使用生成版 Java client 调用 UC REST API |
| `Sdk*CRUDTest` | 组合基类和 SDK operations，验证 REST + service + persistence 主路径 |

对 Lance 用例的影响：

- 如果测试目标是 UC 现有 `/catalogs`、`/schemas`、`/tables` 回归，应优先复用 SDK CRUD 模式。
- 如果测试目标是 Lance 原生路径，例如 `/api/2.1/unity-catalog/lance/v1/...`，应使用 raw HTTP 模式，不应强行塞入现有 UC SDK operations。
- 如果 Phase 1 增加 Java wrapper，可新增类似 `SdkLance*Operations`，但第一批协议兼容用例仍建议保留 raw HTTP 覆盖。

### 4.3 Raw HTTP 协议测试模式

`server/src/test/java/io/unitycatalog/server/service/IcebergRestCatalogTest.java` 是最接近 Lance REST 协议测试的模板。

它的关键做法：

| 步骤 | 机制 |
|---|---|
| 准备 UC 元数据 | 用 `SdkCatalogOperations`、`SdkSchemaOperations`、`SdkTableOperations` 创建 catalog/schema/table |
| 调用协议端点 | 使用 Armeria `WebClient` 直接请求协议前缀 |
| 断言 HTTP 细节 | 断言 status code、response body、协议模型解析结果 |
| 断言错误语义 | 使用协议侧 error parser 验证错误类型 |
| 必要时查库 | 打开 Hibernate session 修改或验证 DAO 字段 |

Lance Phase 1 endpoint 建议采用同样结构：

| Lance 测试域 | 推荐模板 |
|---|---|
| namespace create/list/describe/drop/exists | `BaseServerTest` + `WebClient` |
| table register/declare/create-empty/describe/exists/drop/deregister/list | `BaseServerTest` + `WebClient` |
| method/path/route prefix 兼容 | `IcebergRestCatalogTest` 风格 |
| Lance 兼容错误响应结构 | `WebClient` + JSON parser/object mapper |
| raw HTTP 补齐 Python wrapper 缺失 endpoint | `WebClient` 或 Python `requests/httpx` smoke |

### 4.4 Auth 与 RBAC 测试模式

权限相关测试主要由 `BaseAccessControlCRUDTest`、`AuthServiceTest` 和 SDK access tests 支撑。

已有机制：

| 组件 | 作用 |
|---|---|
| `BaseAccessControlCRUDTest` | 打开 `AUTHORIZATION_ENABLED=enable`，初始化 `SecurityConfiguration` 和 `SecurityContext` |
| `Sdk*AccessControlCRUDTest` | 创建用户、授权、使用不同 token/client 验证允许或拒绝 |
| `TestUtils.assertPermissionDenied` | 统一断言 `PERMISSION_DENIED` |
| `AuthServiceTest` | 用 Armeria `WebClient` 直接测试 `/api/1.0/unity-control/auth/*` |
| `JCasbinAuthorizerTest` | 针对 authorizer 规则和权限判断做更底层验证 |

对 Lance 用例的影响：

- `LanceAuthDecorator`、`x-api-key`、Bearer token、context headers 可优先使用 `BaseServerTest` 或 `BaseAccessControlCRUDTest` + `WebClient`。
- 父 namespace 授权、`LanceResourceKeyMapper` 与 UC 授权图映射，应放在 access control 测试或 authorizer 组件测试中。
- token exchange 不走内部 HTTP 回环调用 `/tokens` 的检查，更适合作为 service/component 测试或代码审查点，而不是纯黑盒 HTTP 测试。
- 非 Lance 请求不进入 `LanceAuthDecorator` 的回归，可以通过原有 UC endpoint + mock/spy 或审计副作用断言完成。

### 4.5 临时凭证与 Storage Options 测试模式

`BaseCRUDTestWithMockCredentials` 和 `SdkTemporaryTableCredentialTest` 提供了凭证测试模板。

已有机制：

| 能力 | 说明 |
|---|---|
| Mock AWS/GCS/Azure vendor | 避免真实云资源依赖 |
| 外部位置准备 | 自动创建 storage credential 和 external location |
| 云路径参数化 | 覆盖 `s3`、`abfs`、`gs` 与配置/未配置路径 |
| 临时凭证断言 | 断言 AWS session token、Azure SAS、GCP OAuth token |
| STS echo client | 验证 AWS role ARN、external id、session policy 等入参 |

对 Lance 用例的影响：

- `DescribeTable(vend_credentials=true)` 和 `storage_options` 建议复用此 mock credential 机制。
- `storage_options_template_json` 的字段级断言应结合 DB 查询，确认只持久化 endpoint、region、provider 等非敏感字段。
- 临时 token、session、secret、expires 等敏感字段应只出现在响应或内存对象中，不应落入持久化模板。

### 4.6 持久化断言模式

Server 测试可以直接通过 `hibernateConfigurator.getSessionFactory().openSession()` 访问测试 DB。

适合直接查库的场景：

| 场景 | 建议断言 |
|---|---|
| `path_key` 编解码 | DAO 中保存 canonical path，不保存 delimiter 语义 |
| `root_scope_id` 映射 | `root_scope_id` 等于 UC metastore id |
| namespace 唯一约束 | 并发创建后仅一条成功，唯一约束字段生效 |
| declared-only table | asset/table 行存在，物理数据不被删除 |
| storage template | `storage_options_template_json` 不包含临时凭证 |
| API key | 只保存 `key_hash`，不保存明文 key |

注意：直接查库测试应尽量集中在 repository/service/component 层；端到端协议测试只在必须验证持久化副作用时查库，避免用例过度绑定内部实现。

## 5. Client 与 Integration 测试机制

### 5.1 Java client tests

`clients/java/src/test/java` 覆盖生成版 Java client 的基础行为，例如 enum 反序列化、user agent、ApiClient builder、token provider、OAuth token provider 和 HTTP retry handler。

对 Lance 用例的影响：

- 如果 Lance endpoint 暂未纳入 UC OpenAPI 生成客户端，则不应把 Phase 1 协议兼容测试放到 Java client tests。
- 如果后续生成 Lance Java client 或扩展 UC OpenAPI，需补充模型反序列化、错误响应、header 注入和 auth provider 行为。

### 5.2 Python client tests

`clients/python/run-tests.sh` 的流程是：

1. 执行 `build/sbt pythonClient/generate` 生成 Python client。
2. 在 `clients/python` 下执行 `uv sync --frozen`。
3. 将 `clients/python/target` 中生成的库安装到 uv venv。
4. 从 repo root 执行 `pytest clients/python/tests`。

`clients/python/tests/conftest.py` 会启动 `bin/start-uc-server`，等待本地 server 就绪，并创建异步 OpenAPI API fixtures。

对 Lance 用例的影响：

- Python wrapper 已暴露的方法可写 Python smoke。
- Python wrapper 未暴露的 Phase 1 endpoint，例如 `POST /v1/namespace/{id}/exists`，应由 raw HTTP 测试补齐。
- Python client tests 默认使用本地 `http://localhost:8080/api/2.1/unity-catalog`，如果 Lance route 使用独立前缀，需要新增专用 fixture 或 raw HTTP helper。

### 5.3 Spark connector tests

Spark connector project 位于 `connectors/spark`，主要用于 Spark catalog 行为验证。CI matrix 会覆盖不同 Spark/Delta 版本组合，其中 spark-only matrix 执行 `build/sbt spark/jacoco`。

对 Lance 用例的影响：

- Phase 1 metadata compatibility 通常不需要 Spark connector 测试作为主门禁。
- 当 Lance 兼容能力影响 Spark/Ray 等生态入口时，再补充 connector 或 integration smoke。
- 不要把 namespace codec、repository、auth decorator 这类 server 内部行为放到 Spark 测试中验证。

### 5.4 Integration tests

`integration-tests` 是独立 SBT project。README 说明它可以连接本地 UC server，也可以通过环境变量连接外部 catalog。

关键点：

| 维度 | 机制 |
|---|---|
| 本地 server | 在 `integration-tests` 目录启动 `../bin/start-uc-server` |
| 外部 catalog | 通过 `CATALOG_URI`、`CATALOG_AUTH_TOKEN`、`CATALOG_NAME` 指定 |
| 存储 | 默认 local filesystem，可通过 `S3_BASE_LOCATION`、`GS_BASE_LOCATION`、`ABFSS_BASE_LOCATION` 启用云路径 |
| SparkSession | `BaseSparkTest` 创建 local Spark，并配置 UC Spark catalog |
| CI | 当前 active workflow 只编译 integration tests，不默认执行完整集成测试 |

对 Lance 用例的影响：

- Phase 1 首批自动化建议以 server component/raw HTTP 为主，integration tests 作为发布前或环境就绪后的补充。
- 涉及真实对象存储、Spark/Ray/DuckDB 的生态验证，不应阻塞普通 PR 的快速反馈，除非项目后续明确提升为门禁。

## 6. OpenAPI 与生成代码机制

仓库通过 SBT `generate` 任务从 OpenAPI spec 生成多语言代码。

相关机制：

| Project | 输入 | 输出/用途 |
|---|---|---|
| `serverModels` | `api/all.yaml` 等 spec | 生成 server 侧 models |
| `client` | UC OpenAPI spec | 生成 Java client |
| `pythonClient` | `api/all.yaml`、`api/delta.yaml` | 生成 Python client |
| `apiDocs` | `api/all.yaml`、`api/delta.yaml` | 生成 API markdown docs |

对 Lance 用例的影响：

- 如果 Lance REST API 不写入 UC 主 OpenAPI，协议兼容测试应以 Lance 上游 OpenAPI baseline + raw HTTP 为主。
- 如果 Lance REST API 写入 UC OpenAPI，需要补充生成代码编译、模型序列化和客户端 smoke。
- 不应直接修改 `target` 下生成代码；测试应修改 spec、源代码或测试源码。

## 7. Lance Phase 1 用例落位建议

| 测试设计域 | 推荐测试层级 | 推荐机制 |
|---|---|---|
| `P1-ID-*` path/identifier codec | Unit/component | 直接测试 `LanceIdentifierCodec`，必要时补 repository 查库 |
| `P1-NS-*` namespace endpoint | Server raw HTTP | `BaseServerTest` + Armeria `WebClient` |
| `P1-TBL-*` table metadata endpoint | Server raw HTTP | `BaseServerTest` + `WebClient` + 必要 DAO 断言 |
| `P1-META-*` metadata persistence | Repository/service component | Hibernate session 断言 DAO 字段、唯一约束、并发行为 |
| `P1-AUTH-*` auth/RBAC/API key | Access control/component | `BaseAccessControlCRUDTest`、`AuthServiceTest` 模式 |
| `P1-CRED-*` storage options | Credential component | `BaseCRUDTestWithMockCredentials` 模式 |
| `P1-ERROR-*` error semantics | Server raw HTTP | `WebClient` + JSON body 字段断言 |
| `P1-CLIENT-*` client compatibility | Python smoke + raw HTTP | Python wrapper 覆盖可暴露能力，raw HTTP 补 endpoint 缺口 |
| `P1-REG-*` regression | Targeted existing tests | UC SDK CRUD、Iceberg REST、Auth decorator 隔离检查 |

建议新增测试包时保持靠近实现位置，例如：

| 用途 | 建议路径 |
|---|---|
| Lance service/protocol tests | `server/src/test/java/io/unitycatalog/server/service/lance` |
| Lance repository tests | `server/src/test/java/io/unitycatalog/server/persist/lance` |
| Lance auth/access tests | `server/src/test/java/io/unitycatalog/server/sdk/access` 或 `server/src/test/java/io/unitycatalog/server/service/lance` |
| Lance codec tests | `server/src/test/java/io/unitycatalog/server/utils` 或实现类所在 package |
| Python smoke | `clients/python/tests`，前提是 route/client fixture 明确 |

## 8. 推荐开发流程

1. 先从测试设计中选定一个测试 ID，明确它验证的是协议、服务、持久化、权限、凭证还是客户端行为。
2. 根据第 7 节选择最窄测试层级，优先 server component/raw HTTP，不默认上升到 integration。
3. 复用现有 `BaseServerTest`、`BaseAccessControlCRUDTest`、`BaseCRUDTestWithMockCredentials` 或 `TestUtils`。
4. endpoint 测试先断言 HTTP method、path、status code、response body，再补充必要的 DAO 副作用断言。
5. 权限测试同时覆盖允许和拒绝路径，拒绝路径要断言 Lance 客户端可识别的错误语义。
6. 凭证测试同时断言响应中可用、DB 中不落敏感字段。
7. 新增持久化表后，确认测试清理路径覆盖对应 DAO 或测试类自行清理。
8. 先运行单测类，再运行相关回归类，最后按影响面选择 project 级命令。

## 9. Lance Phase 1 最小命令集

建议本地开发按以下顺序执行：

```bash
# 只跑单个 Lance 测试类
build/sbt "server/testOnly io.unitycatalog.server.service.lance.LanceNamespaceRestTest"

# 跑 Lance server 测试包，具体通配模式以后续类名为准
build/sbt "server/testOnly *Lance*"

# 跑 server 全量测试
build/sbt server/test

# 跑格式检查
build/sbt javafmtCheck

# 如果影响 Python client
./clients/python/run-tests.sh

# 如果影响 Spark connector
build/sbt spark/test

# 如果需要编译集成测试
build/sbt integrationTests/test:compile
```

CI 对齐命令：

```bash
build/sbt -J-Xmx2G integrationTests/test:compile
build/sbt -J-Xmx2G +jacoco
build/sbt licenseCheck
build/sbt javafmtCheck
./clients/python/run-tests.sh
```

## 10. 注意事项与风险

| 风险 | 说明 | 建议 |
|---|---|---|
| `uc_lance_*` 表清理缺失 | `BaseServerTest.tearDown` 目前只删除现有 UC DAO 表 | 新增 DAO 后补清理或在 Lance 测试中局部清理 |
| Base 类被过滤 | `io.unitycatalog.server.base.*` 不会作为测试执行 | Lance 具体测试类不要放在 `server.base` package 下 |
| 生成代码误改 | `target` 下代码由 OpenAPI 生成 | 修改 spec 或源码，不手改 generated target |
| Python wrapper 覆盖不足 | 部分 Lance endpoint 可能没有 Python 便利方法 | 使用 raw HTTP 补齐协议覆盖 |
| Integration 环境依赖 | 云存储和外部 catalog 需要手动配置 | 普通 PR 以 server tests 为主，integration 作为补充 |
| 权限测试过度黑盒 | 某些实现路径如“不走 HTTP 回环”黑盒难验证 | 放入 component test 或代码审查 checklist |
| 并发唯一约束不稳定 | 并发测试容易受时序影响 | 使用固定线程数、barrier 同步、断言最终 DB 状态 |
| 错误响应只看状态码 | Lance 客户端可能依赖 body 字段 | 同时断言 `type`、`message`、`code` 等结构字段 |

## 11. 对 Phase 1 用例开发的结论

Lance Phase 1 的主力测试应落在 `server/src/test/java`，采用“`BaseServerTest` 启动真实 UC server + Armeria `WebClient` 调 Lance 原生 REST endpoint + Hibernate 做必要持久化断言”的模式。

权限、API key、context headers 和 token exchange 相关用例应复用现有 auth/access 测试机制；`storage_options` 和 `vend_credentials` 应复用 mock credential vendor 机制；Python client smoke 只作为真实客户端消费行为补充，不替代 raw HTTP 协议覆盖。

这样可以在不引入新测试框架的前提下，同时覆盖 Phase 1 最关键的协议兼容、UC 治理接入、持久化正确性和回归隔离要求。
