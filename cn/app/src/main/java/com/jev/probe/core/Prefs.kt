package com.jev.probe.core

import android.content.Context
import android.util.Log

/**
 * App-private config store. Holds the three API routes (judge / reply / vision),
 * the relationship description used in Jev's state, the conversation whitelist,
 * plus the context (D stage) and OCR (B stage) switches.
 *
 * Key handling: stored in app-private SharedPreferences (not world-readable,
 * never logged, never in code/git). Only key *lengths* are ever logged.
 */
class Prefs(context: Context, prefsName: String = PREFS_MAIN) {

    private val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /**
     * Only the real config migrates — and only the real config logs it. The
     * throwaway instances behind the settings test buttons and the KB self-check
     * have nothing to carry over, and used to print one migration line per tap.
     */
    init { if (prefsName == PREFS_MAIN) { migrateIfNeeded(); unseedBochaDefaultIfUnconfigured(); unifyRoutesOnce() } }

    /**
     * v1.9: 判断 / 回复 / 视觉合并成一个接口。以前分开存过的 reply_* 与 vision_* 会
     * 盖住新的「跟随判断接口」逻辑，这里清一次；判断接口本身（base/key/model）不动。
     */
    private fun unifyRoutesOnce() {
        if (sp.getBoolean(K_UNIFIED_V19, false)) return
        sp.edit()
            .putBoolean(K_UNIFIED_V19, true)
            .remove(K_REPLY_BASE).remove(K_REPLY_MODEL)
            .remove(K_VISION_BASE).remove(K_VISION_MODEL)
            .apply()
        Log.i(TAG, "prefs: unified route (legacy reply/vision overrides cleared)")
    }

    /**
     * v1.2 -> v1.3: the single `openrouter_key` becomes the judge route's key.
     * `reply_model` keeps its old storage key, so it carries over untouched.
     */
    private fun migrateIfNeeded() {
        if (sp.getBoolean(K_MIGRATED_V13, false)) return   // runs exactly once
        val legacy = sp.getString(K_LEGACY_KEY, "") ?: ""
        val current = sp.getString(K_JUDGE_KEY, "") ?: ""
        val e = sp.edit().putBoolean(K_MIGRATED_V13, true)
        if (current.isBlank() && legacy.isNotBlank()) {
            e.putString(K_JUDGE_KEY, legacy)
            Log.i(TAG, "prefs migrated judgeKey.len=${legacy.length}")
        } else {
            Log.i(TAG, "prefs migrated judgeKey.len=${current.length} (no legacy key to copy)")
        }
        e.apply()
    }

    /**
     * Fresh install only: default the judge route to Bocha Jev (limited-time free).
     * Runs ONLY when there is no judge config whatsoever — the provider key was
     * never written AND both the current judge key and the legacy OpenRouter key
     * are blank. Any existing or migrated user is left completely untouched, so an
     * OpenRouter key can never be redirected to jev.bocha.cn. The [judgeProvider]
     * getter default stays OpenRouter on purpose; this only seeds a truly new sp.
     */
    /**
     * v1.4.0 seeded fresh installs to Bocha Jev; v1.4.1 restores OpenRouter as the
     * default (Bocha stays available, now second in the list). Undo that earlier
     * auto-seed exactly once, and only when the user never entered a key and never
     * picked a provider by hand — a saved key, or any non-Bocha provider, means a
     * real choice we must not touch. Fresh installs now get no seed at all: the
     * getters already default to OpenRouter.
     */
    private fun unseedBochaDefaultIfUnconfigured() {
        if (sp.getBoolean(K_UNSEEDED_BOCHA, false)) return
        val e = sp.edit().putBoolean(K_UNSEEDED_BOCHA, true)
        val prov = sp.getString(K_JUDGE_PROVIDER, null)
        val key = sp.getString(K_JUDGE_KEY, "") ?: ""
        if (prov == PROVIDER_BOCHA && key.isBlank()) {
            e.remove(K_JUDGE_PROVIDER).remove(K_JUDGE_BASE).remove(K_JUDGE_MODEL)
            Log.i(TAG, "prefs: reverted auto-seeded bocha default to openrouter")
        }
        e.apply()
    }

    // ---------------------------------------------------------------- judge

    /** "bocha" | "openrouter" | "typesafe" | "vercel" | "zen" | "custom". */
    var judgeProvider: String
        get() = sp.getString(K_JUDGE_PROVIDER, PROVIDER_CHAT) ?: PROVIDER_CHAT
        set(v) = sp.edit().putString(K_JUDGE_PROVIDER, v.trim()).apply()

    /** Host root; the path is appended per provider (see [judgeEndpoint]). */
    var judgeBaseUrl: String
        get() {
            val d = if (judgeProvider == PROVIDER_CHAT) DEFAULT_JUDGE_BASE_CHAT else DEFAULT_JUDGE_BASE_OPENROUTER
            return sp.getString(K_JUDGE_BASE, d) ?: d
        }
        set(v) = sp.edit().putString(K_JUDGE_BASE, v.trim()).apply()

    var judgeKey: String
        get() = sp.getString(K_JUDGE_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_JUDGE_KEY, v.trim()).apply()

    var judgeModel: String
        get() {
            val d = if (judgeProvider == PROVIDER_CHAT) DEFAULT_JUDGE_MODEL_CHAT else DEFAULT_JUDGE_MODEL_OPENROUTER
            return sp.getString(K_JUDGE_MODEL, d) ?: d
        }
        set(v) = sp.edit().putString(K_JUDGE_MODEL, v.trim()).apply()

    /** Back-compat alias so older call sites keep compiling. */
    var openRouterKey: String
        get() = judgeKey
        set(v) { judgeKey = v }

    // ---------------------------------------------------------------- reply

    /** OpenAI-compatible base, up to and including `/v1`. 留空 = 跟随判断接口（v1.9 合并）。 */
    var replyBaseUrl: String
        get() = sp.getString(K_REPLY_BASE, "")?.ifBlank { judgeBaseUrl } ?: judgeBaseUrl
        set(v) = sp.edit().putString(K_REPLY_BASE, v.trim()).apply()

    /** Blank = fall back to [judgeKey]. */
    var replyKey: String
        get() = sp.getString(K_REPLY_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_REPLY_KEY, v.trim()).apply()

    /** Generative model for drafting the 3 candidate replies. 留空 = 跟随判断接口。 */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, "")?.ifBlank { judgeModel } ?: judgeModel
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    // --------------------------------------------------------------- vision

    /**
     * 留空 = 跟随判断接口。v1.9 起三张卡合并成一个接口：DeepSeek V4.1 Flash 一个模型
     * 同时能做判断、起草回复和看图，视觉不再单独配置。
     */
    var visionBaseUrl: String
        get() = sp.getString(K_VISION_BASE, "")?.ifBlank { judgeBaseUrl } ?: judgeBaseUrl
        set(v) = sp.edit().putString(K_VISION_BASE, v.trim()).apply()

    /** Blank = fall back to [replyKey] then [judgeKey]. */
    var visionKey: String
        get() = sp.getString(K_VISION_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_VISION_KEY, v.trim()).apply()

    var visionModel: String
        get() = sp.getString(K_VISION_MODEL, "")?.ifBlank { judgeModel } ?: judgeModel
        set(v) = sp.edit().putString(K_VISION_MODEL, v.trim()).apply()

    // -------------------------------------------------------- context (D)

    /**
     * Record per-contact history and inject it into analysis. Default OFF:
     * nothing about the user's chats is written to disk unless they opt in
     * (v1.3 revision, D stage).
     */
    var contextEnabled: Boolean
        get() = sp.getBoolean(K_CTX_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_CTX_ENABLED, v).apply()

    /** How many recent history entries to inject. */
    var contextHistoryCount: Int
        get() = sp.getInt(K_CTX_COUNT, 30)
        set(v) = sp.edit().putInt(K_CTX_COUNT, v).apply()

    /** Auto-summarize a contact once enough history accumulates. */
    var autoSummary: Boolean
        get() = sp.getBoolean(K_AUTO_SUMMARY, true)
        set(v) = sp.edit().putBoolean(K_AUTO_SUMMARY, v).apply()

    // ------------------------------------------------------------ OCR (B)

    /** "mlkit" | "vision". */
    var ocrEngine: String
        get() = sp.getString(K_OCR_ENGINE, OCR_MLKIT) ?: OCR_MLKIT
        set(v) = sp.edit().putString(K_OCR_ENGINE, v.trim()).apply()

    /** Run generic OCR capture on apps with no dedicated adapter. */
    var ocrForUnknownApps: Boolean
        get() = sp.getBoolean(K_OCR_UNKNOWN, true)
        set(v) = sp.edit().putBoolean(K_OCR_UNKNOWN, v).apply()

    /** Fall back to OCR when an adapted app's node tree comes back empty. */
    var ocrFallback: Boolean
        get() = sp.getBoolean(K_OCR_FALLBACK, true)
        set(v) = sp.edit().putBoolean(K_OCR_FALLBACK, v).apply()

    /** Auto-analyze in OCR mode (default off: OCR costs a screenshot each time). */
    var ocrAutoAnalyze: Boolean
        get() = sp.getBoolean(K_OCR_AUTO, false)
        set(v) = sp.edit().putBoolean(K_OCR_AUTO, v).apply()

    // ------------------------------------------------ share / paste (v1.8)

    /**
     * 截屏识别总闸，**默认关闭**。v1.8 起主输入改成「用户分享截图 / 粘贴文本」，
     * 不再让 App 自己去截别人的屏幕：那正是把微信带进 FLAG_SECURE 保护状态的动作。
     */
    var screenshotOcrEnabled: Boolean
        get() = sp.getBoolean(K_SCREENSHOT_OCR, false)
        set(v) = sp.edit().putBoolean(K_SCREENSHOT_OCR, v).apply()

    /** 会话滚动窗口条数：塞给模型的最近消息数，更早的压成摘要。 */
    var sessionWindowSize: Int
        get() = sp.getInt(K_SESSION_WINDOW, 30).coerceIn(10, 100)
        set(v) = sp.edit().putInt(K_SESSION_WINDOW, v.coerceIn(10, 100)).apply()

    /** 候选回复点击时是否尝试写进聊天输入框（失败自动退剪贴板）。 */
    var fillEnabled: Boolean
        get() = sp.getBoolean(K_FILL_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_FILL_ENABLED, v).apply()

    // ------------------------------------------------------------- existing

    /** Free-text describing who the other person is; goes into Jev's state. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations". Stored as a plain string set.
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /** Remembered vertical position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    /** Remembered horizontal position of the bubble (px); -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    /** How many candidate replies to draft (1..3). With 1 the Jev ranking round is skipped. */
    var candidateCount: Int
        get() = sp.getInt(K_CANDIDATES, 3).coerceIn(1, 3)
        set(v) = sp.edit().putInt(K_CANDIDATES, v.coerceIn(1, 3)).apply()

    /** Where the analysis card sits in [pkg] (px), or null for the default top position. */
    fun cardPosition(pkg: String): Pair<Int, Int>? {
        val x = sp.getInt(K_CARD_X + pkg, -1)
        val y = sp.getInt(K_CARD_Y + pkg, -1)
        return if (x < 0 || y < 0) null else x to y
    }

    fun saveCardPosition(pkg: String, x: Int, y: Int) {
        sp.edit().putInt(K_CARD_X + pkg, x).putInt(K_CARD_Y + pkg, y).apply()
    }

    /** Auto-analyze on every incoming message; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    // ------------------------------------------------------------- helpers

    /** Reply route key, falling back to the judge key. */
    fun effectiveReplyKey(): String = replyKey.ifBlank { judgeKey }

    /** Vision route key, falling back to reply then judge. */
    fun effectiveVisionKey(): String = visionKey.ifBlank { effectiveReplyKey() }

    /** Full POST URL for the Jev decisions call, per provider. */
    fun judgeEndpoint(): String {
        val base = judgeBaseUrl.trim().trimEnd('/')
        return when (judgeProvider) {
            PROVIDER_CHAT -> "$base/chat/completions"  // OpenAI-compatible (DeepSeek / DashScope)
            PROVIDER_BOCHA -> "$base/v1/systemone"    // same path as TypeSafe
            PROVIDER_TYPESAFE -> "$base/v1/systemone"
            PROVIDER_VERCEL -> "$base/v1/systemone"   // TypeSafe-compatible gateway
            PROVIDER_ZEN -> "$base/v1/systemone"      // TypeSafe-compatible gateway
            PROVIDER_CUSTOM -> judgeBaseUrl.trim()   // user supplies the full URL
            else -> "$base/alpha/decisions"
        }
    }

    /** Full POST URL for the OpenAI-compatible chat completions call. */
    fun replyEndpoint(): String = "${replyBaseUrl.trim().trimEnd('/')}/chat/completions"

    /** Same shape as [replyEndpoint]; blank falls back to the OpenRouter default. */
    fun visionEndpoint(): String {
        val base = visionBaseUrl.trim().ifBlank { DEFAULT_VISION_BASE }
        return "${base.trimEnd('/')}/chat/completions"
    }

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    /** Readiness gate: the judge route is the one that must be configured. */
    fun hasKey(): Boolean = judgeKey.isNotBlank()

    companion object {
        private const val TAG = "JEVASSIST"

        /** The one real config file. Anything else is a scratch instance. */
        const val PREFS_MAIN = "jev_assistant"

        private const val K_LEGACY_KEY = "openrouter_key"
        private const val K_MIGRATED_V13 = "prefs_migrated_v13"
        private const val K_UNIFIED_V19 = "prefs_unified_v19"
        private const val K_UNSEEDED_BOCHA = "unseeded_bocha_v141"
        private const val K_JUDGE_PROVIDER = "judge_provider"
        private const val K_JUDGE_BASE = "judge_base_url"
        private const val K_JUDGE_KEY = "judge_key"
        private const val K_JUDGE_MODEL = "judge_model"
        private const val K_REPLY_BASE = "reply_base_url"
        private const val K_REPLY_KEY = "reply_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_VISION_BASE = "vision_base_url"
        private const val K_VISION_KEY = "vision_key"
        private const val K_VISION_MODEL = "vision_model"
        private const val K_CTX_ENABLED = "context_enabled"
        private const val K_CTX_COUNT = "context_history_count"
        private const val K_AUTO_SUMMARY = "auto_summary"
        private const val K_OCR_ENGINE = "ocr_engine"
        private const val K_OCR_UNKNOWN = "ocr_unknown_apps"
        private const val K_OCR_FALLBACK = "ocr_fallback"
        private const val K_OCR_AUTO = "ocr_auto_analyze"
    private const val K_SCREENSHOT_OCR = "screenshot_ocr_enabled"
    private const val K_SESSION_WINDOW = "session_window_size"
    private const val K_FILL_ENABLED = "fill_enabled"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"
        private const val K_CANDIDATES = "candidate_count"
        private const val K_CARD_X = "card_x_"
        private const val K_CARD_Y = "card_y_"

        const val PROVIDER_BOCHA = "bocha"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_TYPESAFE = "typesafe"
        const val PROVIDER_VERCEL = "vercel"
        const val PROVIDER_ZEN = "zen"
        const val PROVIDER_CHAT = "chat"
        const val PROVIDER_CUSTOM = "custom"

        const val OCR_MLKIT = "mlkit"
        const val OCR_VISION = "vision"

        // Judge route presets.
        // Bocha Jev: same protocol/path as TypeSafe (/v1/systemone). Limited-time free.
        const val DEFAULT_JUDGE_BASE_BOCHA = "https://jev.bocha.cn"
        const val DEFAULT_JUDGE_MODEL_BOCHA = "bocha-jev-v1"
        const val DEFAULT_JUDGE_BASE_OPENROUTER = "https://openrouter.ai/api"
        const val DEFAULT_JUDGE_MODEL_OPENROUTER = "typesafe/jev-1.13"
        const val DEFAULT_JUDGE_BASE_TYPESAFE = "https://api.typesafe.ai"
        const val DEFAULT_JUDGE_MODEL_TYPESAFE = "jev-latest"
        // Vercel AI Gateway's TypeSafe-compatible API. Same /v1/systemone body
        // and noul answers as TypeSafe direct; model id is the gateway's.
        const val DEFAULT_JUDGE_BASE_VERCEL = "https://ai-gateway.vercel.sh/typesafe"
        const val DEFAULT_JUDGE_MODEL_VERCEL = "typesafe-ai/jev"
        // OpenCode Zen's TypeSafe-compatible API. Same /v1/systemone body and
        // noul answers; jev-1.13 is free on output ($0.042/M input, ~1k tokens
        // per judgment), jev-1.13-free is fully free but capability-limited.
        const val DEFAULT_JUDGE_BASE_ZEN = "https://opencode.ai/zen"
        const val DEFAULT_JUDGE_MODEL_ZEN = "jev-1.13"
        // 国内档：DeepSeek / 通义 等只有 /chat/completions，没有 decisions 协议，
        // 由 JudgeClient 把 state + questions 渲染成提示词再解析回来。
        // v1.9：判断 / 回复 / 看图合并成一个接口，默认 DeepSeek V4.1 Flash。
        const val DEFAULT_JUDGE_BASE_CHAT = "https://api.deepseek.com"
        const val DEFAULT_JUDGE_MODEL_CHAT = "deepseek-flash"

        // Reply / vision presets now follow the same unified route.
        const val DEFAULT_REPLY_BASE = DEFAULT_JUDGE_BASE_CHAT
        const val DEFAULT_REPLY_MODEL = DEFAULT_JUDGE_MODEL_CHAT
        const val DEEPSEEK_BASE = "https://api.deepseek.com"
        const val DEEPSEEK_MODEL = "deepseek-flash"
        const val DEEPSEEK_PRO_BASE = "https://api.deepseek.com"
        const val DEEPSEEK_PRO_MODEL = "deepseek-v4-pro"
        const val DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DASHSCOPE_MODEL = "qwen-plus"

        const val DEFAULT_VISION_BASE = DEFAULT_JUDGE_BASE_CHAT
        const val DEFAULT_VISION_MODEL = DEFAULT_JUDGE_MODEL_CHAT
        const val DASHSCOPE_VISION_MODEL = "qwen-vl-max"

        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    }
}
