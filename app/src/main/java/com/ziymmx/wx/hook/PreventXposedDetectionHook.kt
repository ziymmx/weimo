package com.ziymmx.wx.hook

import com.ziymmx.wx.util.HookUtils
import com.ziymmx.wx.util.WeLogger
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge

/**
 * 阻止微信检测 Xposed 环境。
 *
 * 微信通过检查调用栈中是否出现 Xposed 框架特征类来判断是否运行在
 * Xposed 环境下；将相关检测方法的返回值改为 false，让微信认为当前
 * 环境是纯净的。不限制搜索包名，兼容类被混淆/移动的情况。
 */
internal object PreventXposedDetectionHook {

    fun install(xposed: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val matches = bridge.findMethod {
                matcher {
                    usingEqStrings(
                        "de.robv.android.xposed.XposedBridge",
                        "com.zte.heartyservice.SCC.FrameworkBridge"
                    )
                }
            }.filter { it.returnTypeName == HookUtils.BOOLEAN_TYPE }

            if (matches.isEmpty()) {
                return
            }
            matches.forEachIndexed { index, dexMethod ->
                runCatching {
                    HookUtils.hookBooleanMethod(
                        xposed,
                        dexMethod.getMethodInstance(classLoader),
                        "weimo_prevent_xposed_$index",
                        false
                    )
                }.onFailure { WeLogger.w(xposed, "Xposed 检测 hook 失败", it) }
            }
        }.onFailure { WeLogger.w(xposed, "阻止 Xposed 检测 hook 异常", it) }
    }
}