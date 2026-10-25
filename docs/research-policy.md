# 检索与参考资料

用户要求执行前查资料、界面任务先找截图，并按语言、地区和网络可用性选择来源。规则独立编写在 `AgentResearchInstructions.kt`，每轮请求都包含；已有会话继续时同样生效。`AgentResearchLocale.kt` 只读取语言、地区偏好与时区，不查询定位或IP。当前聊天供应商不影响这些规则。

先核对原始资料、版本与当前对象，再执行修改；公开截图用于参考，实际生成页面仍通过浏览器运行并截图验收。中国大陆访问条件下优先可访问的官方中文来源，外文原始资料仍可用于核验。用户明确的语言、地区、禁网要求与访问反馈优先；设备偏好不代表真实位置，服务端抓取成功也不能证明用户能打开链接。

参考官方源码（2026-10-26核对）：

- [DeepSeek Harness标准模式](https://github.com/deepseek-ai/deepseek-harness/blob/22ddee3792402c3e70ec5f86a63c3e3e895a2f60/packages/bundle/web-app/presets/standard.patch.yml)：按能力组合提示与工具；先读取现状再形成方案。
- [DeepSeek Web工具](https://github.com/deepseek-ai/deepseek-harness/blob/22ddee3792402c3e70ec5f86a63c3e3e895a2f60/packages/web/tool-web/src/search.ts)：来源链接、原文核验和外部内容信任边界。
- [ZCode系统提示词契约](https://github.com/zai-org/ZCode/blob/3c5b227649371507ddc4eb46ac5133afe6db1cab/apps/zcode-cli/docs/design/v2/loop/system-prompt-contract.md) 与 [动态段落](https://github.com/zai-org/ZCode/blob/3c5b227649371507ddc4eb46ac5133afe6db1cab/apps/zcode-cli/packages/core/src/context/dynamic-sections.ts)：证据优先、专用工具、独立查询并行、简明进展、如实报告验证，以及从已有上下文继续。

本插件保留KIGTTS身份、工具协议和授权流程，没有导入上述项目的运行时或工具接口。地区筛选、执行前检索及截图参考是按用户需求新增的规则，不能据此声称上游有相同策略。
