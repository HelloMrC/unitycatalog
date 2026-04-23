# Unity Catalog 在 WSL 的本地构建与运行手册

更新日期：2026-04-17

## 1. 目标

本文档记录了在 WSL Ubuntu 中，从本地源码构建并启动 Unity Catalog `0.5.0-SNAPSHOT` 的完整可复现流程，重点覆盖：

- 需要提前准备哪些环境
- 每一步要执行哪些命令
- 实际遇到了哪些问题
- 这些问题分别是如何解决的
- 当前还有哪些边界和注意事项

本文档对应的源码目录：

```bash
/home/lei/data_ai/learning/codebase/unitycatalog
```

本次已经验证成功的结果：

- 后端服务成功监听 `http://localhost:8080`
- UI 开发服务器成功监听 `http://localhost:3000`
- UI 通过代理成功访问本地后端
- `bin/uc` CLI 已经能够正常运行并验证 catalog、table list、table read
- 根级别完整构建 `build/sbt -batch clean package` 已经成功通过

## 2. 构建结论

如果目标是日常开发和源码级调试，推荐优先使用本地 WSL 构建，而不是 Docker。

原因：

- 本地 Java 进程更适合直接挂 IDE 断点
- 前端 `yarn start` 的热更新链路最直接
- 不需要为调试额外处理容器 rebuild、端口映射和远程 attach
- 这个仓库的 `compose.yaml` 默认使用发布镜像 `unitycatalog/unitycatalog:latest`，并不是你当前本地的 `0.5.0-SNAPSHOT` 源码

Docker 仍然可以作为补充方案，但它不适合替代这份文档里的本地调试主路径。更重要的是，这次实际尝试过源码 Docker build，依然会卡在外部依赖下载上，并没有绕开网络问题。

## 3. 前置环境

下面这些环境需要在 WSL Ubuntu 中可用：

- `bash`
- `curl`
- `tar`
- `java` 17
- `node`
- `npm`
- `corepack`
- `git`

建议先执行下面的检查命令：

```bash
java --version
node -v
npm -v
corepack --version
git --version
```

说明：

- 本次运行时，`node`、`npm`、`corepack` 已经在 WSL 中可用，没有额外安装
- `java` 没有走 `apt install`，因为 Ubuntu mirror 在当前环境中多次超时

## 4. 一次性初始化

这一节只需要做一次。做完以后，新开一个 WSL shell 就能直接继续构建和运行。

### 4.1 安装用户级 JDK 17

这次没有使用 `apt`，而是直接下载 Amazon Corretto 17 的 tar.gz。

执行命令：

```bash
mkdir -p ~/.local/jdks
cd ~/.local/jdks
curl -L -o amazon-corretto-17-x64-linux-jdk.tar.gz https://corretto.aws/downloads/latest/amazon-corretto-17-x64-linux-jdk.tar.gz
tar -xzf amazon-corretto-17-x64-linux-jdk.tar.gz
ls -d ~/.local/jdks/amazon-corretto-17*
```

本次实际安装目录为：

```bash
/home/lei/.local/jdks/amazon-corretto-17.0.18.9.1-linux-x64
```

### 4.2 将 Java 和本地工具目录写入 `~/.bashrc`

执行命令：

```bash
cat >> ~/.bashrc <<'EOF'
# Unity Catalog local Java and Yarn
if [ -d "$HOME/.local/jdks" ]; then
  export JAVA_HOME="$(find "$HOME/.local/jdks" -maxdepth 1 -type d -name 'amazon-corretto-17*' | sort | tail -n 1)"
  export PATH="$HOME/.local/bin:$JAVA_HOME/bin:$PATH"
fi
EOF
```

然后重新加载：

```bash
source ~/.bashrc
```

验证：

```bash
echo "$JAVA_HOME"
java --version
```

### 4.3 一个重要注意点：交互 shell 和非交互 shell 不一样

当前 `~/.bashrc` 顶部有一段 Ubuntu 默认逻辑：

```bash
case $- in
    *i*) ;;
      *) return;;
esac
```

它的含义是：

- 你平时直接打开 WSL 终端时，属于交互 shell，后面的 Java/Yarn 配置会正常生效
- 如果你通过 `wsl bash -lc '...'` 之类的非交互方式执行命令，`~/.bashrc` 会提前 return，后面追加的 Unity Catalog 配置不会自动执行

因此：

- 人工在 WSL 终端里按本文档操作，没有问题
- 如果你想用自动化脚本调用 WSL，请显式导出 `JAVA_HOME` 和 `PATH`，不要只依赖 `source ~/.bashrc`

自动化场景可直接使用：

```bash
export JAVA_HOME="$(find "$HOME/.local/jdks" -maxdepth 1 -type d -name 'amazon-corretto-17*' | sort | tail -n 1)"
export PATH="$HOME/.local/bin:$JAVA_HOME/bin:$PATH"
```

## 5. 配置 Yarn Classic

UI 是通过 Yarn Classic 启动的。本次实际可用方案不是全局安装 Yarn，而是 `corepack` + 用户级 wrapper。

### 5.1 准备 Yarn Classic

执行命令：

```bash
corepack prepare yarn@1.22.22 --activate
```

### 5.2 创建用户级 `yarn` wrapper

`corepack enable` 在当前环境里会尝试往 `/usr/bin` 写链接，并报 `EACCES`。因此实际成功方案是自己放一个用户级脚本到 `~/.local/bin/yarn`。

执行命令：

```bash
mkdir -p ~/.local/bin
cat > ~/.local/bin/yarn <<'EOF'
#!/usr/bin/env bash
exec corepack yarn "$@"
EOF
chmod +x ~/.local/bin/yarn
```

验证：

```bash
yarn -v
```

预期输出：

```bash
1.22.22
```

## 6. 配置 sbt 仓库

这一步非常关键。当前环境里如果直接走默认依赖解析，sbt 在 Maven 依赖下载阶段容易超时。

实际可用方案是：

- 强制 sbt 使用自定义仓库配置
- 显式指向 `repo.maven.apache.org`
- 将 coursier 并发下载数调低到 1

### 6.1 创建仓库配置文件

执行命令：

```bash
cat > ~/.sbt-repositories-unitycatalog <<'EOF'
[repositories]
  local
  maven-central: https://repo.maven.apache.org/maven2/
  scala-sbt-maven-releases: https://repo.scala-sbt.org/scalasbt/maven-releases/
  typesafe-releases: https://repo.typesafe.com/typesafe/releases/
EOF
```

### 6.2 每次构建前导出环境变量

执行命令：

```bash
export SBT_OPTS="-Dsbt.override.build.repos=true -Dsbt.repository.config=$HOME/.sbt-repositories-unitycatalog"
export COURSIER_PARALLEL_DOWNLOAD_COUNT=1
```

说明：

- `SBT_OPTS` 是本次能稳定走到成功构建的关键
- `COURSIER_PARALLEL_DOWNLOAD_COUNT=1` 能降低并发带来的下载波动

如果你希望以后每次开新 shell 都自动生效，可以把上面两行也追加到 `~/.bashrc`。但本次实际成功构建时，是在当前 shell 中手动导出的。

## 7. 完整构建与后端构建

### 7.1 进入仓库

```bash
cd /home/lei/data_ai/learning/codebase/unitycatalog
```

### 7.2 现在已经验证成功的完整构建命令

当前环境下，这条命令已经验证通过：

```bash
build/sbt -batch clean package
```

这意味着以下产物都会一起准备好：

- server
- CLI
- Java client
- Python client
- Spark connector

### 7.3 如果第一次完整构建卡在 `parquet-encoding-1.12.3.jar`

这次根级别完整构建首次失败时，只剩一个依赖下载超时：

```text
org/apache/parquet/parquet-encoding/1.12.3/parquet-encoding-1.12.3.jar
```

解决办法是先把这个 jar 预热到 coursier 缓存，再重新执行完整构建。

执行命令：

```bash
mkdir -p "$HOME/.cache/coursier/v1/https/repo.scala-sbt.org/scalasbt/maven-releases/org/apache/parquet/parquet-encoding/1.12.3"
curl -fL "https://repo.maven.apache.org/maven2/org/apache/parquet/parquet-encoding/1.12.3/parquet-encoding-1.12.3.jar" \
  -o "$HOME/.cache/coursier/v1/https/repo.scala-sbt.org/scalasbt/maven-releases/org/apache/parquet/parquet-encoding/1.12.3/parquet-encoding-1.12.3.jar"
```

为了让 central 和 scala-sbt 两个缓存路径都具备命中，也可以再执行一次：

```bash
mkdir -p "$HOME/.cache/coursier/v1/https/repo.maven.apache.org/maven2/org/apache/parquet/parquet-encoding/1.12.3"
curl -fL "https://repo.maven.apache.org/maven2/org/apache/parquet/parquet-encoding/1.12.3/parquet-encoding-1.12.3.jar" \
  -o "$HOME/.cache/coursier/v1/https/repo.maven.apache.org/maven2/org/apache/parquet/parquet-encoding/1.12.3/parquet-encoding-1.12.3.jar"
```

然后重新执行：

```bash
build/sbt -batch clean package
```

本次按上面的方式预热后，完整构建已经成功通过。

### 7.4 如果你只想最快把后端跑起来

虽然完整构建已经可用，但如果你只是想最短时间启动后端，仍然可以使用最小构建路径：

```bash
build/sbt -batch server/package
```

这一步成功后，应该会生成：

```bash
server/target/unitycatalog-server-0.5.0-SNAPSHOT.jar
server/target/classpath
```

可以执行下面的检查命令：

```bash
ls -l server/target/unitycatalog-server-0.5.0-SNAPSHOT.jar
ls -l server/target/classpath
```

## 8. 启动后端服务

### 8.1 前台运行

如果你要本地调试，推荐前台运行：

```bash
cd /home/lei/data_ai/learning/codebase/unitycatalog
bin/start-uc-server
```

### 8.2 后台运行

如果你只想先跑起来，也可以后台运行：

```bash
cd /home/lei/data_ai/learning/codebase/unitycatalog
nohup bin/start-uc-server > /tmp/unitycatalog-server.log 2>&1 &
echo $! > /tmp/unitycatalog-server.pid
```

说明：

- `bin/start-uc-server` 会复用已经生成的 `server/target` 产物
- 如果这些产物不存在，它会尝试触发构建

### 8.3 验证后端已启动

执行命令：

```bash
ss -ltnp | grep ':8080'
curl http://127.0.0.1:8080/
curl "http://127.0.0.1:8080/api/2.1/unity-catalog/catalogs"
curl "http://127.0.0.1:8080/api/2.1/unity-catalog/schemas?catalog_name=unity"
curl "http://127.0.0.1:8080/api/2.1/unity-catalog/tables/unity.default.numbers"
```

预期结果：

- `8080` 端口有 Java 进程监听
- 根路径返回：

```text
Hello, Unity Catalog!
```

- 上面三个 API 返回有效 JSON

## 9. 启动 UI

### 9.1 安装依赖

```bash
cd /home/lei/data_ai/learning/codebase/unitycatalog/ui
yarn install --network-timeout 600000
```

说明：

- 当前仓库默认无认证模式即可联本地后端
- `ui/package.json` 已经将代理指向 `http://localhost:8080`
- `ui/.env` 中几个认证开关默认是关闭的

### 9.2 启动 UI

前台运行：

```bash
cd /home/lei/data_ai/learning/codebase/unitycatalog/ui
yarn start
```

后台运行：

```bash
cd /home/lei/data_ai/learning/codebase/unitycatalog/ui
nohup yarn start > /tmp/unitycatalog-ui.log 2>&1 &
echo $! > /tmp/unitycatalog-ui.pid
```

### 9.3 验证 UI 已启动

执行命令：

```bash
ss -ltnp | grep ':3000'
curl http://127.0.0.1:3000
curl "http://127.0.0.1:3000/api/2.1/unity-catalog/catalogs"
```

预期结果：

- `3000` 端口有 Node 进程监听
- 访问 `http://127.0.0.1:3000` 能拿到前端 HTML
- 通过 `3000` 访问 API 时，能够返回和后端一致的 catalog JSON

## 10. CLI 验证

这次已经验证 `bin/uc` 可以正常使用。

执行命令：

```bash
cd /home/lei/data_ai/learning/codebase/unitycatalog
bin/uc catalog list
bin/uc table list --catalog unity --schema default
bin/uc table read --full_name unity.default.numbers
```

预期结果：

- `catalog list` 能列出本地 catalog
- `table list --catalog unity --schema default` 能列出 `numbers` 等表
- `table read --full_name unity.default.numbers` 能返回示例数据

说明：

- 如果你是在正常的交互式 WSL 终端中执行，上面三条命令可以直接使用
- 如果你是从自动化脚本里执行，请先显式导出 `JAVA_HOME` 和 `PATH`

## 11. 下次直接复现时建议照着执行的顺序

如果环境已经按本文档初始化过，下次建议直接按这个顺序执行：

### 11.1 打开一个新的 WSL shell

```bash
source ~/.bashrc
export SBT_OPTS="-Dsbt.override.build.repos=true -Dsbt.repository.config=$HOME/.sbt-repositories-unitycatalog"
export COURSIER_PARALLEL_DOWNLOAD_COUNT=1
```

### 11.2 如果你要完整构建

```bash
cd /home/lei/data_ai/learning/codebase/unitycatalog
build/sbt -batch clean package
```

### 11.3 如果完整构建首次卡在 `parquet-encoding`

先执行：

```bash
mkdir -p "$HOME/.cache/coursier/v1/https/repo.scala-sbt.org/scalasbt/maven-releases/org/apache/parquet/parquet-encoding/1.12.3"
curl -fL "https://repo.maven.apache.org/maven2/org/apache/parquet/parquet-encoding/1.12.3/parquet-encoding-1.12.3.jar" \
  -o "$HOME/.cache/coursier/v1/https/repo.scala-sbt.org/scalasbt/maven-releases/org/apache/parquet/parquet-encoding/1.12.3/parquet-encoding-1.12.3.jar"
build/sbt -batch clean package
```

### 11.4 启动后端

```bash
cd /home/lei/data_ai/learning/codebase/unitycatalog
bin/start-uc-server
```

### 11.5 新开一个 shell 启动 UI

```bash
source ~/.bashrc
cd /home/lei/data_ai/learning/codebase/unitycatalog/ui
yarn install --network-timeout 600000
yarn start
```

### 11.6 新开一个 shell 做验证

```bash
curl http://127.0.0.1:8080/
bin/uc catalog list
bin/uc table list --catalog unity --schema default
bin/uc table read --full_name unity.default.numbers
curl "http://127.0.0.1:3000/api/2.1/unity-catalog/catalogs"
```

## 12. 停止服务

如果你是前台启动的，直接在各自终端按 `Ctrl+C`。

如果你是后台启动的，可以执行：

```bash
kill $(cat /tmp/unitycatalog-server.pid)
kill $(cat /tmp/unitycatalog-ui.pid)
```

如果 PID 文件对应的是 wrapper shell，而子进程还活着，再执行：

```bash
ss -ltnp | grep ':8080'
ss -ltnp | grep ':3000'
```

然后按实际的 Java 或 Node PID 杀掉对应进程。

## 13. 本次实际遇到的问题和解决方式

### 问题 1：`apt` 安装 JDK 不稳定

现象：

- `sudo apt update`
- `sudo apt install -y openjdk-17-jdk`

在当前网络环境中多次超时，无法稳定完成。

解决方式：

- 不再依赖 Ubuntu mirror
- 改为直接下载 Amazon Corretto 17 的压缩包
- 手动解压到 `~/.local/jdks`
- 在 `~/.bashrc` 中显式设置 `JAVA_HOME`

### 问题 2：`corepack enable` 报 `EACCES`

现象：

- `corepack enable` 会尝试往 `/usr/bin` 写入 shim
- 当前用户没有权限，导致失败

解决方式：

- 保留 `corepack prepare yarn@1.22.22 --activate`
- 不再执行系统级启用
- 改为自己创建 `~/.local/bin/yarn` wrapper

### 问题 3：sbt 依赖下载经常超时

现象：

- `build/sbt clean package`
- `build/sbt -info clean package`

会在 coursier 解析 Maven 依赖时失败。

解决方式：

- 自定义 `~/.sbt-repositories-unitycatalog`
- 强制 sbt 使用 `repo.maven.apache.org`
- 将 `COURSIER_PARALLEL_DOWNLOAD_COUNT` 降到 1
- 对首次超时的关键依赖做缓存预热

### 问题 4：`bin/uc` 之前不能作为可靠验证命令

之前的状态：

- CLI 子项目构建不完整
- `bin/uc` 不能稳定运行

现在的状态：

- 已解决
- 在本地环境中，`bin/uc catalog list`
- `bin/uc table list --catalog unity --schema default`
- `bin/uc table read --full_name unity.default.numbers`

这三条命令已经验证通过

### 问题 5：根级别完整构建之前不稳定

之前的状态：

- `build/sbt clean package` 无法作为稳定路径

现在的状态：

- 已基本解决
- 在当前环境中，`build/sbt -batch clean package` 已经成功通过
- 如果首次失败，按本文档预热 `parquet-encoding-1.12.3.jar` 后可重新成功

### 问题 6：非交互 shell 不会自动加载 Unity Catalog 的 Java 配置

现象：

- 在交互式 WSL shell 中一切正常
- 在 `wsl bash -lc '...'` 这类非交互调用中，`~/.bashrc` 顶部会提前 return，导致追加的 `JAVA_HOME` 和 PATH 配置不会生效

解决方式：

- 日常人工操作时，直接在 WSL 终端中执行
- 自动化脚本场景里，显式导出 `JAVA_HOME` 和 `PATH`

## 14. 当前仍需注意的边界

下面这些不算阻塞，但仍然值得记录：

### 14.1 冷缓存首次完整构建仍然对网络质量敏感

虽然完整构建已经成功通过，但在一台全新机器或者清空缓存后，第一次依赖下载仍然可能受网络波动影响。

当前最明确、已验证的补救方式是：

- 保留自定义 sbt 仓库配置
- 将 coursier 并发下载数设为 1
- 如果卡在 `parquet-encoding-1.12.3.jar`，先手动预热缓存再重跑

### 14.2 从 PowerShell 查看 WSL 输出时可能出现字符显示乱码

例如：

- CLI 表格中的 box-drawing 字符
- 中文文档内容

可能在 Windows PowerShell 中显示为乱码，但这不代表文件或命令真实结果损坏。用 WSL 终端、VS Code Remote WSL 或支持 UTF-8 的查看方式打开即可正常显示。

### 14.3 Docker 仍然不是当前主推荐路径

原因不是 Docker 一定不能用，而是：

- 它对源码级调试不如本地 WSL 直接
- 这次并没有证明 Docker 能明显改善首次依赖下载成功率

## 15. 建议的后续工作

如果后面要继续补齐本地开发链路，建议按这个顺序处理：

1. 将 `SBT_OPTS` 和 `COURSIER_PARALLEL_DOWNLOAD_COUNT` 也持久化进适合你的 shell 初始化文件
2. 如果后续还经常遇到个别依赖超时，可以整理一个统一的“依赖预热脚本”
3. 如果需要容器化调试，再单独整理 Docker 调试方案

## 16. 最简版操作清单

只看这一节也可以快速跑起来：

```bash
source ~/.bashrc
export SBT_OPTS="-Dsbt.override.build.repos=true -Dsbt.repository.config=$HOME/.sbt-repositories-unitycatalog"
export COURSIER_PARALLEL_DOWNLOAD_COUNT=1

cd /home/lei/data_ai/learning/codebase/unitycatalog
build/sbt -batch clean package
bin/start-uc-server
```

另开一个 shell：

```bash
source ~/.bashrc
cd /home/lei/data_ai/learning/codebase/unitycatalog/ui
yarn install --network-timeout 600000
yarn start
```

再开一个 shell 验证：

```bash
curl http://127.0.0.1:8080/
bin/uc catalog list
bin/uc table list --catalog unity --schema default
bin/uc table read --full_name unity.default.numbers
curl "http://127.0.0.1:3000/api/2.1/unity-catalog/catalogs"
```
