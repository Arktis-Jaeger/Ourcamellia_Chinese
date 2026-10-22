# 更新日志（CHANGELOG）

本项目为 Ourcamellia 的**中文本地化副本**。所有条目面向用户可见行为、缺陷修复与
测试，命令名/协议/JSON 键等对外契约不受影响（见 docs/L10N_GLOSSARY.md）。

## 2.0.0-zh（Terminal UI 与便携数据目录，大版本）

本版为 1.x 之后的首个大版本，版本号由 1.0.0 升至 2.0.0，以反映展示层与本地数据
布局的重构。功能在 1.0.0-zh 基础上增量演进，不改变业务内核、协议、认证、好友/
群组、通知、权限或加密语义。

### Terminal UI

- Observatory 增加真正的菜单/列表焦点导航（↑↓/Enter/Esc）、登录/注册表单
  （Tab 切字段）、通知页、设置、添加联系人、连接到地址、全屏 `${help}`。
- 每个页面使用按终端宽度动态计算的 `=`/`-` 分隔线与上下文标题；私聊不显示重复
  的会话标题，群聊显示群名，服务器/本地列表显示真实名称/IP。
- 聊天页拆分消息区、多行消息输入区与命令区；草稿返回列表后保留，异步消息不会
  覆盖正在输入的内容。
- `terminal.mode` 显式选择 `OBSERVATORY`（默认）或 `OLD`，不再根据终端能力自动切换。
- `terminal.shiftEnterToSend` 默认 true；终端无法区分 Shift+Enter 时提供 Ctrl+S，
  界面与帮助页明确提示限制。
- 所有页面保留命令输入栏（全屏帮助页为例外，返回后恢复原页面）。

### 便携式聊天记录

- 默认数据目录改为 `<程序目录>/data/`；不依赖启动工作目录，不静默写用户主目录。
- 局域网记录位于 `data/chats/local/`；服务器记录位于
  `data/chats/servers/<IP>/<服务器标识>/`，同 IP 可保存多个服务器。
- 服务器标识由 TLS 证书指纹派生；改名不换目录；支持手工移动目录后重新识别。
- 旧 `<data>/history/*.json` 保持只读兼容，不会被自动删除。
- 原子写、损坏隔离、加密文件的会话键外层、删除范围语义保持并补充测试。

### 测试

- 新增配置默认值/旧配置兼容、便携目录/迁移/旧历史兼容、按键解码、多行编辑、
  显示宽度、Enter 策略等单元测试。
- 更新 PTY 集成测试，覆盖菜单焦点、帮助返回、极端缩放与退出。

## 1.0.0-zh（中文本地化版）

本版在原始 1.0.0 基础上完成中文本地化与两个缺陷修复；2.0.0-zh 在此基础上做
展示层与便携数据目录的增量重构。

### 缺陷修复

- **缺陷 A：Observatory 模式无法输入**
  - 根因：JLine 的 `NonBlockingInputStream.available()` 恒返回 0（未重写），
    主循环 `input.available() > 0` 永不成立，键入内容永远不被读取。
  - 修复：引入 `readInputByte()`（对 `nb.read(remaining)` 返回 -1/-2 判定无数据，
    无数据时短暂休眠后继续；非 JLine 流退回 `available()` 路径），键入可实时回显。
- **缺陷 B：拖动窗口大小导致进程崩溃**
  - 根因：`LineEditor.render` 在 `terminalWidth <= 0` 时对行文本执行
    `substring()` 抛出 `StringIndexOutOfBoundsException`，渲染异常未被捕获，
    直接杀死进程。
  - 修复：宽度钳制（`Math.max(1, terminalWidth)`）、`keep` 长度钳制、输入行
    （`inputDirty`）局部重绘、`width/height` 改为 volatile、SIGWINCH 处理
    try/catch 回退旧值、`safeRender()` 捕获 `RuntimeException` 输出诊断信息
    （连续 5 次失败才请求退出，不静默吞错）。

### 中文本地化

- 全部用户可见字符串汉化：客户端（Observatory/Old 两种界面、启动/退出流程、
  各命令摘要与用法）、服务器控制台、本地模式与服务器模式会话层、配置校验、
  加密/传输/持久化/发现/BOT API 的错误与日志。
- 保留对外契约不变：命令名与 `${...}` 记法、参数取值（on/off、local/server、
  pass/reject/over、y/n/c、add/remove、approve/reject、current/peer/all 等）、
  协议帧类型与字段、JSON 键、枚举 `name()` 输出（CONNECTED/DISCOVERED/…）、
  `@账户名`/地址/指纹/群组名/消息正文等动态数据、BOT API HTTP 路径与 JSON
  响应键值、`pbkdf2-sha256` 与 `$\{` 转义语义。
- 关键代码路径补充中文注释，便于中文使用者阅读与维护。

### 回归测试（新增）

- `core`：`LineEditorTest` 新增三个渲染回归用例（非正宽度、极窄窗口、渲染不改
  变缓冲区），覆盖缺陷 B 的崩溃路径。
- `it`：新增 `ObservatoryPtyIT`——在真实 PTY 中启动 observatory 客户端，验证键入
  实时回显（缺陷 A）、60 次密集 SIGWINCH 缩放不崩溃且窄窗口仍可键入（缺陷 B）、
  `${quit}` 以退出码 0 正常退出；环境无 python3 或无可用 PTY 设备时自动跳过。
- 既有测试断言随本地化同步更新（属合法适配），全量测试保持绿色。

### 文档（新增/汉化）

- 新增 `CHANGELOG.md`（本文件）、`docs/L10N_GLOSSARY.md`（术语约定）。
- `README.md`、`docs/OPERATOR.md` 汉化；`docs/config-examples/` 字段含义并入
  中文 README（JSON 不含注释，示例文件保持严格 JSON 以便直接使用）。

## 1.0.0（原始版本）

- 初始版本：轻量、按社区独立运行的聊天平台。
  - `ourcamellia-client`：Observatory（固定布局终端 UI）与 Old（逐行、可脚本）；
  - `ourcamellia-server`：独立服务器 + 运营者控制台（admin/announce/registration/
    review/mod-delete/bot-create/policy/stats/quit）；
  - 本地模式（Local mode）：UDP 发现、TLS（TOFU 指纹固定）、好友门槛、
    观察者相对状态；服务器模式（Server mode）：账户注册与审批策略、好友、
    运营者可见/加密群组、离线通知投递、公告频道、BOT API（HTTP 适配器）；
  - 安全与隐私：PBKDF2-HMAC-SHA256 密码哈希、二级密码密封本地历史、
    SHA-256 令牌摘要、审查日志有界、不泄露 IP、加密群组服务端仅存密文。