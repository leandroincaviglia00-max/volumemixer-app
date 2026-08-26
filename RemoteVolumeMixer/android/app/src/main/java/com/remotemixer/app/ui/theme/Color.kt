package com.remotemixer.app.ui.theme

import androidx.compose.ui.graphics.Color

// Base surfaces
val Ink            = Color(0xFF0B0D12)
val InkDeep        = Color(0xFF07080C)
val Slate          = Color(0xFF131722)
val SlateHi        = Color(0xFF1B2130)
val Hairline       = Color(0x14FFFFFF)
val HairlineStrong = Color(0x24FFFFFF)

// Text
val TextHi   = Color(0xFFE9EDF6)
val TextMid  = Color(0xFFA6AFC4)
val TextLow  = Color(0xFF6C7689)

// Accents
val Iris     = Color(0xFF7C9CFF)
val IrisDeep = Color(0xFF5B7BFF)
val Mint     = Color(0xFF4ADE80)
val Amber    = Color(0xFFFBBF24)
val Rose     = Color(0xFFFB7185)
val Violet   = Color(0xFFA78BFA)
val Cyan     = Color(0xFF22D3EE)

/** Deterministic accent per app so every card feels distinct but consistent. */
fun accentFor(key: String): Color {
    val palette = listOf(Iris, Mint, Violet, Cyan, Amber, Rose)
    if (key.isEmpty()) return Iris
    var h = 0
    for (c in key) h = h * 31 + c.code
    return palette[((h % palette.size) + palette.size) % palette.size]
}
