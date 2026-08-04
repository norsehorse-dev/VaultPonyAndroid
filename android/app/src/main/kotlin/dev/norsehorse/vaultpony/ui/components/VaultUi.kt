package dev.norsehorse.vaultpony.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.norsehorse.vaultpony.ui.theme.MonoLabel

enum class ChipTone { Neutral, Accent, Hidden }

/** A pill of monospace technical metadata (AES · SHA-512 · 64 MB). */
@Composable
fun MonoChip(text: String, tone: ChipTone = ChipTone.Neutral, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val fg = when (tone) {
        ChipTone.Neutral -> cs.onSurfaceVariant
        ChipTone.Accent -> cs.primary
        ChipTone.Hidden -> cs.tertiary
    }
    val bg = when (tone) {
        ChipTone.Neutral -> cs.surfaceVariant
        ChipTone.Accent -> cs.primary.copy(alpha = 0.13f)
        ChipTone.Hidden -> cs.tertiary.copy(alpha = 0.13f)
    }
    val br = when (tone) {
        ChipTone.Neutral -> cs.outline
        ChipTone.Accent -> cs.primary.copy(alpha = 0.45f)
        ChipTone.Hidden -> cs.tertiary.copy(alpha = 0.45f)
    }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .background(bg, shape)
            .border(1.dp, br, shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, style = MonoLabel, color = fg)
    }
}

/** Small uppercase mono section header. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MonoLabel.copy(fontSize = 10.sp, letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/** The vault "seal": a brass lock in a rounded plate with an outer ring. */
@Composable
fun VaultSeal(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.size(128.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(128.dp)
                .border(1.dp, cs.primary.copy(alpha = 0.30f), RoundedCornerShape(36.dp)),
        )
        Box(
            Modifier
                .size(106.dp)
                .background(cs.surfaceContainerHigh, RoundedCornerShape(30.dp))
                .border(1.dp, cs.outline, RoundedCornerShape(30.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = cs.primary, modifier = Modifier.size(44.dp))
        }
    }
}

/** Icon for a directory entry by name/kind. */
fun fileIcon(name: String, isDir: Boolean): ImageVector {
    if (isDir) return Icons.Filled.Folder
    return when (name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp" -> Icons.Filled.Image
        "txt", "md", "log", "json", "xml", "csv", "ini", "cfg" -> Icons.Filled.Description
        "pdf" -> Icons.Filled.PictureAsPdf
        else -> Icons.Filled.InsertDriveFile
    }
}
