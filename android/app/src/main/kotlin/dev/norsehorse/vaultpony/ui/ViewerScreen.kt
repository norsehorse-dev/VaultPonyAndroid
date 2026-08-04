package dev.norsehorse.vaultpony.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.vault_ffi.VaultSession

/** How much of a file we ever pull into memory for viewing (doc §9/§11:
 *  decrypt-to-RAM, never a temp file). Larger files show a size notice. */
// 4 MiB. A single unsigned literal — const requires a compile-time constant,
// and ULong arithmetic (operator times) does not qualify.
private const val VIEW_CAP: ULong = 4_194_304uL

private sealed interface Content {
    data object Loading : Content
    data class TextView(val text: String) : Content
    data class ImageView(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : Content
    data class Binary(val size: ULong) : Content
    data class TooBig(val size: ULong) : Content
    data class Failed(val message: String) : Content
}

@Composable
fun ViewerScreen(
    session: VaultSession,
    path: String,
    size: ULong,
    onBack: () -> Unit,
) {
    var content by remember { mutableStateOf<Content>(Content.Loading) }
    val name = path.substringAfterLast('/')

    LaunchedEffect(path) {
        content = withContext(Dispatchers.IO) {
            if (size > VIEW_CAP) return@withContext Content.TooBig(size)
            try {
                val bytes = readAll(session, path, size)
                classify(name, bytes)
            } catch (e: Exception) {
                Content.Failed(e.message ?: "read failed")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        HorizontalDivider()
        when (val c = content) {
            Content.Loading -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            is Content.TextView -> Text(
                c.text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(16.dp),
            )
            is Content.ImageView -> Image(
                bitmap = c.bitmap,
                contentDescription = name,
                modifier = Modifier.fillMaxSize().padding(16.dp),
            )
            is Content.Binary -> Notice("Binary file (${humanSize(c.size)}). Extract it to open elsewhere.")
            is Content.TooBig -> Notice("File is ${humanSize(c.size)} — too large to preview in-app. Extract it instead.")
            is Content.Failed -> Notice("Could not read this file: ${c.message}")
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
}

private suspend fun readAll(session: VaultSession, path: String, size: ULong): ByteArray {
    val out = ByteArray(size.toInt())
    var offset = 0uL
    while (offset < size) {
        val chunk = session.readAt(path, offset, (1u shl 20))
        if (chunk.isEmpty()) break
        chunk.copyInto(out, offset.toInt())
        offset += chunk.size.toUInt()
    }
    return out
}

private fun classify(name: String, bytes: ByteArray): Content {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp != null) return Content.ImageView(bmp.asImageBitmap())
    }
    // Heuristic: printable text with few control bytes → text. Bytes are
    // signed in Kotlin, so read each as unsigned (0..255) before testing —
    // otherwise every high-bit UTF-8 continuation byte reads as negative and
    // non-ASCII text would be misclassified as binary. NUL and C0 controls
    // (except tab/LF/CR) are the binary tell.
    val sample = bytes.take(4096)
    val controls = sample.count { b ->
        val u = b.toInt() and 0xFF
        u < 0x09 || (u in 0x0e..0x1f)
    }
    val looksText = sample.isEmpty() || controls * 20 < sample.size
    return if (looksText) {
        Content.TextView(String(bytes, Charsets.UTF_8))
    } else {
        Content.Binary(bytes.size.toULong())
    }
}
