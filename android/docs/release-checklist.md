# 发版清单

> 用法：复制一份到 issue / 文档里，逐条勾。每项都写了**负责人占位**与**要执行的命令**。
> 背景与原理见 [ci-cd.md](ci-cd.md)。
>
> 版本：`______`　负责人：`______`　日期：`______`

## 目录

- [A. 提审前（构建与验证）](#a-提审前构建与验证)
- [B. 提审（上传与提交审核）](#b-提审上传与提交审核)
- [C. 放量](#c-放量)
- [D. 事后](#d-事后)

---

## A. 提审前（构建与验证）

### A1. 代码与版本

- [ ] **开发分支干净，CI 全绿**　`@dev`
  ```bash
  git switch feat/android-tv-ime && git pull
  gh run list --workflow=android-ci.yml --limit 3
  ```

- [ ] **`android/version.properties` 的版本号与即将打的 tag 一致**　`@dev`
  ```bash
  cat android/version.properties
  bash scripts/android/compute-version.sh --next
  ```

- [ ] **`android/CHANGELOG.md` 已写入本次版本段落**　`@dev`
  ```bash
  bash scripts/android/bump-version-pr.sh --dry-run
  head -40 android/CHANGELOG.md
  ```

- [ ] **versionCode 未被历史版本占用**　`@dev`
  ```bash
  bash scripts/android/compute-version.sh android-tv-v<版本>
  git tag -l 'android-tv-v*' | while read t; do
    echo "$t -> $(bash scripts/android/compute-version.sh "$t" | grep VERSION_CODE)"
  done
  ```

### A2. 签名

- [ ] **签名 secret 齐全且可用**　`@release-manager`
  ```bash
  gh secret list | grep -E 'ANDROID_(KEYSTORE_BASE64|KEYSTORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)'
  ```

- [ ] **证书指纹与上一版一致**（换机器 / 换 secret 后必查）　`@release-manager`
  ```bash
  # 上一版发布页的 Step Summary 里有记录，本次发布后再次比对
  gh run view --log --job=$(gh run list --workflow=android-release.yml --limit 1 --json databaseId -q '.[0].databaseId') \
    | grep -i 'certificate SHA-256'
  ```

- [ ] **keystore 备份存在且可解密**（离线介质）　`@release-manager`
  - 备份位置：`______`
  - 最近一次验证时间：`______`

### A3. 功能自测（真机）

- [ ] **在至少 2 台不同品牌 TV / 盒子上跑通完整输入流程**　`@qa`
  - [ ] 安装后能在系统设置里找到并启用输入法
  - [ ] 遥控器选中输入框 → 弹出键盘 → 焦点在麦克风按钮
  - [ ] 按住说话 → 松手 → 文字提交到输入框
  - [ ] 宿主 App 切换（浏览器 / 搜索框 / 设置页）后仍可用
  - [ ] **焦点不兼容测试**：在 2 个第三方 App 里试，若出现"输入框无响应"，确认逃生开关可用

- [ ] **无麦克风设备降级正常**　`@qa`
  - [ ] 麦克风按钮置灰 + 有说明文案，不崩溃

- [ ] **权限拒绝路径正常**　`@qa`
  - [ ] 拒绝 `RECORD_AUDIO` 后仍能用遥控器键盘打字
  - [ ] 再次点击麦克风按钮会引导去授权，而不是死循环

- [ ] **登录过期路径正常**　`@qa`
  - [ ] 手工清除凭证后，提示重新登录，不白屏

### A4. 上架材料

- [ ] **隐私政策链接可访问**　`@pm`
  - URL：`______`

- [ ] **TV banner 320×180 已入包**　`@design`
  ```bash
  unzip -l android/app/build/outputs/apk/release/app-release.apk | grep -i banner
  ```

- [ ] **截图 1920×1080 三张以上，横屏，无测试数据**　`@design`
  - 存放位置：`______`

- [ ] **商店文案（简介 / 详细描述 / 本次更新）已定稿**　`@pm`
  - 本次更新文案：`______`

- [ ] **三方渠道包已生成**（若本次也要发国内市场）　`@ops`
  ```bash
  # GitHub → Actions → Android TV 分发 → Run workflow
  # version=<版本>  channel=third-party
  # 下载 artifact: android-tv-third-party-<版本>.zip
  ```

---

## B. 提审（上传与提交审核）

### B1. 触发发布

- [ ] **打 tag 并推送**　`@release-manager`
  ```bash
  git tag android-tv-v<版本>
  git push origin android-tv-v<版本>
  ```

  > ⚠️ 前缀必须是 `android-tv-v`。用 `v*` 会连带触发 macOS / Windows / Flatpak 三端构建。

- [ ] **确认 `android-release.yml` 启动且未触发桌面端工作流**　`@release-manager`
  ```bash
  gh run list --limit 5
  # 期望：只有 "Android TV Release" 在跑；没有 "Release macOS App" / "Windows Release" / "Flatpak"
  ```

- [ ] **确认四个作业步骤全绿：验签 / 版本号 / TV 声明 / IME 声明**　`@release-manager`
  ```bash
  gh run view <run-id> --log | grep -E '\[verify\]'
  ```

- [ ] **Release 资产齐全（5 个）**　`@release-manager`
  ```bash
  gh release view android-tv-v<版本> --json assets -q '.assets[].name'
  # 期望：*.aab / *.apk / *-universal.apk / SHA256SUMS.txt / manifest.json
  ```

### B2. 上传 Play

- [ ] **Play 应用已存在**（首次发布必须先在 Console 手工创建）　`@release-manager`
  - 首次上传手工完成于：`______`

- [ ] **AAB 已在 Play Console 的对应轨道可见（draft）**　`@release-manager`
  - 轨道：`internal`
  - 若自动上传被跳过（未配 `PLAY_SERVICE_ACCOUNT_JSON`），手工上传：
    ```bash
    gh release download android-tv-v<版本> --pattern '*.aab'
    # 然后到 Play Console → 内部测试 → 创建新版本 → 上传
    ```

- [ ] **Play 的"本次更新"文案已填**（`fastlane-metadata/<locale>/changelogs/<versionCode>.txt`）　`@pm`

### B3. 提交审核

- [ ] **内部测试轨道已发布并自测通过**　`@qa`
- [ ] **升级到 alpha 并提交审核**　`@release-manager`
  ```bash
  # GitHub → Actions → Android TV 分发 → Run workflow
  # version=<版本>  channel=play-alpha
  ```
- [ ] **审核问卷已填**：输入法类别、`RECORD_AUDIO` 用途、数据安全表单　`@pm`
- [ ] **记录提交时间**：`______`　`@release-manager`

---

## C. 放量

> 前提：审核通过，且 alpha 轨道已跑够 3–7 天没有 P0/P1 问题。

- [ ] **放量前 24h 观察指标正常**　`@dev`
  - 崩溃率：`______`（阈值 < 1%）
  - 识别成功率：`______`（阈值 > 95%）
  - 无新增 ANR 聚集

- [ ] **确认回滚预案**　`@release-manager`
  - 上一个稳定版本号：`______`
  - Play Console 的 Halt rollout 入口已确认可点

- [ ] **生产放量（第一步：小流量）**　`@release-manager`
  ```bash
  # GitHub → Actions → Android TV 分发 → Run workflow
  # version=<版本>  channel=play-production  confirm_production=true
  # 并在 Environment 审批弹窗里由第二名 reviewer 批准
  ```
  - [ ] **然后在 Play Console 手动改为 staged rollout 5%**（工作流上传的是 completed，需人工降到 5%）

- [ ] **5% 观察 24h**　`@dev`
  - 崩溃率：`______`　ANR：`______`　用户反馈：`______`

- [ ] **20% → 50% → 100% 逐级放量**，每级至少观察 12–24h　`@release-manager`
  - 20%：`______`（时间）
  - 50%：`______`
  - 100%：`______`

- [ ] **sideload 渠道同步发布**（如有盒子/投影客户）　`@ops`
  ```bash
  # GitHub → Actions → Android TV 分发 → version=<版本>  channel=sideload
  # 然后批量安装：
  bash scripts/android/adb-install-tv.sh --apk <universal.apk> --hosts-file hosts.txt
  ```

- [ ] **三方渠道提包**（当贝 / 沙发管家 / 小米 / 华为 / 应用宝）　`@ops`
  - [ ] 下载 `android-tv-third-party-<版本>.zip`
  - [ ] 各家后台提交，记录审核状态：
    | 市场 | 提交时间 | 状态 |
    |---|---|---|
    | 当贝 | | |
    | 沙发管家 | | |
    | 小米 | | |
    | 华为 | | |
    | 应用宝 | | |

---

## D. 事后

### D1. 收尾

- [ ] **Release 已标记为 latest（正式版）/ prerelease（预发布）**　`@release-manager`
  ```bash
  gh release view android-tv-v<版本> --json isPrerelease,isLatest
  ```

- [ ] **OTA manifest 已确认指向正确 URL**　`@ops`
  ```bash
  gh release download android-tv-v<版本> --pattern 'manifest.json' --output - | jq .
  ```

- [ ] **`SHA256SUMS.txt` 与实际资产一致**　`@ops`
  ```bash
  gh release download android-tv-v<版本>
  sha256sum -c SHA256SUMS.txt
  ```

### D2. 同步与记录

- [ ] **`master` 与上游保持一致**（本项目不改 master）　`@dev`
  ```bash
  git fetch upstream master
  git log --oneline -1 upstream/master
  git log --oneline -1 origin/master
  ```

- [ ] **在 CHANGELOG / Release 里补一句"已知问题"**（若有）　`@dev`

- [ ] **更新本文档的"上次发版"记录**　`@release-manager`
  - 上次发版：版本 `______`，日期 `______`，Play 放量完成时间 `______`

- [ ] **归档本次事故/异常**（若有）　`@dev`
  - 现象：`______`
  - 根因：`______`
  - 已加入排障文档：`[ ] 是 / [ ] 否`

### D3. 观察期（发版后 7 天）

- [ ] **Day 1**：崩溃率、ANR、商店评分、客服工单　`@dev`
- [ ] **Day 3**：识别成功率对比上一版，确认无回退　`@dev`
- [ ] **Day 7**：确认无 P0/P1 遗留，可关闭本次发版跟踪 issue　`@pm`
