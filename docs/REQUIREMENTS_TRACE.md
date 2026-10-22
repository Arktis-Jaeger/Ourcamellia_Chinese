# Ourcamellia — Requirements Traceability (v1)

Mapping of REQUIREMENTS.md (and security gates from SECURITY.md/HARNESS_TASK.md) to
implementation areas and tests. Status: P = planned, I = implemented, T = covered by tests,
V = verified in final audit. **Final delivery state: all rows below are I/T; the "V" rows
were additionally re-verified in the final security/requirements audit. Test totals:
common 43, core 92, client 11, server 1, it 2 = 149 tests, all green on `mvn clean package`.**

| ID | Requirement | Implementation area | Tests | Evidence (class) |
|----|-------------|--------------------|-------|--------|
| R1.1 | Complete non-GUI client+server product, CLI is real UI | `client`, `server`, `core` | `it` E2E suites | tests ✓ |
| R1.2 | Builds/runs on standard JVM; cross-platform fallback | Maven, JDK 21, JLine fallbacks | build on JDK 21 | tests ✓ |
| R1.3 | Unit/integration/protocol/persistence/security tests | all modules | full suite | tests ✓ |
| R1.4 | Build/run instructions, config examples, operator docs, limitations | README, OPERATOR, PROTOCOL, THREAT_MODEL | doc review | audit ✓ |
| R2.1 | Observatory mode: fixed layout, context status, resize, narrow, async input, cursor safety | `client.ui.ObservatoryUi` | `ObservatoryModelTest`, manual resize script | tests ✓ |
| R2.1b | Old mode: line-oriented, no ANSI, pipes/scripts, `\n` escape, same core | `client.ui.OldUi` | `OldModeEscapeTest`, parser matrix | tests ✓ |
| R2.2 | Status region fields incl. recording + INFO indicator; no Server-mode IP disclosure | `client.ui.StatusModel`, server strips IPs | `StatusModelTest`, E2E IP-disclosure test | tests ✓ |
| R2.3 | L1 mode choice; L2 list sorted A–Z then 0–9; pinned server channel; local/server entry fields | `client.ui` lists, `ServerAnnounceChannel` | sorting tests, pinned-entry test | tests ✓ |
| R3.1 | Local identity, direct address/port comms, discovery separate from connect | `core.transport`, `core.discovery` | Local E2E, discovery codec tests | tests ✓ |
| R3.2 | Discoverability/connectability independent 2×2 | `LocalPeerSettings` | matrix test | tests ✓ |
| R3.3 | Friend requests; first-contact limited to request path; no chat before acceptance | `core.app.LocalSession` | permission matrix tests | tests ✓ |
| R3.4 | Observer-relative status; UNKNOWN vs OFFLINE honesty | `core.app.PeerStatus` | status transition tests | tests ✓ |
| R4.1 | Independent server, own namespace, no federation, no IP disclosure | `server` | namespace isolation test | tests ✓ |
| R4.2 | Registration fields `@UserName/Passwd/@Email`, configurable required fields + policy auto/manual/external | `core.registration` | policy tests | tests ✓ |
| R4.2b | No guest accounts; PBKDF2 password hashing; login over TLS | `core.security` | hash format tests, plaintext-on-disk test | tests ✓ |
| R4.3 | External verifier URL interfaces, receipt contract, HMAC, idempotency, timeout, SSRF guard | `core.verifier` | verifier integration + SSRF block tests | tests ✓ |
| R4.4 | Server name vs client local alias distinct | `core.app.ServerProfile` | alias tests | tests ✓ |
| R5.1 | Friendship states pending/accepted/rejected/expired/undeliverable; no false acceptance | `core.app.friends` | state machine tests | tests ✓ |
| R5.2 | Private messaging; payload never parsed as command; local history per client | parser + `LocalHistoryStore` | recursion-negative tests | tests ✓ |
| R5.3 | Groups: create/invite/remove/leave/transfer/send; server-side membership; ordinary + encrypted modes; explicit disclosure | `core.groups`, `core.crypto.GroupCrypto` | group matrix, E2E boundary tests | tests ✓ |
| R5.3b | Built-in server group = operator announcement channel (P1) | `core.groups.AnnounceChannel` | channel permission tests | tests ✓ |
| R5.4 | BOT marker, friend list + group presence, formal API, authN/authZ, scoping, revocation, rate limits, no admin-by-default | `core.botapi` | BOT security test suite | tests ✓ |
| R6.1 | Strict whole-line command matching; trailing spaces; extra content → message; no recursion; `\${}` escape; one command per line | `core.cmd` parser | parser test matrix P01–P30 + fuzz | tests ✓ |
| R6.2 | Formal grammar documented, deterministic, non-recursive, GUI/API-usable structured args | COMMAND_GRAMMAR.md, `core.cmd` | matrix | tests ✓ |
| R6.3 | Notification commands: numeric selection, `pass`/`over`, index/permission/stale validation | `core.notify` | notification action tests | tests ✓ |
| R7.1 | Notifications: friend requests + server announcements; offline delivery; retention/expiry; persisted states; dedup stable IDs; no unrelated deletion | `core.notify` | offline/restart/dedup/expiry tests | tests ✓ |
| R8.1 | JSON local history; default recording configurable; ON=auto-persist, OFF=buffer only; re-enable resumes; manual persist; separate deletion; OFF never deletes | `core.persist.LocalHistoryStore` | recording/buffering matrix tests | tests ✓ |
| R8.2 | Exit prompt policy (narrow, P3): only when buffered content exists; yes/no/cancel; no prompt when empty or non-interactive | `client.ui.ExitPolicy` | exit-prompt unit tests | tests ✓ |
| R8.3 | Deletion scopes (P2) with confirmation; atomic writes; JSON corruption recovery; stable message IDs; delivery≠persistence status | `core.persist` | deletion-scope + corruption-recovery tests | tests ✓ |
| R9.1 | Config files + safe defaults (recording, mode, discovery, bind, name, registration, retention, group policy) | `core.config` | config defaults/validation tests | tests ✓ |
| R9.2 | Secrets never logged; logs without message bodies/passwords/tokens | `core.util.RedactingLog` | log-redaction test | tests ✓ |
| R9.3 | Helpful errors, deterministic exit, graceful shutdown, docs of ports/firewall/test server | errors + shutdown hooks + OPERATOR | shutdown test | tests ✓ |
| R10 | All acceptance criteria of REQUIREMENTS §10 | final audit | full suite | tests ✓ + audit |
| S1 | Malicious peers/malformed packets bounded | frame codec + limits | hostile-frame tests | tests ✓ |
| S2 | Impersonation/spoofed discovery | fingerprint pinning; discovery never = identity | spoof tests | tests ✓ |
| S3 | Brute-force login/BOT creds | PBKDF2 + rate limiting | rate-limit tests | tests ✓ |
| S4 | Privilege escalation, unauthorized group/notification actions | server-side authorizer | authz matrix tests | tests ✓ |
| S5 | Leaked backups/history files | documented storage; optional secondary-password AEAD | AEAD format tests | tests ✓ |
| S6 | Malicious verifier URLs (SSRF), forged receipts | SsrGuard + HMAC receipts | SSRF/receipt tests | tests ✓ |
| S7 | Terminal escape injection | input sanitization (control chars) | injection tests | tests ✓ |
| S8 | Corrupted JSON | quarantine + recovery | recovery tests | tests ✓ |
| S9 | Secondary password/group key loss | honest docs; no false recovery promise | doc review | audit ✓ |
| S10 | Operator access per group mode | ordinary=visible, encrypted=not readable; server state inspection test | boundary tests | tests ✓ |
| S11 | Resource exhaustion, spam, repeated connects | rate limits, bounded buffers, timeouts | load/limit tests | tests ✓ |
| G1 | No plaintext passwords in storage/logs | PBKDF2 only | storage/log scan test | tests ✓ |
| G2 | Secondary password ≠ login password; AEAD for protected data | P4 design | format tests | tests ✓ |
| G3 | Group mode disclosure accuracy | UI + creation flow | disclosure tests | tests ✓ |
| G4 | No silent persistence while recording OFF | LocalHistoryStore policy | persistence-boundary tests | tests ✓ |

## 增量重构（Terminal UI 与便携数据目录）

| 需求 | 实现位置 | 测试 |
|---|---|---|
| 菜单/列表焦点导航、Enter/Esc、表单 Tab | `client.ObservatoryUi` | `ObservatoryPtyIT`、双客户端 PTY 验收 |
| 动态 `=`/`-` 分隔线与窗口 resize | `client.ObservatoryUi`、`client.UiText` | `UiTextTest`、`ObservatoryPtyIT`（60 次 SIGWINCH） |
| 页面上下文标题（通知/服务器/本地/群名/私聊无标题） | `ObservatoryUi.pageTitle()`、`buildList()`、`buildChat()` | PTY 验收与源码审查 |
| 全局命令栏与聊天命令/消息分离 | `ObservatoryUi.handlePageKey/handleChatKey` | 双客户端 PTY、旧模式脚本测试 |
| `${help}` 全屏、滚动、返回原页面 | `ObservatoryUi.openHelp/closeHelp`、`HelpContent` | `ObservatoryPtyIT` |
| Enter / Shift+Enter 策略 | `ChatInput`、`ClientConfig.Terminal` | `ChatInputTest`、`ClientConfigTest`、双客户端 PTY |
| 草稿保留、异步消息不覆盖输入 | `ObservatoryUi.drafts/events` | 双客户端 PTY（draft/async/help） |
| 默认 Observatory / 显式 OLD / 不自动切换 | `ClientMain`、`ClientConfig.effectiveTerminalMode()` | `ClientConfigTest`、CLI 验证 |
| 便携数据目录（程序目录、拒绝 target/Study） | `AppPaths`、`CliSupport`、`ChatPaths` | `ClientConfigTest`、`ChatPathsTest`、CLI 验证 |
| 局域网/服务器记录分离、同 IP 多服务器 | `ChatPaths`、`LocalHistoryStore` | `PortableHistoryStoreTest` |
| 服务器标识（指纹派生）、改名/换 IP 识别、手工迁移 | `ChatPaths.serverId`、`LocalHistoryStore.registerServer` | `PortableHistoryStoreTest` |
| 旧扁平历史兼容且不自动删除 | `LocalHistoryStore.loadInto` | `PortableHistoryStoreTest`、`LocalHistoryStoreTest` |
| 原子写、损坏隔离、加密格式 | `AtomicFiles`、`LocalHistoryStore` | 既有 + 便携布局测试 |
| Study 学习版与真实源码一致 | [`Arktis-Jaeger/Ourcamellia_Study`](https://github.com/Arktis-Jaeger/Ourcamellia_Study) | 去注释 token 校验脚本（116 文件） |
