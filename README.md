# 评论风向 · Comment Lens

照常刷帖子，在屏幕边缘查看评论情绪。点击细条展开原文、判断结果和置信度。

基于 [Jev 聊天助手](https://github.com/jev-chat/jev-chat-jarvis) 二次开发，使用 Jev 判断文字情绪，由本仓库独立维护。

**当前版本：v0.3.2（测试版）** · Android 11+ · arm64-v8a · [下载 APK](https://github.com/tianyin231/comment-lens/releases/download/v0.3.2/comment-lens-v0.3.2-debug.apk) · [更新日志](CHANGELOG.md) · [问题反馈](https://github.com/tianyin231/comment-lens/issues)

## 已实现

- **边缘情绪条**：青绿表示正面，珊瑚红表示负面，其余部分表示中性、褒贬混合或不确定；上方小点单独显示帖文情绪。
- **手动 / 自动模式**：默认手动，点击“分析当前屏幕”；开启“自动跟随帖子”后，正常浏览即可持续读取新评论。
- **本帖完整统计**：全部已判断的去重评论参与比例计算，没有最近 30 条的限制。展开后可滚动查看全部已读评论和结果。
- **换帖自动跟随**：新帖开始独立统计，返回缓存中的帖子恢复已有结果。
- **最近 5 帖本地缓存**：保存文字与判断结果，退出帖子或重启服务后可恢复；超过 5 帖时淘汰最久未访问的一帖。
- **减少重复请求**：同文去重，已判断的评论不重复上传。帖文单独判断，不随每条评论重复发送，也不带历史或父评论上下文。

## 支持范围

| 平台 / 内容 | 当前状态 |
|---|---|
| 小黑盒帖子正文、评论、楼中楼 | 已适配，使用系统无障碍节点读取可见文字 |
| B 站视频评论、弹幕、直播反馈 | 尚未适配 |
| 图片、表情图片、视频声音 | 不参与判断 |

当前只分析你浏览到的文字，不拉取全帖评论，也不自动翻页。仓库保留了上游的聊天、OCR 等模块，当前评论风向入口没有启用这些功能。

## 开始使用

从 [v0.3.2 发布页](https://github.com/tianyin231/comment-lens/releases/tag/v0.3.2) 下载 `comment-lens-v0.3.2-debug.apk`。这是已在 MuMu 验证的 debug 签名测试包，与此前同签名的测试包可覆盖安装。仓库 `apk/jev-assistant-v1.3-release.apk` 是上游聊天助手的历史安装包，不是评论风向。

1. 下载并安装测试 APK，打开“评论风向”。
2. 进入“实时情绪设置”，选择 Jev 接口预设，填写自己的 API 密钥和模型，点击“测试 Jev”确认连接后保存。
3. 按首页引导开启无障碍和悬浮窗权限。无障碍只需打开“使用评论风向”，无需开启系统“快捷方式”，以免误触关闭服务。
4. 打开小黑盒帖子，点击右侧细条，手动分析当前屏幕，或开启“自动跟随帖子”。
5. 上下拖动细条可调整位置，点击“收起”返回正常浏览。

内置接口配置：

| 预设 | 地址 | 模型 |
|---|---|---|
| TypeSafe | `https://api.typesafe.ai/v1/systemone` | `jev-latest` |
| OpenRouter | `https://openrouter.ai/api/alpha/decisions` | `typesafe/jev-1.13` |

接口地址、模型和低置信度阈值可以修改。模型请求使用你自己的账户，费用以服务商实际计费为准。

## 数据与结果

- Jev 接收本次新增或尚未判断的文字。每段文字对应一道独立情绪选择题，多段可合并为一次请求，不附带历史、父评论或图片。
- 最近 5 帖缓存写入应用私有目录的 `live-post-cache.json`，不作为上下文再次上传。清除应用数据或卸载会删除缓存及配置。
- 帖文情绪与评论比例分开计算；等待判断的评论不计入比例。
- 百分比的分母是本帖全部已判断的去重文本，不代表人数、全帖评论总量，也不代表赞成或反对帖文。
- 低于设定置信度阈值的结果显示为“不确定”。反讽、梗和短回复可能需要上下文，独立文字判断存在误差。

## 构建

准备 JDK 17 或兼容版本、Android SDK 35，并通过 `ANDROID_HOME` 或未跟踪的 `local.properties` 配置 SDK 路径。

```bash
git clone https://github.com/tianyin231/comment-lens.git
cd comment-lens
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Windows 使用 `gradlew.bat`。测试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，包名为 `com.jev.commentlens`。

正式构建使用仓库外的签名配置文件，通过 `JEV_KEYSTORE_PROPS` 指定路径。配置字段为 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，然后执行：

```bash
./gradlew :app:assembleRelease
```

未提供签名配置时会生成未签名包，不能直接安装。签名文件和密码不能提交到仓库；同包名应用需要相同签名才能覆盖升级。

## 开发入口

| 文件 / 目录 | 作用 |
|---|---|
| `capture/CommentNodeReader.kt`、`comments/CommentPage.kt` | 小黑盒可见正文读取、评论解析和帖子识别 |
| `capture/ChatCaptureService.kt` | 手动 / 自动采集、后台判断、页面切换 |
| `comments/LiveSentiment.kt` | 去重、全帖统计、待判断队列、最近 5 帖缓存管理 |
| `comments/LiveCacheCodec.kt`、`comments/LiveCacheStore.kt` | 缓存序列化和原子写入 |
| `comments/JevSentimentClient.kt` | Jev 情绪选择题与置信度处理 |
| `overlay/OverlayController.kt` | 边缘细条、详情和滚动位置保持 |

以上路径均位于 `app/src/main/java/com/jev/probe/`。开发说明见 [CLAUDE.md](CLAUDE.md)，验证范围见 [验收说明](docs/acceptance.md)。

新增平台需要实现对应节点读取与帖子解析，并接入服务的平台识别；当前解析依赖小黑盒的控件结构，平台界面更新后可能需要调整。帖子暂以作者、标题与正文生成标识，正文变化可能被识别为新帖。

## 来源与许可

基于 [jev-chat/jev-chat-jarvis](https://github.com/jev-chat/jev-chat-jarvis) 二次开发，遵循 [MIT License](LICENSE)，保留原作者版权声明与 [NOTICE](NOTICE)。原项目及本项目的贡献者见 [CONTRIBUTORS.md](CONTRIBUTORS.md)。

“评论风向”由本仓库独立维护，不代表原作者出品或背书。问题和建议请提交到本仓库的 [Issues](https://github.com/tianyin231/comment-lens/issues)。
