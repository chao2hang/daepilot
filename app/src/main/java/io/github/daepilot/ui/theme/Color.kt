package io.github.daepilot.ui.theme

import androidx.compose.ui.graphics.Color

// Accent families. The runtime palette is generated from these hues so the
// same contrast and neutral surfaces hold for every choice.
val Forest = Color(0xFF2C6B54)
val Ocean = Color(0xFF2F6FA8)
val Indigo = Color(0xFF5B57B8)

// Status colours only. They never fill a surface and never stand in for the
// accent, so "latency is good" stays green even when the theme is purple.
val SuccessLight = Color(0xFF2C744D)
val SuccessDark = Color(0xFF71C99A)
val WarningLight = Color(0xFF94650F)
val WarningDark = Color(0xFFD6AE6A)
val ErrorLight = Color(0xFFA03730)
val ErrorDark = Color(0xFFE58E86)
