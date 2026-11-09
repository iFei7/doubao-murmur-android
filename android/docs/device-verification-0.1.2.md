# 0.1.2 真机验收清单

适用版本：`android-tv-v0.1.2`（versionCode 102）。
本版同时修复「唤起 IME 崩溃循环」与「竖屏手机不可用」，两条线是**链式依赖**关系，必须同一个包验证。

---

## 0. 先确认装的是哪个包

```bash
adb shell dumpsys package com.doubao.murmur.tv | grep -E 'versionName|versionCode'
# 期望 versionName=0.1.2  versionCode=102
adb install -r doubao-murmur-tv-0.1.2-universal.apk
```

若显示的仍是 0.1.0 / 0.1.1，后面所有结论都无效——先覆盖安装。

一键抓日志（仓库脚本，自动定位 adb）：

```bash
bash scripts/android/grab-ime-crash.sh
```

---

## 1. 这次修了什么（崩溃是三层链）

| 层 | 阶段 | 异常 | 只在何时出现 |
|---|---|---|---|
| L1 | 未 attach 时框架 measure inputView | `Cannot locate windowRecomposer; View ... is not attached to a window` | v0.1.0；已由 `ImeComposeContainer` 挡住 |
| L2 | attach 时组合创建，`getWindowRecomposer` 沿 parent 找 owner | `IllegalStateException: ViewTreeLifecycleOwner not found from ...` | **v0.1.1 的主因**，与 TV/手机无关；0.1.2 把三个 owner 提升到 window 顶层 |
| L3 | measure 阶段主 Row 固定宽 532dp > 可用 264dp | `IllegalArgumentException: Cannot coerce value to an empty range: maximum ... is less than minimum 0.` | **仅手机竖屏**，且只在 L2 修好后才暴露；0.1.2 改响应式布局 |

> 关键判别：如果真机上抓到 **L3 的 `Cannot coerce value to an empty range`**，说明 **L2 已经修好了**，只是进入了下一层，不要误判成 owner 提升没生效。

另外补了三处非崩溃问题：IME 协程异常兜底（`TextCommitter` 局部吞 Binder 异常 + `CoroutineExceptionHandler`）、占位阶段 insets 不再吞掉全屏触摸、OkHttp WS 回调异常不再杀进程。

---

## 2. TV 横屏验证（回归，确认没有改坏原有行为）

1. 点任意输入框 → **键盘 UI 正常绘制**（不再空白）
2. **不再弹「豆包语音输入法屡次停止运行」**，连续唤起 5 次都稳定
3. 长按 OK 键说话 → 松手 → 文字上屏
4. D-pad 上下左右移动焦点、焦点高亮 1.15× 正常
5. BACK 收起键盘 / 录音中 BACK 取消，行为与 0.1.0 预期一致
6. 键盘布局与 0.1.1 **视觉一致**（TV 分支值逐字保留）

## 3. 手机竖屏验证（本版新增能力）

1. 应用能从**手机启动器**打开（`leanback required=false`），且**不再被强制横屏**
2. 首页三张状态卡上下堆叠、可纵向滚动，按钮全部可见可点、无裁切
3. 点输入框 → 键盘弹出，**不崩溃、不空白**
4. 键盘为纵向布局：状态头 → 转写卡 → 麦克风按钮 → 动作网格 → 提示条；**所有按钮都在屏内**
5. 按住麦克风按钮说话、松手上屏；点按可锁定/结束录音（400ms 阈值生效，无误触）
6. 提示条显示手机文案，不出现「长按 OK / SEARCH / MENU」
7. 登录页、诊断页、IME 设置页在 360dp 下不横向溢出，按钮可点

### 自动化辅助

```bash
# 所有按钮 bounds 必须落在屏宽内（防止「用无界约束绕开断言」的假修复）
adb shell uiautomator dump /sdcard/w.xml && adb shell cat /sdcard/w.xml | grep -o 'bounds="[^"]*"' | tail -20
adb shell wm size
adb shell dumpsys window | grep -i mCurrentFocus
```

---

## 4. logcat 判别表

```bash
adb logcat -v threadtime -b main -b crash | grep -iE \
  "ViewTreeLifecycleOwner|windowRecomposer|Cannot locate|FATAL EXCEPTION|DeadObject|OkHttp Dispatcher|Cannot coerce value to an empty range|ANR in"
```

| 看到什么 | 判定 |
|---|---|
| 无任何 FATAL、键盘正常 | ✅ 修复生效 |
| `ViewTreeLifecycleOwner not found from ...` | L2 未修好（或装的是 0.1.1） |
| `Cannot locate windowRecomposer; View ... is not attached` + 栈含 `onMeasure` | 装的是 **0.1.0** 老包 |
| `Cannot coerce value to an empty range` | L2 已修好，L3 未修好（或装错包） |
| `FATAL EXCEPTION: main` + `DeadObjectException` / `TextCommitter` | 提交链路异常兜底失效 |
| `FATAL EXCEPTION: OkHttp Dispatcher` | ASR 回调兜底失效 |
| 无 FATAL 但按键/触摸完全无响应 | 查 `adb shell dumpsys input_method` 的 `touchableInsets` 是否为 REGION、region 矩形是否 ≤1/3 屏高 |

ANR 补充（如果表现为「卡住」而非「闪退」）：

```bash
adb logcat -b events | grep am_anr
adb shell ls -l /data/anr/ && adb pull /data/anr/ .
```

---

## 5. 仍需真机确认、静态无法验证的项

1. **touchableRegion**：0.1.2 的 `onComputeInsets` 改用外层 container 推导矩形，布局宽高变化后 region 是否与实际可见按钮对齐（错位会表现为「点不到」）。
   - 额外注意：占位分支用 `resources.displayMetrics` 的全局屏宽高算 region，已布局分支用 `container.getLocationInWindow()` 的 IME 窗口坐标系，**两分支坐标来源不同**。占位期很短且两分支都发布有界 region（不吞全屏），属「需真机验证」而非已确认缺陷；请确认竖屏下占位期 region 不覆盖宿主输入区、且与 attach 后真实矩形平滑衔接。若发现偏移，可选改进是统一两分支的坐标来源（占位期改取 IME window decor 尺寸），但 rootView 尺寸在占位阶段可能尚未确定，`displayMetrics` 是当时的可靠兜底，故不列为待改项。
2. **系统栏 insets**：主题保留 `windowTranslucentStatus/Navigation=true`，`safeDrawingPadding()` 在刘海/挖孔屏、手势导航栏下是否足额，纵向滚动内容是否被底部导航条遮住。
3. **旋转 / 配置变更**：`configChanges` 只保护 Activity，IME 是 Service 不受保护；旋转时 `onCreateInputView` 二次触发与旧 `composeView.dispose()` 是否和 window decor 上缓存的 recomposer 冲突。请横竖屏各切一次再唤起键盘。
4. **`smallestScreenWidthDp` 取值**：分屏 / 小窗 / 部分 TV ROM 下该值可能不等于预期，导致 Compact 判定偏差。
5. **长按阈值手感**：400ms 是否偏长、是否与宿主的长按点击冲突。
6. **30s 长会话**：点按锁定后切走宿主输入框、让 30s 守卫触发，确认 `commitFinal` 正常上屏且无竞态异常。

---

## 6. 如果仍有问题

把 `bash scripts/android/grab-ime-crash.sh` 生成的 `ime-crash-<时间戳>.log` 回传即可，里面含设备型号、已安装版本、crash buffer、DropBox、ANR 线索与 IME 生命周期日志。
