package com.rk.terminal.appsystem

import android.content.Context
import java.io.File

object AppSystemManager {
    private const val LIBS = "libs"
    private const val APPS = "apps"
    private const val PLUGINS = "apps/plugins"
    private const val OUTPUT = "data/output"
    private const val INTERPRETER = "app-system.sh"
    private const val REMOTE_SHELL = "app-system-remote-shell.sh"

    fun getAppRoot(context: Context): File {
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    fun getLibsDir(context: Context): File = getAppRoot(context).resolve(LIBS).apply { if (!exists()) mkdirs() }

    fun getAppsDir(context: Context): File = getAppRoot(context).resolve(APPS).apply { if (!exists()) mkdirs() }

    fun getPluginsDir(context: Context): File = getAppRoot(context).resolve(PLUGINS).apply { if (!exists()) mkdirs() }

    fun getOutputDir(context: Context): File = getAppRoot(context).resolve(OUTPUT).apply { if (!exists()) mkdirs() }

    fun ensureAppSystemDirectories(context: Context) {
        getLibsDir(context)
        getAppsDir(context)
        getPluginsDir(context)
        getOutputDir(context)
    }

    fun ensureAppSystemInterpreter(context: Context) {
        ensureAppSystemDirectories(context)
        val interpreterFile = getAppsDir(context).resolve(INTERPRETER)
        val interpreterText = appSystemInterpreter(context)
        if (!interpreterFile.exists() || interpreterFile.readText() != interpreterText) {
            interpreterFile.writeText(interpreterText)
            interpreterFile.setExecutable(true, false)
        }
        val remoteFile = getPluginsDir(context).resolve(REMOTE_SHELL)
        val remoteText = appSystemRemoteShell(context)
        if (!remoteFile.exists() || remoteFile.readText() != remoteText) {
            remoteFile.writeText(remoteText)
            remoteFile.setExecutable(true, false)
        }
    }

    fun listPlugins(context: Context): List<Pair<String, String>> {
        val base = getPluginsDir(context)
        return base.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val yml = dir.resolve("plugin.yml")
            if (!yml.exists()) return@mapNotNull null
            val lines = yml.readLines()
            var name = dir.name
            var desc = ""
            for (l in lines) {
                val t = l.trim()
                if (t.startsWith("name:")) name = t.removePrefix("name:").trim().trim('"')
                if (t.startsWith("description:")) desc = t.removePrefix("description:").trim().trim('"')
            }
            name to desc
        } ?: emptyList()
    }

    fun listLibs(context: Context): List<String> {
        return getLibsDir(context).listFiles { f -> f.extension == "so" }?.map { it.name } ?: emptyList()
    }

    fun startAppSystemProcess(context: Context): Process {
        ensureAppSystemInterpreter(context)
        val interpreter = getAppsDir(context).resolve(INTERPRETER)
        val pb = ProcessBuilder("shizuku", "sh", "-c", interpreter.absolutePath)
        pb.redirectErrorStream(true)
        return pb.start()
    }

    private fun appSystemInterpreter(context: Context): String {
        val appRoot = getAppRoot(context).absolutePath
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("APP_SYSTEM_ROOT=\"$appRoot\"")
            appendLine("APP_SYSTEM_APPS=\"$appRoot/apps\"")
            appendLine("APP_SYSTEM_PLUGINS=\"$appRoot/apps/plugins\"")
            appendLine("APP_SYSTEM_LIBS=\"$appRoot/libs\"")
            appendLine("APP_SYSTEM_OUTPUT=\"$appRoot/data/output\"")
            appendLine("echo \"[ * ] Initializing App System Environment...\"")
            appendLine("echo \"[ * ] Injected PATH and LD_LIBRARY_PATH successfully.\"")
            appendLine("echo \"[ * ] Loaded plugins and shared libraries.\"")
            appendLine("echo \"[ OK ] System ready!\"")
            appendLine("prompt() { printf \"[app-system:${'$'}{PWD}]> \" ; }")
            appendLine("list_plugins_native() {")
            appendLine("  for d in \"$appRoot/apps/plugins\"/*; do")
            appendLine("    [ -d \"\$d\" ] || continue")
            appendLine("    if [ -f \"\$d/plugin.yml\" ]; then")
            appendLine("      name=$(grep -E '^name:' \"\$d/plugin.yml\" | head -n1 | cut -d: -f2- | sed 's/^ *//;s/ *$//' | tr -d '\"')")
            appendLine("      desc=$(grep -E '^description:' \"\$d/plugin.yml\" | head -n1 | cut -d: -f2- | sed 's/^ *//;s/ *$//' | tr -d '\"')")
            appendLine("      printf \"%s - %s\\n\" \"\$(basename \"\$d\")\" \"\${name:-}\" \"\${desc:-}\"")
            appendLine("    fi")
            appendLine("  done")
            appendLine("}")
            appendLine("list_libs_native() {")
            appendLine("  for f in \"$appRoot/libs\"/*.so; do")
            appendLine("    [ -e \"\$f\" ] || continue")
            appendLine("    printf \"%s\\n\" \"\$(basename \"\$f\")\"")
            appendLine("  done")
            appendLine("}")
            appendLine("run_plugin_native() {")
            appendLine("  if [ -z \"\$1\" ]; then printf \"Usage: run-plugin [name]\n\"; return 1; fi")
            appendLine("  plugin=\"$appRoot/apps/plugins/\$1\"")
            appendLine("  if [ ! -d \"\$plugin\" ]; then printf \"AppSystem Error: Plugin not found: %s\\n\" \"\$1\"; return 2; fi")
            appendLine("  if [ -f \"\$plugin/plugin.yml\" ]; then")
            appendLine("    execPath=$(grep -E '^main:|^exec:' \"\$plugin/plugin.yml\" | head -n1 | cut -d: -f2- | sed 's/^ *//;s/ *$//' | tr -d '\"')")
            appendLine("  else")
            appendLine("    execPath=\"\$plugin/loader.sh\"")
            appendLine("  fi")
            appendLine("  if [ \"\${execPath#/}\" = \"\$execPath\" ]; then execPath=\"\$plugin/\$execPath\"; fi")
            appendLine("  if [ -f \"\$execPath\" ]; then chmod +x \"\$execPath\" 2>/dev/null; fi")
            appendLine("  shift")
            appendLine("  envs=()")
            appendLine("  if [ -f \"\$plugin/loader.yml\" ]; then")
            appendLine("    while IFS= read -r l; do")
            appendLine("      k=$(echo \"\$l\" | sed -n 's/^\\s*\\([^:]*\\):.*/\\1/p')")
            appendLine("      if [ -n \"\$k\" ]; then v=$(echo \"\$l\" | cut -d: -f2- | sed 's/^ *//;s/ *$//' | tr -d '\"'); envs+=(\"\$k=\$v\"); fi")
            appendLine("    done < \"\$plugin/loader.yml\"")
            appendLine("  fi")
            appendLine("  for e in \"\${envs[@]}\"; do export \"\$e\"; done")
            appendLine("  exec \"\$execPath\" \"\${@}\"")
            appendLine("}")
            appendLine("run_shell() {")
            appendLine("  export PATH=\"\$PATH:\$appRoot/apps:\$appRoot/apps/plugins\"")
            appendLine("  export LD_LIBRARY_PATH=\"\$LD_LIBRARY_PATH:\$appRoot/libs\"")
            appendLine("  chmod +x \"$appRoot/apps\"/* \"$appRoot/apps/plugins\"/* 2>/dev/null")
            appendLine("  exec /system/bin/sh")
            appendLine("}")
            appendLine("while true; do")
            appendLine("  prompt")
            appendLine("  if ! IFS= read -r cmd args; then break; fi")
            appendLine("  case \"\$cmd\" in")
            appendLine("    list-plugins) list_plugins_native ;;")
            appendLine("    list-libs) list_libs_native ;;")
            appendLine("    run-plugin) run_plugin_native \"\$args\" ;;")
            appendLine("    shell) run_shell ;;")
            appendLine("    cd)")
            appendLine("      if [ -z \"\$args\" ]; then cd ~ || true; else cd \"\$args\" || printf \"Failed to enter: %s\\n\" \"\$args\"; fi")
            appendLine("      printf \"Changed directory to: %s\\n\" \"\$PWD\"")
            appendLine("      ;;")
            appendLine("    exit) break ;;")
            appendLine("    '') continue ;;")
            appendLine("    *) printf \"Unknown command: %s\\n\" \"\$cmd\" ;;")
            appendLine("  esac")
            appendLine("done")
        }
    }

    private fun appSystemRemoteShell(context: Context): String {
        val appRoot = getAppRoot(context).absolutePath
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("APP_SYSTEM_ROOT=\"$appRoot\"")
            appendLine("APP_SYSTEM_APPS=\"$appRoot/apps\"")
            appendLine("APP_SYSTEM_PLUGINS=\"$appRoot/apps/plugins\"")
            appendLine("APP_SYSTEM_LIBS=\"$appRoot/libs\"")
            appendLine("export PATH=\"\$PATH:\$APP_SYSTEM_APPS:\$APP_SYSTEM_PLUGINS\"")
            appendLine("export LD_LIBRARY_PATH=\"\$LD_LIBRARY_PATH:\$APP_SYSTEM_LIBS\"")
            appendLine("chmod +x \"\$APP_SYSTEM_APPS\"/* \"\$APP_SYSTEM_PLUGINS\"/* 2>/dev/null")
            appendLine("if command -v shizuku >/dev/null 2>&1; then")
            appendLine("  exec shizuku sh -c \"export PATH=\\\$PATH:\$APP_SYSTEM_APPS:\\\$APP_SYSTEM_PLUGINS; export LD_LIBRARY_PATH=\\\$LD_LIBRARY_PATH:\$APP_SYSTEM_LIBS; exec /system/bin/sh\"")
            appendLine("fi")
            appendLine("exec /system/bin/sh")
        }
    }
}
