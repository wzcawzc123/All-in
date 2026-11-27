# 豆包供应商（工作模式 + 网页版）

`doubao` 供应商把豆包网页工作模式接进 All In：登录自己的豆包账号，用该账号在豆包客户端的额度/积分调用豆包聚合的模型，包括 DeepSeek、Kimi、GLM、Qwen。网关不提供账号，也不共享额度。

## 两套豆包供应商

All In 同时内置两条豆包入口，互不替代：

| 供应商 ID | 名称 | 协议 | 模型 | 用途 |
| --- | --- | --- | --- | --- |
| `doubao` | 豆包工作模式（积分/额度池） | `doubao` | 16 个聚合模型 | 使用本人豆包账号的客户端积分/额度，调用豆包、DeepSeek、Kimi、GLM、Qwen、MiMo |
| `doubao-web` | 豆包网页版（三档思考） | `doubao-web` | `doubao`、`doubao-think`、`doubao-expert` | 直连 `POST /chat/completion`，只走豆包网页对话的三档思考 |

两套共用同一条 `www.doubao.com` Cookie，但各自维护账号池、模型目录、路由和签到记录。旧版只有一个 `doubao` 时，启动迁移会识别旧网页版并改名为 `doubao-web`，保留账号与密钥，再补一个空的 `doubao` 工作模式。

## 能力边界

| 项目 | 当前行为 |
| --- | --- |
| 登录 | 手机面板 WebView 打开 `www.doubao.com/chat/`，扫码或手机号登录后点「完成入池」；也可从桌面浏览器复制整条 Cookie 手动导入 |
| 凭据 | `sessionid` 及同域 Cookie；没有 refresh，过期只能重新登录 |
| 模型 | 2026-11-27 按本机豆包客户端 2.37.16 工作模式菜单校准为 16 项：自动、豆包 2.1 Lite/Pro、DeepSeek V4.1 Flash/V4 Pro、Kimi K3/K3.1 Pro/K3.1 Pro 高速、GLM 5.4/5.3/5.3 Flash、Qwen 4 Max/Qwen 3.8 Max/Flash、MiMo V2.6 Pro/Flash |
| 协议 | `POST https://www.doubao.com/chat/completion`，SSE；`model_item_key` 决定上游模型 |
| 文本 | 支持，历史消息压平成一条用户文本 |
| 工具 | 提示词工具桥（`DeepSeekToolBridge`）：把 tools schema 写进提示词，解析 `<tool_call>`；真正执行在客户端 |
| 图片/文件 | 不支持；会在本地返回 400，不会静默丢弃 |
| 额度/积分 | 上游按账号实时扣减；接口不返回绝对余额，只返回 `used_percent`，面板不伪造余额，倍率/余额以豆包客户端为准 |
| 计费 | `maxOutputTokens` 不能由网页端强制执行，因此守卫开启该上限时直接拒绝请求 |

## 登录态与设备标识

豆包网页端的身份是 Cookie 里的 `sessionid`，但 `device_id`、`web_id`、`fp` 参与风控。账号入池时：

- `deviceId` 取账号字段；缺省用 `SHA-256(cookie)` 前 12 位十六进制稳定派生；
- `webId` 缺省复用 `deviceId`；
- `localAppId` 固定 `582478`，`aid`/`real_aid` 也是 `582478`，`bot_id` 固定 `7338286299411103781`，`mode_id` 固定 `3`；
- 公共参数按桌面客户端 2.37.16 发送：`channel=win`、`client_platform=pc_client`、`doubao_device_platform=desktop`、`pc_version=2.37.16`、`doubao_pc_version=2.37.16`、`runtime_version=3.46.10`；
- 请求体里的 `client_option.os=Windows`、`agent_task_param.device_name=USER`，不伪装成 Android；
- `X-Flow-Trace` 每次请求生成 `04-32hex-16hex-01`，不签名、不缓存。

Cookie 只保存在本机配置里，不写日志、不出现在错误响应；面板账号卡片显示的是脱敏后的账号名。

## 请求体要点

工作模式不是 OpenAI 兼容接口，关键字段：

```json
{
  "client_meta": { "conversation_id": "", "bot_id": "7338286299411103781", "local_permissions": [...] },
  "messages": [{ "local_message_id": "...", "content_block": [{ "block_type": 10000, "content": { "text_block": { "text": "..." } } }] }],
  "option": {
    "agent_mode": 1,
    "conversation_mode": 2,
    "need_create_conversation": true,
    "conversation_init_ext": { "model_item_key": "person-deepseek-v4-1-flash-261017", "reasoning_effort": "5", "mode_id": "3" },
    "model_config": { "model_item_key": "...", "model_extra_params": { "memory_profile": "300k_230", "total_window_size": 300000 }, "reasoning_effort": 5 },
    "aggregate_params": { "provider_id": "", "mode_id": "3", "agent_mode": "1", ... },
    "general_task_param": { ... }
  }
}
```

`reasoning_effort` 不是 0–4，而是豆包客户端的 3–7 档：3 低、4 中、5 高、6 极高、7 最高；`auto`/豆包 2.1 Lite 还有 8 自动。OpenAI 的 `low/medium/high/xhigh/max` 映射到 3/4/5/6/7，缺档时向上取（例如 GLM 没有 4，`medium` 落到 5）。`provider_id` 必须是空字符串，写 `doubao` 会被上游当成另一个来源。

`model_extra_params` 必须按模型发送：豆包 2.1 系列和 `auto` 是 256k/256000（`auto` 与 Pro 的 `memory_profile` 为空），DeepSeek/Kimi/GLM/Qwen 是 300k_230/300000。把 300k 一刀切发给豆包 2.1 会导致上下文档位与客户端不一致。

## SSE 解码

- 正文优先 `CHUNK_DELTA` 的 `text`；一旦出现过 `CHUNK_DELTA`，不再用 `STREAM_CHUNK` 的 `text_block`，否则同一 token 会重复；
- 没有 `CHUNK_DELTA` 时，退回 `STREAM_CHUNK.patch_op[].patch_value.content_block[]`，`block_type=10000` 取正文；
- `block_type=10040` 的 `thinking_block.streaming_title` 作为 `reasoning_content`；
- `SSE_ACK.ack_client_meta.conversation_id` 用于同一问题的会话复用（进程内 LRU，6 小时，128 条）；
- `SSE_REPLY_END.end_type=1` 的 `msg_finish_attr.brief` 是完整答案，流式增量缺失时用它补全；
- `STREAM_ERROR` 的 `error_code` 参与状态映射：`710012001` → 401，`710022004`（rate limited）→ 429，其余 → 502，不再一律 502。

## 模型 key

2026-11-27 从本机豆包客户端 2.37.16 的 `Preferences` 中 `_DesktopActionBarBriefListRequestCacheDataV1`（`modeSelectData.menuConfV2.mode_list` 工作模式）实抓，共 16 项：

| 面板模型 | `model_item_key` | 客户端倍率 | 上下文/输出 | 默认推理档 |
| --- | --- | --- | --- | --- |
| `auto` | `9` | 小额免费 | 256k / 32k | 8 自动 |
| `doubao-2.1-lite` | `seed-lite-7b` | 限时优惠 0.01x | 256k / 32k | 8 自动 |
| `doubao-2.1-pro` | `5` | 0.65x | 256k / 32k | 5 高 |
| `deepseek-v4.1-flash` | `person-deepseek-v4-1-flash-261017` | 限时优惠 0.01x | 300k / 64k | 7 最高 |
| `glm-5.4` | `person-glm-5-4` | 新上线 0.80x | 300k / 64k | 7 最高 |
| `qwen4-max` | `person-qwen4-max` | 新上线 0.47x | 300k / 64k | 5 高 |
| `kimi-k3.1-pro` | `person-kimi-k3-1-pro-261111` | 0.95x | 300k / 64k | 7 最高 |
| `kimi-k3.1-pro-fast` | `person-kimi-k3-1-pro-highspeed-261111` | 1.44x | 300k / 64k | 7 最高 |
| `kimi-k3` | `person-kimi-k3-261111` | 1.48x | 300k / 64k | 7 最高 |
| `glm-5.3` | `person-glm-5-3-261111` | 0.80x | 300k / 64k | 7 最高 |
| `glm-5.3-flash` | `person-glm-5-3-flash-261111` | 0.08x | 300k / 64k | 7 最高 |
| `deepseek-v4-pro` | `person-deepseek-v4-pro-ga-260813` | 0.46x | 300k / 64k | 7 最高 |
| `qwen3.8-max` | `person-qwen3-8-max` | 0.74x | 300k / 64k | 5 高 |
| `qwen3.8-flash` | `person-qwen3-8-flash` | 0.05x | 300k / 64k | 5 高 |
| `mimo-v2.6-pro` | `person-mimo-v2-6-pro` | 0.07x | 300k / 64k | 5 高 |
| `mimo-v2.6-flash` | `person-mimo-v2-6-flash` | 0.03x | 300k / 64k | 5 高 |

倍率是客户端菜单标签，豆包会调整；以客户端模型选择页为准。`model_item_key` 失效时，模型会返回上游错误，不会悄悄换模型。GLM 5.3 两个模型在 doubao2api 的旧实测里返回“出了点问题”，本目录保留条目但在描述里标注，是否可用以账号实际返回为准。

## 签到中心接入

`doubao` 与 `doubao-web` 都已接入 `CheckinCenter.SUPPORTED`，两套账号会分别在签到中心显示，支持状态查询、手动签到、自动签到和结果对账。入口沿用现有签到中心：

- `GET /api/checkin/status`：返回两套豆包账号及各自的今日结果；
- `POST /api/checkin/run`：手动触发，body `{"provider":"doubao","accountId":"..."}`，网页版把 provider 换成 `doubao-web`；
- `POST /api/checkin/reconcile`：只打开官方活动页核对状态，不点击签到。

豆包签到接口不能直接照抄 HTTP。活动接口 `POST https://www.doubao.com/alice/ug/campaign/landing/checkin` 需要 PC 客户端安全 SDK 的签名；只带 Cookie、带 `passport_csrf_token`、或复制官方完整 `a_bogus` URL，实测都返回 `710010202 common invalid param`。因此实现不伪造签名，而是：

1. `DoubaoCheckinPage` 用 App 内不可见 WebView，注入账号 Cookie（`CookieManager.setCookie` 回调确认后再 `loadUrl`，最多等 1.5 秒兜底），使用桌面 Chrome UA；
2. 加载 `https://www.doubao.com/pc/agent-explore?enter_method=fixed_entry`，由官方页面自己调用活动接口；
3. `PROBE_JS` 读取页面正文和 `button` 文本，识别「立即签到」「已签到」、登录、安全验证、失败提示；
4. 只有官方页面出现可点击的「立即签到」时才点击一次，点击后轮询约 14 秒等待页面确认；
5. 已签到、点击后页面确认、或只看到成功提示，分别返回 `done` / `claimed`；页面显示失败返回 `failed` 并允许 30 分钟后重试；点击后无法确认返回 `unknown`，当天不再重复点击。

状态映射：

| 页面状态 | 签到中心结果 | 行为 |
| --- | --- | --- |
| 今日已签到 / 已签到按钮 | `done` | 不点击，当日缓存 |
| 立即签到按钮 | `not_checked` | 手动或自动点击一次 |
| 登录页 / 登录按钮 / 登录提示 | `auth_required` | 不点击，提示重新登录 |
| 安全验证 / 滑块 / 验证码 | `captcha` | 不点击，提示先在豆包完成验证 |
| 页面没有签到任务 | `inactive` | 不点击，不伪造领取 |
| 点击后页面报错 | `failed` | `safeToRetry=true`，30 分钟后重试 |
| 点击后无确认 | `unknown` | `retryBlocked=true`，当天不重复发送 |

实现位置：

- `DoubaoCheckin.java`：与 Android 解耦的页面状态机；
- `DoubaoCheckinPage.java`：WebView、Cookie 注入、页面探测和按钮点击；
- `CheckinCenter.java`：`doubao`、`doubao-web` 加入 `SUPPORTED`，两套 Cookie 账号都参与自动/手动签到；
- `GatewayServer.java`：`performDailyCheckin` / `readDailyCheckinStatus` 对两个 provider 都走 `doubaoPageCheckin`；
- `DoubaoCheckinOfflineTest.java`、`DoubaoDualProviderOfflineTest.java`：JVM 状态机与双供应商迁移契约测试。

### 签到验证结果（2026-11-27）

- `:app:offlineTest_DoubaoCheckinOfflineTest`：`DoubaoCheckinOfflineTest OK`。覆盖页面解析、状态映射、只点击一次、已签到不点击、官方失败重试、未知结果当天封锁、登录态和验证码拦截。
- 全量离线套件（`--max-workers=1 --continue`）：260 个套件执行，259 个通过，1 个失败。唯一失败仍是 `GatewayManagementCredentialOfflineTest` 在 Windows 上创建符号链接缺少 `SeCreateSymbolicLinkPrivilege`，与签到改动无关；此前 5 个 `Address already in use` 是模拟器 `adb forward tcp:3425` 占用了测试端口，移除转发后复跑通过。
- Android 15 x86_64 模拟器实装 debug APK：写入一个格式合法但未登录的豆包 Cookie 后，App 自动签到实际创建 WebView、加载官方活动页并返回 `inactive`，没有走 JVM「无 WebView」兜底；说明页面加载、Cookie 注入、状态探测和签到中心接线在真实 Android WebView 中可执行。
- 当前没有把真实账号 Cookie 写入仓库或测试日志，因此「真实账号点击签到成功」尚未在本机复现；而且本机浏览器里官方活动页当前自身返回 `710010702 系统错误`，签到按钮点击就是失败。App 会把这类官方失败显示为「签到失败，请稍后重试」，并在 30 分钟后重试；若官方页面之后恢复，真实账号应走同一条 WebView 点击路径。

## 实现位置

- `DoubaoAuth.java`：Cookie 规整、设备派生、公共参数、只读登录校验；
- `DoubaoProtocol.java`：请求体与 SSE 解码；
- `DoubaoChat.java`：OpenAI chat.completion 适配、工具桥、会话缓存、错误映射；
- `DoubaoHttp.java`：`HttpURLConnection` 传输（走 `NetworkPolicy`）；
- `GatewayServer`：`doubao` 与 `doubao-web` 两条协议路由、`/api/auth/{provider}/add`、`/api/auth/{provider}/sync-models`；
- `LoginActivity`：WebView 登录后从 `CookieManager` 取整条 Cookie，两套供应商分别入池；
- `dashboard.html`：两张豆包卡片、Cookie 导入、各自模型池；
- `DoubaoWorkOfflineTest`：请求体/16 模型目录/倍率/推理档/SSE/工具桥/会话复用/401/429 的离线契约测试；`DoubaoMenuParityOfflineTest` 与客户端 16 项菜单快照逐项比对；`DoubaoCatalogSyncHttpTest` 用真实环回 HTTP 验证同步接口和 `/v1/models` 展示名。
- `DoubaoDualProviderOfflineTest`：旧网页版迁移、工作模式账号保留、两套并存不重复、默认目录不完整时不改动。

**「同步模型列表」的真实语义**：它不会向豆包官方实时拉取在线模型目录。豆包公开的 `brief_list` 需要 PC 客户端安全 SDK 签名，纯 Cookie 请求实测返回 `710010202 common invalid param`，这条路没有打通。面板上的同步/恢复内置目录是把安装包内维护的目录写回配置，再配合 `doubaoCatalogVersion=4` 的升级迁移，把旧配置校准到 16 项；它能保证当前安装包与 2026-11-27 客户端菜单一致，但不能自动发现豆包未来新增的型号。

## 验收

```bash
# 只跑豆包离线契约测试（main() 风格，非 JUnit）
./gradlew :app:offlineTest_DoubaoWorkOfflineTest :app:offlineTest_DoubaoCheckinOfflineTest :app:offlineTest_DoubaoDualProviderOfflineTest :app:offlineTest_DoubaoMenuParityOfflineTest :app:offlineTest_DoubaoCatalogSyncHttpTest
# 跑全部离线套件（--continue 保证单个失败不会中断其余套件）
./gradlew :app:offlineTests --continue
```

2026-11-27 在最新 `xune`（`1.2.158-test`）上跑全量离线：294 个套件执行，293 个通过，1 个环境失败。

- 唯一失败是 `GatewayManagementCredentialOfflineTest`：测试调用 `Files.createSymbolicLink`，当前 Windows 账号没有 `SeCreateSymbolicLinkPrivilege` 且未开启开发者模式，抛 `客户端没有所需的特权`。该测试文件未被本次改动修改，属于环境权限限制，不是功能回归；在 Linux/macOS 或开启开发者模式的 Windows 上可正常执行。
- 豆包专项 `DoubaoWorkOfflineTest`、`DoubaoCheckinOfflineTest`、`DoubaoDualProviderOfflineTest`、`DoubaoMenuParityOfflineTest`、`DoubaoCatalogSyncHttpTest` 全部为 `OK`。
- `BackupConfigSchema` 协议白名单已同时包含 `doubao` 与 `doubao-web`，`ConfigConsistencyOfflineTest`、备份恢复和发布加固套件通过。
- 新增的 `doubao-web` 网页协议源码来自远端 `xune` 的 `DoubaoWeb.java` / `DoubaoWebChat.java`，本分支只做双供应商路由、账号池和面板接入。

2026-11-27 已用真实账号做过一次上游实测（未把 Cookie 写入仓库或日志）：

- 老版本升级后，启动时会自动把 `doubao` 的 `models/modelMeta/note` 对齐到安装包内置的 16 项目录，不会清空账号或 Key；旧网页版 `doubao` 会改名为 `doubao-web` 并保留账号/密钥；面板上的「恢复内置目录」仍可手动触发。

- `doubao-2.1-lite`：`POST /chat/completion` 返回 `200 text/event-stream`，长度约 11 KB，事件含 `SSE_ACK`、`STREAM_CHUNK`、`SSE_REPLY_END`；豆包会话页实际显示 assistant 回复「正常」；
- `deepseek-v4.1-flash`（旧 id `deepseek-v4-flash` 仍兼容）：返回 `200 text/event-stream`，`STREAM_CHUNK` 5 条，`SSE_REPLY_END` 的 `brief=正常`；
- 同一账号额度接口的 `used_percent` 从 12% 变为 16%，证明请求确实走了本人账号额度，而不是空转；
- 连续快速请求后上游返回 `710022004 rate limited`，说明风控/频控生效；代码已把它映射成 429，不再误报 502。

真机验收顺序：

1. 安装 debug 包，在「供应商 → 豆包工作模式」点「登录账号」；
2. 完成扫码/手机号登录，点「完成入池」，确认卡片账号数变为 1；
3. `GET /v1/models` 应出现 16 个 `doubao/*`，模型标签上的倍率应为 0.01x/0.65x/0.80x/0.47x 等，而不是旧的 2x/7x/12x；`deepseek-v4.1-flash` 和 `mimo-v2.6-*` 都应出现；
4. 豆包网页版卡片单独登录同一账号后，应出现 `doubao-web/doubao`、`doubao-web/doubao-think`、`doubao-web/doubao-expert` 三个模型；两套账号池互不影响；
5. 发一条普通对话，确认非流式与流式都能返回；
6. 带 `tools` 发一条请求，确认模型输出 `<tool_call>` 被翻译成 OpenAI `tool_calls`；
7. 在豆包客户端确认该次调用确实扣了本人账号的额度；
8. 退出豆包登录（Cookie 失效）后请求，应返回 401 和「登录态已过期」，而不是 502；触发频控时应返回 429。

## 风险

- 这是网页协议适配，不是官方 API；豆包改版、风控或模型 key 调整都会导致失效。
- `doubao-web` 的三档网页协议来自远端 `xune` 实现，本次合并后已通过编译与离线路由/模型目录/迁移测试，但尚未用真实账号重新跑网页版对话；上线前按「验收」步骤 4–5 各发一次真实请求。
- 账号额度是个人资产，多客户端共用同一账号可能触发风控或额度耗尽；不要拿别人的 Cookie 入池。
- 豆包额度接口只返回 `used_percent`，不返回绝对余额；面板不展示余额，避免显示一个过期或伪造的数字。
- 工作模式会在豆包侧新建会话；网关只做 6 小时的进程内复用，重启后丢失，豆包会话列表可能变长。
- 连续请求会触发 `710022004 rate limited`（语义风控）；这是账号级频控，换模型也可能继续被限，需要等待而不是重试风暴。
- 工具桥依赖模型愿意遵守 `<tool_call>` 约定，复杂 schema 或强制 `tool_choice` 可能不稳定；这不是豆包原生 function calling。

## 参考实现

- [dsh-connect-doubaowork](https://github.com/Joseph2Young/dsh-connect-doubaowork)：工作模式 `chat/completion` 请求体、SSE 事件与 `model_item_key` 目录的公开抓包整理。
- [doubao2api](https://github.com/yyydsz/doubao2api)：工作模式 SSE 的 `CHUNK_DELTA` / `STREAM_CHUNK` / `SSE_REPLY_END` 解码顺序。
