package com.vidnest.app.domain.model


/**
 * 倍速预设：用户可在设置里从全集中挑选自己的倍速列表（升序展示）。
 * 默认 1.0 / 1.5 / 2.0 / 2.5 / 3.0。
 */
object Speeds {
    val UNIVERSE = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
    val DEFAULT = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)

    /** 存储格式："0.50,1.00,1.50,2.00,2.50,3.00"（纯数字，容忍带 x 后缀的旧数据） */
    fun encode(options: List<Float>): String =
        options.joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) }

    fun decode(raw: String?): List<Float> {
        if (raw.isNullOrBlank()) return DEFAULT
        val parsed = raw.split(",")
            .mapNotNull { token ->
                token.trim().removeSuffix("x").removeSuffix("X").toFloatOrNull()
            }
            .filter { v -> UNIVERSE.any { kotlin.math.abs(it - v) < 0.001f } }
            .distinct()
            .sorted()
        return parsed.ifEmpty { DEFAULT }
    }

    fun formatSpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"
}
