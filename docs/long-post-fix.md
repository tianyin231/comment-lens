# 小黑盒长文章修复记录（本地候选，未发布）

基线：`main` / `62594b10de077dd0b2432ee561c24974ae23a11a`。本轮从远端重新克隆，开始时工作区干净；未取得另一工作区的初步补丁和 7 个测试。已阅读 `CLAUDE.md`、`README.md` 和验收说明，仓库内未发现 `AGENTS.md`。Astra Max 主导实现，6.1 Sol 独立审查并编写回归测试。

## 已核验的代码问题

1. `CommentNodeReader` 原本仅在第一个 `post_like` 内查找 `post_content_container`，会漏掉位于页面其他位置的正文。
2. `CommentPageParser` 原本只要标题为空就返回空 `postKey`，即使 `tv_desc` 正文已经读到。
3. `ChatCaptureService` 收到空 `postKey` 后会 `resetPage()`，清空当前状态并隐藏细条。因此前两项可以直接导致“连细条都没有”，无需先发生情绪分析错误。
4. 原来的模式切换仅使情绪分析批次失效，进行中的读屏仍可能按旧模式采集；相同 Activity/窗口重新进入帖子也未必使旧任务失效。

这些是从基线代码及合成节点可确定的问题。当前没有用户真机日志、指定复现文章或真实长帖节点树，尚不能确定它们覆盖了用户遇到的所有长文布局。

## 修复范围

- 从页面根部以已知 ID 查找正文；正文仍只读取其内部的 `tv_title` / `tv_desc`，不把全屏文字、昵称或推荐说明当作正文。
- 要求详情页标志（`title_name` 或 `tv_all_comment`）和唯一正文容器；首页卡片、多个正文候选或无正文身份时保守拒绝，不沿用上一帖标识来显示细条。
- 无独立标题时可由正文建立身份；多段 `tv_desc` 按顺序合并。作者、标题和完整正文参与 SHA-256，分析文本的既有 3000 字限制不截断身份计算。
- 正文滚出屏幕但仍在无障碍树中时保留其身份，评论仍只采集可见正文；节点查询返回的句柄在正常、歧义和异常路径上释放。
- 页面导航、模式变化及前台窗口变化使旧任务失效；读屏及分析回调复核状态，无法确认页面时隐藏旧结果并等待重读。旧结果不能写入已切换的帖子批次。
- 移除构建脚本默认的维护者 Windows 签名路径。仅显式提供 `JEV_KEYSTORE_PROPS` 时读仓库外签名配置；未配置时 debug 使用本地 debug 签名，release 保持未签名。

没有增加强制显示细条的兜底，没有新增平台支持，没有改变 Jev 请求内容或版本号。

## 云端验证

构建环境使用 JDK 17、Gradle 8.9、Android SDK Platform 35。构建脚本默认选择的 Build Tools 为 34.0.0。

在未改源码的独立基线工作树中，原有 41 项单元测试通过；lint 通过，22 条 Warning、0 条 Error。

首次准备时的阻断及处理：

- Maven Central 返回 HTTP 429，首次依赖解析被中止；本机临时 Gradle init 脚本使用 Google 提供的 Maven Central 镜像继续下载，未修改仓库中的依赖仓库配置或版本。
- 基线在 Linux 上因 `Cannot convert URL 'H:/android/keys/jev-release.properties' to a file` 配置失败。基线检查通过环境参数绕过，候选代码已移除该硬编码默认值。

候选代码最终结果（2026-09-30）：

| 检查 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | 通过：67 项，0 failure / error / skipped；新增 26 项 |
| `:app:lintDebug` | 通过：0 Error、22 Warning；按规则、信息及文件比对，与基线完全一致 |
| `:app:assembleDebug` | 通过，生成可安装的 debug APK |
| `apksigner verify --verbose --print-certs` | 通过，APK Signature Scheme v2 |
| `zipalign -c -P 16 -v 4` | 通过；此为 APK ZIP 对齐检查，不等同于真机兼容性验证 |
| `git diff --check` | 通过 |
| 未解决的自动检查失败 | 无；准备阶段的限流与签名路径错误已处理 |
| 设备 / instrumentation / 真实长帖验收 | 未运行；`adb devices -l` 没有连接设备，也没有指定复现文章 |
| 真实 Jev 账号调用、release 构建、发布 | 未运行；HTTP 测试使用 MockWebServer，本轮只交付 debug 候选 |

新增测试包括：`LongPostRegressionTest` 11 项、`XiaoheiheNodeReaderTest` 7 项、`CaptureGuardTest` 8 项。原有滚动身份测试同时增加非空标识断言，防止两个空标识被误判为身份稳定。`CaptureGuard` 的测试直接验证服务实际共用的类；Android 事件到服务的真实接线仍需设备验收。6.1 Sol 最终审查没有发现必须修复的新问题。

本次执行命令（代理、CA、SDK 及 JDK 路径仅配置在当前云环境）：

```bash
source /workspace/toolchains/env.sh
bash gradlew --console=plain --init-script /workspace/toolchains/maven-central-mirror.init.gradle :app:assembleDebug
bash gradlew --console=plain --init-script /workspace/toolchains/maven-central-mirror.init.gradle :app:testDebugUnitTest :app:lintDebug
git diff --check
```

网络可正常访问 Maven Central 的环境按仓库常规 Gradle 命令构建即可，无需临时镜像。编译仍有既有 Android `recycle()` 弃用提示及上游未使用参数提示；ML Kit 本地库未剥离调试符号，按原样打包，构建成功。

## 测试包

- 文件：`/workspace/artifacts/comment-lens-v0.3.2-longpost-debug.apk`；原始产物位于 `app/build/outputs/apk/debug/app-debug.apk`。
- 大小：28,227,443 字节（约 26.9 MiB）。
- SHA-256：`fbc780b00afccc517be9a4d69f665821248d25d18c0e6e25cf283fc5965ab520`。
- 包名：`com.jev.commentlens`；版本名 / 代码保留为 `0.3.2` / `5`，这是未发布候选，不是新的正式版本。
- Android 11+（minSdk 30）、targetSdk 35、`arm64-v8a`。
- 使用当前环境新生成的 Android Debug 签名，未使用此前发布包的私钥；不能假定可覆盖旧包。建议在独立测试设备或测试用户安装，避免卸载现有应用导致缓存和配置丢失。

完整本地补丁、校验文件和验证日志与 APK 一并保存在 `/workspace/artifacts/`；签名私钥不在交付包中。

## 验证边界与真机验收

按 [长文章候选包真机验收步骤](acceptance.md#长文章修复候选包的真机验收) 检查首页、短帖、有/无标题长帖、正文到评论区滚动、缓存恢复、快速换帖和模式切换。不能仅凭细条出现或合成节点测试通过，就判定长文章问题已经完全解决。

- 当前仍依赖小黑盒实际暴露的控件 ID。正文完全不在无障碍树中、以其他控件或 WebView 暴露时，本轮没有证据证明可读。
- 当前没有平台稳定帖子 ID；同作者、同标题、同完整正文的不同真实帖子仍无法仅凭内容指纹区分，正文变化也可能形成新指纹。
- Android 真实事件顺序、布局变化、系统权限和悬浮窗表现需要设备验证。完全不暴露导航事件且尚未完成新一轮读屏的同窗口变化，不能由合成节点测试证明无竞态。
- 无障碍无法保证读取未加载评论；本轮不获取全帖评论，不实现不滑屏读取前 N 条。

后续平台适配及评论获取方式的核验需求已记录在 [后续需求](roadmap.md)。本轮不提交、推送、合并或发布。
