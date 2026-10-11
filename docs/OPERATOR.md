# Ourcamellia —— 运营者指南

运行一个独立的社区服务器。每台服务器完全独立：拥有自己的账户、好友、群组、
通知与策略。没有联邦、也没有中央目录。

## 1. 启动

```bash
java -jar ourcamellia-server.jar run --data-dir ./mydata
```

- 配置文件：`<data-dir>/server.json`（首次运行按默认值生成；参见
  docs/config-examples/server.json）。未知键会给出警告并被忽略；无效值会以清晰
  的错误信息拒绝启动。
- 首次启动会生成自签名 TLS 证书（`<data-dir>/tls/keystore.p12`，密码保存在
  `tls/keystore.password`，权限 0600）。客户端在首次连接时固定证书指纹（TOFU），
  之后拒绝任何变更——请通过带外渠道把你的指纹发布给用户。如需 CA 签发的身份，
  可把自己的 PKCS12 密钥库放到配置路径并设置 `tlsKeystorePassword`，或使用
  `ourcamellia-server genkey`。
- 默认端口：**54530/TCP**（TLS）。请在防火墙中开放。

## 2. 控制台（运营者终端，仅限本机）

控制台在服务器主机上经标准输入运行——绝不经网络。主机访问权限就是运营者的
认证边界。

```
${admin add @name} / ${admin remove @name}
${announce("text")}                    → 公告频道 + 通知（离线投递）
${registration approve <ticketId>} / ${registration reject <ticketId>}
${groups}                              → 群组 id 与隐私模式
${review <groupId> [N]}                → 运营者审查日志（仅运营者可见群组）
${mod-delete <groupId> <msgId>}        → 在所有地方移除某条消息（仅运营者可见群组）
${bot-create @name}                    → 一次性打印 BOT 令牌 + X25519 密钥
${bot-revoke @name}                    → 吊销某 BOT 的所有令牌
${policy} / ${stats} / ${quit}         → 策略摘要 / 统计 / 优雅关闭
```

## 3. 注册策略

- `auto` — 立即批准（默认）。
- `manual` — 待审批，直到运营者在控制台批准。待审批票据在
  `pendingTtlMinutes` 后过期。
- `external` — 服务器把每个票据 POST 给你的外部校验器（回执契约见
  PROTOCOL.md）。校验器需要你自己运行；Ourcamellia 只提供 URL 接口。请配置
  `verifier.url`、`verifier.sharedSecret`（HMAC）、超时/重试与 SSRF 设置。请把
  URL 放在你控制的网段；默认拒绝私有目标（`allowPrivateTargets` 必须显式为
  true 才能访问）。

必填字段：`@UserName` 与 `Passwd` 始终必填；可在 `requiredRegistrationFields`
中增删字段（默认 `["@Email"]`，且 `requireEmail` 开启）。

## 4. 群组与审核

- 运营者可见群组：你可以读取内容（`${review}`）并审核（`${mod-delete}`）。
  内容保存在 `data/group-review.json`，上限为 `reviewLogRetention` 条。创建时
  会明确告知成员这一点。
- 加密群组：内容在成员之间端到端加密（X25519 + AES-256-GCM）。你无法读取、
  无法审核，也不对其负责——产品会明确说明这一点。服务器只存储包裹后的密钥与
  密文。`encryptedGroupsAllowed` 控制该模式的开关。
- 公告频道（固定在每个成员的列表顶部）仅运营者可发布。

## 5. 数据、备份与恢复

| 文件 | 内容 |
|---|---|
| `data/server-state.json` | 账户（PBKDF2 密码哈希）、好友、群组、通知、注册、BOT 令牌（SHA-256 摘要）、管理员集合 |
| `data/group-review.json` | 运营者可见群组内容（有界） |
| `data/data.lock` | 防止两个服务器进程使用同一目录 |
| `data/tls/*` | TLS 身份 |
| `data/logs/ourcamellia.log*` | 脱敏日志（不含密码/令牌/正文） |

- 备份：停止服务器后复制数据目录。原子写入（临时文件 + fsync + 重命名）保证
  快照永远不会处于写了一半的状态。
- 损坏的 `server-state.json` 会被隔离（`*.corrupt-<时间戳>`），服务器拒绝启动
  而不是丢弃账户——请检查隔离文件并手工修复。
- 任何备份中的加密群组内容都只是密文；没有成员密钥就无法还原明文，产品也不做
  此类承诺。
- 如果用户丢失二级密码（它只保护用户本地的历史记录，且在客户端侧），你服务器上
  的任何数据都帮不上忙——请如实告知。你的服务器永远看不到那个密码。

## 6. BOT API

- 进程内 API：`ServerCore.openBotSession(token, x25519PrivateKey)`——BOT 以自己
  独立的标记账户身份活动；API 不包含任何管理操作。
- 可选 HTTP 适配器：`botApi.enabled`，默认绑定 `127.0.0.1:54700`（按设计使用
  明文 HTTP——请保持它只监听回环地址；绑定到其它地址是明确的、有记录的
  风险决策）。端点：`/v1/auth`、`/v1/send`、`/v1/group-send`、
  `/v1/friend-request`、`/v1/events`——Bearer 令牌认证、按令牌限流、没有管理
  端点、无有效令牌返回 401。
- 令牌只显示一次，以 SHA-256 摘要存储，可按 BOT 吊销。

## 7. 安全相关配置

- `passwordHashIterations`（PBKDF2-HMAC-SHA256；默认 210000——在性能允许的硬件
  上可向 600000 提高）。
- `rateLimits` — 登录/注册/连接/好友请求/消息/群组操作：每分钟、按 key 的限额。
- `notificationRetentionDays` — 通知与好友请求的过期时间。
- `adminUsernames` — 引导名单：注册此类名字的账户在（自动）批准时即成为运营者。
  请至少保留一个；运营者可通过控制台互相提升/移除。
- `logLevel` — 日志默认从不包含密码、令牌、二级密码或消息正文（脱敏在格式化器
  中强制执行）。