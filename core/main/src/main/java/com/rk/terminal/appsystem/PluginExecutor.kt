package com.rk.terminal.appsystem

import android.content.Context
import android.util.Log
import com.rk.libcommons.child
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class PluginExecutor(private val context: Context) {
    private val executor = Executors.newCachedThreadPool()

    fun runPlugin(pluginName: String, onOutput: (String) -> Unit, onError: (String) -> Unit) {
        executor.execute {
            try {
                val pluginDir = AppSystemManager.findPluginDirectory(context, pluginName)
                    ?: throw IllegalStateException("Plugin directory not found: $pluginName")

                val pluginDescriptor = loadPluginDescriptor(pluginDir)
                val loaderFile = AppSystemManager.resolvePluginLoaderFile(pluginDir, pluginDescriptor.loader)
                val loaderConfig = loadLoaderConfig(loaderFile)
                val executionCommand = buildExecutionCommand(pluginDir, loaderConfig)
                val environment = buildExecutionEnvironment(pluginDir, loaderConfig)

                runRemoteProcess(pluginName, executionCommand, environment, onOutput, onError)
            } catch (t: Throwable) {
                val message = "AppSystem Error: ${t.message ?: "Plugin execution failed"}"
                onError(message)
                logCrash(pluginName, message, t)
            }
        }
    }

    private fun loadPluginDescriptor(pluginDir: File): PluginDescriptor {
        val pluginConfig = pluginDir.child("plugin.yml")
        if (!pluginConfig.exists()) {
            return PluginDescriptor()
        }
        val data = parseYamlAsMap(pluginConfig)
        val loader = data["loader"] as? String
        return PluginDescriptor(loader = loader)
    }

    private fun loadLoaderConfig(loaderFile: File): LoaderConfig {
        if (!loaderFile.exists()) {
            throw IllegalStateException("loader.yml not found at ${loaderFile.absolutePath}")
        }
        val data = parseYamlAsMap(loaderFile)
        val exec = data["exec"] as? String ?: throw IllegalStateException("Missing exec in loader.yml")
        val args = (data["args"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val env = (data["env"] as? Map<*, *>)?.mapNotNull { if (it.key is String && it.value is String) it.key as String to it.value as String else null }?.toMap() ?: emptyMap()
        return LoaderConfig(exec = exec, args = args, env = env)
    }

    private fun buildExecutionCommand(pluginDir: File, loaderConfig: LoaderConfig): List<String> {
        val resolvedExec = resolvePath(pluginDir, loaderConfig.exec)
        if (resolvedExec.isFile && resolvedExec.exists()) {
            resolvedExec.setExecutable(true, false)
        }
        return listOf(resolvedExec.absolutePath) + loaderConfig.args.map { resolvePath(pluginDir, it).absolutePath }
    }

    private fun buildExecutionEnvironment(pluginDir: File, loaderConfig: LoaderConfig): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["PATH"] = System.getenv("PATH").orEmpty() + ":${AppSystemManager.getAppsDir(context).absolutePath}:${AppSystemManager.getPluginsDir(context).absolutePath}"
        env["LD_LIBRARY_PATH"] = System.getenv("LD_LIBRARY_PATH").orEmpty() + ":${AppSystemManager.getLibsDir(context).absolutePath}"
        env.putAll(loaderConfig.env)
        return env
    }

    private fun resolvePath(pluginDir: File, path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else pluginDir.child(path)
    }

    private fun runRemoteProcess(
        pluginName: String,
        command: List<String>,
        environment: Map<String, String>,
        onOutput: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            if (command.isEmpty()) {
                throw IllegalStateException("Execution command is empty")
            }
            val remoteCommand = command.joinToString(" ") { escapeShellArg(it) }
            val fullShell = buildString {
                append("export PATH=\"${environment["PATH"] ?: ""}\"\n")
                append("export LD_LIBRARY_PATH=\"${environment["LD_LIBRARY_PATH"] ?: ""}\"\n")
                environment.filterKeys { it != "PATH" && it != "LD_LIBRARY_PATH" }
                    .forEach { (key, value) -> append("export $key=\"${value.replace("\"", "\\\"")}\"\n") }
                append("chmod +x ${escapeShellArg(AppSystemManager.getAppsDir(context).absolutePath)}/* ${escapeShellArg(AppSystemManager.getPluginsDir(context).absolutePath)}/* 2>/dev/null\n")
                append(remoteCommand)
            }

            val processBuilder = if (isCommandAvailable("shizuku")) {
                ProcessBuilder("shizuku", "sh", "-c", fullShell)
            } else {
                ProcessBuilder("/system/bin/sh", "-c", fullShell)
            }

            val shellProcess = processBuilder
                .directory(AppSystemManager.getAppRoot(context))
                .redirectErrorStream(true)
                .start()

            val stdout = shellProcess.inputStream.bufferedReader()
            var line: String?
            while (stdout.readLine().also { line = it } != null) {
                onOutput(line.orEmpty())
            }
            val exitCode = shellProcess.waitFor()
            if (exitCode != 0) {
                val errorMessage = "AppSystem Error: Plugin $pluginName exited with code $exitCode"
                onError(errorMessage)
                logCrash(pluginName, errorMessage, null)
            }
        } catch (t: Throwable) {
            if (t is java.io.IOException && t.message?.contains("No such file or directory") == true) {
                onError("AppSystem Error: Interpreter not found")
                logCrash(pluginName, "Interpreter not found", t)
            } else {
                onError("AppSystem Error: ${t.message}")
                logCrash(pluginName, "Plugin execution failed", t)
            }
        }
    }

    private fun escapeShellArg(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun isCommandAvailable(name: String): Boolean {
        val path = System.getenv("PATH").orEmpty()
        return path.split(":").any { File(it, name).canExecute() }
    }

    private fun logCrash(pluginName: String, message: String, throwable: Throwable?) {
        val logName = "crash_${pluginName}.log"
        AppSystemManager.writeOutput(context, logName, "${System.currentTimeMillis()} - $message")
        throwable?.stackTrace?.joinToString("\n")?.let { AppSystemManager.writeOutput(context, logName, it) }
    }

    private fun parseYamlAsMap(file: File): Map<String, Any?> {
        FileInputStream(file).use { stream ->
            val load = Load(LoadSettings.builder().build())
            val data = load.loadFromReader(InputStreamReader(stream, StandardCharsets.UTF_8))
            return (data as? Map<String, Any?>) ?: emptyMap()
        }
    }
}
