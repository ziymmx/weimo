# 微末 Weimo

一个面向微信（`com.tencent.mm`）的增强模块，包名 `com.ziymmx.wx`。本项目完全由 AI 驱动开发与维护。

> 仅作学习交流使用，请勿用于违反当地法律法规、微信用户协议或其他不正当用途。

## 功能

| 功能 | 说明 |
| --- | --- |
| 防撤回 | 拦截 `revokemsg` 系统消息，对方撤回的消息原样保留在聊天界面；同时插入「xxx 撤回了一条消息」提示，点击提示可滚动并高亮定位到原消息 |
| 强制平板模式 | 让微信将当前设备识别为平板/折叠屏设备，启用平板布局，并放行「以平板身份登录」校验 |
| 阻止 Xposed 检测 | 让微信调用栈检测返回 false，隐藏 Xposed 框架痕迹 |
| 禁用热更新 | 关闭微信内置 Tinker 热更新机制，避免被静默更新到不兼容版本 |
| 朋友圈防撤回（实验） | 保留被撤回的朋友圈内容 |
| 朋友圈评论防撤回（实验） | 保留被撤回的朋友圈评论 |
| 朋友圈广告拦截（实验） | 过滤朋友圈信息流中的广告 |

所有功能**默认启用**；LSPosed 版无需任何配置，Zygisk 版可通过 WebUI 单独开关每个功能。

## 特点

- 全部逻辑在本地实现，**不申请任何网络权限**，不会联网上传任何数据；
- 不嵌入、不修改微信聊天界面与布局（仅登录页会显示微信自带的「登录其他设备」按钮，用于平板登录流程）；
- 安装后**不生成桌面图标**，在 LSPosed 中启用并勾选微信即生效（LSPosed 版）；Zygisk 版仅作用于微信主进程，其他进程零开销；
- 针对微信 v8.0.76 验证，兼容大部分 8.x 版本；
- 基于 libxposed / Pine + DexKit，运行时动态定位微信内部方法，不依赖具体混淆名。

## 环境要求

- Android 13（API 33）及以上
- LSPosed 版：需要 [LSPosed](https://github.com/LSPosed/LSPosed)（或其他兼容 libxposed API 的框架）
- Zygisk 版：需要 KernelSU / Magisk（启用 Zygisk）
- 微信 8.x 版本（推荐 v8.0.76）

## 使用方式

### LSPosed 版

1. 安装微末 APK（见「获取构建产物」）；
2. 打开 LSPosed → 模块，启用「微末」并勾选作用域 `com.tencent.mm`；
3. 重启微信（建议先清除微信后台进程）。

### Zygisk 版

1. 下载 `weimo-zygisk-<version>.zip`（见「获取构建产物」）；
2. 在 KernelSU / Magisk 中安装该模块并重启；
3. 在 KernelSU 的模块 WebUI 中可单独开关各功能（默认全部启用）；
4. 修改开关后重启微信生效（也可在模块「动作」中一键重启微信）。

## 获取构建产物

本项目通过 GitHub Actions 自动编译，构建产物以 **Actions Artifact** 形式保留，不发布到 Releases：

1. 打开仓库的 **Actions** 页面；
2. 进入最新的 **Android CI** 工作流运行记录；
3. 在页面底部的 **Artifacts** 区域下载：
   - `weimo-apks`：LSPosed 版 APK（`app-release.apk` 已签名，可直接安装；`app-debug.apk` 为调试版）；
   - `weimo-zygisk`：Zygisk 模块 ZIP（`weimo-zygisk-<version>.zip`）。

## 本地构建

需要 JDK 17 与 Android SDK（compileSdk 37）。

```bash
git clone <your-repo-url>
cd weimo

# 可选：生成 release 签名（不生成则 release 为未签名 APK）
keytool -genkeypair -v \
  -keystore release.keystore -alias weimo \
  -keyalg RSA -keysize 2048 -validity 3650 \
  -storepass weimo123 -keypass weimo123 \
  -dname "CN=Weimo, OU=Weimo, O=Weimo, L=CN, ST=CN, C=CN"

./gradlew assembleDebug assembleRelease
```

APK 产物位于 `app/build/outputs/apk/`。

构建 Zygisk 模块还需要 Android NDK（r27c 及以上）：

```bash
bash zygisk/scripts/build-zygisk.sh \
  app/build/outputs/apk/release/app-release.apk dist
```

模块 ZIP 产物位于 `dist/weimo-zygisk-<version>.zip`。

## 技术说明

- LSPosed 入口：`com.ziymmx.wx.HookEntry`（继承 `io.github.libxposed.api.XposedModule`）；
- Zygisk 入口：原生侧 `zygisk/jni/module.cpp` 仅拦截微信主进程，通过 root companion 读取 WebUI 配置的功能掩码，再经 `com.ziymmx.wx.loader.zygisk.ZygiskEntry`（Pine）注入；
- 使用 [DexKit](https://github.com/LuckyPray/DexKit) 在运行时定位微信内部方法并 Hook；
- 功能开关配置持久化在 `/data/adb/weimo_zygisk/config.tsv`，缺失时按「全部启用」处理。

## 致谢

- [WeKite](https://github.com/SymonChu/WeKite)：防撤回提示、消息存储与 Zygisk 注入相关思路
- [TabletHook](https://github.com/roro2239/TabletHook)：平板布局强制启用相关思路
- [I-Am-Pad](https://github.com/Houvven/I-Am-Pad)：平板模式、登录校验与日志精简相关思路

## 免责声明

本项目与腾讯公司及微信官方无关，未获得微信官方任何形式的认可或授权。使用本项目产生的任何后果由使用者自行承担。