package com.myai.assistant.tools

import com.myai.assistant.agent.Tool
import com.myai.assistant.agent.ToolContext
import com.myai.assistant.agent.ToolOutcome
import com.myai.assistant.agent.ToolParameter
import com.myai.assistant.agent.intArg
import com.myai.assistant.agent.ParameterType
import kotlinx.coroutines.delay

class WaitTool : Tool {
    override val id = "wait"
    override val title = "انتظار"
    override val description = "ينتظر مدة محددة بين خطوتين عند الحاجة (حد أقصى 15 ثانية)."
    override val parameters = listOf(
        ToolParameter("seconds", "مدة الانتظار بالثواني", type = ParameterType.INTEGER)
    )

    override fun summary(args: Map<String, String>): String = "انتظار ${args["seconds"].orEmpty()} ثانية"

    override suspend fun execute(context: ToolContext, args: Map<String, String>): ToolOutcome {
        val seconds = (args.intArg("seconds") ?: 1).coerceIn(1, 15)
        delay(seconds * 1000L)
        return ToolOutcome.verified("تم الانتظار $seconds ثانية.")
    }
}
