package com.ziymmx.wx.util

import com.ziymmx.wx.hook.common.HookBridge
import java.io.File
import java.lang.reflect.Method

internal object HookUtils {

    const val TAG = "Weimo"

    val BOOLEAN_TYPE: String = Boolean::class.javaPrimitiveType!!.name

    /** 从模块 native 目录加载 DexKit 动态库（LSPosed 路径）。 */
    fun loadDexKitLibrary(nativeLibraryDir: String?) {
        check(!nativeLibraryDir.isNullOrEmpty()) { "无法获取模块 native library 目录" }
        loadDexKitLibrary(File(nativeLibraryDir, System.mapLibraryName(DEXKIT_LIBRARY)))
    }

    /** 从显式文件路径加载 DexKit 动态库（Zygisk 路径）。 */
    fun loadDexKitLibrary(libraryFile: File) {
        check(libraryFile.isFile) { "DexKit native library 不存在：${libraryFile.absolutePath}" }
        System.load(libraryFile.absolutePath)
    }

    /** 将方法 hook 为固定布尔返回值。 */
    fun hookBooleanMethod(
        hook: HookBridge,
        method: Method,
        hookId: String,
        value: Boolean
    ) {
        hook.hookMethod(method, hookId) { value }
    }

    private const val DEXKIT_LIBRARY = "dexkit"
}
