# PostgreSQL Integration Tests Setup Guide

## 1. 启动 PostgreSQL 容器

```bash
# 使用 docker-compose 启动 PostgreSQL
cd /home/lei/data_ai/learning/codebase/unitycatalog
docker-compose -f etc/db/postgres-example.yml up -d

# 验证 PostgreSQL 启动
docker ps | grep postgres
```

## 2. 配置 Hibernate 使用 PostgreSQL

创建 PostgreSQL hibernate 配置文件：

```bash
# 复制模板
cp etc/conf/hibernate.properties etc/conf/hibernate-postgres.properties
```

编辑 `hibernate-postgres.properties`：

```properties
hibernate.connection.driver_class=org.postgresql.Driver
hibernate.connection.url=jdbc:postgresql://localhost:5432/ucdb
hibernate.connection.username=uc_default_user
hibernate.connection.password=uc_default_password
hibernate.hbm2ddl.auto=update
hibernate.show_sql=false
hibernate.archive.autodetection=class
hibernate.use_sql_comments=true
org.hibernate.SQL=INFO
org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

## 3. 运行 Lance 测试

```bash
# 使用 PostgreSQL 配置运行测试
build/sbt "server/testOnly *LancePhase2* -Dhibernate.config=hibernate-postgres.properties"

# 或运行全部 Lance 测试
build/sbt "server/testOnly *Lance* -Dhibernate.config=hibernate-postgres.properties"
```

## 4. 验证 DDL

关键验证项：
- uc_lance_namespaces 表创建成功
- uc_lance_assets 表创建成功
- uc_lance_tables 表创建成功
- uc_lance_tags 表创建成功（Phase 3）
- uc_lance_versions 表创建成功（Phase 3）
- uc_lance_indices 表创建成功（Phase 3）
- uc_lance_transactions 表创建成功（Phase 3）
- 唯一约束正确
- 外键约束正确

## 5. 验证并发测试

```bash
# 运行并发测试
build/sbt "server/testOnly LancePhase2ConcurrencyRestTest -Dhibernate.config=hibernate-postgres.properties"
```

## 6. 验证 Phase 3 元数据测试

```bash
# 运行 Phase 3 测试
build/sbt "server/testOnly *LancePhase3* -Dhibernate.config=hibernate-postgres.properties"
```

## 7. 验证元数据迁移

H2 → PostgreSQL 数据迁移验证：
1. 创建测试 namespace/table
2. 导出 H2 数据
3. 导入 PostgreSQL
4. 验证数据完整性

## 8. 清理

```bash
# 停止 PostgreSQL 容器
docker-compose -f etc/db/postgres-example.yml down

# 删除数据卷（可选）
docker-compose -f etc/db/postgres-example.yml down -v
```

## 9. 当前状态

| 项目 | 状态 |
|------|------|
| PostgreSQL 容器启动 | ❌ 未启动 |
| hibernate 配置 | ❌ 未配置 |
| DDL 验证 | ❌ 未验证 |
| 并发测试 | ❌ 未验证 |
| Phase 3 测试 | ❌ 未验证 |
| 数据迁移 | ❌ 未验证 |

## 10. 依赖

需要安装：
- Docker 或 Docker Desktop
- PostgreSQL JDBC driver（已在 build.sbt 中配置）