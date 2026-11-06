# 豆包语音输入法（TV）__VERSION__ 手动安装说明

- versionCode: `__VERSION_CODE__`
- 安装包: `__APK_NAME__`

## 一、单台安装

1. TV 打开「开发者选项 → 网络调试（ADB）」，记下电视的 IP。
2. 电脑执行：

```bash
adb connect <TV_IP>:5555
adb install -r "__APK_NAME__"
```

3. TV 设置 → 系统 → 语言和输入法 → 当前输入法 → 选择「豆包语音输入法（TV）」。
4. 首次使用需要授予麦克风权限（应用内会引导）。

## 二、批量安装（多台盒子 / 投影）

```bash
./adb-install-tv.sh --apk "__APK_NAME__" --hosts "192.168.1.20:5555 192.168.1.21:5555"
```

设备列表也可以写在文件里，每行一个 `host:port`：

```bash
./adb-install-tv.sh --apk "__APK_NAME__" --hosts-file hosts.txt
```

## 三、校验下载完整性

```bash
sha256sum -c SHA256SUMS.txt
```

## 四、升级与降级

- 升级：直接 `adb install -r` 覆盖安装，用户数据（登录态）保留。
- 降级：sideload 渠道允许 `adb install -r -d`（需自行加 `--downgrade` 参数）；
  **Play 渠道的 versionCode 不可回退**，只能发更大的版本号。

## 五、卸载

```bash
adb uninstall com.doubao.murmur.tv
```

> 卸载前请先在 TV 设置里把输入法切回系统键盘，否则部分 ROM 会短暂出现"无输入法"状态。
