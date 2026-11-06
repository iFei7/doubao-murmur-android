#!/usr/bin/env bash
#
# 生成 Gradle Wrapper。
#
# 为什么需要这个脚本：gradle-wrapper.jar 是二进制文件，无法用文本方式手写进仓库，
# 因此仓库里只提交 gradle/wrapper/gradle-wrapper.properties，jar 由本脚本（或 CI 的
# gradle/actions/setup-gradle）生成。首次克隆仓库后请先执行一次，之后即可直接用
# ./gradlew 构建。
#
# 用法：
#   bash scripts/android/bootstrap-wrapper.sh
#
set -euo pipefail

GRADLE_VERSION="8.9"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "${SCRIPT_DIR}/../../android" && pwd)"

if [ ! -f "${ANDROID_DIR}/gradle/wrapper/gradle-wrapper.properties" ]; then
    echo "错误：找不到 ${ANDROID_DIR}/gradle/wrapper/gradle-wrapper.properties" >&2
    exit 1
fi

cd "${ANDROID_DIR}"

if command -v gradle >/dev/null 2>&1; then
    CURRENT_VERSION="$(gradle --version 2>/dev/null | awk '/^Gradle /{print $2; exit}')"
    echo "==> 检测到本机 Gradle ${CURRENT_VERSION:-unknown}，用它生成 ${GRADLE_VERSION} 的 wrapper"
    # --distribution-type bin：只下载 bin 包（不含源码与文档），体积更小。
    gradle wrapper --gradle-version "${GRADLE_VERSION}" --distribution-type bin
    echo "==> 完成：android/gradlew、android/gradlew.bat、android/gradle/wrapper/gradle-wrapper.jar"
    echo "==> 校验：cd android && ./gradlew --version"
    exit 0
fi

cat >&2 <<'EOF'
未检测到本机的 gradle 命令，无法自动生成 gradle-wrapper.jar。请在下列任一种方式中完成引导：

方式一（推荐，有 JDK 17 的机器上安装 Gradle 8.9）
  1. 安装 Gradle 8.9（例如 sdkman: sdk install gradle 8.9，或 Homebrew: brew install gradle）
  2. 重新执行本脚本：bash scripts/android/bootstrap-wrapper.sh
  3. 之后在 android/ 目录使用 ./gradlew

方式二（手工下载官方分发包）
  1. 下载 https://services.gradle.org/distributions/gradle-8.9-bin.zip 并解压
  2. 进入 android/ 目录执行：<解压目录>/bin/gradle wrapper --gradle-version 8.9 --distribution-type bin
  3. 提交生成的 gradlew、gradlew.bat 与 gradle/wrapper/gradle-wrapper.jar

方式三（CI）
  GitHub Actions 使用 gradle/actions/setup-gradle@v4 或 gradle/actions/wrapper-validation，
  即可在没有 wrapper jar 的情况下运行构建（见 .github/workflows 中的 Android 工作流）。

注意：Gradle 8.9 需要 JDK 17 运行；Android 构建本身还要求安装 Android SDK（compileSdk 35）。
EOF

exit 1
