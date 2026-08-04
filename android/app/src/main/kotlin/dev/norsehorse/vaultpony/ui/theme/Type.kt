package dev.norsehorse.vaultpony.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Sans for everything; a monospace style is used deliberately for technical
// facts (cipher, hash, size, paths) so the app reads as an instrument.
private val base = Typography()

val VaultTypography = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Monospace style for chips and technical metadata (AES · SHA-512 · 64 MB). */
val MonoLabel = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    letterSpacing = 0.3.sp,
)
