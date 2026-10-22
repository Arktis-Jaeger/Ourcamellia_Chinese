# 我们的山茶花（Ourcamellia）术语约定

> 本文件记录中文版汉化过程中使用的统一术语，供维护者在今后扩展或修正时保持一致。
> `Ourcamellia`、`Observatory`、`Old` 等产品/模式名保留原文；命令语法 `${...}`、
> 协议字段、JSON 键、Java 标识符、枚举名一律不翻译。

## 1. 保留原文（不翻译）的内容

- 命令语法与命令名：`${help}`、`${mode("local", name)}`、`${friend-request(...)}`、`${quit}` 等
  —— 这些是可执行语法，翻译会破坏程序兼容性与自动化测试。
- 命令参数的取值：`"on"/"off"`、`"local"/"server"`、`"pass"/"reject"/"over"`、
  `"current"/"peer"/"all"`、`"approve"/"reject"`、`"add"/"remove"`。
- 退出确认字符：`y`/`n`/`c`；确认 `YES`。
- 协议/状态字段：`T_MESSAGE`、`T_HELLO`、`FRAME`、`pbkdf2-sha256`、`friendRequests`、
  JSON 配置键（`mode`、`dataDir`、`listenPort`…）。
- 枚举名与其显示名：`CONNECTED`、`DISCOVERED`、`UNREACHABLE`、`UNCONFIRMED`、
  `PUBLIC`、`ENCRYPTED`、`OPERATOR_VISIBLE`、`FRIEND_REQUEST` 等协议/状态标识。
- 用户名、群组名、服务器名、地址、指纹、账户名等动态数据。
- `BOT`、`TLS`、`TOFU`、`IP`、`X25519`、`PBKDF2` 等通用技术缩写。
- 面向开发者/日志的原始异常信息（保留，另加中文解释）。

## 2. 统一译法

| 英文 | 中文 | 备注 |
|---|---|---|
| Local mode | 本地模式 | |
| Server mode | 服务器模式 | |
| mode menu | 模式菜单 | |
| menu | 菜单 | 界面层级 |
| list | 列表 | 界面层级 |
| conversation | 会话 | |
| message(s) | 消息 | |
| friend(s) | 好友 | |
| group(s) | 群组 | |
| notification(s) | 通知 | |
| recording | 录制 | |
| buffered (messages) | 待持久化消息 | 详情页显示“N 条待持久化” |
| persist | 持久化 | |
| history | 历史记录 | |
| discoverable | 可被发现 | |
| connectable | 可连接 | |
| connected | 已连接 | |
| discovered | 在线可见 | |
| unreachable | 不可达 | |
| unconfirmed | 未确认 | 观察者视图中留空 |
| undeliverable | 无法送达 | |
| pending | 待处理 | |
| accepted | 已接受 | |
| rejected | 已拒绝 | |
| logged in | 已登录 | |
| not logged in | 未登录 | |
| login | 登录 | 命令 `login` 保留 |
| register | 注册 | 命令 `register` 保留 |
| logout | 登出 | |
| password | 密码 | |
| quit / exit | 退出 | 命令 `quit`/`exit` 保留 |
| help | 帮助 | 命令 `help` 保留 |
| delete | 删除 | |
| announcement | 公告 | |
| announcement channel | 公告频道 | |
| operator | 运营者（管理员） | 管理控制台用“管理” |
| admin | 管理员 | 命令 `admin` 保留 |
| owner | 群主/所有者 | |
| member | 成员 | |
| invite | 邀请 | |
| transfer ownership | 转让群主 | |
| review log | 审查日志 | |
| mod-delete / moderat | 审核删除 | |
| token | 令牌 | |
| fingerprint | 指纹 | |
| identity | 身份 | |
| server name | 服务器名称 | |
| address | 地址 | |
| alias | 备注 | |
| policy | 策略 | |
| stats | 统计 | |
| registration | 注册审批 | |
| encrypted group | 加密群组 | |
| operator-visible group | 运营者可见群组 | |
| online | 在线 | |
| offline | 离线 | |
| connections | 连接 | |
| accounts | 账户 | |
| BOT | BOT | |
| error | 错误 | |
| warning | 警告 | |
| cannot | 无法/不能 | |
| invalid | 无效 | |
| unknown | 未知/未识别 | |
| malformed command | 命令格式有误 | |
| nothing was sent or executed | 未发送或执行任何内容 | |
| no conversations yet | 尚无会话 | |
| no friends yet | 尚无好友 | |
| no active notifications | 当前无活动通知 | |

## 3. 翻译原则

- 表达自然、简洁，不生硬直译。
- 同一术语在菜单、提示、日志、文档中保持一致（以本表为准）。
- `on/off` 在状态栏显示为英文原文（它们是记录开关的取值语义），状态栏以
  `录制 on/off`、`可被发现 on/off` 等混合形式呈现。
- 面向普通用户的错误提示汉化；保留必要的原始异常信息，并在其前后提供中文解释。
- 不得为汉化而改变命令、协议、配置或数据格式。
