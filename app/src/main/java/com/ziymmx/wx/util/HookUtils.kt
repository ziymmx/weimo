package com.ziymmx.wx.util

import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Method

internal object HookUtils {

    const val TAG = "Weimo"

    val BOOLEAN_TYPE: String = Boolean::class.javaPrimitiveType!!.name

    /** 从模块 native 目录加载 DexKit 动态库。 */
    fun loadDexKitLibrary(nativeLibraryDir: String?) {
        check(!nativeLibraryDir.isNullOrEmpty()) { "无法获取模块 native library 目录" }
        val dexKitLibrary = File(nativeLibraryDir, System.mapLibraryName(DEXKIT_LIBRARY))
        check(dexKitLibrary.isFile) { "DexKit native library 不存在：${dexKitLibrary.absolutePath}" }
        System.load(dexKitLibrary.absolutePath)
    }

    /** 将方法 hook 为固定布尔返回值。 */
    fun hookBooleanMethod(
        xposed: XposedInterface,
        method: Method,
        hookId: String,
        value: Boolean
    ) {
        method.isAccessible = true
        xposed.hook(method)
            .setId(hookId)
            .intercept { value }
    }

    private const val DEXKIT_LIBRARY = "dexkit"
}