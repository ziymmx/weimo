package com.ziymmx.wx.util

/**
 * 各功能开关的位掩码定义。
 *
 * Zygisk 版本由 native 侧读取 /data/adb/weimo/config.tsv 得到掩码后传入；
 * LSPosed 版本默认全部启用（[ALL]）。
 */
object FeatureFlags {

    const val ANTI_RECALL = 1 shl 0
    const val FORCE_TABLET = 1 shl 1
    const val ANTI_XPOSED_DETECT = 1 shl 2
    const val DISABLE_HOT_UPDATE = 1 shl 3
    const val MOMENTS_ANTI_RECALL = 1 shl 4
    const val MOMENTS_COMMENT_ANTI_RECALL = 1 shl 5
    const val MOMENTS_AD_BLOCK = 1 shl 6

    const val ALL = ANTI_RECALL or FORCE_TABLET or ANTI_XPOSED_DETECT or
        DISABLE_HOT_UPDATE or MOMENTS_ANTI_RECALL or MOMENTS_COMMENT_ANTI_RECALL or
        MOMENTS_AD_BLOCK

    /** 配置文件中使用的功能名（与 WebUI / config.sh / native companion 保持一致）。 */
    val NAMES: List<Pair<String, Int>> = listOf(
        "anti-recall" to ANTI_RECALL,
        "force-tablet" to FORCE_TABLET,
        "anti-xposed-detect" to ANTI_XPOSED_DETECT,
        "disable-hot-update" to DISABLE_HOT_UPDATE,
        "moments-anti-recall" to MOMENTS_ANTI_RECALL,
        "moments-comment-anti-recall" to MOMENTS_COMMENT_ANTI_RECALL,
        "moments-ad-block" to MOMENTS_AD_BLOCK,
    )

    fun fromMask(mask: Int): Int = mask and ALL
}
