package com.ziymmx.wx

import android.os.Process
import com.ziymmx.wx.hook.FeatureInstaller
import com.ziymmx.wx.hook.xposed.XposedHookBridge
import com.ziymmx.wx.util.FeatureFlags
import com.ziymmx.wx.util.HookUtils
import com.ziymmx.wx.util.WeLogger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge

/**
 * 微末 Weimo —— 微信 Xposed 模块入口（LSPosed 路径）。
 *
 * 全部功能默认启用，仅作用于 com.tencent.mm 主进程，不注入任何微信 UI，
 * 不申请任何网络权限，所有逻辑均在本地完成。
 */
class HookEntry : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        // 仅在微信主进程（com.tencent.mm）中安装 Hook：
        // 子进程（如 xweb_privileged_process_0、push 等）不会初始化微信
        // ServiceManager，过早调用 getService 会抛
        // IllegalStateException: please call initialize(...) first。
        if (param.packageName != TARGET_PACKAGE) return
        if (Process.myProcessName() != TARGET_PACKAGE) return
        if (!param.isFirstPackage) return

        val logger = XposedHookBridge(this)
        try {
            // 从模块自身 native 目录加载 DexKit，再针对微信 APK 建立索引。
            HookUtils.loadDexKitLibrary(getModuleApplicationInfo().nativeLibraryDir)

            DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                FeatureInstaller.install(
                    hook = logger,
                    dex = bridge,
                    classLoader = param.classLoader,
                    flags = FeatureFlags.ALL,
                )
            }
        } catch (t: Throwable) {
            WeLogger.e(logger, "Weimo 模块 Hook 初始化失败", t)
        }
    }

    companion object {
        private const val TARGET_PACKAGE = "com.tencent.mm"
    }
}
