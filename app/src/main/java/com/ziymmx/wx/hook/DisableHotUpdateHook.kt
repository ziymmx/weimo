package com.ziymmx.wx.hook

import com.ziymmx.wx.hook.common.HookBridge

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.ziymmx.wx.util.HookUtils
import com.ziymmx.wx.util.WeLogger
import java.io.File

/**
 * 禁用微信热更新（Tinker）机制。
 *
 * 热更新是微信内置的 Tinker 框架在后台拉取补丁并静默更新自身，
 * 可能造成模块失效或与当前版本不兼容。本模块将 Tinker 的
 * isTinkerEnabled* 系列方法全部改为 false，并尽力清理补丁目录、
 * 禁用相关服务组件（全部为尽力而为，失败不影响主功能）。
 */
internal object DisableHotUpdateHook {

    private val componentNames = listOf(
        "com.tencent.tinker.lib.service.TinkerPatchForeService",
        "com.tencent.tinker.lib.service.TinkerPatchService",
        "com.tencent.tinker.lib.service.TinkerPatchService\$InnerService",
        "com.tencent.tinker.lib.service.DefaultTinkerResultService"
    )

    fun install(hook: HookBridge, classLoader: ClassLoader) {
        hookTinkerEnabled(hook, classLoader)
        cleanupTinker(classLoader)
    }

    private fun hookTinkerEnabled(hook: HookBridge, classLoader: ClassLoader) {
        runCatching {
            val clazz = classLoader.loadClass("com.tencent.tinker.loader.shareutil.ShareTinkerInternals")
            val methods = clazz.declaredMethods.filter { it.name.startsWith("isTinkerEnabled") }
            if (methods.isEmpty()) {
                return
            }
            methods.forEach { method ->
                runCatching {
                    HookUtils.hookBooleanMethod(
                        hook,
                        method,
                        "weimo_tinker_${method.name}",
                        false
                    )
                }.onFailure { WeLogger.w(hook, "Tinker hook 失败：${method.name}", it) }
            }
        }.onFailure { WeLogger.w(hook, "禁用热更新 hook 异常", it) }
    }

    private fun cleanupTinker(classLoader: ClassLoader) {
        val context = runCatching {
            val activityThread = classLoader.loadClass("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull() ?: return

        runCatching {
            val pm = context.packageManager
            componentNames.forEach { name ->
                runCatching {
                    pm.setComponentEnabledSetting(
                        ComponentName(context.packageName, name),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }

        runCatching {
            val tinkerDir = File(context.applicationInfo.dataDir, "tinker")
            if (tinkerDir.exists()) {
                tinkerDir.deleteRecursively()
            }
        }
    }
}