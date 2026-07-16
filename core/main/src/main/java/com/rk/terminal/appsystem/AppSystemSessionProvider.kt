package com.rk.terminal.appsystem

import android.content.Context
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

object AppSystemSessionProvider {
    fun createAppSystemMenuSession(
        context: Context,
        sessionClient: TerminalSessionClient,
        sessionId: String
    ): TerminalSession {
        AppSystemManager.ensureAppSystemInterpreter(context)
        val appShell = AppSystemManager.getInterpreterFile(context)
        val workDir = AppSystemManager.getAppRoot(context).absolutePath
        val logFile = AppSystemManager.getSessionLogFile(context, sessionId).absolutePath
        val env = mutableListOf<String>().apply {
            add("APP_SYSTEM_ROOT=$workDir")
            add("APP_SYSTEM_APPS=${AppSystemManager.getAppsDir(context).absolutePath}")
            add("APP_SYSTEM_PLUGINS=${AppSystemManager.getPluginsDir(context).absolutePath}")
            add("APP_SYSTEM_LIBS=${AppSystemManager.getLibsDir(context).absolutePath}")
            add("PATH=${System.getenv("PATH") ?: ""}:${AppSystemManager.getAppsDir(context).absolutePath}:${AppSystemManager.getPluginsDir(context).absolutePath}")
            add("LD_LIBRARY_PATH=${System.getenv("LD_LIBRARY_PATH") ?: ""}:${AppSystemManager.getLibsDir(context).absolutePath}")
        }
        return TerminalSession(
            "/system/bin/sh",
            workDir,
            arrayOf("-c", "exec \"${appShell.absolutePath}\" 2>&1 | tee -a \"$logFile\"") ,
            env.toTypedArray(),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            sessionClient
        )
    }
}
