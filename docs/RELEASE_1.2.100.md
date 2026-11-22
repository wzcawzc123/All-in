# All In 1.2.100（106）

- Trae CN 支持 `json_object` 和受支持的 `json_schema`。同时适用于 Chat Completions 的 `response_format`、Responses 的 `text.format` 和 Messages 的 `output_config.format`。
- Trae CN 通过系统指令请求结构化输出，完成时校验 JSON。严格 schema 支持基本类型和类型联合、对象属性、必填项、数组、枚举、常量、`anyOf`、长度和数字范围，以及指向根 `$defs` 的本地引用。无法校验的约束会在请求发出前提示。流式结构化正文在校验后发送。
- Antigravity 识别 Code Assist 事件流末尾的独立 JSON 错误，保留脱敏后的错误信息。Anthropic 客户端收到一次失败事件，已收到的正文可保留。普通 JSON 客户端收到网关失败状态。

- 修复 MiniMax Code 媒体功能在 Android 7.0/7.1 上调用较新 Base64 接口的问题，保留参考图和视频任务 ID 的编码格式。

使用原签名覆盖安装可保留账号与配置。
