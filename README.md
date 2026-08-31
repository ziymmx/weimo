# 微末 Weimo

一个面向微信（`com.tencent.mm`）的 Xposed 增强模块，包名 `com.ziymmx.wx`。本项目完全由 AI 驱动开发与维护。

> 仅作学习交流使用，请勿用于违反当地法律法规、微信用户协议或其他不正当用途。

## 功能

| 功能 | 说明 |
| --- | --- |
| 防撤回 | 拦截 `revokemsg` 系统消息，对方撤回的消息原样保留在聊天界面；同时插入「xxx 撤回了一条消息」提示，点击提示可滚动并高亮定位到原消息 |
| 强制平板模式 | 让微信将当前设备识别为平板/折叠屏设备，启用平板布局，并放行「以平板身份登录」校验 |
| 阻止 Xposed 检测 | 让微信调用栈检测返回 false，隐藏 Xposed 框架痕迹 |
| 禁用热更新 | 关闭微信内置 Tinker 热更新机制，避免被静默更新到不兼容版本 |

所有功能**默认启用**，无需任何配置，也没有需要打开的界面。

## 特点

- 全部逻辑在本地实现，**不申请任何网络权限**，不会联网上传任何数据；
- 不嵌入、不修改微信聊天界面与布局（仅登录页会显示微信自带的「登录其他设备」按钮，用于平板登录流程）；
- 安装后**不生成桌面图标**，在 LSPosed 中启用并勾选微信即生效；
- 针对微信 v8.0.76 验证，兼容大部分 8.x 版本；
- 基于 libxposed + DexKit，运行时动态定位微信内部方法，不依赖具体混淆名。

## 环境要求

- Android 9.0（API 28）及以上
- [LSPosed](https://github.com/LSPosed/LSPosed)（或其他兼容 libxposed API 的框架）
- 微信 8.x 版本（推荐 v8.0.76）

## 使用方式

1. 安装微末 APK（见「获取构建产物」）；
2. 打开 LSPosed → 模块，启用「微末」并勾选作用域 `com.tencent.mm`；
3. 重启微信（建议先清除微信后台进程）。

## 获取构建产物

本项目通过 GitHub Actions 自动编译，构建产物以 **Actions Artifact** 形式保留，不发布到 Releases：

1. 打开仓库的 **Actions** 页面；
2. 进入最新的 **Android CI** 工作流运行记录；
3. 在页面底部的 **Artifacts** 区域下载 `weimo-apks`；
4. 解压后安装 `app-release.apk`（已签名，可直接安装）或 `app-debug.apk`。

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

产物位于 `app/build/outputs/apk/`。

## 技术说明

- 入口：`com.ziymmx.wx.HookEntry`（继承 `io.github.libxposed.api.XposedModule`）；
- 使用 [DexKit](https://github.com/LuckyPray/DexKit) 在运行时定位微信内部方法并 Hook；
- `module.prop`：`minApiVersion=101`，`targetApiVersion=102`，`staticScope=true`；
- 图标：`art/icon-source.webp` 为基础素材，由 `tools/make_icons.py` 生成各分辨率 mipmap。

## 致谢

- [WeKite](https://github.com/SymonChu/WeKite)：防撤回提示与消息存储相关思路
- [TabletHook](https://github.com/roro2239/TabletHook)：平板布局强制启用相关思路
- [I-Am-Pad](https://github.com/Houvven/I-Am-Pad)：平板模式与登录校验相关思路

## 免责声明

本项目与腾讯公司及微信团队无关，未获得微信官方任何形式的认可或授权。使用本项目产生的任何后果由使用者自行承担。
