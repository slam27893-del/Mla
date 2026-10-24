package com.myai.assistant.tools

import com.myai.assistant.agent.Tool

/**
 * The single list of capabilities exposed to the planner. Adding a feature means
 * adding a Tool here (plus a unit test); the AI prompt and the offline engine are
 * both derived from this list.
 */
fun defaultTools(): List<Tool> = listOf(
    OpenAppTool(),
    OpenUrlTool(),
    WebSearchTool(),
    DialTool(),
    ComposeSmsTool(),
    SetAlarmTool(),
    SetTimerTool(),
    CalendarEventTool(),
    ShareTextTool(),
    CopyToClipboardTool(),
    MediaControlTool(),
    FlashlightTool(),
    DeviceStatusTool(),
    OpenSettingsTool(),
    WaitTool(),
    ScreenSummaryTool(),
    ClickTextTool(),
    SetTextTool(),
    ScrollScreenTool(),
    GlobalActionTool()
)
