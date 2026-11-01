# 0007 · 机器翻译作为可选的草稿来源

机器翻译属于首期翻译主流程，但部署方必须显式配置 LibreTranslate-compatible endpoint；默认不调用外部服务，也不把 API key 写入仓库或浏览器。服务端通过 `MachineTranslationProvider` 端口隔离具体引擎，任务持久化并由最多四个 worker 并发执行，支持状态查询和失败项重试。供应商暂不进入领域模型。

自动结果只能创建或更新仍为空的草稿。任务生成时固定源文版本与译文版本；回填时在同一事务锁定源词条并重新检查源版本、译文版本和译文是否仍为空。版本不符或人工已填写内容时将任务项记为跳过/冲突，不覆盖当前译文。重复 HTTP 请求需使用项目内幂等键；worker 不使用内存队列作为任务事实来源。本期不引入 Outbox。

首个 HTTP 适配器遵循 LibreTranslate `/translate` JSON 协议，支持自托管实例或兼容托管服务。部署需配置 `YANDU_MT_BASE_URL`，有鉴权时再配置 `YANDU_MT_API_KEY`。没有可用 provider 配置时 API 明确拒绝创建任务；不能用模拟翻译结果替代真实调用。
