package com.ziymmx.wx.hook

import com.ziymmx.wx.hook.common.HookBridge
import com.ziymmx.wx.util.FeatureFlags
import org.luckypray.dexkit.DexKitBridge

/**
 * 统一功能安装入口。
 *
 * LSPosed 路径由 [com.ziymmx.wx.HookEntry] 传入 [FeatureFlags.ALL]；
 * Zygisk 路径由 native 侧读取 WebUI 配置得到的掩码传入，按开关安装对应 Hook。
 * 未开启的功能完全不会安装，运行期零开销。
 */
internal object FeatureInstaller {

    fun install(
        hook: HookBridge,
        dex: DexKitBridge,
        classLoader: ClassLoader,
        flags: Int,
    ) {
        val enabled = FeatureFlags.fromMask(flags)

        if (enabled and FeatureFlags.FORCE_TABLET != 0) {
            ForceTabletHook.install(hook, dex, classLoader)
        }
        if (enabled and FeatureFlags.ANTI_XPOSED_DETECT != 0) {
            PreventXposedDetectionHook.install(hook, dex, classLoader)
        }
        if (enabled and FeatureFlags.DISABLE_HOT_UPDATE != 0) {
            DisableHotUpdateHook.install(hook, classLoader)
        }
        if (enabled and FeatureFlags.ANTI_RECALL != 0) {
            AntiRecallHook.install(hook, dex, classLoader)
        }
        if (enabled and FeatureFlags.MOMENTS_ANTI_RECALL != 0 ||
            enabled and FeatureFlags.MOMENTS_COMMENT_ANTI_RECALL != 0 ||
            enabled and FeatureFlags.MOMENTS_AD_BLOCK != 0
        ) {
            MomentsHook.install(hook, dex, classLoader, enabled)
        }
    }
}
