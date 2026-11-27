# All In 12 家既有供应商与上游源码（`npm-plugins` / `magpie`）具体实现差异对照

- **核对基线**：
  - **All In 源码**：`C:\gemini\All-in-main`（当前已合并 `1.2.119-test / 127`）
  - **上游插件集**：`C:\gemini\npm-plugins`（`@magpie-community/opencode-*-auth`，同步报告版本见各包）
  - **上游内核**：`C:\gemini\magpie`（Go 核心 `internal/provider/`、`internal/gateway/`、`internal/qoder/`）
- **范围约束**：严格仅对照截图中的 **12 家** All In 已有供应商源码，不包含 All In 尚未接入的其余上游插件（如 Cursor、Kiro、Claude Code、Factory、CodeArts、SenseNova 等）。

---

## 一、总览矩阵（12 家供应商对齐程度与主要差异）

| # | 供应商（All In ID） | All In 核心实现文件 | 上游对照来源 | 整体对齐度 | 最核心具体差异摘要 |
|---:|---|---|---|---|---|
| 1 | **Codex** (`codex`) | `CodexAuth.java`, `CodexRequestPolicy.java`, `CodexClientCompatibility.java`, `CodexChatProjection.java`, `CodexHttp1Connection.java`, `CodexImages.java` | `magpie` 内核（`codex_*.go`；`npm-plugins` 无此插件） | 较深移植，协议/指纹略滞后 | 1) 请求 UA 仍为 `0.159.0`（目录 `0.169.1`），`magpie` 统一 `0.169.1` 且支持切换 `Codex Desktop` UA/originator；2) 缺 `zstd` level 3 请求体压缩；3) 缺无回调 `Device Code` 登录（`codex login --device-auth`）；4) Guard 有硬上限时直接拒发（`UnpreservableOutputLimitException`） |
| 2 | **Cline** (`cline`) | `ClineAuth.java`, `ClineFailurePolicy.java`, `ClineRoutingPolicy.java` | `npm-plugins/opencode-cline-auth` + `magpie/internal/provider/cline*.go`, `internal/gateway/cline_pin.go` | 高（与 `npm-plugins` 一致） | 1) 客户端指纹跟 `opencode-cline-auth` 一致（`cline-cli/3.0.68`, `core=0.0.90`），而 `magpie` 内核用 `cline-desktop/3.5.54`；2) `ClineRoutingPolicy` 默认不注入 OpenRouter `provider.only` 固定；3) `ClineFailurePolicy` 输出脱敏自有文案而非透传上游 `current_balance` / `buy_credits_url` |
| 3 | **GitHub Copilot** (`github-copilot`) | `CopilotAuth.java`, `GatewayServer.java` | `npm-plugins/opencode-copilot-auth` + `magpie/internal/provider/copilot_*.go`, `internal/gateway/copilot_*.go` | 中高 | 1) 缺 GitHub Enterprise（`<name>.ghe.com`）域名认证与 `copilot-api.<name>.ghe.com` 路由；2) 缺 `copilot_gemini_tools_fit`（修复 Gemini 工具 schema 悬空 `required` 与同名去重）；3) 缺 `copilot_opaque`（保存与回填 `reasoning_opaque` 密文块）；4) `parseUsage` 细节略有简化 |
| 4 | **CatPaw** (`catpaw`) | `CatPawAuth.java`, `CatPawStream.java`, `CatPawToolBridge.java` | `npm-plugins/opencode-catpaw-auth` | 极高 | 1) 静态表未标 `longcat-2.0 (限时免费)`（动态目录正常解析 `rateMultiplier`/`free`）；2) 缺强制工具首轮文本输出后的隐式二次 `runTurn` 重试；3) 缺桌面端本地 `auth.json` 读取（手机环境不适用）；4) All In 新增 `125` 空回答报错拦截 |
| 5 | **Raccoon** (`raccoon`) | `RaccoonAccountService.java`, `RaccoonLoginProtocol.java`, `RaccoonOAuthClient.java`, `RaccoonRefreshPolicy.java`, `RaccoonRefreshSend.java`, `RaccoonRefreshService.java`, `RaccoonResponsePolicy.java` | `npm-plugins/opencode-raccoon-auth` | 高 | 1) 积分窗口：插件按 `Daily pool (300×7=2100)` + `Credits`（含近过期回算与 `topBalance` 持久缓存）排序，All In 主要读可用/每日余额与账单核验；2) 插件额外调 `/api/web/auth/v1/user_info` 将手机号脱敏为 `138****8000` 作卡名；3) All In 补全 `RaccoonResponsePolicy`（回写 `model` 与 `X-Magpie-Sign-In`）与防重复首登补贴账本 |
| 6 | **WorkBuddy** (`workbuddy` / `workbuddy-ai`) | `WorkBuddyAuth.java`, `WorkBuddyPolicy.java`, `WorkBuddyRequestPolicy.java`, `WorkBuddyHarnessPolicy.java`, `WorkBuddyNative*`, `WorkBuddyCheckin.java`, `WorkBuddyFailure.java` 等 15 个类 | `npm-plugins/opencode-workbuddy-auth` + `magpie/internal/provider/workbuddy*.go` | 极高（已对齐至 5.9.3 / 0.2.37） | 1) All In 固定仅允许 `copilot.tencent.com` 与 `www.workbuddy.ai`，未支持插件 `0.2.32+` 的云托管企业域名（`*.sso.copilot.tencent.com`、`*.copilot.qq.com`、`*.sso.codebuddy.cn`）；2) All In 独家新增 `126` 固定词表失败诊断（`WorkBuddyFailure`）；3) `workbuddy-ai` 屏蔽 `^glm-` 图片与 `deepseek-v4.1-flash` 关闭思考需核对 |
| 7 | **Trae CN** (`trae-cn`) | `TraeAuth.java`, `TraeProtocol.java`, `TraeModels.java`, `TraeDevice.java`, `TraeStream.java`, `TraeResponseFormat.java`, `TraeAccounts.java`, `TraeControl.java`, `TraeHttpTransport.java` | `npm-plugins/opencode-trae-auth` + `magpie/internal/provider/trae_checkin.go` | 中高（CN 消费版极高） | 1) All In 明确仅做国内个人消费版（`trae-cn`），显式拒绝企业 SaaS 控制台（`enterprise_unsupported`）且不含国际版 `trae-global`（SG/US 双区）；2) 客户端参数完全对齐 `SOLO CN 0.1.69` 与 5 个 `FUNCTIONS`；3) All In 采用独占 `TraeHttpTransport` + 严苛 JSON 校验，拒绝非白名单字段 |
| 8 | **Loomy** (`loomy`) | `LoomyAuth.java`, `LoomyChatRefusal.java`, `LoomyImagePolicy.java`, `GatewayServer.java` | `npm-plugins/opencode-loomy-auth` | 高（含 0.1.12） | 1) 聊天已对齐 `0.1.12`（仅凭 `020002`/`100002` 判定登出、支持 `/v1/images/generations`、支持旧 DeepSeek `<image_base64>`）；2) `LoomyAuth.request`（读模型/查积分/签到路径）仍对 HTTP `401/403` 直接置 `expired=true`，未像插件 `deniedStatus` 一样防代理误踢；3) 缺「内容太长了」→「上下文超出长度限制」错误改写及 `foldReasoning` |
| 9 | **MiniMax Code** (`minimax-code`) | `MiniMaxAuth.java`, `MiniMaxAllowance.java`, `MiniMaxLifecycle.java`, `MiniMaxMedia.java`, `MiniMaxMediaInput.java`, `MiniMaxRefreshJournal.java` | `npm-plugins/opencode-minimax-auth` + `magpie/internal/provider/minimax_checkin.go`, `draw_minimax.go`, `video_minimax.go` | 高（国内全套） | 1) All In 仅支持国内站 `minimax-code`（`.cn`/`.com`），缺国际站 `minimax-code-global`（`.io`）及 Akamai `307` 跨区引导提示 `OtherSite`；2) 缺 `/mavis/api/v1/mcp/web_search` 联网搜索钩子；3) All In 已补齐 M Plan 用量、积分明细、Matrix 生图/生视频、`x-signature` 签名及 `1M/524K` 上限修正 |
| 10 | **Antigravity** (`antigravity`) | `AntigravityAuth.java`, `AntigravityProtocol.java`, `AntigravityVersion.java`, `AntigravityClient.java`, `AntigravityEvents.java`, `AntigravitySchema.java` | `npm-plugins/opencode-antigravity-auth`（0.2.4）+ `magpie/internal/provider/antigravity_*.go`, `google.go`, `codeassist.go`（PC 上另有 `opencode-agy-cli-auth`） | 极高（直连 Code Assist 模式） | 1) All In 走 `opencode-antigravity-auth` Cloud Code Assist 直连模式（不移植仅限桌面的 `agy` 二进制 CLI 桥 `opencode-agy-cli-auth`）；2) 移动端专为绕开 Android OkHttp ALPN `h2` reset，手写了锁定 `http/1.1 + TLSv1.2` 的 `SSLSocket` 客户端并支持 `REQ_BASE`/`LOAD_BASE` 自动切主备；3) 缺 `magpie` 的同名努力词模型家族合并（`EffortFamily`：将 `-low/-medium/-high` 折叠成单模型+档位）；4) 已同步 `antigravity/hub/2.32.1` + 动态版本拉取与 5 条防 429 零宽混淆词 |
| 11 | **ZCode** (`zcode`) | `ZCodeAuth.java`, `ZCodeAnthropic.java`, `ZCodeProviderConfig.java`, `ZCodeGift.java`, `ZCodeGiftControl.java`, `ZCodeGiftBusyPolicy.java`, `ZCodeToolSearchPolicy.java`, `ZCodeRefusalPolicy.java`, `ZCodeRequestPolicy.java`, `ZCodePlanGate.java` | `npm-plugins/opencode-zcode-auth` + `magpie/internal/provider/zcode*.go`, `internal/gateway/zcode_trial.go` | 高（且有本地链路定制） | 1) **核心端点策略不同**：上游套餐统一走 Anthropic（`api.z.ai/api/anthropic` 或 `open.bigmodel.cn/api/anthropic`）；All In 因实测国内网络直连 `api.z.ai` Anthropic 易在 TLS 阶段 EOF 切断，主动把套餐默认改走 OpenAI `/api/coding/paas/v4` 并支持启动后台自动探活切换（`calibrateZCodeBaseAsync`），仅免费 Start Plan 走 `zcode.z.ai/api/v1/zcode-plan/anthropic`；2) **缺 Team 团队席位支持**（`personalProject` 显式跳过 `projectType == "2"`，不读 `zcode-team-api-key`）；3) All In 对旧版 `/billing/preview` 与新版 `/marketing/touch` 领取逻辑、阿里云验证码检测、`ToolSearch` 改名兼容均高度对齐 |
| 12 | **Qoder** (`qoder_cn`) | `QoderAuth.java`, `QoderProtocol.java`, `QoderCatalog.java`, `QoderUsage.java`, `QoderCredits.java`, `QoderRefusal.java`, `QoderCheckin.java`, `QoderStream.java`, `QoderTextTools.java`, `QoderFailurePolicy.java`, `QoderModelIdentity.java` | `npm-plugins/opencode-qoder-auth` + `magpie/internal/qoder/endpoints.go`, `internal/provider/qoder*.go`, `internal/gateway/qoder_session.go` | 中高（CN 消费版极高） | 1) **站点覆盖范围**：上游支持 4 个站（`qoder` 国际、`qoder-cn` 国内、`qwenwork`、`qwenwork-enterprise`）及国内企业 **VPC 私有化域名**（`<instance>.vpc.qoder.com.cn`）；All In 明确**只实现 `qoder_cn` 国内消费版**（`qoder.cn` / `openapi.qoder.com.cn` / `gateway.qoder.com.cn`），排除国际站、千问办公与企业 VPC；2) All In 新增 `QoderCheckin` 双端核验机制与独立 `QoderCredits` 3 池（`Credits` / `Add-on credits` / `Shared credits`）消耗联动，其余 COSY 签名、自定义字母表编解码、`COSY_VERSION=1.1.69`、XML 工具解析与静态模型会话哈希均逐一对齐 |

---

## 二、12 家供应商逐家具体实现差异对照表

### 1. Codex (`codex`)

> 上游依据：`magpie/internal/provider/codex_*.go`、`magpie/internal/gateway/codex_*.go`（`npm-plugins` 无独立插件）  
> All In 实现：`CodexAuth.java`、`CodexRequestPolicy.java`、`CodexClientCompatibility.java`、`CodexChatProjection.java`、`CodexHttp1Connection.java`、`CodexImages.java`

| 对比维度 | All In 当前实现 | 上游 `magpie` / `npm-plugins` 实现 | 具体差异与影响 |
|---|---|---|---|
| **请求客户端版本与 UA** | `CODEX_VERSION = "0.159.0"`，仅拉 `/models` 用 `CATALOG_VERSION = "0.169.1"`；`USER_AGENT` 固定为 `codex_cli_rs/0.159.0 (Linux; aarch64) xterm-256color`，`ORIGINATOR = "codex_cli_rs"` | `codexClientVersion = "0.169.1"`（并可取本机已装最新 Codex CLI 版本与客户端转发 `version` 头中的较新值）；支持切换 `CodexClient() == "desktop"` 时发 `originator: Codex Desktop` 及 `Codex Desktop/0.166.0 (...) unknown (Codex Desktop; 26.1026.10807)` | All In 推理请求自报 `0.159.0` 略旧于最新列表要求的 `0.166.0+`；且不支持模拟 `Codex Desktop`（上游指出部分模型按 originator 分流） |
| **请求体压缩 (`zstd`)** | 发送明文 JSON 至 `https://chatgpt.com/backend-api/codex/responses`（未做 zstd 压缩） | `codex_zstd.go` 对 `POST /backend-api/codex/responses` 使用 zstd level 3（2 MiB 窗口、关闭 CRC）压缩请求体并加 `Content-Encoding: zstd` | 上游实测超长文本（如 ~1.1MB）明文易被后端回 `503`，zstd 压缩后稳定 `200`；All In 长上下文场景更易触发体积拒绝 |
| **登录认证通道** | 仅支持 OAuth 2.0 PKCE 浏览器回调（`localhost:1455/auth/callback`）与粘贴 `auth.json` / `refresh_token` / `sk-*` API Key | 除 OAuth PKCE 外，还实现了无回调 **Device Code 登录**（`codex_device_signin.go`，调 `/api/accounts/deviceauth/usercode` 与 `/token`，验证码页面 `auth.openai.com/codex/device`） | All In 手机端无内置 headless/纯设备码方式，必须依赖 WebView/回调捕获 |
| **输出上限 (`Guard`) 处理** | `CodexRequestPolicy` 删除 `max_output_tokens` / `max_completion_tokens`，保留 `max_tokens`；若 **Context Guard 开启且 `guardAppliedLimit > 0`**，直接抛 `UnpreservableOutputLimitException` 拒绝发送 | 对 Codex Responses 做兼容整形并按后端特性发包，不做本地硬拒发拦截 | All In 更保守严格：启用 Guard 限流时宁可拒发也不允许“伪执行” |
| **传输层协议** | 新增专用 `CodexHttp1Connection` 控制 HTTP/1.1 | 原生 Go `http.Client` + `codex_http1.go` + 支持 `/responses` WebSocket (`responses_ws.go`) | All In 不支持 Codex WebSocket `/responses` 链路，统一 HTTP/1.1 SSE |
| **已对齐部分** | 均支持 `ClientID = app_EMoamEEZ73f0CkXaXp7hrann`、PKCE、`session-id`/`thread-id` 同步（去除了旧下划线名）、`system→developer` 映射、`strict=false` 函数默认值、`service_tier=fast→priority`、`CodexImages` 生图、`/wham/usage` 额度查询与 `/models?client_version=0.169.1` | 同左 | 核心协议与图像能力已对齐 |

---

### 2. Cline (`cline`)

> 上游依据：`npm-plugins/opencode-cline-auth/source/index.mjs`、`magpie/internal/provider/cline*.go`、`magpie/internal/gateway/cline_pin.go`  
> All In 实现：`ClineAuth.java`、`ClineFailurePolicy.java`、`ClineRoutingPolicy.java`

| 对比维度 | All In 当前实现 | 上游 `npm-plugins` / `magpie` 实现 | 具体差异与影响 |
|---|---|---|---|
| **客户端身份伪装头** | 走 `opencode-cline-auth` 路线：`X-CLIENT-TYPE: cline-cli`、`X-CLIENT-VERSION: 3.0.68`、`X-PLATFORM: cli`、`X-PLATFORM-VERSION: 3.0.68`、`X-CORE-VERSION: 0.0.90`、`User-Agent: Cline/3.0.68` | `npm-plugins` 与 All In 完全相同；而 `magpie/internal/provider/cline.go` 已改发桌面端头：`X-CLIENT-TYPE: cline-desktop`、`X-CLIENT-VERSION: 3.5.54`、`User-Agent: Cline/3.5.54` | 桌面端 vs CLI 端会拿到不同版别的免费模型 feed 排序/过滤，当前 All In 与 `npm-plugins` 同步 |
| **OpenRouter 供应商锁定 (`PinUpstream`)** | `ClineRoutingPolicy.apply()` 当前为空实现（注释：Cline 已拒绝 `providerOptions`，默认不自动插字段） | `opencode-cline-auth` 与 `magpie/gateway/cline_pin.go`：已移除 `providerOptions`，改为显式开启 `pinUpstream` 且模型在 OpenRouter 列表中时追加 `"provider": {"only": [up]}` | All In 默认让 Cline 自行路由，无用户开关强制把 `glm-5.3-flash` 等锁到特定上游供应商 |
| **失败分类与报错文案** | `ClineFailurePolicy.classify()` 优先级、整词匹配和正则（`ROUTING_REJECTION`、`NOT_SUBSCRIBED`、`SIGNED_OUT`、`LIMIT`）与上游逐条对齐，但**不透传上游正文**（统一返回脱敏中文提示） | `opencode-cline-auth` 的 `failure()` 会拼接上游原文、余额精确数值（`$x.xx`）和 `buy_credits_url` | All In 出于隐私脱敏策略刻意隐去了上游原始报错体与剩余美元数 |
| **任务会话与刷新保护** | 支持 `X-Task-ID`（基于 session SHA-256 构造 UUID）、WorkOS Device 流 + `/api/v1/auth/register`、单次旋转 refresh token 串行锁与 400 `invalid_grant` 判失效 | 完全一致（含 `RETRYABLE_GRACE = 30s` 瞬断宽限、`spent` token chain `held` 缓存、`users/me/plan/usage-limits` + `users/{id}/balance` 双查询） | 认证、刷新和额度读取高度对齐 |

---

### 3. GitHub Copilot (`github-copilot`)

> 上游依据：`npm-plugins/opencode-copilot-auth/source/{index,endpoints,usage}.mjs`、`magpie/internal/provider/copilot_*.go`、`magpie/internal/gateway/copilot_*.go`  
> All In 实现：`CopilotAuth.java`、`GatewayServer.java`

| 对比维度 | All In 当前实现 | 上游 `npm-plugins` / `magpie` 实现 | 具体差异与影响 |
|---|---|---|---|
| **GitHub Enterprise (GHE) 企业云支持** | 仅写死公网 `github.com` / `api.github.com` / `api.githubcopilot.com`（`CopilotAuth.java` 无 `enterpriseUrl` 字段与校验逻辑） | `opencode-copilot-auth` 与 `magpie/provider/copilot_ghe.go` 完整支持 `<name>.ghe.com`：自动拼 `https://<name>.ghe.com/login/...`、`https://api.<name>.ghe.com/copilot_internal/v2/token` 与 `https://copilot-api.<name>.ghe.com` | All In 尚不支持企业数据驻留版 Copilot 账号登录与转发 |
| **Gemini 工具 Schema 净化** | 未实现同名工具去重与无对应 `properties` 的悬空 `required` 清理 | `magpie/gateway/copilot_gemini_required.go` 对 Copilot 下 Gemini 模型自动裁剪未定义在 `properties` 中的 `required` 字段，并移除重复同名工具 | 通过 Copilot 调 Gemini（尤其挂载了不规范 MCP 工具时），All In 可能因参数校验被 Copilot 返回 `400 invalid request body` |
| **`reasoning_opaque` 密文状态保持** | 未实现 `reasoning_opaque` 本地持久化存储与续聊回填 | `magpie/gateway/copilot_opaque.go` 在本地 `copilot-reasoning/` 按 `(user, model, tool_call_id)` 缓存 Copilot 返回的 `reasoning_opaque`，下一轮工具回调时自动补回对应 assistant 消息 | 使用 Copilot 下某些带密封推理的模型（如 Gemini）多轮工具调用时，All In 会损失前一轮隐式推理上下文 |
| **指纹版本与多协议支持** | 指纹 `GitHubCopilotChat/0.68.0`、`vscode/1.140.0`、`copilot-chat/0.68.0` 完全一致；支持 `/chat/completions`、原生 `/responses` 与 `/responses/compact`、`X-Initiator`（含 `ALTERNATE_INPUT_TYPES` 判断）、`Copilot-Vision-Request`、`/copilot_internal/user` 额度解析 | 完全一致 | 除 GHE 与两个高级网关中间件外，主协议与指纹版本完全同步 |

---

### 4. CatPaw 妙手 (`catpaw`)

> 上游依据：`npm-plugins/opencode-catpaw-auth/source/index.mjs`（v0.2.3）  
> All In 实现：`CatPawAuth.java`、`CatPawStream.java`、`CatPawToolBridge.java`、`GatewayServer.java`

| 对比维度 | All In 当前实现 | 上游 `npm-plugins` 实现 | 具体差异与影响 |
|---|---|---|---|
| **客户端身份与通信协议** | `CatX` / `CATX_APP`、`axios/1.19.0`、`m-appkey: com.sankuai.mcopilot.cli`、`x-agent-version: 1.2.0-beta.6`、`round → event(running) → turn(SSE)` 累积差分转 delta、10 秒 prime 检查、`/api/agent/maas/model-types` 动态拉取 | 完全一致 | 协议、请求头、余额接口域 (`catx.nocode.cn` + `X-Auth-Token`) 与 `402` 余额门控判定完全对齐 |
| **强制工具（`required`/`any`）失败重试** | `CatPawStream` 与 `CatPawToolBridge` **已禁止**任何失败/空回答/未确认回合重发；若未产出合法工具调用则直接报错（`502`） | 若请求 `tool_choice` 为 `required` 且首轮解析不出工具调用，插件会在首帧流尚未发出的缓冲阶段隐式追加一轮 `runTurn` 纠正提示再试一次 | All In 优先遵守严格的“单次不重发”契约（避免无确认重复扣积分），上游则偏重提高强工具调用成功率 |
| **空回答 / 纯空白终态拦截** | **All In 领先/合并修复（125）**：在 `CatPawStream.done()` 与 `CatPawToolBridge` 新增 `text.trim().isEmpty()` 校验，纯空白或只有思考链没有可见正文直接报异常且不发成功终态 | 上游暂未专门拦截“仅有空白文本 `finished:true`”分支 | All In 额外修复了空回答误判成功的问题 |
| **桌面端凭据复用与流心跳** | 移动端无妙手桌面端文件，仅保留网页端 `poll-token?sid=` 通道 + 手动粘贴 token；`CatPawStream` 不输出 SSE `: ka\n\n` 注释心跳 | 支持读取本机妙手桌面端 `auth.json`（`5` 分钟缓存）；等待首帧期间每 3 秒输出一次 `: ka\n\n` 保活客户端超时 | 属于平台形态差异与保活增强差异 |

---

### 5. Raccoon 商汤小浣熊 (`raccoon`)

> 上游依据：`npm-plugins/opencode-raccoon-auth/source/index.mjs`（v0.1.14）  
> All In 实现：`RaccoonAccountService.java`、`RaccoonLoginProtocol.java`、`RaccoonOAuthClient.java`、`RaccoonRefresh*.java`、`RaccoonResponsePolicy.java`

| 对比维度 | All In 当前实现 | 上游 `npm-plugins` 实现 | 具体差异与影响 |
|---|---|---|---|
| **积分与账单调度窗口** | `RaccoonAccountService` 读 `/api/web/points/v1/balance`（兜底 `/api/web/office/v3/setting_info`）获取 `available_points` / `daily_points`；分 5 页读账单仅做 `daily_grant` 签到对账与一次性 `desktopOnboardingSettled` | 构建双层路由窗口：`Daily pool`（`300/天 × 7天 = 2100` 上限、7 天滚动过期 `runsOutAt`）+ `Credits`（汇总账单所有未过期激励包、对已过期但仍在余额中的最新包回算、记录 `topBalance` 本地持久最大值防分母缩水） | 上游 Smart Routing 能准确把“每日即将过期积分多的账号”优先排在最前；All In 侧重余额数值展示与防重复签到对账 |
| **账号显示名（手机号脱敏）** | `RaccoonLoginProtocol` 依次取 JWT `name` → `Raccoon-<uid>` → `"商汤小浣熊"` | 登录和刷新成功后主动调 `GET /api/web/auth/v1/user_info`（带 `6h` 缓存），把 `data.phone` 脱敏成 `138****8000` 作为账号显示名 | 上游对短信注册账号展示更直观，不显示一串 `Raccoon-pu-<uuid>` |
| **桌面端目录自动扫描** | 不扫描 PC 本地 `.office-raccoon` / `.box-agent` | 自动依次探测 Windows/macOS `.office-raccoon/agent/config/auth.json` 与 `.box-agent/config/auth.json` | 平台差异，Android 仅走浏览器授权码登录与 JWT 粘贴 |
| **响应重写与刷新控制** | 在 `GatewayServer` 全量启用 `RaccoonResponsePolicy`（回写请求 `model` 名并加 `X-Magpie-Sign-In: kept/renewed/expired`）+ 独立 `RaccoonRefreshPolicy`（单飞 + 不重试可能已消费的 refresh POST） | 功能完全对应 | 核心登录、刷新安全与响应重写行为高度对齐 |

---

### 6. WorkBuddy (`workbuddy` / `workbuddy-ai`)

> 上游依据：`npm-plugins/opencode-workbuddy-auth/source/index.mjs`（v0.2.37）、`magpie/internal/provider/workbuddy*.go`  
> All In 实现：`WorkBuddyAuth.java`、`WorkBuddyPolicy.java`、`WorkBuddyRequestPolicy.java`、`WorkBuddyHarnessPolicy.java`、`WorkBuddyNative*.java`、`WorkBuddyCheckin.java`、`WorkBuddyFailure.java` 等

| 对比维度 | All In 当前实现 | 上游 `npm-plugins` 实现 | 具体差异与影响 |
|---|---|---|---|
| **云托管企业域名路由** | `WorkBuddyAuth.origin()` 与 `WorkBuddyPolicy.endpointHeaders()` 硬编码只允许两个域名：`copilot.tencent.com` 与 `www.workbuddy.ai` | `opencode-workbuddy-auth (0.2.32+)`：若登录凭据的 `domain` 属于云托管企业模式（`*.sso.copilot.tencent.com`、`*.copilot.qq.com`、`*.sso.codebuddy.cn`，iOA `tencent.*` 除外），推理请求直接发往 `https://<domain>` | 若用户登录的是腾讯企业云托管版 WorkBuddy，All In 会误发回公网域名导致认证被拒 |
| **失败阶段固定词表诊断** | **All In 独有/合并增强（126）**：新增 `WorkBuddyFailure` 与 `UsageLedger` 的 `failure_phase` / `failure_cause` 固定词表追踪与 CSV 导出 | 上游插件无此结构化账本诊断字段（`WorkBuddyFailure` 为本工程 126 新增） | All In 在多账号/流中断（如 EOF、Headers 超时、工具空终态）下能精确归因不污染隐私 |
| **WorkBuddy AI 模型禁图 & DeepSeek 关闭思考** | 确认一下是否在 `GatewayServer`/`WorkBuddyChatPolicy` 处理；目前 `WorkBuddyRequestPolicy` 已对齐：1) `deepseek/kimi-k3.1-pro` 补空格防 tool-call 拒收；2) `max_tokens` 与 `max_completion_tokens` 非取 min 语义；3) DSH harness 文本改写；4) CN 版 Claude 走 `/v1/messages` 原生传输与签名恢复 | 除上述外，显式标记 `workbuddy-ai` 站点所有 `^glm-` 模型为 `textOnly`（防服务端悄悄吞图并注入警告）；在 `deepseek-v4.1-flash` (`-sg`) 上支持用 `reasoning_effort: "none"` 彻底关思考（否则将 `"none"` 转为 `"low"`） | 主协议、5.9.3/CLI 2.165.0、`X-Agent-Runtime: local-agent`、turnId 计算及 native Messages 均已与 `0.2.37` 对齐 |

---

### 7. Trae CN (`trae-cn`)

> 上游依据：`npm-plugins/opencode-trae-auth/source/index.mjs`（v0.2.50）、`magpie/internal/provider/trae_checkin.go`  
> All In 实现：`TraeAuth.java`、`TraeProtocol.java`、`TraeModels.java`、`TraeDevice.java`、`TraeStream.java`、`TraeResponseFormat.java`

| 对比维度 | All In 当前实现 | 上游 `npm-plugins` 实现 | 具体差异与影响 |
|---|---|---|---|
| **站点覆盖（Global & 企业版）** | `TraeAuth.java` 明确限定 **Consumer Trae CN only**：只接 `trae.cn` / `api.trae.cn` / `trae-api-cn.mchost.guru`，明确拒绝企业控制台域（`enterprise_unsupported`）且不含国际版 `trae-global` | 同时包含 **Trae CN**（含 SaaS 企业版 `console.enterprise.trae.cn` 按元席位池 + 消费额度兜底切换 `consoleChat: false`）与 **Trae Global**（`trae.ai` SG/US 双区自动按账号地区路由、美元计费额度读取） | All In 当前仅覆盖国内个人免费/积分版 `trae-cn`，海外用户或国内企业版 SaaS 席位尚不支持 |
| **版本指纹与设备绑定** | 与上游 100% 同步：`clientVersion: 0.1.69`、`20260917`、`en1oxy7wnw8j9n` (`SOLO_PC 0.1.64`) / `ono9krqynydwx5` (`IDE_PC 3.3.99`) 优先尝试 P-256 `DeviceProof` 绑定（不消耗 refresh token，防止签到报错 `9074`） | 完全一致 | 刷新与设备绑定防签到 9074 机制完全同步 |
| **模型目录与 `-max` 派生** | `TraeModels.java` 拉取 5 个 function（`chat_v3`, `solo_work_lite`, `solo_agent`, `solo_agent_lite`, `solo_coder`）并自动生成上下文更大的 `<model>-max` 衍生 ID | 完全一致 | 模型同步与 `-max` 逻辑逐条对齐 |
| **报文校验与错误码 `9074`/`9095`** | 在 `TraeProtocol` 做严格 JSON 语法与白名单字段校验；遇到签到 `9074` 直接报 `ineligible` 不额外重试备用包 | 同样识别 `9074`/`9095`，且企业控制台聊不通（`4245`）时自动降级回消费网关 | 核心消费版协议、文本/Qwen/Anthropic `<function_calls>` 工具解析已高度对齐 |

---

### 8. Loomy 讯飞 (`loomy`)

> 上游依据：`npm-plugins/opencode-loomy-auth/source/index.mjs`（v0.1.12）  
> All In 实现：`LoomyAuth.java`、`LoomyChatRefusal.java`、`LoomyImagePolicy.java`、`GatewayServer.java`

| 对比维度 | All In 当前实现 | 上游 `npm-plugins` 实现 | 具体差异与影响 |
|---|---|---|---|
| **非聊天请求 `401/403` 防误踢** | `LoomyChatRefusal`（聊天通道）已升级到 `0.1.12`：仅见业务码 `020002`/`100002` 才判登出；但 **`LoomyAuth.request()`（第 107 行）仍会在 `sessionRequest && (status==401 \|\| status==403)` 时直接抛 `Failure(..., true)` 判过期** | `0.1.12` 整个插件体系统一按业务码（`020002`/`100002`）判定失效，代理或防火墙返回的裸 401/403 始终标 `signIn: "kept"` 防止“轮着掉登录” | All In 在查模型列表/查积分/签到时若经过返回 401/403 的中转/代理节点，仍存在误踢账号风险 |
| **长上下文错误词改写** | 未见到 `"内容太长(了)?\|内容过长"` → `"上下文超出长度限制"` 的响应体改写 | `retryableRefusal()` 自动把 400/413/422 中未含“上下文”三字的「内容太长了」改写为「上下文超出长度限制」，让上游/客户端及时压缩历史而非反复轮询其余账号导致全池 502 | 若用户启用自动上下文压缩或客户端依文案触发压缩，All In 上 Loomy 的中文超长提示可能触发不必要账号冷却 |
| **思考档折叠 (`foldReasoning`)** | 未在 `LoomyAuth`/`GatewayServer` 见到独立 `foldReasoning` 将多档 reasoning 折叠到 Loomy 支持的 3 档 | `foldReasoning(chat)` 在请求发往 `/api/v1/chat/completions` 前把客户端传入的扩展 reasoning 档位折叠为 Loomy 原生支持的 3 档 | 使用非标思考强度参数发往 Loomy 时兼容性略差 |
| **已对齐部分** | HMAC-SHA1 + Content-MD5 账号域签名（`account.xfinfr.com`、`GM3LOOMY`）、`LoomyImagePolicy`（DeepSeek 旧版 `<image_base64>` 标签包裹）、`/v1/images/generations` 文生图、双积分钱包 (`daily` + `permanent`)、每日首登刷新 | 同左 | 短信登录、绘图、图片标签适配、业务码拒登判定与双钱包读取均已同步 |

---

### 9. MiniMax Code (`minimax-code`)

> 上游依据：`npm-plugins/opencode-minimax-auth/source/{index,native,web-tools,connector-tools}.mjs`、`magpie/internal/provider/minimax_checkin.go`  
> All In 实现：`MiniMaxAuth.java`、`MiniMaxAllowance.java`、`MiniMaxLifecycle.java`、`MiniMaxMedia.java`、`MiniMaxMediaInput.java`、`MiniMaxRefreshJournal.java`

| 对比维度 | All In 当前实现 | 上游 `npm-plugins` 实现 | 具体差异与影响 |
|---|---|---|---|
| **国际版站点 (`minimax-code-global`) 与 307 跨区防护** | 仅支持国内站 `minimax-code`（`account.minimax.cn`、`agent.minimax.cn`、`www.minimaxi.com`），无国际版 `.io` 站点与双站枚举；已禁用 `followRedirects` 防凭据泄露 | 同时提供 `minimax-code` 与 `minimax-code-global`（`account.minimax.io` / `agent.minimax.io` / `platform.minimax.io`），且通过 `siteFetch()` 专门识别 Akamai 返回的 `307 → agent.minimaxi.com` 并抛出明确换区提示 `OtherSite`（不触发误登出） | All In 目前不支持海外版 MiniMax Code 账号；也不处理海外账号在大陆直连时被 Akamai 307 切去国内站的提示 |
| **联网搜索 (`web_search`) 能力** | 未实现 `/mavis/api/v1/mcp/web_search` 搜索工具接口封装 | `web-tools.mjs` + `native.mjs`（`createSearchHook`）对接 `/mavis/api/v1/mcp/web_search` 供网关统一搜索路由调用 | 影响希望用 MiniMax Code 账户充当搜索源的场景（聊天推理/生图/生视频不受影响） |
| **多模态媒体与上限修正** | **已高度对齐 `0.2.9`**：`MiniMaxMedia` 完整支持 Matrix 连接器目录（`upload_temp_url`、生成图 `MiniMax-Image`、视频 `MiniMax-H3`/`H3-Max`/`Hailuo-2.3`）；`MiniMaxAuth` 已含 `x-signature`（`SIGN_SECRET`）与 `yy` 双 MD5 签名、一次性 refresh 串行防并发消费、`1048576 / 524288` M3 真实上下文/输出上限与 `MiniMaxAllowance` 积分+M Plan 额度拆分 | 完全一致 | 国内站核心推理、签名、一次性刷新防护、M Plan 与 Matrix 生图生视频已完整对齐 |

---

### 10. Antigravity (`antigravity`)

> 上游依据：`npm-plugins/opencode-antigravity-auth/source/index.mjs`（v0.2.4）、`npm-plugins/opencode-agy-cli-auth/source/*`、`magpie/internal/provider/{google,antigravity_*}.go`、`magpie/internal/gateway/codeassist.go`  
> All In 实现：`AntigravityAuth.java`、`AntigravityProtocol.java`、`AntigravityVersion.java`、`AntigravityClient.java`、`AntigravityEvents.java`、`AntigravitySchema.java`

| 对比维度 | All In 当前实现 | 上游 `npm-plugins` / `magpie` 实现 | 具体差异与影响 |
|---|---|---|---|
| **两大上游模式取舍** | 采用 **`opencode-antigravity-auth` / `magpie` 直连 Google Code Assist HTTPS 模式**，未移植 `opencode-agy-cli-auth` | 上游另有 `opencode-agy-cli-auth`（在电脑端启动并管理本地 `agy` 二进制 CLI 进程，通过 MCP/ACP 本地通信转发） | Android 无 PC 端 `agy` 可执行文件，走 Code Assist 直连是最合适移动端的架构 |
| **HTTP/1.1 传输层与双域热备** | **移动端关键定制**：为绕开 Android OkHttp ALPN 协商 `h2` 导致 `cloudcode-pa` 直接 reset 连接，`AntigravityAuth` 手写了原生 `SSLSocket`（强制 `http/1.1 + TLSv1.2`，含自写 Chunked 解码与握手 8 次重试），且支持探测后在 `daily-cloudcode-pa` 与 `cloudcode-pa` 间自动切换记忆 | `opencode-antigravity-auth` 使用 Node 原生 `fetch()` 固定分域：`loadCodeAssist` 走 `cloudcode-pa`，其余全走 `daily-cloudcode-pa` | All In 对移动/国内代理网络下的 TLS/ALPN 稳定性做了大幅加固 |
| **模型家族折叠 (`EffortFamily`)** | 面板仍倾向暴露原始变体模型或固定映射（如 `gemini-pro-agent`、`gemini-3.1-pro-low` 等独立列出） | `magpie/internal/provider/antigravity_models.go` 与 `opencode-antigravity-auth` 自动将同一族不同思考后缀（`-low`/`-medium`/`-high`/`-extra-high`/`-minimal`）折叠为单个模型 ID + 可选 `effort` 档位列表，由网关动态还原请求 ID 并回传 `x-magpie-sent-model` | 上游模型选择器更整洁；All In 直接按变体名路由更直观但列表较长 |
| **版本、Cloak 与错误解释** | 已完整同步 `0.2.4` 核心：`AntigravityProtocol.VERSION = "2.32.1"`、`CHANGE_LIST = "1021296516"`、`AntigravityVersion` 每 6 小时自动拉取 `latest-arm64-mac.yml`（失败 1 分钟重试）、5 句零宽空格 `\u200b` 防 429 Cloak、字段有序信封 `Ordered`（含 `labels.cascade_id` / `used_claude`）、403 `ACCESS_TOKEN_SCOPE_INSUFFICIENT` 诊断 | 完全一致 | 除模型家族折叠外，协议头、Cloak、动态版本同步与 OAuth 范围已基本对齐 `0.2.4` |

---

### 11. ZCode (`zcode`)

> 上游依据：`npm-plugins/opencode-zcode-auth/source/index.mjs`（v0.1.69）、`magpie/internal/provider/zcode*.go`  
> All In 实现：`ZCodeAuth.java`、`ZCodeAnthropic.java`、`ZCodeProviderConfig.java`、`ZCodeGift.java`、`ZCodeGiftControl.java`、`ZCodeToolSearchPolicy.java`、`ZCodeRefusalPolicy.java`、`ZCodeRequestPolicy.java`

| 对比维度 | All In 当前实现 | 上游 `npm-plugins` / `magpie` 实现 | 具体差异与影响 |
|---|---|---|---|
| **编码套餐基础端点与协议** | **重大分歧点**：<br>1) 编码套餐默认写向 **OpenAI 兼容地址** `https://api.z.ai/api/coding/paas/v4` / `https://open.bigmodel.cn/api/coding/paas/v4`（启动时甚至会将旧的 `api.z.ai` + `anthropic` 配置强迁过来，并运行 `calibrateZCodeBaseAsync()` 探测哪条链路不被 TLS 切断）；<br>2) **仅免费 Start Plan / 赠送额度**走 `https://zcode.z.ai/api/v1/zcode-plan/anthropic`（配 `ZCodeAnthropic` 转译） | **全程统一走 Anthropic Messages**：<br>- Z.ai 套餐：`https://api.z.ai/api/anthropic`<br>- BigModel 套餐：`https://open.bigmodel.cn/api/anthropic`<br>- Start Plan：`https://zcode.z.ai/api/v1/zcode-plan/anthropic`<br>（仅把请求作为 Anthropic 原生格式转发，且通过 `api.z.ai` / `open.bigmodel.cn` 共享同一把 key） | All In 针对国内网络“直连 `api.z.ai` Anthropic 常被 TLS EOF 切断”做了务实妥协（改走 Coding PaaS v4 OpenAI 通道），但可能造成套餐端部分 Anthropic 特有字段/缓存语义与上游不完全一致 |
| **Team 团队席位支持** | **不支持 Team 席位**：`ZCodeAuth.personalProject()`（第 1096 行）明确跳过 `projectType == "2"` 的团队项目，只创建 `zcode-api-key` | 支持 `projectType === 2` 团队项目：检索/创建 `zcode-team-api-key`，并独立保存 `org` / `project` 以查询团队套餐 | 使用 Z.ai / BigModel 团队版席位的用户无法在 All In 中获取团队 Key |
| **赠送包 (`Start Plan` / `Gift`) 与工具兼容** | **高度对齐 `0.1.69`**：`APP_VERSION = "3.17.2"`；支持旧 `/billing/preview` 与新 `/api/v1/marketing/touch` 领取；识别 `X-Aliyun-Captcha-Verify-Param` 配置并在需要验证码时直接停手交由客户端处理；支持 `ZCodeProviderConfig` 动态同步能力规则；支持 `ZCodeToolSearchPolicy` 将 `ToolSearch` ↔ `ToolSearchTool` 双向改名并清理 `defer_loading` | 完全一致（另有 `prompt.mjs` 等提示/文案辅助） | 免费活动领取、验证码守门、配置规则解析和 Claude Code `ToolSearch` 修复均已对齐最新 `0.1.69` |

---

### 12. Qoder (`qoder_cn`)

> 上游依据：`npm-plugins/opencode-qoder-auth/source/index.mjs`（v0.2.71）、`magpie/internal/qoder/endpoints.go`、`magpie/internal/provider/qoder*.go`  
> All In 实现：`QoderAuth.java`、`QoderProtocol.java`、`QoderCatalog.java`、`QoderUsage.java`、`QoderCredits.java`、`QoderRefusal.java`、`QoderCheckin.java`、`QoderStream.java`、`QoderTextTools.java`

| 对比维度 | All In 当前实现 | 上游 `npm-plugins` / `magpie` 实现 | 具体差异与影响 |
|---|---|---|---|
| **站点与 VPC 企业私有部署覆盖** | **仅实现 Qoder CN 国内公开版（`qoder_cn`）**：硬编码 `qoder.cn` / `openapi.qoder.com.cn` / `gateway.qoder.com.cn`；不接国际站 `qoder.com`、千问办公 `qwenwork`/`qwenwork-enterprise`，也不支持国内企业 **VPC 私有实例域名**（`*.vpc.qoder.com.cn`） | `opencode-qoder-auth (0.2.71)` 一包覆盖 **4 个 Provider**：`qoder`（国际版）、`qoder-cn`（国内版）、`qwenwork`（千问办公公有云）与 `qwenwork-enterprise`（自定义企业域名）；且 `qoder-cn` 支持用户输入实例名自动重写为 `<instance>.vpc.qoder.com.cn` / `<instance>-openapi.vpc.qoder.com.cn` / `<instance>-gateway.vpc.qoder.com.cn` | 国际站用户在注释里说明了国内代理环境易 DNS 劫持到 `28.0.0.0/8` 而暂弃；企业 VPC 用户目前无法在 All In 使用私有实例接入 |
| **每日签到与活动领取 (`QoderCheckin`)** | All In 专门抽出了 `QoderCheckin`：同时查询新活动页 `openapi.qoder.com.cn/sash/api/v1/me/campaigns`（`CLAIM_BENEFIT`）与旧签到状态 `gateway.qoder.com.cn/sash/api/v1/me/daily-check-in/status`（显式识别 `DISABLED` 停用状态，未明确允许绝不盲领） | 上游对应维护活动/旧签到探测并处理千问办公不同的设备头（`qoderwork/<version>` + `X-QwenWork-*`） | All In 对国内站活动与旧签到接口停用的边界判断更加严谨清晰 |
| **COSY 签名、加密Codec与三池积分** | **逐项高度对齐 `0.2.71`**：`COSY_VERSION = "1.1.69"`、`CLIENT_ID = e883ade2-...`、自定义乱序字母表 `_doRTgH...` + `swapThirds` 编解码、AES-128-CBC + RSA-1024 信封、`Cosy-MachineHostname` 安全化、`jobToken` 失败回退 `deviceToken` 直聊、确定性 `sessionId` 哈希、`QoderCredits` 联合判定三池（`Credits` + `Add-on credits` + `Shared credits`）全花光才停用 | 核心 COSY 协议、编解码与三池额度语义一致（上游额外带多站点配置切换与千问办公 RSA 公钥） | 在国内个人版（`qoder_cn`）场景下协议兼容性极高 |

---

## 三、建议优先关注的可同步项（按收益/风险排序）

1. **Loomy 查模型/查积分/签到防误踢对齐（低改动、高收益）**：当前只有聊天通道走 `LoomyChatRefusal`，而 `LoomyAuth.request()`（第 107 行）仍会在 HTTP `401`/`403` 时直接将账号标为失效（`expired=true`），可同步 `0.1.12` 的“仅凭业务码 `020002`/`100002` 判定失效”，避免代理波动把正常账号轮流踢掉登录。
2. **Codex 版本号升级、zstd 压缩与 Desktop Originator（中等改动、解决 503/模型可见性）**：将请求头默认版本从 `0.159.0` 升至与目录一致的 `0.169.1`（或支持可选 `Codex Desktop`），并为 `POST /backend-api/codex/responses` 补充 `zstd` 压缩，可减少长提示词被后端拒收 `503` 的问题。
3. **WorkBuddy 云托管企业域支持（低改动）**：在 `WorkBuddyAuth` / `WorkBuddyPolicy` 中按 `opencode-workbuddy-auth 0.2.32+` 增加 `*.sso.copilot.tencent.com`、`*.copilot.qq.com`、`*.sso.codebuddy.cn` 域名白名单放行（iOA 除外），即可兼容云托管企业账号。
4. **GitHub Copilot GHE 与 Gemini 工具 Schema 清理（中等改动）**：如有企业版 Copilot 需求或常用 Copilot 跑 Gemini + MCP 工具，可按 `copilot_ghe.go` 与 `copilot_gemini_required.go` 补齐 GHE 域名拼装与 `required` 悬空字段裁剪。
5. **ZCode Team 席位与 MiniMax Global（按需扩展）**：若计划支持 Z.ai/BigModel 团队账号或海外版 MiniMax Code，分别补齐 `projectType == "2"` 团队 Key（`zcode-team-api-key`）与 `.io` 站点+307 `OtherSite` 检测即可。
