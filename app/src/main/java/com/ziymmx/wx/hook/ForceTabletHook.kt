package com.ziymmx.wx.hook

import android.util.Log
import android.view.View
import android.widget.Button
import com.ziymmx.wx.util.HookUtils
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

/**
 * 强制微信启用平板布局。
 *
 * 通过把微信内部的平板/折叠屏判定与「以平板身份登录」接口全部改为 true，
 * 让微信在当前设备上直接以平板模式运行；同时仅在登录页显示微信自带的
 * 「登录其他设备」按钮（不新增、不嵌入任何界面元素）。
 */
internal object ForceTabletHook {

    fun install(xposed: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader) {
        hookIsTablet(xposed, bridge, classLoader)
        hookFoldableDevice(xposed, bridge, classLoader)
        hookOtherDeviceLoginButton(xposed, bridge, classLoader)
        hookCheckLoginAsPad(xposed, bridge, classLoader)
        hookLoginHistoryInit(xposed, classLoader)
    }

    // 1. 平板环境判定：微信通过内置的平板机型表判断当前设备是否平板。
    private fun hookIsTablet(xposed: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val matches = bridge.findMethod {
                matcher {
                    usingEqStrings("Lenovo TB-9707F", "eebbk")
                }
            }.filter { it.returnTypeName == HookUtils.BOOLEAN_TYPE }

            if (matches.isEmpty()) {
                xposed.log(Log.WARN, HookUtils.TAG, "未找到平板判定方法")
                return
            }
            matches.forEachIndexed { index, dexMethod ->
                runCatching {
                    HookUtils.hookBooleanMethod(
                        xposed,
                        dexMethod.getMethodInstance(classLoader),
                        "weimo_tablet_$index",
                        true
                    )
                }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "平板判定 hook 失败", it) }
            }
            xposed.log(Log.INFO, HookUtils.TAG, "平板判定已启用：${matches.size} 个方法")
        }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "平板判定 hook 异常", it) }
    }

    // 2. 折叠屏设备判定：让微信把当前设备识别为可折叠/平板形态。
    private fun hookFoldableDevice(xposed: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val matches = bridge.findMethod {
                matcher {
                    usingEqStrings("MicroMsg.UIUtils", "isRoyoleFoldableDevice!!!")
                }
            }.filter { it.returnTypeName == HookUtils.BOOLEAN_TYPE }

            if (matches.isEmpty()) {
                xposed.log(Log.WARN, HookUtils.TAG, "未找到折叠屏判定方法")
                return
            }
            matches.forEachIndexed { index, dexMethod ->
                runCatching {
                    HookUtils.hookBooleanMethod(
                        xposed,
                        dexMethod.getMethodInstance(classLoader),
                        "weimo_foldable_$index",
                        true
                    )
                }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "折叠屏判定 hook 失败", it) }
            }
            xposed.log(Log.INFO, HookUtils.TAG, "折叠屏判定已启用：${matches.size} 个方法")
        }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "折叠屏判定 hook 异常", it) }
    }

    // 3. 登录页「登录其他设备」按钮：平板登录流程需要该入口，确保其可见。
    private fun hookOtherDeviceLoginButton(
        xposed: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader
    ) {
        runCatching {
            val matches = bridge.findMethod {
                matcher {
                    usingEqStrings("loginAsOtherDeviceBtn")
                }
            }

            if (matches.isEmpty()) {
                xposed.log(Log.WARN, HookUtils.TAG, "未找到登录其他设备按钮逻辑")
                return
            }
            matches.forEachIndexed { index, dexMethod ->
                runCatching {
                    val method = dexMethod.getMethodInstance(classLoader)
                    method.isAccessible = true
                    xposed.hook(method)
                        .setId("weimo_login_other_btn_$index")
                        .intercept { chain ->
                            val original = chain.proceed()
                            chain.args.forEach { arg ->
                                if (arg is View && arg.visibility != View.VISIBLE) {
                                    arg.visibility = View.VISIBLE
                                }
                            }
                            original
                        }
                }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "登录其他设备按钮 hook 失败", it) }
            }
            xposed.log(Log.INFO, HookUtils.TAG, "登录其他设备按钮已启用：${matches.size} 个方法")
        }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "登录其他设备按钮 hook 异常", it) }
    }

    // 4. 以平板身份登录的资格校验接口：直接放行。
    private fun hookCheckLoginAsPad(xposed: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val matches = bridge.findMethod {
                matcher {
                    usingEqStrings(
                        "MicroMsg.CgiCheckLoginAsPad",
                        "/cgi-bin/micromsg-bin/checkloginaspad"
                    )
                }
            }.filter {
                // suspend 函数在字节码中形如 (String, String, Continuation) -> Object
                it.paramTypeNames.size == 3
            }

            if (matches.isEmpty()) {
                xposed.log(Log.WARN, HookUtils.TAG, "未找到以平板身份登录校验方法")
                return
            }
            matches.forEachIndexed { index, dexMethod ->
                runCatching {
                    val method = dexMethod.getMethodInstance(classLoader)
                    method.isAccessible = true
                    xposed.hook(method)
                        .setId("weimo_check_login_as_pad_$index")
                        .intercept { true }
                }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "平板登录校验 hook 失败", it) }
            }
            xposed.log(Log.INFO, HookUtils.TAG, "平板登录校验已启用：${matches.size} 个方法")
        }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "平板登录校验 hook 异常", it) }
    }

    // 5. 登录历史界面初始化后，把登录相关 Button 全部置为可见（兜底）。
    private fun hookLoginHistoryInit(xposed: XposedInterface, classLoader: ClassLoader) {
        runCatching {
            val clazz = classLoader.loadClass("com.tencent.mm.plugin.account.ui.LoginHistoryUI")
            val initView = clazz.getDeclaredMethod("initView")
            initView.isAccessible = true
            xposed.hook(initView)
                .setId("weimo_login_history_init")
                .intercept { chain ->
                    val original = chain.proceed()
                    runCatching {
                        val target = chain.thisObject ?: return@runCatching
                        clazz.declaredFields
                            .filter { Button::class.java.isAssignableFrom(it.type) }
                            .forEach { field ->
                                field.isAccessible = true
                                val view = field.get(target) as? View
                                if (view != null && view.visibility != View.VISIBLE) {
                                    view.visibility = View.VISIBLE
                                }
                            }
                    }
                    original
                }
        }.onFailure { xposed.log(Log.WARN, HookUtils.TAG, "登录历史界面兜底 hook 跳过", it) }
    }
}