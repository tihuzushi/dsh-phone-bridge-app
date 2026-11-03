[English](README.en.md) | 中文

# DSH Phone Bridge — Android 客户端

**这是桥接客户端，不是手机版 DSH。** DSH 跑在你的电脑上，这个 App 只是把那台电脑的界面搬到手机上。

- **电脑关机或断网，App 就用不了** —— 它没有自己的 DSH，只是个遥控器
- 会话、文件、插件、密钥都在电脑上，App 里不留一份
- **支持远程连接**：手机用 4G/5G、电脑放在家里，跨城市跨运营商都能连，不需要在同一个 WiFi
- 内置 `tsnet` 用户态 Tailscale 节点：**不接管系统 VPN**，也不用装 Tailscale App

> 这是 **DSH Phone Bridge** 项目的安卓仓库。电脑端（DSH 插件）在另一个仓库：
> **[tihuzushi/dsh-phone-bridge](https://github.com/tihuzushi/dsh-phone-bridge)** ← 先装那个，手机才有东西可连。

📦 下载 APK

**短链（永远指向最新版）**：<https://www.hanhaichem.cn/dsh.apk>

当前版本的直链（2.4.3）：<https://www.hanhaichem.cn/wp-content/uploads/2026/11/DSH-Phone-Bridge-2.4.3.apk>

GitHub Releases（原仓库，国内可能很慢）：<https://github.com/tihuzushi/dsh-phone-bridge-app/releases/latest>

装之前校验一下，哈希对不上就别装：

```powershell
Get-FileHash .\DSH-Phone-Bridge-2.4.3.apk -Algorithm SHA256
# 应为 5AD53680C96E27D0D953C7628A3F336B995F30D2E592666D991970870F0ED5EA
```

> App 里「检查更新 → 直接下载」走的也是这个直连，不会跳 GitHub。
> 短链在每次发版后自动指向新文件，不用改这里。

🎬 作者 B 站主页

<https://space.bilibili.com/157183587>

---

## 从零开始配置（照着做完就能用）

整个项目分两半：**电脑上的 DSH 插件**（桥接 + 手机版界面）和**手机上的这个 APK**。两边通过 Tailscale 的 tailnet 连起来：优先点对点直连，直连打不通才走 Tailscale 的加密中继——这个项目自己没有服务器。

### 第 0 步：准备

| 需要的 | 说明 |
|---|---|
| 一台 Windows 电脑 | 装 DeepSeek Harness（DSH），能正常打开它的界面 |
| 一台安卓手机 | Android 8.0（API 26）及以上 |
| 一个 Tailscale 账号 | 免费版够用，<https://login.tailscale.com/start> 注册 |

### 第 1 步：电脑装 Tailscale

1. 打开 <https://tailscale.com/download/windows> 下载安装
2. 安装完在任务栏托盘里登录同一个 Tailscale 账号，点 **Connect**
3. 确认状态是 Connected（设置页会显示这台电脑的设备名）

> 为什么要这一步：手机端虽然自带 Tailscale，但**电脑也必须在同一个 tailnet 里**，手机才找得到它。

### 第 2 步：电脑装 DSH 插件

在 DSH 里：**设置 → 插件 → 添加插件**，填：

```
https://github.com/tihuzushi/dsh-phone-bridge
```

装完**重启 DSH**。然后在 **设置 → DSH Phone Assistant** 里应该能看到：

- 状态：运行中
- **配对码**（6 位数字，下面要用）
- Tailscale 一栏显示「已连接」+ 你的设备名
- 端口：默认 8765

如果 Tailscale 一栏是红的「这台电脑没有安装」，回第 1 步。

### 第 3 步：手机装这个 App

1. 打开 <https://github.com/tihuzushi/dsh-phone-bridge-app/releases/latest>
2. 下载 `DSH-Phone-Bridge-<版本>.apk`
3. 手机上点开安装。首次会提示「允许安装未知来源应用」，允许即可（APK 用本仓库的固定密钥签名，没有上架应用商店）

### 第 4 步：手机登录 Tailscale

1. 打开 App → 右上角进 **连接设置**
2. 点「打开 Tailscale 登录页」，在浏览器里登录**同一个 Tailscale 账号**
3. 回到 App，等状态变成「已连接」

> App 里的 Tailscale 是内嵌的，跟你在第 1 步电脑上装的那个是同一个账号下的两个节点。

### 第 5 步：选电脑 + 填配对码

1. 在「连接设备」里选中你的电脑（列表来自你的 tailnet）
2. 填上第 2 步电脑设置页里显示的 **6 位配对码**
3. 点「保存并连接」

连上以后，DSH 的界面就会在 App 里打开，手机版布局由电脑上的插件注入。

### 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 一直闪屏、进不去 | 配对码不对，App 在反复重试。去电脑设置页看当前配对码；插件 0.4.5 起不会因为重试把你锁死 |
| 设备列表是空的 | 手机端 Tailscale 没登录，或电脑没连上 tailnet |
| 电脑设置页显示「未运行」 | 插件没启动，重启 DSH；仍不行看 8765 端口是否被别的程序占用 |
| 换了电脑 / 重装了插件 | 配对码会重新生成，重新填一次即可 |

---

## 它做什么

1. 用内置的 `tsnet` 登录你自己的 tailnet，列出账号里的电脑
2. 连上电脑上 DSH 的 8765 端口（cookie + 配对码）
3. 在 WebView 里打开 DSH，手机版界面由插件注入
4. 文件预览 / 下载 / 用其他应用打开，都走 App 自己的下载目录

## 版本与更新

设置页最下面有「DSH PHONE BRIDGE / 当前版本 / 检查更新」。点检查更新会去
`api.github.com/repos/tihuzushi/dsh-phone-bridge-app/releases/latest` 比版本，
有新版本会弹窗，**弹窗里带这次发布的更新日志**，点「去下载」用系统浏览器打开 Release
页面，不在 App 内下载安装包。

## 自己编译

需要 JDK 17、Android SDK（`platforms/android-34` + `build-tools/34.0.0`），以及
`libs/tsnetmobile.aar`（**不在本仓库内**，见下）。

```powershell
# 1) 生成 tsnet 绑定（只做一次）
#    gomobile bind -target=android/arm64,android/amd64 -androidapi 26 \
#        -o android/libs/tsnetmobile.aar ./tsnetmobile
#    构建脚本会自己从 aar 里解出 classes.jar 和 jni/*.so

# 2) 编译（没有签名密钥时产出 unsigned APK）
$env:HP_STORE_PASS = "<你的 keystore 密码>"
powershell -ExecutionPolicy Bypass -File android\build-apk.ps1 `
    -Version 2.4.3 -VersionCode 71 `
    -KeystorePath C:\path\to\your.jks
```

产物：`android/dist/DSH-Phone-Bridge-<version>.apk`。

### 仓库里为什么没有这些

| 东西 | 原因 |
|---|---|
| `libs/tsnetmobile.aar`（30 MB） | 里面是 `gomobile` 打包的 Tailscale `tsnet`（BSD-3-Clause），属于构建产物，本地生成，不重复分发 |
| `keystore/*.jks` | 签名密钥，绝不进仓库 |
| `dist/*.apk` | 构建产物，正式包只在 Releases |

`tsnet` 属于 Tailscale Inc.，按其 BSD-3-Clause 许可证使用；本项目只调用其公开 API。
打出来的 APK 里那个 `libgojni.so` 还静态链接了 Tailscale 自己的依赖，完整版权声明和
许可证全文见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)，同时也会被打进 APK：
`assets/licenses/THIRD-PARTY-NOTICES.txt`。

## 结构

```
android/
  build-apk.ps1                       # aapt2 + javac + d8 + zipalign + apksigner
  app/src/main/
    AndroidManifest.xml
    java/com/harness/phone/v2/
      MainActivity.java               # WebView、文件桥、重连
      ConnectionSettingsActivity.java # Tailscale 登录 / 选设备 / 配对码 / 检查更新
      UpdateChecker.java              # 检查更新 + 更新日志弹窗
      TsnetManager.java, TsnetService.java
      FileOpenService.java, HarnessFileProvider.java
    res/                              # 图标、主题、字符串
    assets/offline/                   # 离线壳：没连上电脑时显示的界面
```

## 相关仓库

- **[tihuzushi/dsh-phone-bridge](https://github.com/tihuzushi/dsh-phone-bridge)** — 电脑端 DSH 插件：桥接服务 + 手机版界面 + 配对码/端口设置
- **[tihuzushi/dsh-phone-bridge-app](https://github.com/tihuzushi/dsh-phone-bridge-app)** — 本仓库：安卓客户端

## 许可证

**本项目自己写的代码**（Java、离线壳的 JS/CSS、构建脚本）是 MIT，见 [LICENSE](LICENSE)。

**发出去的 APK 不是纯 MIT**。它内含的 `libgojni.so`（内置 Tailscale 用户态节点）静态
链接了 29 个第三方 Go 模块：BSD-3-Clause × 16、BSD-2-Clause × 2、MIT × 5、
Apache-2.0 × 6。它们都是宽松许可证，跟 MIT 兼容、不要求开源本项目，但要求二进制
分发时保留版权声明和许可证全文，所以完整清单在
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

那份清单是 `go version -m libgojni.so` 直接从 APK 里的库读出来的，不是手写的。

---

## 写这个的人

B 站主页

<https://space.bilibili.com/157183587>
