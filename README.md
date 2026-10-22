# Ourcamellia（中文版）

轻量、按社区独立运行的聊天平台。每台服务器都是独立的社区，拥有自己的
身份命名空间、账户、关系、群组、策略与数据。没有联邦、没有中央目录、也没有中央
账户系统。

> 当前版本：**2.0.0-zh**（大版本；Terminal UI 与便携数据目录重构，详见
> [CHANGELOG.md](CHANGELOG.md)）。
> 学习版源码（逐文件中文注释）：[Arktis-Jaeger/Ourcamellia_Study](https://github.com/Arktis-Jaeger/Ourcamellia_Study)。

本副本为**中文本地化版本**：所有用户可见界面、提示、反馈与文档均为中文，并对关键
代码添加了中文注释。命令行命令名、协议帧、JSON 键、枚举 `name()` 输出以及 `@账户名`
等标识符保持不变（详见 [docs/L10N_GLOSSARY.md](docs/L10N_GLOSSARY.md) 术语约定）。

组件：

- `ourcamellia-client` — 命令行客户端，两种界面模式：**Observatory**（固定布局终端
  UI，真实终端上默认启用）与 **Old**（逐行输出，可脚本化）。
- `ourcamellia-server` — 可独立运行的社区服务器，带运营者控制台。
- 本地模式（Local mode）— 端到端直连通信：UDP 发现、TLS 传输、好友门槛、
  观察者相对状态。
- 服务器模式（Server mode）— 账户（可配置注册字段与审批策略）、好友、
  群组（运营者可见群组与加密群组）、带离线投递的通知、公告频道，以及 BOT API。

## 运行环境要求

- JDK 21（任意 OpenJDK 兼容发行版；开发环境为 Zulu 21.0.2）。
- Maven 3.9+（`mvn`）——也可以使用 Maven Wrapper：`./mvnw`。
- Wrapper 脚本需要 POSIX shell（macOS/Linux）。Windows 上请直接使用 shaded jar
  （`java -jar ...`）。

## 构建

```bash
mvn -q package          # 编译、运行完整测试套件、生成 shaded jar
```

产物：

- `server/target/ourcamellia-server.jar`
- `client/target/ourcamellia-client.jar`

各类测试的数量与覆盖内容见 [docs/REQUIREMENTS_TRACE.md](docs/REQUIREMENTS_TRACE.md)。

## 运行——客户端

```bash
java -jar client/target/ourcamellia-client.jar                       # 默认 Observatory
java -jar client/target/ourcamellia-client.jar --mode old            # 脚本/管道
java -jar client/target/ourcamellia-client.jar --data-dir ./my-data  # 自选数据目录
java -jar client/target/ourcamellia-client.jar --help
```

- 展示模式必须显式选择：默认 `terminal.mode=OBSERVATORY`；设置为 `OLD` 或使用
  `--mode old` 进入 Old。程序**不会**根据终端尺寸/能力自动切换。在哑终端
  （dumb terminal）上，Observatory 会给出明确错误并拒绝启动，请改用 `--mode old`。
- Observatory 提供菜单导航（↑↓/Enter/Esc）、登录/注册表单（Tab 切换字段）、
  会话列表、通知页、私聊/群聊消息区与多行输入、全局命令栏和全屏 `${help}`。
- Old 模式完全可脚本化：消息中的 `\n` 表示换行，`\${` 是字面量 `${`。命令不依赖
  任何终端能力。
- 数据目录默认是**程序目录**下的 `data/`（打包运行时为发行目录，开发运行时为
  仓库根目录）；不依赖启动时所在的工作目录，也不会静默写到用户主目录。
- 配置文件位于 `<data-dir>/config.json`（全部选项与默认值见
  `docs/config-examples/client.json`）。

客户端快速上手：

```
${mode("local", "alice")}            # 本地模式，临时本地身份
${connect("192.168.1.20:54536")}     # 按地址连接
${friend-request("bob")}             # 首次接触只能走好友请求通道
${info}                              # 查看通知列表
1 pass                               # 接受好友请求（快捷方式）
hello there                          # 普通文本即消息（默认 say）
${create-group("Study", "@bob")}     # 服务器模式：运营者可见群组
${create-encrypted-group("Secret", "@bob")}   # 加密群组：运营者无法读取其内容
${record("off")}                     # 关闭自动录制（绝不删除历史）
${persist}                           # 按需持久化缓冲消息
${history-delete("current")}         # 确认删除（all 需要双重确认）
${quit}                              # 退出（存在缓冲内容时才提示）
```

## 运行——服务器

```bash
mkdir -p ./ourcamellia-server && cd ./ourcamellia-server
java -jar ../server/target/ourcamellia-server.jar run            # 控制台 + 守护进程
java -jar ../server/target/ourcamellia-server.jar genkey         # 自定义 TLS 身份
```

- 运营者控制台在服务器主机上经标准输入运行（绝不经网络）：
  `${admin add @name}`、`${announce("text")}`、`${registration approve <ticketId>}`、
  `${review <groupId>}`、`${mod-delete <groupId> <msgId>}`、`${bot-create @name}`、
  `${policy}`、`${stats}`、`${quit}`。
- 配置文件：`<data-dir>/server.json`（见 `docs/config-examples/server.json`）。
- 默认端口 **54530/TCP**（TLS）。本地模式客户端另使用 **54536/TCP**（对端监听）与
  **54535/UDP**（发现）。需要时请在防火墙中开放这些端口；发现功能依赖局域网允许
  UDP 广播（发现是可选的——手动输入地址始终可用）。
- 服务器首次启动会生成自签名 TLS 证书；客户端首次连接时固定其指纹（TOFU），
  之后拒绝指纹被更改的证书。

## 数据位置与备份

| 组件 | 位置 |
|---|---|
| 客户端数据（默认） | `<程序目录>/data/` |
| 局域网聊天记录 | `<data>/chats/local/<本地会话标识>/conversation.json` |
| 服务器聊天记录 | `<data>/chats/servers/<IP>/<服务器标识>/conversations/<会话标识>.json` |
| 服务器元数据 | `<data>/chats/servers/<IP>/<服务器标识>/server.json` |
| 旧版扁平历史（只读兼容） | `<data>/history/*.json`（升级后仍可读取；不会自动删除） |
| 客户端状态（好友、指纹、通知、别名、X25519 密钥） | `<data>/state.json` |
| 服务器数据（默认） | `./data/` |
| 服务器结构状态 | `data/server-state.json`（原子写入；唯一事实来源） |
| 运营者审查日志 | `data/group-review.json`（运营者可见群组内容，有界） |
| TLS 身份 | `data/tls/keystore.p12` + `data/tls/keystore.password` |
| 日志 | `<data-dir>/logs/ourcamellia.log`（已隐去机密、不含消息正文） |

### 服务器 IP 变化后的手工迁移

1. 找到 `<data>/chats/servers/<旧IP>/`；
2. 在其中找到目标服务器子目录（目录名形如 `srv-xxxxxxxxxxxx`，由 TLS 证书指纹派生）；
3. 把整个服务器子目录移动到 `<data>/chats/servers/<新IP>/` 下；
4. 如有需要，修改该目录中 `server.json` 的 `address` 字段（程序在下次连接时也会更新）；
5. 重新连接后，程序按服务器标识识别原目录，继续读取原记录。

不要只移动某个 `conversations/*.json` 文件；不要合并同一 IP 下的其他服务器目录。
程序不会自动移动或合并历史，也不会把未验证的服务器视为可信服务器。

备份：在进程停止时复制数据目录（服务器通过文件锁拒绝两个进程使用同一目录）。
**加密群组内容的备份只有密文**——群组密钥保存在成员设备上，服务器备份永远无法
还原加密群组的明文。因二级密码丢失而受保护的客户端历史按设计不可恢复
（见 THREAT_MODEL）。

## 文档

- [docs/DECISIONS.md](docs/DECISIONS.md) — 架构决策与已批准的产品决策
- [docs/COMMAND_GRAMMAR.md](docs/COMMAND_GRAMMAR.md) — 正式命令文法与测试矩阵
- [docs/REQUIREMENTS_TRACE.md](docs/REQUIREMENTS_TRACE.md) — 需求 → 代码/测试 映射
- [docs/PROTOCOL.md](docs/PROTOCOL.md) — 线上协议、分帧、发现、校验器契约
- [docs/OPERATOR.md](docs/OPERATOR.md) — 服务器运营者指南（策略、端口、备份、审核）
- [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) — 保证、缓解措施与残余风险
- [docs/config-examples/](docs/config-examples/) — 完整配置示例
- [docs/L10N_GLOSSARY.md](docs/L10N_GLOSSARY.md) — 本地化术语约定（本版新增）

## 已知限制（第一版）

- 本地发现依赖 UDP 广播；在禁止广播的网络上发现将返回空结果，手动输入地址仍
  完全可用。
- 服务器不为私聊消息排队：对端离线时给出诚实的"未送达"报告。服务器公告（通知）
  会排队投递。
- 加密群组：没有失陷后安全（post-compromise security）——被移除的成员若曾截获
  旧密文，仍能解密旧消息；移除后群主必须轮换密钥（UI 会提醒）。运营者无法审核
  加密群组内容，也不对其负责。
- Observatory 的消息输入支持多行；Enter/Shift+Enter 的发送策略由
  `terminal.shiftEnterToSend` 控制（默认 true：Enter 换行、Shift+Enter 发送）。
  部分终端把 Shift+Enter 与 Enter 发送成同一个字节；此时请使用 Ctrl+S 发送，
  界面和帮助页会明确提示该限制。
- 服务器中普通私聊对端离线时只显示“未知”，不会把不可达/不可发现伪装成在线或离线。
- BOT HTTP 适配器按设计使用明文 HTTP——请保持它只绑定回环地址。
- Observatory 渲染中 CJK 文本的宽度为近似计算。

## 隐私摘要（如实说明）

- 本地历史仅属于本机安装（可选使用二级密码密封；服务器永远看不到）。
- 服务器运营者可以读取并审核**运营者可见群组**的内容；他们**无法**读取加密群组
  内容——服务器只以密文中转。
- 密码以 PBKDF2-HMAC-SHA256 哈希存储；BOT 令牌以 SHA-256 摘要存储。
- 产品永远不会向普通服务器模式用户显示其他用户的 IP 地址。
- "已发送"不等于"已送达"：送达回执只反映传输层交接。
- 我们无法阻止收件人复制或截图内容——产品不作此类承诺。

## 许可证

Copyright (C) 2026 Arktis-Jaeger

本程序是自由软件：你可以在自由软件基金会发布的 **GNU 通用公共许可证第 3 版**
（GNU GPL v3）条款下重新分发和/或修改它。完整文本见 [LICENSE](LICENSE)。

本程序按"有用"的希望分发，但**不提供任何保证**；甚至不附带适销性或特定用途
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