# 评论风向开发说明

本仓库是 Jev 聊天助手的 Android 二开项目，当前目标是在用户正常浏览小黑盒帖子时显示评论情绪。产品范围以 [README.md](README.md) 为准。

## 实现约定

- Kotlin、传统 Android View；minSdk 30，compileSdk / targetSdk 35，包名 `com.jev.commentlens`。
- 使用系统无障碍节点读取可见正文，不修改目标应用、不注入、不读取其数据库，不自动翻页、点赞或发送内容。
- 默认手动，可开启自动。帖子正文与评论分别判断；Jev 每段文字一道选择题，不附加历史、父评论或其他上下文。
- 正负面比例使用本帖全部已判断的去重评论，待判断内容不进分母，不使用最近 30 条窗口。
- 最近 5 帖保留完整文字与结果，返回时恢复；相同文字和已判断结果不重复上传。缓存只存应用私有目录。
- 页面或模式切换后，旧请求不能污染当前帖。详情更新保持当前阅读位置。
- 当前只适配小黑盒。仓库保留的聊天回复、知识库、OCR 和旧样本模块，不等于当前产品已支持这些功能。
- 保留 `LICENSE`、`NOTICE` 和来源声明。真实 API 密钥、签名材料、设备缓存、抓屏及账号数据不提交、不写日志。
- UTF-8 编码，构建路径使用 ASCII；JDK / SDK 路径由本地环境配置，不在代码中绑定维护者的电脑路径。

## 主要入口

源码根目录：`app/src/main/java/com/jev/probe/`。

- `capture/CommentNodeReader.kt` / `comments/CommentPage.kt`：读取、解析平台正文和帖子标识。
- `capture/ChatCaptureService.kt`：前台页面跟随、读屏线程、Jev 队列、缓存保存。
- `comments/LiveSentiment.kt`：完整评论列表、全帖统计、去重和缓存顺序。
- `comments/LiveCacheCodec.kt` / `LiveCacheStore.kt`：序列化和原子写入。
- `comments/JevSentimentClient.kt`：独立文本情绪判断；测试在 `app/src/test/`。
- `overlay/OverlayController.kt`：细条及详情。

## 验证与提交

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
git diff --check
```

Windows 使用 `gradlew.bat`。按改动范围验证，真实设备检查见 [docs/acceptance.md](docs/acceptance.md)。不要把未完成的平台适配写成已支持。

提交、推送及打标签按用户当前授权执行；发版时同步 `app/build.gradle.kts`、`README.md` 和 `CHANGELOG.md` 的版本。签名配置通过 `JEV_KEYSTORE_PROPS` 指向仓库外文件。

`docs/probe_spec.md`、`docs/v1.3-*.md` 和 `tools/jev/TASK.md` 是上游历史资料，不作为当前功能和任务要求。
