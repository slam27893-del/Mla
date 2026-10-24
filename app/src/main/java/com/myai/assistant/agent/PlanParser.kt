package com.myai.assistant.agent

import com.myai.assistant.core.model.ExecutionPlan
import com.myai.assistant.core.model.PlanStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses the strict JSON contract returned by an AI provider:
 *
 * {"reply":"...","steps":[{"tool":"open_app","args":{"app":"واتساب"},"title":"..."}]}
 *
 * Unknown tools and missing required parameters are rejected *before* anything
 * is executed, so a hallucinating model cannot reach a tool with bad arguments.
 */
object PlanParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, tools: List<Tool>, goal: String, source: String = "llm"): PlannerResult {
        val objectText = extractJsonObject(raw)
            ?: return PlannerResult.Failed("لم يُرجع النموذج JSON صالحًا. جرّب صياغة أوضح أو استخدم مزوّدًا يدعم JSON.")

        val root = runCatching { json.parseToJsonElement(objectText) as? JsonObject }.getOrNull()
            ?: return PlannerResult.Failed("تعذّر تحليل استجابة النموذج كـ JSON.")

        val reply = (root["reply"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val stepsArray = root["steps"] as? JsonArray ?: JsonArray(emptyList())

        if (stepsArray.isEmpty()) {
            return if (reply.isNotBlank()) {
                PlannerResult.DirectAnswer(reply)
            } else {
                PlannerResult.Failed("استجابة النموذج لا تحتوي خطوات ولا ردًا نصيًا.")
            }
        }

        val steps = mutableListOf<PlanStep>()
        stepsArray.forEachIndexed { index, element ->
            val obj = element as? JsonObject
                ?: return PlannerResult.Failed("الخطوة ${index + 1} ليست كائن JSON.")
            val toolId = (obj["tool"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (toolId.isEmpty()) {
                return PlannerResult.Failed("الخطوة ${index + 1} لا تحدد اسم أداة.")
            }
            val tool = tools.firstOrNull { it.id == toolId }
                ?: return PlannerResult.Failed("طلب النموذج أداة غير معروفة: $toolId")

            val args = mutableMapOf<String, String>()
            (obj["args"] as? JsonObject)?.forEach { (key, value) ->
                if (value is JsonNull) return@forEach
                (value as? JsonPrimitive)?.content?.let { args[key] = it }
            }

            val missing = tool.parameters.filter { it.required && args[it.name].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                return PlannerResult.Failed(
                    "الأداة ${tool.id} تحتاج المعاملات: ${missing.joinToString("، ") { it.name }}"
                )
            }
            val title = (obj["title"] as? JsonPrimitive)?.content?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: tool.summary(args)

            steps += PlanStep(
                id = "step-${index + 1}",
                toolId = tool.id,
                title = title,
                args = args
            )
        }

        return PlannerResult.Planned(
            plan = ExecutionPlan(goal = goal, source = source, steps = steps),
            reply = reply.ifBlank { "سأنفّذ ${steps.size} خطوة." }
        )
    }

    /**
     * Extracts the first balanced JSON object from a model response that may be
     * wrapped in ```json fences or extra prose. String literals and escapes are
     * respected so braces inside strings do not confuse the scanner.
     */
    fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val ch = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
