# 云端助手

目前仍处于研发阶段，后续更新可能包含不兼容变更。更新前请备份重要内容。

## 0.9.24

检索时结合用户语言、地区和访问条件选择来源，优先可靠且方便打开的资料；遇到受限站点会查找合适替代。系统语言、地区偏好和时区随请求更新，只作提示，不作为真实定位。提示词设计参考 [DeepSeek Harness 标准模式](https://github.com/deepseek-ai/deepseek-harness/blob/22ddee3792402c3e70ec5f86a63c3e3e895a2f60/packages/bundle/web-app/presets/standard.patch.yml) 和 [ZCode 官方提示词契约](https://github.com/zai-org/ZCode/blob/3c5b227649371507ddc4eb46ac5133afe6db1cab/apps/zcode-cli/docs/design/v2/loop/system-prompt-contract.md)，具体取舍见 [检索规则](docs/research-policy.md)。

## 0.9.23

执行任务前先检索相关资料、核对官方说明和版本；界面任务同时查找截图与示例，完成后检查实际页面。检索不可用时说明查证缺口，复用本会话已有的有效资料，并遵从用户的联网选择。

## 0.9.22

添加 DeepSeek 官方连接并填写可用密钥后，联网搜索与网页读取会优先使用其 Search / Fetch 服务，浏览器工具也可调用。密钥不可用或服务暂时失败时，会标明原因并使用备用来源。

接口说明：[DeepSeek Search & Fetch](https://api-docs.deepseek.com/guides/search/)。

## 0.9.21

修复密钥状态、接口格式选择和模型切换；添加供应商时自动区分同名配置。可将助手加入悬浮入口。

新增音频波形、节拍估计、视频采样预览、数值拟合及 SVG/glTF/GLB 结构分析，支持下载音视频直链。网页调试支持持续会话、拖拽和键盘操作，并跟随软件的主题色和明暗模式。音频和视频分析结果为估计值，不代替专业测量。

## 0.9.18

支持带选项的问题，点击即可继续当前会话；完善任务完成、出错和待审核提醒，并修复与新版主程序的兼容问题。

KIGTTS的云端助手独立仓库。本仓库采用 **MIT**，完整文本见[LICENSE](LICENSE)，来源与第三方许可见[NOTICE.md](NOTICE.md)。SDK的许可不会覆盖插件的许可。

## 0.9.14

增加任务通知：执行时查看状态，完成后收到提醒，也可从通知回到对应会话或停止任务。可在助手设置中关闭。首次使用需要插件通知权限及系统通知授权，需 API20 或更新宿主。

## 构建

需要JDK17、Android SDK36、PowerShell7。克隆时获取已固定版本的SDK及共享组件，不需要克隆主程序。

```powershell
git clone --recurse-submodules https://github.com/KIGTTS/plugin-cloud-agent.git
cd plugin-cloud-agent
./gradlew.bat testDebugUnitTest assembleDebug
./Package.ps1 -SigningKey /path/to/publisher-private.der -AndroidSdk /path/to/android-sdk
```

Linux/macOS使用`./gradlew`。编译产物在`build/outputs/aar`，签名插件包在`build/plugin-packages`。发布私钥始终由维护者保管，不提交到本仓库；正式更新沿用既有发布者签名、插件ID与Appdata范围。已发布版本不可覆盖，发行新内容须增加版本号。

[SDK与接口](https://github.com/KIGTTS/plugin-sdk) · [官方源](https://modelscope.cn/datasets/LHTSTUDIO/KIGTTS-Official-Plugins)
