# jev-assistant-cn · 中文聊天助手（DeepSeek 国内版）

一个 Android 上的聊天回复助手：**你把微信聊天截图分享进来（或粘贴一段文字），它给出 3 条候选回复，点一下复制到剪贴板，你自己粘贴发送。**

不读屏、不后台截屏、不自动发送，不需要 root，不修改微信。

> 本仓库是 [jev-chat/jev-chat-jarvis](https://github.com/jev-chat/jev-chat-jarvis)（MIT）的国内化分支。
> 上游原本靠无障碍读屏 + 悬浮窗工作；微信从 8.0.5x 起混淆了无障碍节点、并对部分账号的聊天页打开了
> `FLAG_SECURE`，读屏与截屏都拿不到内容。这个分支改成「用户主动分享」的模型，并把接口默认指向 DeepSeek。

---

## 为什么改了方向

| 现象 | 结论 |
|---|---|
| 微信聊天页 `screencap` 全黑、`uiautomator dump` 只有一个空节点 | 窗口带 `SECURE` 标志，所有截图路径（无障碍 `takeScreenshot`、`MediaProjection`、系统截屏键、adb、投屏、录屏）都走同一层合成，SECURE 窗口不进可捕获缓冲区 |
| 上游 Issue #42 | 装过读屏助手后微信部分页面无法截屏，卸载/重启/安全模式均无效，只有**卸载重装微信**能恢复；上游 v1.4 宣布微信不再支持 |
| 绕过的唯一路径 | root + LSPosed 去 `FLAG_SECURE`，需要解 BL 锁，代价大且与「不改微信、不 root」冲突 |

所以把输入从「App 自己去看屏幕」改成「你把内容递给它」——这条路不需要任何系统权限，也不碰微信进程。

## 功能

- **分享截图**：微信里长按截图 → 分享 → 选「Jev助手」。本机 ML Kit 中文 OCR 认出气泡，按**气泡底色**（自己的绿色 / 对方的白色）区分「我 / 对方」，不需要联网。
- **粘贴文字**：复制聊天后打开 App，主按钮会带上剪贴板内容；行首 `我：` / `对方：` 能识别方向。
- **跨截图连续会话**：同一段对话连拍两张，靠「最长重合段」把第二张接在第一张后面，重叠部分自动吃掉；找不到重合会明确提示「与前文可能不连续」，不硬拼。
- **3 条候选**：一次调用生成三条，点击复制；无障碍服务在跑且输入框已聚焦时会直接 `ACTION_SET_TEXT` 填入，失败退剪贴板。**从不自动发送。**
- **单接口配置**：判断、起草回复、看图共用一份 `Base URL + 密钥 + 模型`，默认 `https://api.deepseek.com` + `deepseek-flash`。

## 隐私

- 只在**你主动分享**时读取：不后台读屏、不自动截屏。
- OCR 在本机跑（ML Kit），图片不上传。
- 会话只落在 App 私有目录 `files/sessions/<会话名>.json`，「清空本会话」即时删除。
- 你的密钥只存在 App 私有 SharedPreferences，代码里没有任何硬编码密钥，日志只打长度。

## 构建

需要 JDK 17 + Android SDK（platform 35、build-tools 35.0.0）。

```bash
cd cn
# 指向你的 SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleRelease
```

签名：release 配置从环境变量 `JEV_KEYSTORE_PROPS` 指向的 properties 文件读取
（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`），不设就走未签名。
想临时打测试包可以用 `-PjevAbi=x86_64` 换 ABI。

## 使用步骤

1. 安装 APK，打开「设置」→「模型接口」填 Base URL、密钥、模型，点「测试判断 / 测试回复 / 测试看图」确认连通。
2. 回首页点「分享截图 / 粘贴文字」，或在微信里长按截图 → 分享 → 选本 App。
3. 看候选 → 点一条复制 → 回微信长按输入框粘贴 → 自己按发送。

## 相对上游的改动

- 新增「分享 / 粘贴」入口（`ShareReceiverActivity`）、会话存储（`session/`）、截图解析（`capture/ocr/`）。
- 分边算法：从「离哪条屏幕边更近」改为「两段式参考列 + 气泡底色」，修复长消息被判成对方的问题。
- 接口：`Prefs` 三路（判断/回复/视觉）合并为一份；新增 DeepSeek chat 兼容路由；视觉不再对 DeepSeek 拒绝。
- 首页与设置按「用到什么留什么」精简：读屏/悬浮窗/自启动的入口全部移除，只留分享链路用得上的项。
- 默认关闭自动截屏 OCR（`screenshot_ocr_enabled=false`）。

## 已知限制

- 群聊没有昵称列，多人交替说话时仍可能把两次发言合成一条。
- 截图 OCR 有字识别误差（表情、语音条、引用回复会变成噪声行）；已做常见噪声过滤，但没有结构化识别。
- 需要微信侧能截屏：若微信已被 `FLAG_SECURE` 保护（部分账号），分享截图会失败，只能先备份 → 卸载 → 重装微信恢复。

## 许可

MIT，见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。上游版权归 jev-chat/jev-chat-jarvis 作者所有；
本分支的改动同样以 MIT 发布。
