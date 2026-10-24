package com.myai.assistant.agent

class ToolRegistry(tools: List<Tool>) {

    private val ordered: List<Tool> = tools.toList()
    private val byId: Map<String, Tool>

    init {
        val duplicates = ordered.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate tool ids: $duplicates" }
        byId = ordered.associateBy { it.id }
    }

    fun all(): List<Tool> = ordered

    fun get(id: String): Tool? = byId[id]

    fun ids(): Set<String> = byId.keys
}
