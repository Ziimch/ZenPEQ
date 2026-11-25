package com.zenpeq.data

import android.content.Context
import android.net.Uri
import com.zenpeq.model.AutoEqParser
import com.zenpeq.model.EqProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** One headphone in the AutoEq results index. [path] is URL-encoded: `source/type/Name`. */
data class AutoEqEntry(val name: String, val source: String, val path: String) {
    val kind: String get() = path.split('/').getOrNull(1)?.let { Uri.decode(it) }.orEmpty()
    val norm: String = name.lowercase().filter { it.isLetterOrDigit() }
}

/** A preset we found for a device that just connected. */
data class AutoEqSuggestion(val deviceKey: String, val deviceLabel: String, val entry: AutoEqEntry)

object AutoEqSuggestions {
    val current = MutableStateFlow<AutoEqSuggestion?>(null)
}

/**
 * Browses the public AutoEq results (github.com/jaakkopasanen/AutoEq). Only the index (~850 KB) is cached
 * on the device; a preset's ParametricEQ.txt is downloaded when you pick it.
 */
class AutoEqRepository(context: Context) {
    private val file = File(context.applicationContext.filesDir, "autoeq_index.md")
    @Volatile private var cached: List<AutoEqEntry>? = null

    suspend fun entries(forceRefresh: Boolean = false): List<AutoEqEntry> = withContext(Dispatchers.IO) {
        if (!forceRefresh) cached?.let { return@withContext it }
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS
        if (forceRefresh || stale) {
            try {
                download()
            } catch (e: IOException) {
                if (!file.exists()) throw e // an old copy is better than nothing
            }
        }
        parse(file.readText()).also { cached = it }
    }

    suspend fun fetchPreset(entry: AutoEqEntry): String = withContext(Dispatchers.IO) {
        val last = entry.path.substringAfterLast('/')
        open("$BASE${entry.path}/$last%20ParametricEQ.txt").bufferedReader().use { it.readText() }
    }

    fun search(entries: List<AutoEqEntry>, query: String, limit: Int = 80): List<AutoEqEntry> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return entries.asSequence()
            .filter { e ->
                val hay = (e.name + " " + e.source).lowercase()
                tokens.all { it in hay }
            }
            .sortedWith(
                compareBy<AutoEqEntry>(
                    { !it.name.lowercase().startsWith(tokens[0]) },
                    { sourceRank(it.source) },
                    { it.name.length },
                ),
            )
            .take(limit)
            .toList()
    }

    /** Best preset for a device name such as "WH-1000XM4", or null when nothing matches closely. */
    suspend fun bestMatch(deviceName: String): AutoEqEntry? {
        val q = deviceName.lowercase().filter { it.isLetterOrDigit() }
        if (q.length < 4) return null
        val list = entries()
        list.filter { it.norm == q }.minByOrNull { sourceRank(it.source) }?.let { return it }
        return list
            .filter { it.norm.contains(q) && q.length >= it.norm.length * 0.6 }
            .minWithOrNull(compareBy<AutoEqEntry>({ sourceRank(it.source) }, { it.norm.length }))
    }

    private fun sourceRank(source: String) = when (source) {
        "oratory1990" -> 0
        "crinacle" -> 1
        "Super Review" -> 2
        "Rtings" -> 3
        else -> 5
    }

    private fun parse(text: String): List<AutoEqEntry> = text.lineSequence().mapNotNull { line ->
        LINE.find(line)?.let { m ->
            AutoEqEntry(m.groupValues[1], m.groupValues[3].substringBefore(" on "), m.groupValues[2])
        }
    }.toList()

    private fun download() {
        val tmp = File(file.parentFile, "autoeq_index.tmp")
        open(INDEX_URL).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (tmp.length() < 10_000) {
            tmp.delete()
            throw IOException("Unexpected index")
        }
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }

    private fun open(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "ZenPEQ")
        if (c.responseCode != 200) {
            val code = c.responseCode
            c.disconnect()
            throw IOException("HTTP $code")
        }
        return c.inputStream
    }

    private companion object {
        const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
        const val INDEX_URL = BASE + "INDEX.md"
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        // - [Name](./source/type/Name) by Source on Rig   (names may contain parentheses)
        val LINE = Regex("""^- \[(.*?)\]\(\./(.*)\) by (.*)$""")
    }
}

/** Downloads [entry], turns it into a profile and (optionally) assigns it to an output device. */
suspend fun importAutoEqPreset(
    repo: ZenRepository,
    autoEq: AutoEqRepository,
    entry: AutoEqEntry,
    deviceKey: String?,
): String {
    val r = AutoEqParser.parse(autoEq.fetchPreset(entry))
    if (r.filters.isEmpty()) throw IOException("Empty preset")
    val profile = EqProfile(
        id = UUID.randomUUID().toString(),
        name = "${entry.name} (${entry.source})",
        preampDb = r.preampDb ?: 0f,
        autoPreamp = r.preampDb == null,
        filters = r.filters,
    )
    repo.upsertProfile(profile)
    if (deviceKey != null) repo.assignDevice(deviceKey, profile.id)
    return profile.id
}
