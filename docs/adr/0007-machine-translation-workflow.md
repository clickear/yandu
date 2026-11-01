# 0007 · 机器翻译作为可选的草稿来源

机器翻译属于首期翻译主流程，但部署方必须显式配置 provider；默认不调用外部服务，也不把 API key 写入仓库或浏览器。服务端通过 `MachineTranslationProvider` 端口隔离具体引擎，任务持久化并由最多四个 worker 并发执行，支持状态查询和失败项重试。供应商暂不进入领域模型。配置支持 LibreTranslate 协议及 OpenAI-compatible Chat Completions，可连接大模型服务或实现该协议的第三方网关。

自动结果只能创建或更新仍为空的草稿。任务生成时固定源文版本与译文版本；回填时在同一事务锁定源词条并重新检查源版本、译文版本和译文是否仍为空。版本不符或人工已填写内容时将任务项记为跳过/冲突，不覆盖当前译文。重复 HTTP 请求需使用项目内幂等键；worker 不使用内存队列作为任务事实来源。本期不引入 Outbox。

任务项状态转换统一先锁定任务行，再读取或更新任务项并刷新汇总状态。任务汇总状态由 `MachineTranslationJob` 聚合依据任务项状态计数推导，MyBatis 适配器只读取明细并持久化聚合结果。`attempts` 是当前执行租约代次：worker 在外部翻译完成后，必须在回填事务中重新锁定任务和任务项，并确认状态仍为 `RUNNING` 且代次匹配，才可保存译文；完成、失败和释放租约也都以该代次为条件。超时回收或重试后，迟到的旧 worker 不能写草稿、结束新一轮任务或释放新租约。拒绝入队时会回退未实际执行的尝试计数。

LibreTranslate 适配器遵循 `/translate` JSON 协议；大模型及兼容网关适配器遵循 `/chat/completions` JSON 协议。provider port 位于独立 contract 模块；两种实现分别位于可选 Maven 模块，部署构建用 `machine-translation-libretranslate` 或 `machine-translation-openai-compatible` profile 引入需要的实现，再由 `YANDU_MT_PROVIDER` 选择已引入的 provider（默认 `libretranslate`）。未引入实现时 API 明确拒绝创建任务。配置 `YANDU_MT_BASE_URL`；大模型适配器还要求 `YANDU_MT_MODEL`。需要鉴权时配置 `YANDU_MT_API_KEY`，密钥只在服务端发送。不能用模拟翻译结果替代真实调用。外部真实服务调用需在部署凭据和网络具备后单独验收。
