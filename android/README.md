# Android TV 语音输入法

在电视上用遥控器说话，把语音直接变成输入框里的文字。

本目录是 **Android TV 版**的完整工程，位于 `feat/android-tv-ime` 分支。
与上游桌面三端（macOS / Windows / Linux）共用同一个仓库，但代码完全隔离在 `android/` 下。

> **构建状态声明**：构建脚本已在装有 JDK 17 + Android SDK 的机器上做过实机验证——
> `gradle help`（7 个模块全部配置成功）、`:asr:detekt`、`:asr:test`、清单合并 + `aapt2` 资源链接、
> `:app:generateDebugBuildConfig`、签名门禁的正反两态，均已跑通。
>
> 但 **`:app:assembleDebug` 尚未全绿**：目前只剩 **`:ime` 一处**编译错误
> （`DoubaoVoiceInputMethodService.kt` 里对 `InputMethodService.onBackPressed` 做了覆写，
> 而 `InputMethodService` 并没有这个方法——正确做法是走 `setBackDisposition()` / `onKeyDown`）。
> 修复后即可端到端产出 APK。所以：
>
> - Gradle / AGP / 版本派生 / 签名 / 清单合并这些**构建配置**是可信的；
> - **端到端的 `assembleDebug` 与 `assembleRelease` 尚未验证通过**，首次全绿前不要当作可发布状态。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 技术选型对比、模块职责、数据流、关键取舍 |
| [docs/tv-adaptation.md](docs/tv-adaptation.md) | 遥控器按键、焦点管理、麦克风权限、协议接入、上架红线 |
| [docs/ci-cd.md](docs/ci-cd.md) | 五条工作流、版本与签名策略、分发渠道矩阵、回滚与排障 |
| [docs/release-checklist.md](docs/release-checklist.md) | 发版勾选清单（提审前 / 提审 / 放量 / 事后） |

## 模块

| 模块 | 类型 | 职责 |
|---|---|---|
| `:app` | application | Leanback 启动器、登录、启用输入法引导、麦克风授权、诊断页 |
| `:ime` | library | `DoubaoVoiceInputMethodService` + 遥控器键盘 UI（manifest 合并进 `:app`） |
| `:ui` | library | Compose TV 设计系统：主题、过扫描安全区、焦点放大组件 |
| `:audio` | library | `AudioRecord` 采集、48k→16k 重采样、能量 VAD、麦克风能力探测 |
| `:auth` | library | WebView 凭证提取（Cookie + localStorage）、Keystore 加密存储 |
| `:asr` | kotlin-jvm | 豆包 WSS 协议（**零 Android 依赖，CI 可跑真单测**） |
| `:benchmark` | com.android.test | Macrobenchmark：键盘弹出、焦点移动、录音启动耗时 |

依赖方向见 [docs/architecture.md](docs/architecture.md#3-模块职责)。

## 本地构建

### 0. 前置

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | AGP 8.7 要求 JDK 17，不要用 21 以下或 8 |
| Android SDK | API 35（compileSdk）+ Build-Tools 35 | 通过 Android Studio 或 `sdkmanager` 安装 |
| Gradle | 8.9 | 由 wrapper 或 CI 提供，不需要手工装 |

### 1. 生成 Gradle Wrapper

仓库里**没有 `gradle-wrapper.jar`**（二进制无法手写提交）。首次克隆后执行一次：

```bash
bash scripts/android/bootstrap-wrapper.sh
```

跑完后 `android/gradlew`、`android/gradlew.bat`、`android/gradle/wrapper/gradle-wrapper.jar` 就都有了。
之后可以用 `./gradlew`，也可以继续用系统 `gradle`。

> CI **不使用** `./gradlew`，而是用 `gradle/actions/setup-gradle` 安装 Gradle 8.9，
> 原因是 CI 里不能保证 wrapper jar 存在。

**Gradle 版本说明**：AGP 8.7.3 官方支持的组合是 **Gradle 8.9**，CI 固定用它。
如果本机因为网络原因下不到 8.9 发行包（`services.gradle.org` 被拦），
可以用本机已缓存的 Gradle 9.x 临时跑通编译——实测 AGP 8.7.3 在 9.1 上能配置并执行任务，
但**这属于未官方支持的组合**，报错时优先怀疑版本不匹配，不要据此判断业务代码有问题。

### 2. 配置 SDK 路径

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
```

- Windows：`sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`
- macOS：`sdk.dir=/Users/<you>/Library/Android/sdk`
- Linux：`sdk.dir=/home/<you>/Android/Sdk`

`local.properties` 已在 `.gitignore` 里，**不要提交**。

### 3. 构建 Debug

```bash
cd android
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

装到电视：

```bash
adb connect <TV_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **多人 / 多 agent 同时开发时不要并行构建。**
> 两个人（或两个 agent）同时在同一份工作区跑 Gradle 会互相踩：
> 轻则 `.../build/... Permission denied`（写 class 文件冲突），
> 重则构建跑到一半收到 `Gradle build daemon has been stopped: stop command received`。
>
> 约定：**同一时间只跑一轮构建**；需要清理用 `--no-daemon`，
> **不要执行 `gradle --stop`**——它会杀掉别人正在用的单次 daemon，让对方的构建中途失败。

### 4. 跑测试

```bash
cd android
./gradlew :asr:test              # 协议单测，纯 JVM，最快
./gradlew testDebugUnitTest      # 各 Android 模块的单测
./gradlew :asr:detekt :app:detekt
./gradlew :app:lintDebug
```

### 5. 构建 Release（需要签名）

Release 构建要求四个环境变量齐全，缺任何一个都会在 `checkReleaseSigning` 门禁处失败：

```bash
export ANDROID_KEYSTORE_PATH=/absolute/path/to/release.keystore
export ANDROID_KEYSTORE_PASSWORD='...'
export ANDROID_KEY_ALIAS='doubao-tv'
export ANDROID_KEY_PASSWORD='...'

cd android
./gradlew :app:bundleRelease :app:assembleRelease \
  -PversionName=0.1.0 \
  -PversionCode=100 \
  -Pchannel=stable
```

只验证能否编译（不打正式包）时，可以跳过签名：

```bash
./gradlew :app:assembleRelease -PallowUnsignedRelease=true
```

> 生成 keystore 的 `keytool` 命令、base64 化、备份与轮换要求见
> [docs/ci-cd.md §6](docs/ci-cd.md#6-签名管理)。**密钥绝不入库。**

### 6. 其他可选参数

| 参数 / 环境变量 | 作用 | 默认 |
|---|---|---|
| `-PversionName=` | 覆盖 versionName | 取自 `version.properties` |
| `-PversionCode=` | 覆盖 versionCode | 由 `version.properties` 计算 |
| `-Pchannel=` | 注入 `BuildConfig.BUILD_CHANNEL` | `dev` |
| `GIT_SHA` 环境变量 | 注入 `BuildConfig.GIT_SHA` | `local` |
| `-PallowUnsignedRelease=true` | 允许无签名的 release 编译 | `false` |

## 分支与 tag

| 分支 / tag | 角色 |
|---|---|
| `master` | **上游镜像**，与 `iFei7/doubao-murmur-android` 保持一致，不落 Android 代码 |
| `feat/android-tv-ime` | 开发分支，全部 Android 交付物在 `android/` |
| `android-tv-v*` | **唯一合法的 Android 发版 tag 前缀** |

```bash
# 发版
git tag android-tv-v0.1.0
git push origin android-tv-v0.1.0
```

⚠️ **不要用 `v*` 打 Android 版本。** 上游的 `release.yml` / `windows-release.yml` /
`flatpak-release.yml` 都以 `v*` 触发，会连带构建 macOS / Windows / Flatpak 三端并把产物混进同一个 Release。

版本号规则（`versionCode = major*10000 + minor*100 + patch`）与预发布策略见
[docs/ci-cd.md §5](docs/ci-cd.md#5-版本策略)。

## 常用脚本

| 脚本 | 用途 |
|---|---|
| `scripts/android/bootstrap-wrapper.sh` | 生成 `gradle-wrapper.jar` |
| `scripts/android/sync-upstream.sh` | 在可直连 GitHub 的环境中恢复真实上游历史 |
| `scripts/android/compute-version.sh` | 由 tag 派生 `VERSION_NAME` / `VERSION_CODE` |
| `scripts/android/decode-keystore.sh` | CI 中把 base64 secret 还原成临时 keystore |
| `scripts/android/verify-artifacts.sh` | 验签 + 校验版本号 + 校验 TV/IME 声明 |
| `scripts/android/make-ota-manifest.sh` | 生成 TV 盒子 OTA 用的 `manifest.json` |
| `scripts/android/gen-changelog.sh` | 由 conventional commits 生成 changelog |
| `scripts/android/bump-version-pr.sh` | 计算下一版本并改 `version.properties` / `CHANGELOG.md` |
| `scripts/android/adb-install-tv.sh` | 多台 TV 盒子批量安装 |

所有脚本都可以在 `ubuntu-latest` 与 Git Bash 下运行，头部有中文用法说明。

## CI/CD

| 工作流 | 触发 | 产出 |
|---|---|---|
| `android-ci.yml` | PR / push 到 `feat/**`、`develop`、`master` | detekt + lint 报告、测试报告、Debug APK |
| `android-release.yml` | tag `android-tv-v*` / 手动 | AAB + APK + universal APK + OTA manifest + GitHub Release + Play(draft) |
| `android-tv-distribute.yml` | 手动 | Play 轨道晋级 / sideload 包 / 三方渠道 zip |
| `android-nightly-upstream-sync.yml` | 每日 02:00（北京时间） | 上游合并结果或冲突 issue |
| `android-benchmark.yml` | 每周一 / 手动 | macrobenchmark 编译产物 +（可选）FTL 基准 |

完整说明（含 Secrets 清单、分发矩阵、回滚策略）见 [docs/ci-cd.md](docs/ci-cd.md)。
