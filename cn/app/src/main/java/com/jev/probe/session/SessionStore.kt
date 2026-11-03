package com.jev.probe.session

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话窗口的落盘：`filesDir/sessions/<id>.json`，一个会话一个文件。
 * 单锁 + 先写临时文件再 rename，和 KbStore 的约定一致：写坏了也不会留下半个文件。
 */
class SessionStore(private val ctx: Context, private val window: () -> Int) {

    private val lock = Any()
    private val dir: File get() = File(ctx.filesDir, "sessions").apply { if (!exists()) mkdirs() }

    fun idFor(title: String): String {
        val key = OverlapMerger.normalize(title).ifBlank { "default" }
        return key.hashCode().toUInt().toString(16) + "_" + key.take(12).hashCode().toUInt().toString(16)
    }

    fun list(): List<ChatSession> = synchronized(lock) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { read(it) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun load(id: String): ChatSession? = synchronized(lock) {
        val f = File(dir, "$id.json")
        if (!f.exists()) null else runCatching { read(f) }.getOrNull()
    }

    /** 把这一屏接到会话后面；返回合并后的会话。 */
    fun append(title: String, incoming: List<ChatMsg>, id: String? = null): ChatSession =
        synchronized(lock) {
            val sid = id ?: idFor(title)
            val existing = load(sid)
            val base = existing?.messages ?: emptyList()
            val r = OverlapMerger.merge(base, incoming)
            val merged = ChatSession(
                id = sid,
                title = title.ifBlank { existing?.title ?: "未命名会话" },
                messages = r.merged,
                summary = existing?.summary ?: "",
                updatedAt = System.currentTimeMillis(),
                anchored = if (existing == null) true else r.anchored
            ).trimmed(window())
            write(merged)
            merged
        }

    fun save(s: ChatSession): ChatSession = synchronized(lock) {
        val trimmed = s.trimmed(window())
        write(trimmed)
        trimmed
    }

    fun clear(id: String) = synchronized(lock) {
        File(dir, "$id.json").delete()
        Unit
    }

    private fun write(s: ChatSession) {
        val f = File(dir, "${s.id}.json")
        val tmp = File(dir, "${s.id}.json.tmp")
        runCatching {
            tmp.writeText(s.toJson().toString())
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }.onFailure { Log.w(TAG, "session write failed: ${it.message}") }
    }

    private fun read(f: File): ChatSession {
        val s = fromJson(JSONObject(f.readText()))
        return if (s.id.isBlank()) s.copy(id = f.nameWithoutExtension) else s
    }

    private fun fromJson(o: JSONObject): ChatSession {
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val msgs = ArrayList<ChatMsg>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            msgs.add(
                ChatMsg(
                    side = m.optString("side", "other"),
                    text = m.optString("text", ""),
                    ts = m.optLong("ts", 0L),
                    source = m.optString("source", "share")
                )
            )
        }
        return ChatSession(
            id = o.optString("id"),
            title = o.optString("title", "未命名会话"),
            messages = msgs,
            summary = o.optString("summary", ""),
            updatedAt = o.optLong("updatedAt", 0L),
            anchored = o.optBoolean("anchored", true)
        )
    }

    private fun ChatSession.toJson(): JSONObject {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(
                JSONObject()
                    .put("side", m.side)
                    .put("text", m.text)
                    .put("ts", m.ts)
                    .put("source", m.source)
            )
        }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("summary", summary)
            .put("updatedAt", updatedAt)
            .put("anchored", anchored)
            .put("messages", arr)
    }

    companion object {
        private const val TAG = "JEVASSIST"
    }
}
