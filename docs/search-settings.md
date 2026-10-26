# 搜索与网页读取

在「助手设置 → 搜索与网页读取」选择默认搜索源。支持 Bing RSS（无需密钥）、DeepSeek、博查、Tavily、Brave、Serper。API 可分别设置地址和密钥，密钥沿用 Android Keystore 加密保存，与聊天连接分开。

可调整每次结果数量（3–10条）、网页读取方式，以及失败时是否使用备用来源。备用搜索为 Bing，备用读取为直接读取网页；关闭备用来源后报告错误。浏览器的 search/fetch 与独立网页工具共用配置。

DeepSeek 复用实际地址为 HTTPS `api.deepseek.com` 的官方供应商连接，不依据显示名称。首次成功获取模型目录、确认密钥可用后，弹窗询问是否设为默认搜索源。选择「暂不」会保留原选择，且不再自动弹出；后续可手动更改。弹窗接受前会重新核对连接和密钥指纹。搜索 API 的权限、余额及网络可达性仍由服务商决定。

接口参考：

- [DeepSeek Search / Fetch](https://api-docs.deepseek.com/guides/search/)
- [博查官方示例](https://open.bochaai.com/)，POST `/v1/web-search`，Bearer 密钥，兼容 `data.webPages.value` 和直接 `webPages.value`。
- [Tavily Search](https://docs.tavily.com/documentation/api-reference/endpoint/search)，POST `/search`，Bearer 密钥。
- [Brave Web Search](https://api-dashboard.search.brave.com/app/documentation/web-search/get-started)，GET `/res/v1/web/search`，`X-Subscription-Token`。
- [Serper](https://serper.dev/)，POST `/search`，`X-API-KEY`。

请求仅使用 HTTPS，不跟随携带密钥的重定向，响应最多1 MiB；结果统一为来源、标题、URL、摘要。失败消息不回显密钥或服务端正文。自动回归使用模拟服务，不消耗用户的 API 额度；真实账户连通性及服务额度未代验。
