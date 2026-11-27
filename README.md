# NekoProxy

**NekoProxy** 是一个使用 Rust 编写、以 [ViaProxy](https://github.com/ViaVersion/ViaProxy) 为协议转换核心的 Minecraft Java Edition TCP 代理。项目将轻量级连接转发、连接状态跟踪与流量统计交给 Rust 处理，并通过 ViaProxy / ViaVersion 的协议管线兼容不同 Minecraft 客户端版本；同时提供代理层管理命令、SQLite 封禁名单和聊天消息注入插件。

> 项目仍在持续开发中。Minecraft 协议兼容性取决于所使用的 ViaProxy 版本及其协议支持范围。

## 功能

- **Rust TCP 代理入口**：默认监听 `0.0.0.0:25565`，接收 Minecraft Java Edition 客户端连接。
- **ViaProxy 协议转换**：由独立 Java 子进程处理实际的 Minecraft 协议转换与后端连接。
- **自定义后端**：可指定目标服务器地址和端口；默认目标为项目配置的时光墟服务器。
- **代理层管理命令**：支持 `say`、`tell`、`kick`、`ban`、`unban`、`banlist` 和 `list`，不依赖后端 RCON 或服务端管理插件。
- **SQLite 封禁名单**：在 `plugins/NekoGate/bans.sqlite3` 保存玩家名、可用时的 UUID、封禁原因、操作者和时间；玩家连接后由 ViaProxy 插件检查封禁状态，并断开已封禁玩家。
- **玩家活动审计日志（默认关闭）**：可通过 `plugins/NekoGate/config.properties` 开关，按需记录玩家聊天、玩家指令、上下线时间及游戏内坐标/维度；日志以 JSON Lines 格式持续追加到 `plugins/NekoGate/player-activity.jsonl`，不会覆盖旧记录。
- **聊天消息注入**：自动构建并安装 ViaProxy 插件，通过 ViaVersion 转换管线按目标玩家客户端协议发送系统聊天消息。
- **连接状态跟踪**：跟踪连接数、玩家状态以及握手、登录和配置阶段。
- **流量统计**：统计上行、下行和总流量，并定期输出报告。
- **可配置 MOTD 与 LAN 发现**：支持自定义局域网广播 MOTD、指定广播网卡或关闭广播。
- **可配置日志**：支持调整日志等级，并将日志写入 `Log/proxy.log`。

## 工作方式

```text
Minecraft 客户端
       |
       | TCP（默认 :25565）
       v
   NekoProxy（Rust）
       |
       | 本机 TCP（默认 127.0.0.1:25568）
       v
 ViaProxy（Java / ViaVersion）
       |
       | Minecraft 协议转换与转发
       v
   目标 Minecraft 服务器

NekoProxy Rust  <---- 本机 JSON 控制通道 :25626 ----> ViaProxy 管理插件
                                                   |
                                                   +---- SQLite 封禁数据库
```

Rust 代理负责接入客户端、跟踪连接状态和统计流量；ViaProxy 负责协议转换及后端连接。ViaProxy 管理插件接收 Rust 控制台的 JSON 命令，直接管理 ViaProxy 玩家会话；消息包仍经 ViaVersion 转换。封禁数据由插件写入 SQLite，并在玩家登录时检查。

## 环境要求

- **Rust 工具链**：安装稳定版 Rust（包含 Cargo）。
- **Java**：安装可用的 Java 运行环境，并确保 `java` 命令可以从终端执行。
- **ViaProxy**：首次启动会自动检查并下载 ViaProxy 官方最新成功构建（包含对应版本的 ViaVersion）；若无法联网，可手动将 `ViaProxy.jar` 放在项目根目录。
- **Gradle 构建环境**：首次启动时，项目会调用 `scripts/build-viaproxy-plugin.ps1`（Windows）或 `scripts/build-viaproxy-plugin.sh`（Linux / macOS）自动构建聊天插件；构建脚本会自动下载 Gradle，无需预装。
- **Python 3（Linux / macOS）**：用于启动前执行 ViaProxy 官方更新检查；Windows 使用系统 PowerShell。

## 快速开始

### 1. 获取项目

```powershell
git clone https://github.com/MITS-CN/SerVer-MInecraft-NEXT.git
cd SerVer-MInecraft-NEXT
```

### 2. ViaProxy 自动更新

首次启动会自动下载官方最新成功构建。若服务器无法联网，可手动将 `ViaProxy.jar` 或 `ViaProxy-3.4.14.jar` 放在项目根目录。

### 3. 启动

```powershell
cargo run --release
```

首次启动时，程序会先检查 ViaProxy 官方最新成功构建；如有更新，会下载并校验构建产物，备份旧 JAR 后替换。之后自动构建聊天插件、将插件复制到 `plugins/`，再启动 ViaProxy。更新检查默认每 24 小时执行一次，网络不可用时会继续使用本地旧版本。\n\n如需临时禁用 ViaProxy 自动更新，可设置环境变量 `NEKOGATE_DISABLE_VIAPROXY_AUTO_UPDATE=1`。

如果希望直接运行编译后的程序：

```powershell
cargo build --release
.\\target\\release\\rust-proxy.exe
```

Linux / macOS 可运行：

```sh
cargo build --release
./target/release/rust-proxy
```

## 命令行参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--listen` | `0.0.0.0:25565` | NekoProxy 对 Minecraft 客户端开放的监听地址 |
| `--target` | `shiguangxu.minecraft.momeow.top:20226` | 后端 Minecraft 服务器地址 |
| `--motd` | 内置时光墟 MOTD | 局域网发现广播使用的 MOTD |
| `--lan-ip` | 未指定 | 指定用于 UDP 局域网发现的本机 IP |
| `--no-broadcast` | 关闭 | 禁用 UDP 局域网发现广播 |
| `--log-level` | `INFO` | 日志等级 |
| `--viaproxy-port` | `25568` | Rust 与 ViaProxy 之间的本机监听端口 |

也可以将后端地址作为位置参数传入：

```sh
cargo run --release -- play.example.com:25565
```

示例：更改客户端监听端口、后端地址和 MOTD：

```sh
cargo run --release -- --listen 0.0.0.0:25566 --target play.example.com:25565 --motd "My Minecraft Server"
```

示例：指定 LAN 广播网卡并调整日志等级：

```sh
cargo run --release -- --lan-ip 192.168.1.10 --log-level DEBUG
```

关闭 LAN 发现：

```sh
cargo run --release -- --no-broadcast
```

## 控制台命令

Rust 提供标准输入控制台，也会在本机开放一个 TCP 控制台（`127.0.0.1:25625`）。两种入口使用相同的命令处理逻辑。管理操作通过本机 JSON 控制通道转发给 ViaProxy 插件（`127.0.0.1:25626`），不需要连接后端 RCON。

| 命令 | 别名 | 用法与说明 |
| --- | --- | --- |
| `help` | — | 显示命令帮助 |
| `list` | — | 查看 ViaProxy 当前在线玩家 |
| `say` | — | `say <消息>`，向在线玩家广播系统消息 |
| `tell` | `msg`、`w` | `tell <玩家> <消息>`，向指定玩家发送消息 |
| `kick` | — | `kick <玩家> [原因]`，断开指定玩家连接 |
| `ban` | — | `ban <玩家> [原因]`，写入 SQLite 封禁名单，并断开在线玩家 |
| `unban` | — | `unban <玩家名或 UUID>`，移除封禁记录 |
| `banlist` | — | 查看 SQLite 中保存的封禁记录 |
| `quit` | `exit` | 关闭代理 |

示例：

```text
say 欢迎来到时光墟！
tell Steve 欢迎回来
kick Steve 请先阅读服务器规则
ban Alex 多次恶意破坏
banlist
unban Alex
list
help
```

封禁数据库默认保存在 `plugins/NekoGate/bans.sqlite3`。请定期备份该文件；删除它会丢失本地封禁记录。封禁检查由 ViaProxy 插件执行，命令和封禁数据都不依赖后端服务器的 RCON。

## 玩家活动日志

ViaProxy 插件首次启动时会生成 `plugins/NekoGate/config.properties`。默认配置不会记录玩家活动：

```properties
logging.enabled=false
logging.chat=true
logging.server_chat=true
logging.commands=true
logging.connections=true
logging.locations=true
```

将 `logging.enabled` 改为 `true` 并重启代理即可启用。隧道玩家自己发送的聊天、后端广播给隧道玩家的其他玩家聊天、玩家指令、上下线事件和游戏内位置可以分别控制。`logging.server_chat` 默认开启：只要至少一名隧道玩家在线，就从后端发往隧道客户端的玩家聊天数据包中记录其他玩家的消息；多名隧道玩家同时在线时会去重，避免同一条广播重复写入。后端聊天记录包含发送者 UUID、原始消息和消息时间戳。日志写入 `plugins/NekoGate/player-activity.jsonl`，每行是一条独立的 JSON 事件，采用追加写入，重启后继续累计。

上线和下线事件会尽可能包含玩家最后观测到的维度、X/Y/Z 坐标及朝向（Yaw/Pitch）；维度切换会单独生成 `dimension_change` 事件。坐标来自玩家移动数据包，初次上线位置可能要等客户端发出第一条移动数据后才可用；若协议包未提供可识别的位置或维度，相关字段会缺省，而不会伪造数据。位置日志只保存上线、下线和维度切换节点，不会把每次移动都写入文件。

**注意：** 聊天日志会保存玩家发送的原文；指令日志记录代理观察到的玩家指令，不代表后端一定执行成功。活动日志可能包含个人信息并持续增长，请告知玩家相关记录规则，并妥善限制文件访问、定期归档或清理。

## 网络与安全说明

- 默认的 Minecraft 监听地址是 `0.0.0.0:25565`，这意味着代理会接受来自所有网络接口的连接。请通过防火墙控制可访问范围。
- ViaProxy 的监听地址由 Rust 程序设置为本机回环地址，默认端口为 `25568`。
- 聊天插件控制通道使用本机地址 `127.0.0.1:25626`，不应将其暴露到公网。
- Rust TCP 管理控制台绑定在 `127.0.0.1:25625`，ViaProxy 插件控制通道绑定在 `127.0.0.1:25626`；两者都仅供本机访问，不应通过端口转发或其他方式暴露到不可信网络。控制通道没有独立身份验证，安全边界是回环地址。
- ViaProxy 启动参数当前使用 `--auth-method NONE`。请确认后端服务器的在线模式、身份验证和访问控制符合你的部署需求。

## 项目结构

```text
.
├── src/                    # Rust 代理、协议处理、控制台、日志与统计
│   ├── main.rs
│   ├── proxy.rs
│   ├── viaproxy.rs
│   ├── console.rs
│   ├── lan.rs
│   ├── mc.rs
│   ├── traffic.rs
│   ├── logger.rs
│   └── util.rs
├── viaproxy-plugin/        # ViaProxy 聊天注入插件工程
├── scripts/                # 插件自动构建脚本
├── data/                   # 日志文本与界面字符等运行数据
├── plugins/                # 运行时安装的 ViaProxy 插件
├── Log/                    # 运行日志
├── Cargo.toml
└── ViaProxy.jar            # 需自行放置的 ViaProxy 程序
```

## 开发

格式检查：

```sh
cargo fmt --check
```

构建：

```sh
cargo build --release
```

运行：

```sh
cargo run --release -- --log-level DEBUG
```

## 许可证

请以仓库中的许可证文件为准。ViaProxy、ViaVersion、Minecraft 及其相关商标和项目归各自权利人所有；本项目不代表 Mojang 或 Microsoft。
