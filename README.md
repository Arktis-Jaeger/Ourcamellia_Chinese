# Ourcamellia（中文本地化版）

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-c71a36.svg)](https://maven.apache.org/)
[![界面语言](https://img.shields.io/badge/%E7%95%8C%E9%9D%A2%E8%AF%AD%E8%A8%80-%E4%B8%AD%E6%96%87-red.svg)](#与原始版本的区别)

**轻量、按社区独立运行的聊天平台**——第一版。每台服务器都是一个完全独立的社区，
拥有自己的身份命名空间、账户、关系、群组、策略与数据：**没有联邦、没有中央目录、
也没有中央账户系统**。

本仓库是 [Arktis-Jaeger/Ourcamellia](https://github.com/Arktis-Jaeger/Ourcamellia)
的**中文本地化副本（1.0.0-zh）**：所有用户可见的界面、提示、反馈与文档均为中文，
关键代码路径补充了中文注释，并修复了原始版本中两个已确认的缺陷。
命令名、协议帧、JSON 键、枚举 `name()` 输出与 `@账户名` 等对外标识符**一律保持原样**，
因此中文版与原始版的存储格式和线上协议完全兼容。

---

## 目录

- [项目定位](#项目定位)
- [功能概览](#功能概览)
- [与原始版本的区别](#与原始版本的区别)
- [项目结构](#项目结构)
- [运行环境要求](#运行环境要求)
- [构建](#构建)
- [快速上手：客户端](#快速上手客户端)
- [快速上手：服务器](#快速上手服务器)
- [配置](#配置)
- [数据位置与备份](#数据位置与备份)
- [文档](#文档)
- [常见问题（FAQ）](#常见问题faq)
- [已知限制（第一版）](#已知限制第一版)
- [隐私摘要（如实说明）](#隐私摘要如实说明)
- [许可证](#许可证)

---

## 项目定位

大多数聊天产品要么依赖中心化服务，要么把「联邦」做成了另一套需要信任的中间层。
Ourcamellia 选择第三条路：**每一台服务器就是一个完整的社区**。

- 你的社区归你自己：账户、好友关系、群组、审核策略、公告与全部数据都在你自己的机器上。
- 没有跨服务器的目录、索引或账户漫游——两台服务器之间不存在信任关系，
  因此也不存在「联邦协议被滥用」这一类攻击面。
- 除了服务器模式，还提供**本地模式（Local mode）**：局域网内两台设备端到端直连，
  UDP 发现、TLS 传输、好友门槛，完全不需要任何服务器。

一句话概括：**要么你自己开一个社区，要么你和朋友直接点对点说话。**

## 功能概览

### 两种运行模式

| 模式 | 说明 |
|---|---|
| **本地模式（Local mode）** | 端到端直连通信：UDP 发现（可选）、TLS 传输、好友门槛、观察者相对状态。无需服务器。 |
| **服务器模式（Server mode）** | 连接到独立社区服务器：账户注册与审批、好友、群组、离线通知投递、公告频道、BOT API。 |

### 两个可执行组件

- **`ourcamellia-client`** — 命令行客户端，两种界面：
  - **Observatory**：固定布局终端 UI（真实终端上默认启用），状态区、会话区、
    输入行分区渲染，支持窗口缩放。
  - **Old**：逐行输出，完全可脚本化（管道 / 自动化测试友好）。
- **`ourcamellia-server`** — 可独立运行的社区服务器，带**仅限本机**的运营者控制台。

### 能力清单

- **账户与注册**：可配置必填注册字段（如 `@Email`）与审批策略
  （`auto` 立即批准 / `manual` 运营者人工审批 / `external` 外部校验器回调）。
- **好友**：首次接触必须走好友请求通道，接受后才建立关系。
- **群组**：
  - **运营者可见群组**：服务器运营者可以按策略审查内容（审查日志有界）；
  - **加密群组**：端侧加密，服务器只中转密文，运营者**无法**读取内容。
- **消息与通知**：普通消息、带离线投递的通知、公告频道（运营者发布，离线也排队）。
- **本地历史与录制**：客户端自动录制到本地历史；可选二级密码密封；删除必须显式确认。
- **安全**：PBKDF2-HMAC-SHA256 密码哈希、AES-GCM 载荷、X25519 密钥交换、
  TLS 并固定指纹（TOFU）、SSRF 防护、BOT 令牌以 SHA-256 摘要存储。
- **BOT API**：HTTP 适配器（默认关闭，且默认只绑定回环地址），带限流。

## 与原始版本的区别

| 项 | 说明 |
|---|---|
| 版本号 | `1.0.0-zh`（描述性后缀；`pom.xml` 中 `<version>` 仍为 `1.0.0`，Maven 坐标未变） |
| 界面语言 | 全部用户可见字符串汉化（两种客户端界面、服务器控制台、全部错误与提示） |
| 代码注释 | 关键路径补充中文注释（命令解析、渲染循环、TOFU、SSRF、密码学流程等） |
| 缺陷修复 A | **Observatory 模式无法输入**：JLine `NonBlockingInputStream.available()` 恒返回 0，主循环永远读不到键入内容。改为按 `read()` 返回值判定有无数据。 |
| 缺陷修复 B | **拖动窗口大小导致进程崩溃**：`LineEditor.render` 在 `terminalWidth <= 0` 时 `substring()` 抛 `StringIndexOutOfBoundsException`。加入宽度/长度钳制、输入行局部重绘、`width`/`height` 改 `volatile`、SIGWINCH 处理 try/catch、`safeRender()` 捕获异常并输出诊断（连续 5 次失败才请求退出，不静默吞错）。 |
| 新增回归测试 | `core` 的 `LineEditorTest` 增加非正宽度、极窄窗口、渲染不改缓冲区三个用例；`it` 新增 `ObservatoryPtyIT`，在真实 PTY 中验证键入实时回显、60 次密集 SIGWINCH 缩放不崩溃、`${quit}` 以退出码 0 退出（无 PTY 环境自动跳过）。 |

**明确未改动**：命令名与 `${...}` 记法、参数取值、协议帧与字段、JSON 键、
枚举 `name()` 输出、存储格式、依赖版本、方法签名。
变更明细见 [CHANGELOG.md](CHANGELOG.md)。

## 项目结构

```
Ourcamellia_Chinese/
├── pom.xml                 # Maven 多模块父工程（groupId org.ourcamellia，version 1.0.0）
├── mvnw / mvnw.cmd         # Maven Wrapper（Maven 3.9.16）
├── common/                 # ourcamellia-common：共享域模型、协议线格式、配置
│   └── src/main/java/org/ourcamellia/common/
│       ├── config/         #   客户端/服务器配置模型与校验
│       ├── domain/         #   领域对象（账户、群组、消息、通知…）
│       └── protocol/       #   帧编解码、Wire 消息、加密封装
├── core/                   # ourcamellia-core：会话层、命令层、密码学、持久化
│   └── src/main/java/org/ourcamellia/core/
│       ├── app/            #   ClientApp / LocalSession / ServerSession
│       ├── cmd/            #   命令解析、分发、命令集（${...} 语法）
│       ├── crypto/         #   PBKDF2、AES-GCM、X25519、HKDF
│       ├── discovery/      #   UDP 局域网发现
│       ├── persist/        #   原子写入、状态存储、本地历史
│       ├── security/       #   TOFU 指纹固定、SSRF 防护
│       ├── server/         #   服务器核心、会话、状态
│       ├── transport/      #   TLS 连接、套接字、内存链路
│       ├── botapi/         #   BOT HTTP 适配器与 BOT 会话
│       ├── verifier/       #   外部注册校验器客户端
│       └── util/           #   日志脱敏等工具
├── server/                 # ourcamellia-server：服务器入口 + 运营者控制台
├── client/                 # ourcamellia-client：客户端入口 + Observatory / Old 两套 UI
├── it/                     # ourcamellia-it：跨进程集成测试（含 PTY 测试）
└── docs/                   # 文档（协议、命令文法、运营者指南、威胁模型、配置示例…）
```

Java 源码 101 个文件、约 1.5 万行；测试类 24 个。模块依赖方向为
`common ← core ← server/client`，`it` 依赖全部模块并做进程级验证。

## 运行环境要求

- **JDK 21**（任意 OpenJDK 兼容发行版；开发环境为 Zulu 21.0.2）。
- **Maven 3.9+**（`mvn`）——仓库自带 Maven Wrapper，也可直接用 `./mvnw`。
- Wrapper 脚本需要 POSIX shell（macOS / Linux）。Windows 上请直接使用构建好的
  shaded jar（`java -jar ...`），或使用 `mvnw.cmd`。

## 构建

```bash
./mvnw -q package      # 或：mvn -q package
```

该命令会编译全部模块、运行完整测试套件，并生成两个可直接运行的可执行 jar：

- `server/target/ourcamellia-server.jar`
- `client/target/ourcamellia-client.jar`

加快本地迭代可跳过测试（发布前请完整跑一次）：

```bash
./mvnw -q -DskipTests package
```

各类测试的数量与覆盖内容见 [docs/REQUIREMENTS_TRACE.md](docs/REQUIREMENTS_TRACE.md)。

## 快速上手：客户端

```bash
java -jar client/target/ourcamellia-client.jar --data-dir ~/.ourcamellia
java -jar client/target/ourcamellia-client.jar --mode old      # 脚本 / 管道
java -jar client/target/ourcamellia-client.jar --help
```

- 在真实终端上自动选择 **Observatory** 模式；在哑终端（dumb terminal）上会给出
  明确错误并拒绝启动——请改用 `--mode old`。
- **Old 模式完全可脚本化**：消息中的 `\n` 表示换行，`\${` 是字面量 `${`；
  命令不依赖任何终端能力。
- 配置文件位于 `<data-dir>/config.json`（全部选项与默认值见
  [docs/config-examples/client.json](docs/config-examples/client.json)）。

### 客户端命令示例

```
${mode("local", "alice")}            # 本地模式，创建临时本地身份
${connect("192.168.1.20:54536")}     # 按地址连接（发现不可用时始终可用）
${friend-request("bob")}             # 首次接触只能走好友请求通道
${info}                              # 查看通知列表
1 pass                               # 接受好友请求（列表快捷方式）
hello there                          # 普通文本即消息（默认 say）
${create-group("Study", "@bob")}     # 服务器模式：运营者可见群组
${create-encrypted-group("Secret", "@bob")}   # 加密群组：运营者无法读取其内容
${record("off")}                     # 关闭自动录制（绝不删除历史）
${persist}                           # 按需持久化缓冲消息
${history-delete("current")}         # 确认删除（all 需要双重确认）
${quit}                              # 退出（存在缓冲内容时才提示）
```

完整命令文法与参数矩阵见 [docs/COMMAND_GRAMMAR.md](docs/COMMAND_GRAMMAR.md)。
在客户端内输入 `${help}` 可查看当时的命令列表与用法。

## 快速上手：服务器

```bash
mkdir -p ./ourcamellia-server && cd ./ourcamellia-server
java -jar ../server/target/ourcamellia-server.jar run            # 控制台 + 守护进程
java -jar ../server/target/ourcamellia-server.jar genkey         # 自定义 TLS 身份
```

- **运营者控制台在服务器主机上经标准输入运行，绝不经网络**——拥有主机访问权限
  即拥有运营者权限，这就是认证边界。
- 配置文件：`<data-dir>/server.json`（见
  [docs/config-examples/server.json](docs/config-examples/server.json)）。
- **默认端口 54530/TCP（TLS）**；本地模式客户端另使用 **54536/TCP**（对端监听）
  与 **54535/UDP**（发现）。需要时请在防火墙中开放；发现功能依赖局域网允许
  UDP 广播（发现是可选的——手动输入地址始终可用）。
- 首次启动会生成自签名 TLS 证书，客户端首次连接时固定其指纹（**TOFU**），
  之后拒绝指纹被更改的证书。请通过带外渠道把指纹告知你的用户。

### 运营者控制台命令

```
${admin add @name} / ${admin remove @name}
${announce("text")}                      → 公告频道 + 通知（离线投递）
${registration approve <ticketId>} / ${registration reject <ticketId>}
${groups}                                → 群组 id 与隐私模式
${review <groupId> [N]}                  → 审查日志（仅运营者可见群组）
${mod-delete <groupId> <msgId>}          → 在所有地方移除某条消息
${bot-create @name}                      → 一次性打印 BOT 令牌 + X25519 密钥
${bot-revoke @name}                      → 吊销某 BOT 的所有令牌
${policy} / ${stats} / ${quit}           → 策略摘要 / 统计 / 优雅关闭
```

运营细节（策略、备份、审核流程、外部校验器契约）见
[docs/OPERATOR.md](docs/OPERATOR.md)。

## 配置

两个组件都使用严格 JSON 配置文件，首次运行按默认值生成；未知键给出警告并被忽略，
无效值以清晰的中文错误信息拒绝启动。完整示例：

- 客户端：[docs/config-examples/client.json](docs/config-examples/client.json)
  — 模式、数据目录、发现/监听端口、各类超时、录制开关、二级密码保护、日志级别。
- 服务器：[docs/config-examples/server.json](docs/config-examples/server.json)
  — 绑定地址与端口、服务器名、TLS、必填注册字段与审批策略、外部校验器、
  通知保留期、BOT API、各类限流、运营者账户、审查日志上限。

## 数据位置与备份

| 组件 | 位置 |
|---|---|
| 客户端数据（默认） | `~/.ourcamellia/` |
| 客户端历史 | `~/.ourcamellia/history/*.json`（每个会话一个 JSON 文件；可选使用二级密码密封） |
| 客户端状态（好友、指纹、通知、别名、X25519 密钥） | `~/.ourcamellia/state.json` |
| 服务器数据（默认） | `./data/` |
| 服务器结构状态 | `data/server-state.json`（原子写入；唯一事实来源） |
| 运营者审查日志 | `data/group-review.json`（仅运营者可见群组内容，有界） |
| TLS 身份 | `data/tls/keystore.p12` + `data/tls/keystore.password` |
| 日志 | `<data-dir>/logs/ourcamellia.log`（已隐去机密、不含消息正文） |

**备份**：在进程停止时复制数据目录（服务器通过文件锁拒绝两个进程使用同一目录）。

> ⚠️ **加密群组内容的备份只有密文**——群组密钥保存在成员设备上，服务器备份
> 永远无法还原加密群组的明文。因二级密码丢失而受保护的客户端历史按设计不可恢复
> （见 [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md)）。

## 文档

- [CHANGELOG.md](CHANGELOG.md) — 更新日志（中文本地化与缺陷修复明细）
- [docs/DECISIONS.md](docs/DECISIONS.md) — 架构决策与已批准的产品决策
- [docs/COMMAND_GRAMMAR.md](docs/COMMAND_GRAMMAR.md) — 正式命令文法与测试矩阵
- [docs/REQUIREMENTS_TRACE.md](docs/REQUIREMENTS_TRACE.md) — 需求 → 代码/测试 映射
- [docs/PROTOCOL.md](docs/PROTOCOL.md) — 线上协议、分帧、发现、校验器契约
- [docs/OPERATOR.md](docs/OPERATOR.md) — 服务器运营者指南（策略、端口、备份、审核）
- [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) — 保证、缓解措施与残余风险
- [docs/config-examples/](docs/config-examples/) — 完整配置示例

## 常见问题（FAQ）

**Q：为什么 Observatory 模式在管道或某些终端里启动失败？**
A：Observatory 需要真实终端。检测到哑终端时会明确报错并拒绝启动，这是有意设计。
请改用 `java -jar ... --mode old`，Old 模式不依赖任何终端能力。

**Q：局域网里搜不到对方？**
A：发现依赖 UDP 广播（54535/UDP）。在禁止广播的网络（不少企业 Wi-Fi、VPN、
容器网络）中会返回空结果——请直接使用 `${connect("host:54536")}` 手动连接，
功能完全一致。

**Q：客户端提示服务器指纹变化，连不上？**
A：这是 TOFU 保护在生效。指纹变化可能意味着服务器重建了 TLS 身份，也可能意味着
中间人攻击。请通过带外渠道核对指纹；确认无误后再更新本地固定值。

**Q：运营者能看到加密群组的消息吗？**
A：不能。加密群组由端侧加密，服务器只中转密文，备份里也只有密文。相应地，
运营者也不对加密群组内容负责，且**无法**审核它。

**Q：移除群成员之后就安全了吗？**
A：不是。第一版没有失陷后安全（post-compromise security）：被移除的成员若曾
截获旧密文，仍能解密旧消息。移除成员后群主必须轮换密钥（界面会提醒）。

**Q：消息里的 `${` 会被当成命令吗？**
A：会被解析为命令起始。需要发送字面量时请写 `\${`；Old 模式下消息中的 `\n`
表示换行。

**Q：Windows 上能用 `mvnw` 吗？**
A：`mvnw` 脚本需要 POSIX shell。Windows 请使用 `mvnw.cmd`，或直接运行构建好的
两个 shaded jar。

## 已知限制（第一版）

- 本地发现依赖 UDP 广播；在禁止广播的网络上发现将返回空结果，手动输入地址仍
  完全可用。
- 服务器**不为私聊消息排队**：对端离线时给出诚实的「未送达」报告。服务器公告
  （通知）会排队投递。
- 加密群组没有失陷后安全（post-compromise security）——被移除的成员若曾截获旧
  密文，仍能解密旧消息；移除后群主必须轮换密钥（UI 会提醒）。运营者无法审核加密
  群组内容，也不对其负责。
- Observatory 输入为单行（Old 模式支持消息内 `\n`）。
- BOT HTTP 适配器按设计使用明文 HTTP——请保持它只绑定回环地址。
- Observatory 渲染中 CJK 文本的宽度为近似计算。

## 隐私摘要（如实说明）

- 本地历史仅属于本机安装（可选使用二级密码密封；服务器永远看不到）。
- 服务器运营者可以读取并审核**运营者可见群组**的内容；他们**无法**读取加密群组
  内容——服务器只以密文中转。
- 密码以 PBKDF2-HMAC-SHA256 哈希存储；BOT 令牌以 SHA-256 摘要存储。
- 产品永远不会向普通服务器模式用户显示其他用户的 IP 地址。
- 「已发送」不等于「已送达」：送达回执只反映传输层交接。
- 我们无法阻止收件人复制或截图内容——产品不作此类承诺。

## 许可证

Copyright (C) 2026 Arktis-Jaeger

本程序是自由软件：你可以在自由软件基金会发布的 **GNU 通用公共许可证第 3 版**
（GNU GPL v3）条款下重新分发和/或修改它。完整文本见 [LICENSE](LICENSE)。

本程序按「有用」的希望分发，但**不提供任何保证**；甚至不附带适销性或特定用途
适用性的默示保证。

由于本程序以 GPL-3.0 分发，任何传播或修改它的人必须传递同样的自由，并提供相应
源代码。在自己的服务器上运行未修改副本本身不触发该义务。

### 第三方组件

运行时依赖单独授权，并保留各自条款；GPL-3.0 与它们全部兼容：

| 组件 | 许可证 |
|---|---|
| Jackson（`com.fasterxml.jackson.core`） | Apache License 2.0 |
| JLine（`org.jline`） | BSD 3-Clause |
| JUnit Jupiter（仅测试范围） | Eclipse Public License 2.0 |

---

上游项目：[Arktis-Jaeger/Ourcamellia](https://github.com/Arktis-Jaeger/Ourcamellia)（GPL-3.0）
