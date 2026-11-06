# OMO 编排台本：Android TV 语音输入法（2026-11-06）

仓库：`C:/Users/hwx1254427/WorkBuddy/2026-11-06-14-31-47/doubao-murmur-android`
上游：`https://github.com/iFei7/doubao-murmur-android`（默认分支 `master`，MIT，fork 自上游）

## 0. 分支策略（已落盘）

| 分支 | 角色 | 内容 |
|---|---|---|
| `master` | 上游镜像，**默认分支**，只用于 fork / 同步上游 | 与 iFei7 仓库保持一致，不落 Android 代码 |
| `feat/android-tv-ime` | 开发分支（从 master 拉出） | 全部 Android TV 交付物位于 `android/` 子目录 |

- `upstream` remote → iFei7 原仓库；`origin` 留空，由使用者指向自己的 fork。
- 沙箱代理拒绝对 `github.com:443` 的 CONNECT，故本地 master 为上游快照单提交；`scripts/android/sync-upstream.sh` 用于在可直连 GitHub 的环境中恢复真实上游历史。
- **Tag 前缀必须为 `android-tv-v*`**：上游 `release.yml` / `windows-release.yml` / `flatpak-release.yml` 均以 `v*` 触发，误用会让 Android 发版连带触发三端桌面构建。

## 1. 已确认的技术事实（来自上游 `docs/doubao-wss-asr-spec.md` 与 `linux/src/doubao_murmur/asr_client.py`）

- 端点：`wss://ws-samantha.doubao.com/samantha/audio/asr`，无子协议、无握手消息，连上即发裸 PCM。
- 鉴权：`Cookie`（含 httpOnly `sessionid`）+ `Origin: https://www.doubao.com`。
- 固定 query：`version_code=20800, language=zh, device_platform=web, aid=497858, real_aid=497858, pkg_type=release_version, pc_version=3.12.3, region=, sys_region=, samantha_web=1, use-olympus-account=1, format=pcm`
- 动态 query：`device_id`（`localStorage['samantha_web_web_id'].web_id`）、`web_id`/`tea_uuid`（`localStorage['__tea_cache_tokens_497858'].web_id`）、`web_tab_id`（UUID v4 每次新建）。
- 音频：16 kHz / 单声道 / Int16 LE / 裸字节，无帧头。
- 下行：`{"event":"result","result":{"Text":"…"}}` 全文覆盖式（非增量）、`{"event":"finish"}`。
- 鉴权失败码：`709599054`(Invalid)、`710012001`(login invalid)、`710022013`(tourist reach limited)；握手 HTTP 200 + JSON 体亦为鉴权失败；服务端未发任何消息即 `1000` 关闭 = 登录过期。
- 收尾：停止前补 200 ms 数字静音（16k×1ch×2B×200ms = 6400 B），否则末字丢失；接收端静默 250 ms 后才认作终稿。

## 2. 技术选型（结论）

**原生 Kotlin + Compose for TV（`androidx.tv:tv-material`）+ `InputMethodService`。**

否决理由：IME 必须返回原生 `View` 树（`onCreateInputView`）并继承 framework 的 `InputMethodService`，Flutter/RN 无官方 IME 支持，桥接成本大于收益；语音链路（AudioRecord + OkHttp WebSocket）原生最稳，且能直接复用上游三端已验证的协议。

## 3. 模块划分（`android/` 下 Gradle 多模块）

| 模块 | 类型 | 职责 |
|---|---|---|
| `:asr` | kotlin-jvm | 豆包 WSS 协议：URL 构建、消息解析、失败分类、引擎抽象。**零 Android 依赖 → CI 可跑真单元测试** |
| `:audio` | android-lib | AudioRecord 采集、48k→16k 重采样、能量 VAD、麦克风能力探测 |
| `:auth` | android-lib | WebView 凭证提取（Cookie + localStorage）、Keystore 加密存储 |
| `:ui` | android-lib | Compose TV 设计系统：主题、过扫描安全区、焦点放大组件 |
| `:ime` | android-lib | `InputMethodService` + 遥控器键盘 UI + 文本提交（manifest 合并进 :app） |
| `:app` | application | Leanback 启动器：登录、启用输入法引导、麦克风授权、诊断 |
| `:benchmark` | com.android.test | Macrobenchmark（TV 设备） |

单 APK 交付（`:ime` 以 library 形式并入 `:app`），换取单一 versionCode 与单一分发链路；模块化仍保证可测。

## 4. 命名与契约（所有子代理必须严格遵守）

- 命名空间：`:app` = `com.doubao.murmur.tv.app`，`:ime` = `com.doubao.murmur.tv.ime`，`:asr` = `com.doubao.murmur.tv.asr`，`:audio` = `com.doubao.murmur.tv.audio`，`:auth` = `com.doubao.murmur.tv.auth`，`:ui` = `com.doubao.murmur.tv.ui`
- applicationId：`com.doubao.murmur.tv`
- 固定 FQCN（manifest 已引用，实现不得改名）：
  - `com.doubao.murmur.tv.app.MainActivity`
  - `com.doubao.murmur.tv.app.login.LoginActivity`
  - `com.doubao.murmur.tv.app.permission.MicPermissionActivity`
  - `com.doubao.murmur.tv.app.diagnostics.DiagnosticsActivity`
  - `com.doubao.murmur.tv.ime.DoubaoVoiceInputMethodService`
  - `com.doubao.murmur.tv.ime.settings.ImeSettingsActivity`
- `:asr` 对外契约（`:ime` 依赖它，签名不可变）：

```kotlin
data class AsrCredentials(val cookies: Map<String, String>, val deviceId: String, val webId: String) {
    fun cookieHeader(): String
}
sealed interface AsrEvent {
    data object Connected : AsrEvent
    data class Partial(val text: String) : AsrEvent
    data class Final(val text: String) : AsrEvent
    data object Finished : AsrEvent
    data class Failed(val failure: AsrFailure, val cause: Throwable? = null) : AsrEvent
}
sealed interface AsrFailure {
    data object LoginExpired : AsrFailure
    data object TokenInvalid : AsrFailure
    data class Server(val code: Int, val message: String) : AsrFailure
    data object Network : AsrFailure
    data object Timeout : AsrFailure
}
interface AsrEngine : java.io.Closeable {
    val events: kotlinx.coroutines.flow.Flow<AsrEvent>
    suspend fun start()
    fun feed(pcm: ByteArray)
    suspend fun stopAndAwaitFinal(timeoutMs: Long = 3000L): String
    fun cancel()
}
object AsrProtocol {
    const val WSS_ENDPOINT: String
    const val ORIGIN: String
    const val SAMPLE_RATE: Int          // 16000
    const val TRAILING_SILENCE_MS: Int  // 200
    fun buildUrl(credentials: AsrCredentials, webTabId: String): String
    fun parse(text: String): AsrEvent?
}
class DoubaoAsrEngine(credentials: AsrCredentials, client: okhttp3.OkHttpClient, ) : AsrEngine
```

- `:auth` 对外契约：`interface CredentialStore { suspend fun load(): AsrCredentials?; suspend fun save(...); suspend fun clear(); }`，实现 `EncryptedCredentialStore`（EncryptedSharedPreferences + Keystore MasterKey）+ `WebViewCredentialExtractor`。
- `:audio` 对外契约：`PcmRecorder.start(onChunk: (ByteArray) -> Unit)` / `stop()`、`MicProbe.probe(context): MicCapability`。

## 5. 验收门禁（每个子代理交回时逐条核）

1. 文件只落在自己的 owner 范围内（见各任务 description）。
2. Gradle 构建脚本未被非 owner 改动。
3. Kotlin 无未解析引用（对照契约与 build 文件里声明的依赖）。
4. `:asr` 的单元测试可被 `gradle :asr:test` 执行，且覆盖 URL 构建 / 失败分类 / 消息解析 / 引擎收发四条线。
5. 无硬编码密钥、无明文凭证落盘。
6. 报告里写明：改动文件清单、自检结果、遗留风险。

## 6. 子任务与依赖

```
T1 骨架（串行） ──┬─> T2 :asr + 单测
                 ├─> T3 :audio + :auth
                 ├─> T4 :ui + :ime
                 ├─> T5 :app
                 └─> T6 CI/CD + 文档
```
T2–T6 文件owner互不重叠，可并行。

## 7. 环境诚实边界

本机无 Android SDK / JDK17 / Xcode，**无法真实编译打包**；交付为可编译的源码与配置级产物，真实构建在 CI 或开发者本地执行。
