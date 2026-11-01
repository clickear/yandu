# 已发布翻译的消费端同步

发布消费端使用独立的 Spring Boot Starter，按配置的项目、目标语言和格式同步最新已发布版本。平台提供公开只读元数据与快照接口；接口只暴露已发布内容，不提供草稿、审核数据或项目协作操作，本期不要求消费端身份认证。工作台管理 API 继续使用 OIDC 和项目角色授权。

快照是不可变发布内容。Starter 先读取版本及 SHA-256 元数据，再下载对应的最新内容；只有内容校验和解析成功后才用一次原子替换更新内存目录。同步失败时保留上一个有效目录，并在下一轮继续重试。消费应用通过 `TranslationCatalog` 按键读取当前快照，不需要依赖平台数据库。

同步传输方式以配置选择：普通 HTTP 轮询作为兼容性兜底，同时支持 Netty TCP 长连接与 WebSocket 实时推送。Netty 使用独立监听端口及 newline-delimited JSON 订阅帧；WebSocket 使用 HTTP Upgrade 握手和 JSON 订阅消息。实时通道只发送发布版本变化元数据，客户端再通过公开 HTTP API 获取完整快照。所有方式复用相同的不可变快照、SHA-256 校验和本地原子替换契约；轮询间隔默认 60 秒，长连接断开后自动重连。

Netty 与 WebSocket 长连接均不要求消费方身份认证，只能订阅并获知已发布资源变化；工作台 API 继续强制 OIDC 身份和项目角色权限。普通轮询、Netty 与 WebSocket 已实现。基础 Starter 只依赖 HTTP/Jackson；Netty 客户端和 WebSocket 客户端分别由可选 Maven 模块提供，应用只引入所选实现。多节点实时通知不使用 Outbox；数据库中的 release 快照仍是权威状态，长连接心跳触发客户端重新读取最新元数据，覆盖连接落在另一应用实例的情况。MySQL 8.0.46 CI 已验证匿名快照 API 读取。新增跨模块集成用例改为调用 `ReleaseApplicationService.publish` 生成快照，再验证事务提交后 Netty/WebSocket 通知、HTTP 拉取和 SHA-256 校验；最新 CI 结果待确认。该用例不覆盖发布任务 HTTP/UI 入口；消费 Starter 的快照替换和客户端协议另外由模块测试覆盖。远程缓存持久化和 webhook 暂不实现；公开接口只支持 JSON Flat v1 与 Java Properties v1。
