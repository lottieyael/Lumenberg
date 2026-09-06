package dev.lumenberg.agent

enum class PolicyDecision {
    ALLOW,
    DENY,
}

fun interface PolicyEngine {
    fun decide(tool: ToolSpec): PolicyDecision
}

object DefaultPolicy : PolicyEngine {
    override fun decide(tool: ToolSpec): PolicyDecision = when (tool.risk) {
        ToolRisk.READ, ToolRisk.LOCAL -> PolicyDecision.ALLOW
        ToolRisk.EXTERNAL, ToolRisk.WEALTH, ToolRisk.DESTRUCTIVE -> PolicyDecision.DENY
    }
}
