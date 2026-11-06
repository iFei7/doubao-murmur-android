# TV 适配：遥控器、焦点、麦克风与输入法

> 本文是本项目**最容易踩坑**的部分。每条结论都写了"为什么"，因为下一个接手的人大概率会
> 想当然地把手机端的做法搬过来，然后在电视上撞墙。

## 目录

- [1. 电视和手机到底差在哪](#1-电视和手机到底差在哪)
- [2. 遥控器交互](#2-遥控器交互)
- [3. 焦点管理](#3-焦点管理)
- [4. 麦克风权限与音频采集](#4-麦克风权限与音频采集)
- [5. 语音识别接入](#5-语音识别接入)
- [6. 画面与安全区](#6-画面与安全区)
- [7. 无障碍](#7-无障碍)
- [8. 上架红线](#8-上架红线)

## 1. 电视和手机到底差在哪

| 维度 | 手机 | Android TV | 对本项目的影响 |
|---|---|---|---|
| 输入设备 | 触摸屏 + 软键盘 | 只有遥控器（D-Pad + OK + 返回 + 音量） | 键盘 UI 必须完全靠焦点导航，触摸是"有则更好" |
| 输入法唤起 | 点输入框即弹 | 焦点落在输入框 → 按 OK → 弹 | 弹窗后焦点在 IME 与宿主之间来回跳 |
| 屏幕 | 竖屏为主 | 横屏 16:9，且有过扫描裁切 | 必须留 5% 安全区，锁定横屏 |
| 麦克风 | 基本都有 | **多数盒子/投影没有内置麦** | 必须做能力探测与降级提示 |
| 系统版本 | 碎片但较新 | 更碎：Android 9/11/12 混杂，部分 ROM 魔改 | 不能依赖新 API，要做兜底 |
| 应用分发 | Play 为主 | Play + 一堆国内 TV 市场 + U 盘 sideload | 需要多条分发链路 |
| 输入法权限 | 用户熟悉 | 用户根本不知道"输入法"在哪启用 | 必须有引导页与一键跳转 |

## 2. 遥控器交互

### 2.1 为什么 `KEYCODE_VOICE_ASSIST`(231) 不能用

几乎每个 TV 遥控器上都有一个语音键，直觉上"按下它 → 我的输入法开始录音"应该成立。**不成立。**

- `KeyEvent.KEYCODE_VOICE_ASSIST` 的官方文档明确写着 **"Not delivered to applications"**。
- 在 AOSP 里，这个键在 `PhoneWindowManager.interceptKeyBeforeDispatching()` 阶段就被系统消费，
  用于拉起语音助手（Assist）。应用层（包括 `InputMethodService`）**收不到**这个 `KeyEvent`。
- 即使某些 ROM 把它放出来，行为也各不相同：有的拉起 Google Assistant，有的拉起厂商自己的语音助手，
  有的直接无响应。

结论：**不要为这个键写任何逻辑**，它只会给你一个"在我这台机器上能用，在别人那台不能用"的假象。

### 2.2 可行的四条路径

| 路径 | 可行性 | 优点 | 缺点 | 本项目用法 |
|---|---|---|---|---|
| **IME 内的麦克风按钮**（焦点可选中） | ✅ 完全可行 | 不依赖任何按键语义；所有遥控器都有方向键 + OK；可在 UI 上标注 | 需要先弹出键盘（多一步） | **主路径** |
| **长按 `DPAD_CENTER`** | ✅ 可行（IME 视图可见时） | 单键完成"说一句"；遥控器都有 | 仅在 IME 视图持有焦点时有效；部分宿主 App 会自己消费长按 | **次路径** |
| **`KEYCODE_SEARCH`(84)** | ⚠️ 视设备而定 | 不少遥控器有独立的搜索键 | 很多新遥控器已取消该键；部分 ROM 把它映射成别的功能 | **降级路径** |
| **无障碍服务按键过滤**（`AccessibilityService.onKeyEvent`） | ⚠️ 技术可行，产品不建议 | 全局可用，不受 IME 焦点限制 | 属于高敏感权限，需要用户手动在设置里开启；Play 审核会重点问用途；常驻服务耗电 | **可选开关，默认关闭** |
| 注册 `android.speech.action.WEB_SEARCH` / `ACTION_ASSIST` Activity | ⚠️ 只能"唤起自己的界面" | 能把系统语音键接到本项目 | 只能打开一个 Activity，无法把文字**内联提交**到当前输入框；TV 上还会打断宿主 App | **不采用**（列为已知限制） |
| `KEYCODE_VOICE_ASSIST`(231) | ❌ 不可行 | — | 系统层消费，应用收不到 | **不实现** |

### 2.3 本项目的选型与降级链

```
① IME 视图内的「按住说话」大按钮（焦点默认落点）
       ↓ 用户嫌麻烦 / 想用遥控器语音键
② 长按 DPAD_CENTER（IME 视图持有焦点时）
       ↓ IME 视图没有焦点（宿主 App 抢走）
③ KEYCODE_SEARCH(84)（若设备支持）
       ↓ 以上都不可用
④ 设置项「无障碍按键」开关（默认关闭，用户手动开启后才注册 AccessibilityService）
       ↓ 仍然不可用
⑤ 明确提示：本机遥控器不支持一键唤起，请用遥控器选中麦克风按钮后按 OK
```

**第 ⑤ 步很重要**：产品上宁可承认"你这台机器要按两下"，也不要让用户以为功能坏了。
诊断页（`DiagnosticsActivity`）会显示当前设备实际能收到哪些按键，方便用户与客服对齐。

### 2.4 按键处理代码要点

在 `DoubaoVoiceInputMethodService` 的 InputView 里，对 `DPAD_CENTER` 做**长按**判定：

```kotlin
// 长按阈值用系统的 ViewConfiguration.getLongPressTimeout()，不要自己写 500ms
private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

Modifier.onKeyEvent { event ->
    when (event.key) {
        Key.DirectionCenter -> {
            when (event.type) {
                KeyEventType.KeyDown -> { startLongPressTimer(); true }
                KeyEventType.KeyUp   -> { cancelTimer(); if (!longPressFired) commitFocusedKey(); true }
                else -> false
            }
        }
        else -> false
    }
}
```

注意事项：

- `KeyEventType.KeyDown` 会**连续触发**（按住不放时），必须用"首次 KeyDown 起计时"而不是"每次 KeyDown 重置"。
- 长按触发录音后，**松开时不要再提交一次字符**，否则会先录一段音再往输入框塞一个空格。
- 返回键 `KEYCODE_BACK` 在 IME 上会被框架用于"收起键盘"，不要在 InputView 里拦截它。

## 3. 焦点管理

### 3.1 Compose TV 的三个关键工具

| 工具 | 作用 | 本项目用法 |
|---|---|---|
| `FocusRequester` | 主动把焦点移到某个组件 | 键盘弹出时把焦点放到麦克风按钮；从语音态返回时恢复到最后按过的键 |
| `Modifier.focusRestorer()` | 焦点离开容器再回来时，恢复到上次的位置 | 包裹整个键盘容器，避免用户切出去再切回来焦点回到第一个键 |
| `Modifier.focusProperties { }` | 自定义 next/previous/up/down/left/right 与 `canFocus` | 实现环形焦点、跳过禁用键、把麦克风按钮固定在右侧 |

### 3.2 焦点视觉

```kotlin
val interactionSource = remember { MutableInteractionSource() }
val focused by interactionSource.collectIsFocusedAsState()
val scale by animateFloatAsState(if (focused) 1.15f else 1.0f, label = "focus-scale")
val borderColor by animateColorAsState(if (focused) TvFocusBorder else Color.Transparent)

Box(
    Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .border(if (focused) 3.dp else 0.dp, borderColor, RoundedCornerShape(12.dp))
        .focusable(interactionSource = interactionSource)
)
```

硬性要求：

- **放大 1.15×**：低于 1.1× 在 3 米观看距离下看不出来；高于 1.2× 会导致相邻元素重叠。
- **高对比描边 3dp**：只靠放大在浅色背景下不够，必须有描边。描边颜色与背景对比度 ≥ 4.5:1。
- **放大不能触发重排**：用 `graphicsLayer` 而不是改 `Modifier.size`，否则焦点移动时整个布局抖动。
- 相邻元素间距要预留放大后的空间（至少 8dp）。

### 3.3 焦点环路

D-Pad 在网格里移动时必须能"绕回来"，否则用户按到最右一列就卡住（电视上最典型的体验事故）。

```kotlin
Modifier.focusProperties {
    right = if (isLastColumn) firstColumnRequester else FocusRequester.Default
    left  = if (isFirstColumn) lastColumnRequester else FocusRequester.Default
}
```

三条规则：

1. **每一行、每一列都要闭环**，不能只在最后一行做。
2. **麦克风按钮固定在键盘右侧**，且在所有方向上都可达（它是最重要的按钮）。
3. **返回键不等于"焦点回退"**：返回键是收起键盘，焦点历史由 `focusRestorer` 负责。

### 3.4 已知问题：IME 窗口抢走宿主 App 的焦点

这是第三方输入法在 TV 上最烦人的 bug：

- 部分 TV App（尤其是用 `SurfaceView` / 自绘 View 的老应用）在 IME 弹出后会丢失焦点，
  表现为**输入框没有光标、按键全部失灵、甚至整个画面卡住**。
- 根因是 IME 窗口是 `TYPE_INPUT_METHOD` 的独立 Window，宿主 App 的 `Window` 失去 focus，
  而这些 App 没有正确处理 `onWindowFocusChanged(false)`。

**本项目的处理：**

1. 弹出键盘时**不要**主动 `requestFocus()` 到宿主（IME 无权这么做）；
2. 提供设置项 **`ime_key_fallback_system`**（设置 → 兼容性 → "遇到输入框无响应时一键切回系统键盘"）：
   点击后调用 `InputMethodManager` 清空当前 IME 绑定并切回系统默认输入法：
   ```kotlin
   val imm = getSystemService(InputMethodManager::class.java)
   imm.setInputMethod(window.windowToken, null)   // null = 回到系统默认输入法
   ```
   若上述调用被 ROM 忽略（部分国产 ROM 会），则退化为跳转系统输入法设置页：
   ```kotlin
   startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
       .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   ```
3. 在 `DiagnosticsActivity` 里记录"最近一次 IME 弹出时宿主包名"，方便定位是哪个 App 不兼容。

> 这个开关是**逃生舱**，不是常规功能。它保证用户在最坏情况下不会"输入法装了但电视没法打字"。

## 4. 麦克风权限与音频采集

### 4.1 为什么需要 `MicPermissionActivity`

`InputMethodService` 是 **Service**，不是 Activity。Service 里不能调用
`requestPermissions()`（该方法在 `Activity`/`Fragment` 上）。但录音需要 `RECORD_AUDIO` 运行时权限。

方案：由一个**透明的 Activity** 承担权限申请，IME 在需要时把它拉起来：

```kotlin
// AndroidManifest.xml（:app 模块）
<activity
    android:name=".permission.MicPermissionActivity"
    android:theme="@style/Theme.Transparent"
    android:excludeFromRecents="true"
    android:launchMode="singleTask"
    android:exported="false" />
```

```kotlin
// DoubaoVoiceInputMethodService 内部
if (!hasRecordPermission()) {
    startActivity(Intent(this, MicPermissionActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return   // 本次不录音；用户授权后回到键盘再按一次
}
```

要点：

- Activity 主题必须是**全透明无标题**，否则 TV 上会闪一下黑屏。
- `FLAG_ACTIVITY_NEW_TASK` 必须有（从 Service 启动 Activity）。
- 授权结果通过 `SharedPreferences` 或 `ActivityResult` 广播回 IME；
  不要假设"授权后 Activity 会回到 IME"，TV 上返回栈行为不稳定。
- 用户拒绝后**不要反复弹**，改为在键盘上显示一条静态提示条（可聚焦的"去授权"按钮）。

### 4.2 TV 没有内置麦克风怎么办

现实情况：大部分国产 TV 盒子、投影仪**没有麦克风**，遥控器上的语音键走的是蓝牙 HID 语音通道，
系统层面并不暴露为标准麦克风输入。

`MicProbe.probe(context)` 的探测顺序：

1. `packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)` —— 快速判断；
2. 枚举 `AudioDeviceInfo`（`AudioManager.getDevices(GET_DEVICES_INPUTS)`），
   检查是否存在 `TYPE_BUILTIN_MIC` / `TYPE_USB_DEVICE` / `TYPE_BLUETOOTH_SCO`；
3. 尝试以 16 kHz 打开 `AudioRecord` 并 `startRecording()` 一小段，读取有效样本数；
4. 输出 `MicCapability`：

| 结果 | 判定 | UI 表现 |
|---|---|---|
| `BuiltIn` | 内置麦可用 | 正常显示麦克风按钮 |
| `External(usb/bt)` | 外接麦可用 | 正常显示，设置页提示"当前使用外接麦克风" |
| `Unsupported16k` | 只能 48 kHz | 正常显示，内部走重采样 |
| `None` | 无可用输入设备 | 麦克风按钮置灰 + 说明"本机未检测到麦克风，可外接 USB 麦克风后重试" |

### 4.3 48 kHz → 16 kHz 重采样

豆包 WSS 只接受 **16 kHz / 单声道 / PCM16LE**。不少设备的 `AudioRecord` 不支持直接 16 kHz
（尤其某些 USB 声卡只给 44.1 kHz / 48 kHz），此时用 48 kHz 采集再降采样：

```
48 kHz 采集 → 抗混叠低通（截止 7.5 kHz） → 每 3 个样本取 1 个 → 16 kHz
```

注意事项：

- **必须先低通再抽取**，直接每 3 个丢 2 个会产生混叠，语音高频段变"沙哑"，识别率明显下降。
- 用 `AudioRecord` 的原生 48 kHz + `SAMPLE_RATE` 转换比手动插值更稳；只有在原生不支持时
  才走自实现的线性相位 FIR（`:audio` 里的 `Resampler48kTo16k`）。
- 采集缓冲区建议 `minBufferSize * 2`，每次读取 20 ms（16 kHz 下 640 字节），
  太小的块会让 WebSocket 帧过碎，服务端 VAD 判定不稳。

### 4.4 Android 14 的 `FOREGROUND_SERVICE_MICROPHONE` 边界

Android 14 收紧了后台录音：应用在后台使用麦克风必须启动带 `microphone` 类型的前台服务，
并声明 `FOREGROUND_SERVICE_MICROPHONE` 权限。

**本项目的边界：**

| 场景 | 是否需要 FGS |
|---|---|
| IME 键盘可见，用户按住说话 | ❌ 不需要。IME 窗口在前台，属于"使用中"状态 |
| 用户松手后等待终稿（≤3 秒） | ❌ 不需要，此时麦克风已停止 |
| IME 被收起但仍在录音（本项目不这样做） | ✅ 需要。这也是我们不实现"后台常驻录音"的原因之一 |

结论：**不申请 `FOREGROUND_SERVICE_MICROPHONE`**。少一个权限、少一次审核问询，
代价是放弃"键盘收起后继续录音"这种没人需要的场景。

## 5. 语音识别接入

### 5.1 协议事实（与上游三端一致）

| 项 | 值 |
|---|---|
| 端点 | `wss://ws-samantha.doubao.com/samantha/audio/asr` |
| 子协议 | 无（不要设置 `Sec-WebSocket-Protocol`） |
| 握手消息 | 无（连上即发裸 PCM 字节） |
| 鉴权 | `Cookie: <含 httpOnly sessionid>` + `Origin: https://www.doubao.com` |
| 固定 query | `version_code=20800&language=zh&device_platform=web&aid=497858&real_aid=497858&pkg_type=release_version&pc_version=3.12.3&region=&sys_region=&samantha_web=1&use-olympus-account=1&format=pcm` |
| 动态 query | `device_id`（`localStorage['samantha_web_web_id'].web_id`）、`web_id`/`tea_uuid`（`localStorage['__tea_cache_tokens_497858'].web_id`）、`web_tab_id`（每次连接新建 UUID v4） |
| 上行音频 | 16 kHz / 单声道 / Int16 LE / 裸字节，无帧头 |
| 下行 | `{"event":"result","result":{"Text":"…"}}`（**全文覆盖式**，非增量）、`{"event":"finish"}` |
| 收尾 | 停止前补 200 ms 数字静音（6400 字节），否则丢末字 |

鉴权失败码：

| 码 | 含义 | 处理 |
|---|---|---|
| `709599054` | Invalid（凭证格式不对） | 清除本地凭证，重新登录 |
| `710012001` | login invalid（登录失效） | 同上 |
| `710022013` | tourist reach limited（游客额度用尽） | 提示登录正式账号 |
| 握手 HTTP 200 + JSON 体 | 也是鉴权失败（WSS 没升上去） | 同上 |
| 连上后服务端未发任何消息即 `1000` 关闭 | 登录过期 | 同上 |

### 5.2 Android 侧拿凭证比 iOS 简单

`sessionid` 是 **httpOnly** cookie，这意味着 JS 的 `document.cookie` 读不到它。

- **iOS**：`WKHTTPCookieStore` 和 `document.cookie` 都拿不到 httpOnly cookie，
  必须走 `WKWebView` 的 `WKWebsiteDataStore` 或者在原生侧拦截请求，麻烦且不稳定。
- **Android**：`CookieManager.getInstance().getCookie(url)` **会返回 httpOnly cookie**。
  只要 WebView 成功登录过 `https://www.doubao.com`，直接：

```kotlin
CookieManager.getInstance().flush()                  // 确保 cookie 落盘
val cookie = CookieManager.getInstance()
    .getCookie("https://www.doubao.com") ?: return null   // 形如 "sessionid=xxx; ttwid=yyy; ..."
```

动态 query 的 `device_id` / `web_id` 存在 `localStorage` 里，`CookieManager` 拿不到，
需要用同一个 WebView 执行 JS：

```kotlin
webView.evaluateJavascript(
    "(function(){try{" +
    "  var a=JSON.parse(localStorage.getItem('samantha_web_web_id')||'{}');" +
    "  var b=JSON.parse(localStorage.getItem('__tea_cache_tokens_497858')||'{}');" +
    "  return JSON.stringify({deviceId:a.web_id||'',webId:b.web_id||''});" +
    "}catch(e){return '{}'}})()"
) { raw -> /* 解析 JsonPrimitive 字符串（注意 evaluateJavascript 返回的是带引号的 JSON 字符串） */ }
```

坑点：

- `evaluateJavascript` 的回调是**带转义的 JSON 字符串**，要二次 `Json.decodeFromString<String>()`。
- 必须在 `onPageFinished` 之后再取，登录流程未完成时 `localStorage` 是空的。
- 凭证会过期，失败码 `710012001` 就是过期信号，此时应清理 `CredentialStore` 并引导重新登录。

### 5.3 `AsrEngine` 抽象与可替换实现

`:asr` 只暴露接口，`:ime` 不关心底层是谁：

```kotlin
interface AsrEngine : Closeable {
    val events: Flow<AsrEvent>
    suspend fun start()
    fun feed(pcm: ByteArray)
    suspend fun stopAndAwaitFinal(timeoutMs: Long = 3000L): String
    fun cancel()
}
```

| 实现 | 用途 | 触发条件 |
|---|---|---|
| `DoubaoAsrEngine` | 主实现，直连豆包 WSS | 有有效凭证 |
| `SystemSpeechRecognizerEngine` | 兜底，走系统 `SpeechRecognizer` | 无凭证 / 豆包不可用 / 用户显式选择 |
| `MockAsrEngine` | 单元测试与 UI 联调 | 仅 debug 构建 |

这样做的价值：**协议变更或被限流时，用户仍然能用系统识别**，不至于整个输入法不可用。
`SystemSpeechRecognizerEngine` 的局限是 TV 上很多 ROM 根本没有语音识别 Service，
所以它只是兜底，不是主路径。

## 6. 画面与安全区

### 6.1 过扫描（Overscan）安全区

老电视会对画面做 3%–5% 的裁切。**所有内容必须留 5% 内边距**：

| 分辨率 | 5% 横向 | 5% 纵向 | 本项目取值（dp，按 1080p / xhdpi 折算） |
|---|---|---|---|
| 1920×1080 | 96 px | 54 px | 水平 48dp，垂直 27dp |
| 1280×720 | 64 px | 36 px | 同上（dp 不随分辨率变） |

`:ui` 里统一提供 `TvSafeArea`：

```kotlin
@Composable
fun TvSafeArea(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 27.dp),
        content = content
    )
}
```

键盘本身是"贴底"的，垂直方向不需要额外内边距，但**按键网格的最下一行要抬起来**，
否则会被电视底部的裁切吃掉。

### 6.2 横屏锁定

```xml
<activity android:screenOrientation="landscape" ... />
```

TV 设备理论上不会旋转，但部分"电视盒子 + 显示器"的组合、以及支持旋转的投影仪会。
强制 landscape 可以避免 IME 在旋转瞬间被重建导致的录音中断。

### 6.3 `onEvaluateFullscreenMode()` 返回 `false`

```kotlin
override fun onEvaluateFullscreenMode(): Boolean = false
```

原因：全屏模式（fullscreen mode）是为**横屏手机 + 全屏编辑**设计的：系统会隐藏状态栏、
把输入框滚到上半屏。在 TV 上这会导致：

- 键盘背景变成不透明黑色，遮住半个屏幕；
- 输入框被推到奇怪的位置；
- 焦点重新计算，用户正在按的键跳走。

TV 上要的是"半透明悬浮键盘贴在底部"，所以必须显式关掉全屏模式。

### 6.4 `onComputeInsets`

```kotlin
override fun onComputeInsets(outInsets: Insets) {
    super.onComputeInsets(outInsets)
    // 内容区（宿主 App 可见区域）到键盘顶部为止
    outInsets.contentTopInsets = keyboardTopPx
    // 触摸/焦点可交互区域同样从键盘顶部开始
    outInsets.visibleTopInsets = keyboardTopPx
    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
    outInsets.touchableRegion.set(0, keyboardTopPx, screenWidthPx, screenHeightPx)
}
```

注意：

- `contentTopInsets` 决定宿主 App 认为"自己还剩多少可视区域"。设错会导致宿主把输入框滚到键盘下面。
- `TOUCHABLE_INSETS_REGION` + 手动 `touchableRegion` 保证遥控器焦点只落在键盘区域，
  不会跑到键盘背后的宿主 App 上（那会导致"按 OK 却点在键盘后面的按钮"）。
- 键盘高度用 `WindowManager.currentWindowMetrics`（API 30+）计算，低版本回退到
  `DisplayMetrics` + 导航栏高度补偿。

## 7. 无障碍

TV 上的 TalkBack 使用率不低（视障用户 + 老人），而且**Google 的 TV 应用质量指南明确要求**可达性。

| 要求 | 做法 |
|---|---|
| 每个可聚焦元素有语义 | `Modifier.semantics { contentDescription = "字母 Q" }` |
| 图标按钮要有描述 | 麦克风按钮：`contentDescription = "按住说话"`；删除键：`"删除"` |
| 状态变化要播报 | `stateDescription = if (recording) "正在录音" else "待机"` |
| 装饰性元素要排除 | `Modifier.clearAndSetSemantics {}` |
| 语音反馈不能只靠视觉 | 录音开始/结束用 `ToneGenerator` 或 `AccessibilityManager.sendAccessibilityEvent(TYPE_ANNOUNCEMENT)` |
| 按键可发现 | 键盘上的键必须能被 D-Pad 逐个到达，不能有"只能用触摸点"的键 |

一个容易忽略的点：**Compose 的 `Text` 默认会被 TalkBack 读，但用 `Canvas` 画的字母不会**。
本项目的键位全部用 `Text` + 背景绘制，不手动 `drawText`。

## 8. 上架红线

输入法是 Play 上审核最严的类别之一，TV 应用又叠加了一层设备资格要求。

### 8.1 输入法（IME）专项

| 要求 | 说明 | 本项目的做法 |
|---|---|---|
| 隐私政策 | 必须提供可公开访问的隐私政策链接 | Release 资产与 Play 商店页都要挂；渠道包里 `listing.md` 有占位符 |
| 不得收集用户输入内容 | 输入法不得把用户敲的内容上传到服务器（**包括"匿名统计"**） | 只上传"用户主动按住说话"期间的音频流，识别完成即丢弃；不落盘、不打点 |
| `RECORD_AUDIO` 用途披露 | 商店页与首次使用都要说明为什么需要麦克风 | 首次录音前弹一次说明；拒绝后仍可用遥控器键盘 |
| 不得静默启用 | 不能自动把自己设成默认输入法 | 只提供"去设置"按钮，跳系统设置页 |
| 不得在输入法内展示广告 | 明文禁止 | 不做 |

### 8.2 Android TV 应用资格

| 要求 | 做法 |
|---|---|
| 声明 Leanback | `<uses-feature android:name="android.software.leanback" android:required="true" />` |
| 提供 TV 启动入口 | `<category android:name="android.intent.category.LEANBACK_LAUNCHER" />` |
| **Banner 320×180** | 必须有，否则 Play 的 TV 设备会直接过滤掉；放在 `res/drawable-xhdpi/tv_banner.png`，manifest 里 `android:banner="@drawable/tv_banner"` |
| 不要求触摸屏 | `<uses-feature android:name="android.hardware.touchscreen" android:required="false" />` |
| 横屏 | `android:screenOrientation="landscape"` |
| 不依赖 Google Play Services 的硬依赖 | 语音走自家 WSS；系统 `SpeechRecognizer` 只是可选兜底 |

### 8.3 常见拒审原因（提前规避）

| 拒审理由 | 触发点 | 规避方式 |
|---|---|---|
| "输入法收集用户数据" | 隐私政策缺失，或代码里有埋点上报 | 隐私政策前置；代码里禁止任何输入内容上报 |
| "缺少 TV banner" | 只有手机 banner | 单独出 320×180 图 |
| "应用在 TV 上不可用" | 没有 `LEANBACK_LAUNCHER`，或横屏下 UI 溢出 | 用 TV 模拟器 + 真机各过一遍 |
| "权限用途不明" | `RECORD_AUDIO` 没有说明 | 商店页描述 + 首次使用弹窗双向说明 |
| "功能不完整" | 无麦克风的设备上直接崩溃 | `MicProbe` 探测 + 优雅降级 |
