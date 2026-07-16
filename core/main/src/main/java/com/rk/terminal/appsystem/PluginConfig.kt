package com.rk.terminal.appsystem

data class PluginDescriptor(
    val loader: String? = null
)

data class LoaderConfig(
    val exec: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap()
)

data class PluginExecutionOutcome(
    val pluginName: String,
    val exitCode: Int,
    val outputLog: String,
    val errorLog: String
)
