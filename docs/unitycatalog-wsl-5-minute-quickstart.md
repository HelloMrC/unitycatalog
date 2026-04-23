# Unity Catalog WSL 5 分钟快速启动清单

更新日期：2026-04-17

## 适用场景

这份清单用于已经完成一次性环境初始化之后，快速在 WSL 中重新构建并启动 Unity Catalog 本地源码。

如果你是第一次在这台机器上配置环境，或者遇到依赖下载、JDK、Yarn、sbt 仓库等问题，请先看完整手册：

- [unitycatalog-wsl-local-run.md](/home/lei/data_ai/learning/codebase/unitycatalog/docs/unitycatalog-wsl-local-run.md)

## 默认前提

开始前默认以下条件已经满足：

- 已安装并可用 `java` 17
- 已安装并可用 `node`、`npm`、`corepack`
- `~/.bashrc` 已配置 `JAVA_HOME` 和 `~/.local/bin`
- `~/.local/bin/yarn` 已存在并可执行
- `~/.sbt-repositories-unitycatalog` 已存在

如果你不确定，可以先执行：

```bash
source ~/.bashrc
java --version
yarn -v
test -f ~/.sbt-repositories-unitycatalog && echo OK
```

## Terminal 1：构建并启动后端

```bash
source ~/.bashrc
export SBT_OPTS="-Dsbt.override.build.repos=true -Dsbt.repository.config=$HOME/.sbt-repositories-unitycatalog"
export COURSIER_PARALLEL_DOWNLOAD_COUNT=1

cd /home/lei/data_ai/learning/codebase/unitycatalog
build/sbt -batch server/package
bin/start-uc-server
```

后端启动成功后，默认监听：

```text
http://localhost:8080
```

## Terminal 2：启动 UI

```bash
source ~/.bashrc
cd /home/lei/data_ai/learning/codebase/unitycatalog/ui
yarn install --network-timeout 600000
yarn start
```

UI 启动成功后，默认监听：

```text
http://localhost:3000
```

## Terminal 3：快速验证

```bash
curl http://127.0.0.1:8080/
curl "http://127.0.0.1:8080/api/2.1/unity-catalog/catalogs"
curl "http://127.0.0.1:3000/api/2.1/unity-catalog/catalogs"
```

预期结果：

- 第 1 条命令返回 `Hello, Unity Catalog!`
- 第 2 条命令返回后端 catalog JSON
- 第 3 条命令返回与后端一致的 catalog JSON，说明 UI 代理正常

## 停止服务

如果两个进程都在前台运行，直接在各自终端按 `Ctrl+C`。

如果你改成后台运行，可参考完整手册中的 PID 文件方式停止：

- [unitycatalog-wsl-local-run.md](/home/lei/data_ai/learning/codebase/unitycatalog/docs/unitycatalog-wsl-local-run.md)

## 失败时先看这 3 件事

```bash
source ~/.bashrc
echo "$JAVA_HOME"
yarn -v
echo "$SBT_OPTS"
```

然后检查端口：

```bash
ss -ltnp | grep ':8080'
ss -ltnp | grep ':3000'
```

## 当前快速启动路线的边界

这份清单只覆盖目前已经验证成功的最短路径：

- 后端构建使用 `build/sbt -batch server/package`
- 后端验证使用 HTTP API
- UI 验证使用前端代理

目前不包含：

- `bin/uc` CLI 验证
- `build/sbt clean package` 根级别完整构建
- Docker 调试方案

这些内容已经在完整手册中记录了现状和问题。
