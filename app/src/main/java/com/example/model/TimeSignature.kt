package com.example.model

/**
 * Represents a musical time signature (میزان‌نما).
 */
data class TimeSignature(
    val numerator: Int = 4,   // Number of beats per bar (تعداد ضرب در هر میزان)
    val denominator: Int = 4  // Note value that represents one beat (ارزش زمانی هر ضرب)
) {
    val displayName: String
        get() = "$numerator/$denominator"

    val persianDisplayName: String
        get() = when ("$numerator/$denominator") {
            "4/4" -> "۴/۴ (چهار چهارم)"
            "3/4" -> "۳/۴ (سه چهارم - والس)"
            "2/4" -> "۲/۴ (دو چهارم - مارش/ساده)"
            "6/8" -> "۶/۸ (شش و هشت ریتمیک)"
            else -> "$numerator/$denominator"
        }

    val beatsPerBar: Int
        get() = numerator

    companion object {
        val Common44 = TimeSignature(4, 4)
        val Waltz34 = TimeSignature(3, 4)
        val March24 = TimeSignature(2, 4)
        val SixEight68 = TimeSignature(6, 8)

        val ALL_PRESETS = listOf(Common44, Waltz34, March24, SixEight68)
    }
}
