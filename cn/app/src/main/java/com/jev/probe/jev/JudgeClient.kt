package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Jev judgment route only: the 7 judgment questions in one call, and the
 * ranking question over already-drafted candidates. Reads judgeProvider /
 * judgeBaseUrl / judgeKey / judgeModel from [Prefs]; nothing generative here.
 */
class JudgeClient(private val prefs: Prefs) {

    /**
     * The 7 judgment questions (fast, ~1s). Errors are returned, not thrown.
     *
     * @param ctx D-stage knowledge context; null or empty means the request body
     *        is byte-for-byte what v1.2 sent.
     */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        if (isChatRoute()) return judgeByChat(snapshot, relationship, ctx)
        val start = System.currentTimeMillis()
        return try {
            val answers = postDecisions(
                snapshot, relationship, ctx,
                JevQuestions.judge()
            )
            Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = e.message ?: "判断接口请求失败")
        }
    }

    /** Ask Jev which of the candidate replies is best; throws on failure. */
    fun rank(
        snapshot: ChatSnapshot,
        relationship: String,
        candidates: List<String>,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        if (isChatRoute()) return rankByChat(snapshot, relationship, candidates, ctx)
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val answers = postDecisions(snapshot, relationship, ctx, questions)
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    /**
     * POST one decisions request, with the knowledge fields when there are any.
     *
     * Defensive retry: whether the live `alpha/decisions` endpoint accepts the
     * new `background` / `history` state fields or rejects unknown ones with a
     * 4xx is not verified against production yet (see the A-stage report). If a
     * request carrying them comes back 4xx, it is sent again once without them,
     * so an unverified field can degrade the analysis but never break it.
     */
    private fun postDecisions(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext?,
        questions: JSONObject
    ): JSONObject {
        val background = ctx?.background(relationship) ?: ""
        val history = ctx?.history ?: emptyList()
        val enriched = background.isNotBlank() || history.isNotEmpty()
        return try {
            send(JevQuestions.buildState(snapshot, relationship, background, history), questions)
        } catch (e: ApiException) {
            if (enriched && e.status != null && e.status in 400..499) {
                Log.w(TAG, "judge HTTP ${e.status} with background/history; retrying plain")
                send(JevQuestions.buildState(snapshot, relationship), questions)
            } else throw e
        }
    }

    private fun send(state: JSONObject, questions: JSONObject): JSONObject {
        val url = prefs.judgeEndpoint()
        val body = JSONObject()
            .put("model", prefs.judgeModel)
            .put("state", state)
            .put("questions", questions)
        val resp = HttpJson.post(url, prefs.judgeKey, body, Route.JUDGE, HttpJson.headersFor(url))
        return resp.optJSONObject("answers") ?: JSONObject()
    }

    // ------------------------------------------------ OpenAI-compatible judge
    // 国内档：DeepSeek / 通义 只有 /chat/completions，不走 decisions 协议。
    // 同一份 state + questions 交给模型，要求它用同样的键名回 JSON。

    private fun isChatRoute(): Boolean = prefs.judgeProvider == Prefs.PROVIDER_CHAT

    private fun judgeByChat(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext?
    ): Analysis {
        val start = System.currentTimeMillis()
        return try {
            val state = JevQuestions.buildState(
                snapshot, relationship,
                ctx?.background(relationship) ?: "",
                ctx?.history ?: emptyList()
            )
            val obj = askJson(state, JevQuestions.judge())
            Analysis(
                trueIntent = parseChoice(obj.optJSONObject("true_intent")),
                dangerLevel = parseScore(obj.optJSONObject("danger_level")),
                sheNeeds = parseChoice(obj.optJSONObject("she_needs")),
                shouldReplyNow = obj.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(obj.optJSONObject("best_action")),
                tensionResolved = obj.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = obj.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge(chat) failed: ${e.message}")
            Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = e.message ?: "判断接口请求失败")
        }
    }

    private fun rankByChat(
        snapshot: ChatSnapshot,
        relationship: String,
        candidates: List<String>,
        ctx: ChatContext?
    ): List<RankedReply> {
        val state = JevQuestions.buildState(
            snapshot, relationship,
            ctx?.background(relationship) ?: "",
            ctx?.history ?: emptyList()
        )
        val sys = """你在帮用户挑一条最合适的回复。只输出 JSON：{"probabilities":{"reply_a":0.0,"reply_b":0.0,"reply_c":0.0}}，不要解释。"""
        val user = "对话状态：\n" + state.toString() + "\n\n候选：\nA. " +
            candidates.getOrElse(0) { "" } + "\nB. " +
            candidates.getOrElse(1) { "" } + "\nC. " + candidates.getOrElse(2) { "" }
        val ranked = parseRanked(extractJson(chatRaw(sys, user)), candidates)
        return if (ranked.all { it.prob <= 0.0 }) {
            val w = 1.0 / candidates.size.coerceAtLeast(1)
            candidates.map { RankedReply(it, w) }
        } else ranked
    }

    private fun askJson(state: JSONObject, questions: JSONObject): JSONObject {
        val user = "state：\n" + state.toString(1) + "\n\nquestions：\n" + questions.toString(1)
        return extractJson(chatRaw(JUDGE_SYSTEM, user))
    }

    private fun chatRaw(system: String, user: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.judgeModel)
            .put("messages", messages)
            .put("temperature", 0.1)
            .put("stream", false)
        val url = prefs.judgeEndpoint()
        val resp = HttpJson.post(url, prefs.judgeKey, body, Route.JUDGE, HttpJson.headersFor(url))
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    /** 取回复里的第一个 JSON 对象；容忍代码块包裹和 {answers:{...}} 外层的写法。 */
    private fun extractJson(content: String): JSONObject {
        val s = content.indexOf('{')
        val e = content.lastIndexOf('}')
        require(s >= 0 && e > s) { "模型没有返回 JSON" }
        val o = JSONObject(content.substring(s, e + 1))
        return o.optJSONObject("answers") ?: o
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }
        return list.sortedByDescending { it.prob }
    }

    companion object {
        private const val TAG = "JEVASSIST"

        private const val JUDGE_SYSTEM = """你是中文即时通讯分析器。只输出一个 JSON 对象，键名与层级严格如下，不要解释、不要代码块：
{"true_intent":{"choice":"选项原文","confidence":0.0,"probabilities":{}},
 "danger_level":{"score":1,"confidence":0.0,"legend":{"1":"没什么风险","9":"极其危险"}},
 "she_needs":{"choice":"选项原文","confidence":0.0,"probabilities":{}},
 "should_reply_now":{"noul":0.0},
 "best_action":{"choice":"选项原文","confidence":0.0,"probabilities":{}},
 "tension_resolved":{"noul":0.0},
 "literal_question":{"noul":0.0}}
questions 里给了候选就用候选原文当 choice 和 probabilities 的键；noul / confidence / probabilities 都是 0..1 的数。"""
    }
}
