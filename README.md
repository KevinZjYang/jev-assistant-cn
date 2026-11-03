# Jev 聊天助手 · 国内版（Android）

把微信聊天截图分享进来、或者粘贴一段聊天文字，它给出 3 条候选回复，点一下复制到剪贴板，你自己粘贴发送。

不读屏、不后台截屏、不自动发送，不需要 root，不修改微信。

上游项目：[jev-chat/jev-chat-jarvis](https://github.com/jev-chat/jev-chat-jarvis)（MIT）。本仓库是它的国内化分支，改动见下面两节。

## 用法

1. 从 [Releases](https://github.com/KevinZjYang/jev-assistant-cn/releases) 下载 `jev-assistant-1.9.2-cn.apk`（Android 11+，arm64-v8a）。
2. 打开 App →「设置」→「模型接口」，填 `https://api.deepseek.com`、`deepseek-flash`、你的密钥，三个按钮（测试判断 / 测试回复 / 测试看图）各点一次确认通。
3. 微信里长按截图 →「分享」→ 选「Jev助手」；或者先复制文字，再点首页「分享截图 / 粘贴文字」。
4. 看候选 → 点一条复制 → 回微信长按输入框粘贴 → 自己按发送。

不需要开无障碍、不需要悬浮窗、不需要自启动。

## 我们改了什么

**输入方式（核心改动）**

- 新增 `ShareReceiverActivity`：接收 `ACTION_SEND`（图片 / 文本 / 多图），也作为首页主入口，可带剪贴板内容进来。
- 新增 `session/`：`ChatSession`（消息 + 摘要 + 是否连续）、`OverlapMerger`（多次分享的重合段对齐）、`SessionStore`（`files/sessions/<会话名>.json`，原子写）。
- 新增 `capture/ocr/`：`ChatShotParser` 把截图交给本机 ML Kit 中文 OCR，`BubbleGrouper` 把文字行切成气泡。
- 分边算法：先用**气泡底色**（自己的绿 / 对方的白，中线横向采 9 点）判「我 / 对方」，读不出底色才退回几何（所有气泡的左边缘最小值、右边缘最大值当两条参考列）。上游那套「离哪条屏幕边更近」在长消息上会翻车，已用真实事故坐标写了回归用例。
- 跨截图连续：两张图重叠的部分自动吃掉；找不到重合会标「与前文可能不连续」，不硬拼成一段假的连续对话。
- 候选点击：先试 `ACTION_SET_TEXT` 填进输入框（需要无障碍在跑），失败退剪贴板并提示长按粘贴。任何情况下不点发送。

**接口**

- 判断 / 回复 / 看图三张卡合并成一张：Base URL + 密钥 + 模型，填一次。
- `Prefs` 里 `reply*` / `vision*` 改成「自己没值就跟判断接口」，并加一次性迁移（`prefs_unified_v19`）清掉旧的分开配置。
- `JudgeClient` 新增 OpenAI 兼容路由：把 state 渲染成提示词，要求模型按固定的 7 键 JSON 回答，再把结果解析回原来的 `Analysis` 结构。
- 默认值改为 `https://api.deepseek.com` + `deepseek-flash`；视觉不再对 DeepSeek 拒绝（V4.1 Flash 支持看图），`deepseek-v4-pro` 不支持。
- 三个诊断按钮保留，分别验判断 / 回复 / 看图。

**界面**

- 首页只留：一句话说明、一个「分享截图 / 粘贴文字」主按钮、设置入口、版本号；只在没填密钥时插入一张提示卡。
- 设置只留：模型接口卡、关系描述、候选条数、关于与隐私、保存。
- 关于文案改成「只在你主动分享时读取」，因为已经不读屏了。

**其他**

- 自动截屏 OCR 默认关闭（`screenshot_ocr_enabled=false`）。
- 打包：包名 `com.jev.probe`、`1.9.2-cn`（versionCode 14）、minSdk 30 / targetSdk 35、仅 `arm64-v8a`、自签证书。
- README 与截图重写（截图是模拟器 + 假聊天数据，没有真实聊天记录）。

## 我们删掉了什么

**从界面上删掉的（功能入口不存在了）**

- 首页的权限向导：无障碍、悬浮窗、自启动 + 省电三张卡；就绪状态卡；助手总开关。
- 设置里的：会话白名单、对方发消息时自动分析、树读不到正文时用 OCR 兜底、OCR 模式自动分析、记录聊天历史与注入条数、知识库与联系人、清空知识库、自检、外观（悬浮窗不透明度）、开源仓库按钮。
- 判断接口的预设项：OpenRouter、博查 Jev、TypeSafe 直连、Vercel、OpenCode Zen——现在只留 DeepSeek Flash / V4 Pro / 通义兼容 / 自定义。对应的代码路径仍在，只是不再从界面暴露。

**从默认路径上摘掉的（代码在，不启用）**

- 微信的无障碍读屏适配器 `WeChatAdapter`：已从 `ChatCaptureService` 的适配器列表移除，`BLOCKED_PKGS` 恢复拉黑 `com.tencent.mm`。
- 悬浮窗：`OverlayController` 保留，但首页与设置都没有入口，分享链路不调用它。
- 无障碍服务本身仍随包安装（`ChatCaptureService`）——只有「点候选直接填进微信输入框」这一条会用到它；不给权限就退化成复制 + 手动粘贴。

**没删但也没做的事**

- 群聊仍然分不清发言人：没有昵称列，只能靠底色 / 边缘，多人交替说话时可能把两次发言合成一条。
- 表情包、语音条、引用回复在 OCR 里会变成噪声行；只做了常见噪声过滤，没有结构化识别。

## 为什么不再读屏

微信 8.0.5x 起把消息文字从无障碍节点里藏了，部分账号的聊天页还带 `FLAG_SECURE`：`screencap`、系统截屏键、无障碍 `takeScreenshot`、`MediaProjection`、adb、投屏、录屏全部拿不到画面。所有截图路径都走 SurfaceFlinger 同一层合成，SECURE 窗口不进可捕获缓冲区，App 侧绕不过去。上游有同样的记录（[Issue #42](https://github.com/jev-chat/jev-chat-jarvis/issues/42)），最终只有卸载重装微信能恢复，上游 v1.4 宣布微信不再支持。

如果你的微信聊天页已经被保护（截图全黑、系统提示「当前页面不允许截图」），分享这条路暂时没有素材可用；恢复办法是备份聊天记录 → 卸载微信 → 重装。

## 构建

需要 JDK 17 + Android SDK（platform 35、build-tools 35.0.0）。

```bash
cd cn
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleRelease
```

签名从环境变量 `JEV_KEYSTORE_PROPS` 指向的 properties 读（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`），不设则输出未签名包。打模拟器测试包可加 `-PjevAbi=x86_64`。

单测：`./gradlew testReleaseUnitTest`，45 个用例，覆盖分边、重叠合并、门限与输入写入。

## 隐私

- 只在你主动分享时读取；不后台读屏、不自动截屏。
- 图片在本机 OCR，不上传；只有 OCR 出来的文字会发到你自己配置的模型接口。
- 密钥存在 App 私有 SharedPreferences，日志只打长度；会话存在 `files/sessions/`，卡片上「清空本会话」即时删除。
- 作者不运营中转服务器，收不到任何内容。

## 许可

MIT，见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。上游版权归 jev-chat/jev-chat-jarvis 作者所有，本分支的改动同样以 MIT 发布。
