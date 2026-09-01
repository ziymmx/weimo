package com.ziymmx.wx.hook

import com.ziymmx.wx.util.WeLogger
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge

/**
 * 朋友圈实验性增强（实验性支持：找不到目标时静默跳过，不影响其他功能）：
 * 1. 朋友圈防撤回：拦截服务端同步的删除动作 processSnsDelAction / processAdSnsDelAction，
 *    只阻止他人撤回/删除导致的朋友圈消失，本地主动删除不受影响；
 * 2. 朋友圈评论防撤回：拦截 processCommentDelAction，阻止服务端删除评论；
 * 3. 朋友圈广告拦截：ADInfo(String) 构造返回 null。
 */
internal object MomentsHook {

    private const val NETSCENE_SNS_SYNC_TAG = "com.tencent.mm.plugin.sns.model.NetSceneSnsSync"
    private const val AD_INFO_CLASS = "com.tencent.mm.plugin.sns.storage.ADInfo"

    fun install(xposed: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader) {
        hookSnsDelAction(xposed, bridge, classLoader)
        hookCommentDelAction(xposed, bridge, classLoader)
        hookAdBlock(xposed, bridge, classLoader)
    }

    // 1. 朋友圈防撤回：拦截服务端同步的『删除朋友圈』动作。
    private fun hookSnsDelAction(
        xposed: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader
    ) {
        runCatching {
            val matches = findDelAction(bridge, "processSnsDelAction") +
                findDelAction(bridge, "processAdSnsDelAction")

            if (matches.isEmpty()) {
                WeLogger.w(xposed, "朋友圈防撤回：未找到服务端删除动作处理方法")
                return
            }
            matches.forEachIndexed { index, dexMethod ->
                runCatching {
                    val method = dexMethod.getMethodInstance(classLoader).apply { isAccessible = true }
                    xposed.hook(method)
                        .setId("weimo_moments_del_$index")
                        .intercept { true }
                }.onFailure { WeLogger.w(xposed, "朋友圈防撤回 hook 失败", it) }
            }
        }.onFailure { WeLogger.w(xposed, "朋友圈防撤回 hook 异常", it) }
    }

    // 2. 评论防撤回：拦截服务端同步的『删除评论』动作。
    private fun hookCommentDelAction(
        xposed: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader
    ) {
        runCatching {
            val matches = findDelAction(bridge, "processCommentDelAction")

            if (matches.isEmpty()) {
                WeLogger.w(xposed, "评论防撤回：未找到服务端删除评论处理方法")
                return
            }
            matches.forEachIndexed { index, dexMethod ->
                runCatching {
                    val method = dexMethod.getMethodInstance(classLoader).apply { isAccessible = true }
                    xposed.hook(method)
                        .setId("weimo_comment_del_$index")
                        .intercept { true }
                }.onFailure { WeLogger.w(xposed, "评论防撤回 hook 失败", it) }
            }
        }.onFailure { WeLogger.w(xposed, "评论防撤回 hook 异常", it) }
    }

    // 3. 朋友圈广告拦截：ADInfo(String) 构造返回 null。
    private fun hookAdBlock(
        xposed: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader
    ) {
        runCatching {
            val matches = bridge.findMethod {
                matcher {
                    declaredClass(AD_INFO_CLASS)
                    paramTypes(String::class.java)
                }
            }.filter { it.isConstructor }

            if (matches.isEmpty()) {
                WeLogger.w(xposed, "广告拦截：未找到 ADInfo 构造")
                return
            }
            matches.forEachIndexed { index, dexMethod ->
                runCatching {
                    val constructor =
                        dexMethod.getConstructorInstance(classLoader).apply { isAccessible = true }
                    xposed.hook(constructor)
                        .setId("weimo_moments_ad_$index")
                        .intercept { null }
                }.onFailure { WeLogger.w(xposed, "广告拦截 hook 失败", it) }
            }
        }.onFailure { WeLogger.w(xposed, "广告拦截 hook 异常", it) }
    }

    private fun findDelAction(bridge: DexKitBridge, actionName: String) =
        bridge.findMethod {
            matcher {
                usingEqStrings(actionName, NETSCENE_SNS_SYNC_TAG)
                returnType("boolean")
            }
        }
}