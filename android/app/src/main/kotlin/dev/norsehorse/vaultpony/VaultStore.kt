package dev.norsehorse.vaultpony

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

/** One remembered container: its persisted SAF uri, a display name, and when
 *  it was last opened. */
data class VaultRef(val uri: String, val name: String, val lastOpened: Long)

/** The remembered-vaults list (home screen). Only ever written when the
 *  "remember my vaults" setting is on; no-trace mode leaves it empty. */
object VaultStore {
    private const val FILE = "vaultpony_vaults"
    private const val KEY = "list"
    private const val MAX = 40
    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(c: Context): List<VaultRef> {
        val raw = p(c).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                VaultRef(o.getString("uri"), o.getString("name"), o.optLong("ts"))
            }.sortedByDescending { it.lastOpened }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(c: Context, uri: Uri) {
        if (!AppPrefs.rememberVaults(c)) return
        val u = uri.toString()
        val entry = VaultRef(u, displayName(c, uri), System.currentTimeMillis())
        val next = (listOf(entry) + list(c).filter { it.uri != u }).take(MAX)
        save(c, next)
    }

    fun remove(c: Context, uri: String) = save(c, list(c).filter { it.uri != uri })

    fun clear(c: Context) = p(c).edit().remove(KEY).apply()

    private fun save(c: Context, refs: List<VaultRef>) {
        val arr = JSONArray()
        refs.forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("ts", it.lastOpened))
        }
        p(c).edit().putString(KEY, arr.toString()).apply()
    }

    private fun displayName(c: Context, uri: Uri): String {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cur ->
                val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cur.moveToFirst()) cur.getString(idx)?.let { return it }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "container"
    }
}
