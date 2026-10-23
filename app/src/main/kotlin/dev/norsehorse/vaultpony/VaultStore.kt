package dev.norsehorse.vaultpony

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import uniffi.vault_ffi.KdfFilter

/** One remembered container: its persisted SAF uri, a display name (empty
 *  under "Hide names"), when it was last opened, the hash the user picked
 *  by hand on the unlock screen (a [KdfFilter] name), if any, and whether the
 *  user ticked "TrueCrypt mode" by hand for it (2.0.0 rc5, plan 6.2). */
data class VaultRef(
    val uri: String,
    val name: String,
    val lastOpened: Long,
    val hash: String? = null,
    val truecrypt: Boolean = false,
)

/** The remembered-vaults list (home screen). Written only while the vault
 *  list setting keeps a list; "Don't keep" leaves it empty (plan §5.4). */
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
                VaultRef(
                    uri = o.getString("uri"),
                    name = o.optString("name", ""),
                    lastOpened = o.optLong("ts"),
                    hash = if (o.has("hash")) o.optString("hash").takeIf { h -> h.isNotEmpty() } else null,
                    truecrypt = o.optBoolean("tc", false),
                )
            }.sortedByDescending { it.lastOpened }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Remember [uri] as just opened. Keeps a hash and the TrueCrypt mode
     *  remembered for it. Stores no display name under "Hide names", nothing
     *  at all under "Don't keep". */
    fun add(c: Context, uri: Uri) {
        val mode = AppPrefs.vaultListMode(c)
        if (mode == VaultListMode.NONE) return
        val u = uri.toString()
        val current = list(c)
        val previous = current.firstOrNull { it.uri == u }
        val name = if (mode == VaultListMode.HIDE) "" else displayName(c, uri)
        val entry = VaultRef(u, name, System.currentTimeMillis(), previous?.hash, previous?.truecrypt ?: false)
        val next = (listOf(entry) + current.filter { it.uri != u }).take(MAX)
        save(c, next)
    }

    fun remove(c: Context, uri: String) = save(c, list(c).filter { it.uri != uri })

    fun clear(c: Context) = p(c).edit().remove(KEY).apply()

    /** The hash remembered for [uri], or null (the unlock menu starts on Auto). */
    fun rememberedHash(c: Context, uri: Uri): KdfFilter? {
        if (AppPrefs.vaultListMode(c) == VaultListMode.NONE) return null
        val name = list(c).firstOrNull { it.uri == uri.toString() }?.hash ?: return null
        return KdfFilter.entries.firstOrNull { it.name == name }?.takeIf { it != KdfFilter.AUTO }
    }

    /** Remember the hash the user picked by hand for [uri] after an unlock with
     *  it succeeded, or forget it ([filter] null or Auto). Only for a vault on
     *  the list, so never under "Don't keep" (D21). */
    fun setHash(c: Context, uri: Uri, filter: KdfFilter?) {
        if (AppPrefs.vaultListMode(c) == VaultListMode.NONE) return
        val u = uri.toString()
        val current = list(c)
        if (current.none { it.uri == u }) return
        val value = filter?.takeIf { it != KdfFilter.AUTO }?.name
        save(c, current.map { if (it.uri == u) it.copy(hash = value) else it })
    }

    /** Whether "TrueCrypt mode" is remembered for [uri] (the unlock screen's
     *  checkbox starts ticked). Never under "Don't keep". */
    fun rememberedTrueCrypt(c: Context, uri: Uri): Boolean {
        if (AppPrefs.vaultListMode(c) == VaultListMode.NONE) return false
        return list(c).firstOrNull { it.uri == uri.toString() }?.truecrypt ?: false
    }

    /** Remember "TrueCrypt mode" for [uri] after the user ticked it by hand
     *  and the unlock succeeded, or forget it ([on] false: unticked). Same
     *  rules as [setHash]: only for a vault on the list, never under "Don't
     *  keep" (2.0.0 rc5, plan 6.2). */
    fun setTrueCrypt(c: Context, uri: Uri, on: Boolean) {
        if (AppPrefs.vaultListMode(c) == VaultListMode.NONE) return
        val u = uri.toString()
        val current = list(c)
        if (current.none { it.uri == u }) return
        save(c, current.map { if (it.uri == u) it.copy(truecrypt = on) else it })
    }

    /** "Hide names": drop every stored display name. */
    fun wipeNames(c: Context) {
        val current = list(c)
        if (current.isEmpty()) return
        save(c, current.map { it.copy(name = "") })
    }

    /** "Don't keep": delete every entry rather than hide it. Each one's
     *  persisted file permission is released and its biometric unlock removed
     *  (its remembered hash and TrueCrypt mode go with the entry). [keepUris]
     *  are containers unlocked right now; they lose theirs when they lock. */
    fun deleteAll(c: Context, keepUris: Set<String> = emptySet()) {
        clear(c)
        releaseGrants(c, keepUris)
        // Every biometric unlock, including ones for vaults removed from the
        // list earlier: none of them may outlive the list under "Don't keep".
        BiometricUnlock.clearAllExcept(c, keepUris.map { VaultRepository.volumeId(Uri.parse(it)) }.toSet())
    }

    /** Release every persisted file permission VaultPony holds except those
     *  for [keepUris]. Only containers ever get one (the open and create
     *  pickers), so under "Don't keep" none of them should outlive a lock. */
    fun releaseGrants(c: Context, keepUris: Set<String>) {
        val resolver = c.contentResolver
        val held = runCatching { resolver.persistedUriPermissions }.getOrDefault(emptyList())
        for (perm in held) {
            val u = perm.uri
            if (u.toString() in keepUris) continue
            var flags = 0
            if (perm.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (perm.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) runCatching { resolver.releasePersistableUriPermission(u, flags) }
        }
    }

    private fun save(c: Context, refs: List<VaultRef>) {
        val arr = JSONArray()
        refs.forEach {
            val o = JSONObject().put("uri", it.uri).put("name", it.name).put("ts", it.lastOpened)
            if (it.hash != null) o.put("hash", it.hash)
            if (it.truecrypt) o.put("tc", true)
            arr.put(o)
        }
        p(c).edit().putString(KEY, arr.toString()).apply()
    }

    private fun displayName(c: Context, uri: Uri): String {
        runCatching {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cur ->
                    val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cur.moveToFirst()) cur.getString(idx)?.let { return it }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "container"
    }
}
