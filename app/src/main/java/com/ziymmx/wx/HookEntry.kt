package com.ziymmx.wx

import android.os.Process
import android.util.Log
import com.ziymmx.wx.hook.AntiRecallHook
import com.ziymmx.wx.hook.DisableHotUpdateHook
import com.ziymmx.wx.hook.ForceTabletHook
import com.ziymmx.wx.hook.PreventXposedDetectionHook
import com.ziymmx.wx.util.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge

/**
 * 微末 Weimo —— 微信 Xposed 模块入口。
 *
 * 全部功能默认启用，仅作用于 com.tencent.mm，不注入任何微信 UI，
 * 不申请任何网络权限，所有逻辑均在本地完成。
 */
class HookEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // 仅首次激活时打印一次；之后正常启动不再输出成功日志，
        // 避免每次打开微信都刷新 LSPosed 日志。错误仍会以 WARN/ERROR 输出。
        runCatching {
            val prefs = getRemotePreferences("weimo_prefs")
            if (!prefs.getBoolean("activated", false)) {
                log(Log.INFO, HookUtils.TAG, "微末模块已激活")
                prefs.edit().putBoolean("activated", true).apply()
            }
        }.onFailure { /* 远程偏好不可用时静默，不刷日志 */ }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // 仅在微信主进程（com.tencent.mm）中安装 Hook：
        // 子进程（如 xweb_privileged_process_0、push 等）不会初始化微信
        // ServiceManager，过早调用 getService 会抛
        // IllegalStateException: please call initialize(...) first。
        if (param.packageName != TARGET_PACKAGE) return
        if (Process.myProcessName() != TARGET_PACKAGE) return
        if (!param.isFirstPackage) return

        try {
            // 从模块自身 native 目录加载 DexKit，再针对微信 APK 建立索引。
            HookUtils.loadDexKitLibrary(getModuleApplicationInfo().nativeLibraryDir)

            DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                val classLoader = param.classLoader

                ForceTabletHook.install(this, bridge, classLoader)
                PreventXposedDetectionHook.install(this, bridge, classLoader)
                DisableHotUpdateHook.install(this, classLoader)
                AntiRecallHook.install(this, bridge, classLoader)

            }
        } catch (t: Throwable) {
            log(Log.ERROR, HookUtils.TAG, "微末模块 Hook 初始化失败", t)
        }
    }

    companion object {
        private const val TARGET_PACKAGE = "com.tencent.mm"
    }
}