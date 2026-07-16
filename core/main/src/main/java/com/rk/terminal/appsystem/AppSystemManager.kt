package com.rk.terminal.appsystem

import android.content.Context
import com.rk.libcommons.child
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

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

    fun getLibsDir(context: Context): File {
        return getAppRoot(context).child(LIBS).also { if (!it.exists()) it.mkdirs() }
    }

    fun getAppsDir(context: Context): File {
        return getAppRoot(context).child(APPS).also { if (!it.exists()) it.mkdirs() }
    }

    fun getPluginsDir(context: Context): File {
        return getAppRoot(context).child(PLUGINS).also { if (!it.exists()) it.mkdirs() }
    }

    fun findPluginDirectory(context: Context, pluginName: String): File? {
        val pluginDir = getPluginsDir(context).child(pluginName)
        if (pluginDir.exists() && pluginDir.isDirectory) {
            return pluginDir
        }
        return getPluginsDir(context).listFiles()?.firstOrNull {
            it.isDirectory && it.name == pluginName
        }
    }

    fun resolvePluginLoaderFile(pluginDir: File, loaderPath: String?): File {
        if (loaderPath.isNullOrBlank()) {
            return pluginDir.child("loader.yml")
        }
        val loaderFile = File(loaderPath)
        return if (loaderFile.isAbsolute) loaderFile else pluginDir.child(loaderPath)
    }

    fun getOutputDir(context: Context): File {
        return getAppRoot(context).child(OUTPUT).also { if (!it.exists()) it.mkdirs() }
    }

    fun ensureAppSystemDirectories(context: Context) {
        getLibsDir(context)
        getAppsDir(context)
        getPluginsDir(context)
        getOutputDir(context)
    }

    fun ensureAppSystemInterpreter(context: Context) {
        ensureAppSystemDirectories(context)
        val interpreterFile = getAppsDir(context).child(INTERPRETER)
        val interpreterText = appSystemInterpreter(context)
        if (!interpreterFile.exists() || interpreterFile.readText() != interpreterText) {
            interpreterFile.writeText(interpreterText)
            interpreterFile.setExecutable(true, false)
        }
        val remoteShellFile = getPluginsDir(context).child(REMOTE_SHELL)
        val remoteShellText = appSystemRemoteShell(context)
        if (!remoteShellFile.exists() || remoteShellFile.readText() != remoteShellText) {
            remoteShellFile.writeText(remoteShellText)
            remoteShellFile.setExecutable(true, false)
        }
    }

    fun listPlugins(context: Context): List<String> {
        return getPluginsDir(context).listFiles()
            ?.filter { it.isFile && (it.canExecute() || it.extension in setOf("sh", "bin")) }
            ?.sortedBy { it.name }
            ?.map { it.name }
            ?: emptyList()
    }

    fun listLibs(context: Context): List<String> {
        return getLibsDir(context).listFiles { _, name -> name.endsWith(".so") }
            ?.sortedBy { it.name }
            ?.map { it.name }
            ?: emptyList()
    }

    fun writeOutput(context: Context, name: String, text: String, append: Boolean = true) {
        val outputFile = getOutputDir(context).child(name)
        outputFile.parentFile?.mkdirs()
        BufferedWriter(FileWriter(outputFile, append)).use {
            it.append(text)
            it.newLine()
        }
    }

    fun getInterpreterFile(context: Context): File {
        return getAppsDir(context).child(INTERPRETER)
    }

    fun getRemoteShellFile(context: Context): File {
        return getPluginsDir(context).child(REMOTE_SHELL)
    }

    fun getSessionLogFile(context: Context, sessionId: String): File {
        return getOutputDir(context).child("session-$sessionId.log")
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
            appendLine("prompt() { printf \"app-system> \" ; }")
            appendLine("format_entry() { printf \"  %s\\n\" \$1 ; }")
            appendLine("list_plugins() {")
            appendLine("  for file in \$APP_SYSTEM_PLUGINS/*; do")
            appendLine("    [ -e \$file ] || continue")
            appendLine("    if [ -d \$file ]; then")
            appendLine("      format_entry \"$(basename \$file)/\"")
            appendLine("    elif [ -f \$file ] && [ -x \$file ]; then")
            appendLine("      format_entry \"$(basename \$file)\"")
            appendLine("    elif [ -f \$file ] && [ \${file##*.} = \"sh\" ]; then")
            appendLine("      format_entry \"$(basename \$file)\"")
            appendLine("    fi")
            appendLine("  done")
            appendLine("}")
            appendLine("list_libs() {")
            appendLine("  for file in \$APP_SYSTEM_LIBS/*.so; do")
            appendLine("    [ -e \$file ] || continue")
            appendLine("    format_entry \"$(basename \$file)\"")
            appendLine("  done")
            appendLine("}")
            appendLine("help_text() {")
            appendLine("cat <<'EOF'")
            appendLine("Available commands:")
            appendLine("  list-plugins   List executable plugins and scripts")
            appendLine("  list-libs      List shared libraries in libs")
            appendLine("  run-plugin     Execute a plugin by directory name")
            appendLine("  help           Show this help")
            appendLine("  shell          Launch Shizuku-backed Android shell with injected environment")
            appendLine("EOF")
            appendLine("}")
            appendLine("run_shell() {")
            appendLine("  export PATH=\"\$PATH:\$APP_SYSTEM_APPS:\$APP_SYSTEM_PLUGINS\"")
            appendLine("  export LD_LIBRARY_PATH=\"\$LD_LIBRARY_PATH:\$APP_SYSTEM_LIBS\"")
            appendLine("  chmod +x \"\$APP_SYSTEM_APPS\"/* \"\$APP_SYSTEM_PLUGINS\"/* 2>/dev/null")
            appendLine("  if command -v app-system-remote-shell.sh >/dev/null 2>&1; then")
            appendLine("    exec app-system-remote-shell.sh")
            appendLine("  fi")
            appendLine("  exec /system/bin/sh")
            appendLine("}")
            appendLine("run_plugin() {")
            appendLine("  if [ -z \"\$1\" ]; then")
            appendLine("    printf \"Usage: run-plugin [name]\\n\"")
            appendLine("    return 1")
            appendLine("  fi")
            appendLine("  plugin_name=\"\$1\"")
            appendLine("  plugin_dir=\"\$APP_SYSTEM_PLUGINS/\$plugin_name\"")
            appendLine("  if [ ! -d \"\$plugin_dir\" ]; then")
            appendLine("    printf \"AppSystem Error: Plugin directory not found: %s\\n\" \"\$plugin_name\"")
            appendLine("    return 2")
            appendLine("  fi")
            appendLine("  loader_file=\"\$plugin_dir/plugin.yml\"")
            appendLine("  if [ -f \"\$loader_file\" ]; then")
            appendLine("    loader_path=$(grep -E '^loader:' \"\$loader_file\" | awk '{print $2}' | tr -d '\"')")
            appendLine("    if [ -n \"\$loader_path\" ]; then")
            appendLine("      loader_file=\"\$plugin_dir/\$loader_path\"")
            appendLine("    else")
            appendLine("      loader_file=\"\$plugin_dir/loader.yml\"")
            appendLine("    fi")
            appendLine("  else")
            appendLine("    loader_file=\"\$plugin_dir/loader.yml\"")
            appendLine("  fi")
            appendLine("  if [ ! -f \"\$loader_file\" ]; then")
            appendLine("    printf \"AppSystem Error: loader.yml not found for plugin %s\\n\" \"\$plugin_name\"")
            appendLine("    return 3")
            appendLine("  fi")
            appendLine("  exec_path=$(grep -E '^exec:' \"\$loader_file\" | awk '{for (i=2; i<=NF; i++) printf \"%s%s\", \$i, (i<NF?\" \":\"\\n\")}' | sed 's/^ *//;s/ *$//' | tr -d '\"')")
            appendLine("  if [ -z \"\$exec_path\" ]; then")
            appendLine("    printf \"AppSystem Error: exec is required in loader.yml\\n\"")
            appendLine("    return 4")
            appendLine("  fi")
            appendLine("  if [ \"\${exec_path#/}\" = \"\$exec_path\" ]; then")
            appendLine("    resolved_exec=\"\$plugin_dir/\$exec_path\"")
            appendLine("  else")
            appendLine("    resolved_exec=\"\$exec_path\"")
            appendLine("  fi")
            appendLine("  if [ -f \"\$resolved_exec\" ]; then")
            appendLine("    chmod +x \"\$resolved_exec\" 2>/dev/null")
            appendLine("  fi")
            appendLine("  args=()")
            appendLine("  while IFS= read -r line; do")
            appendLine("    if echo \"\$line\" | grep -qE '^args:'; then")
            appendLine("      continue")
            appendLine("    fi")
            appendLine("    if echo \"\$line\" | grep -qE '^- '; then")
            appendLine("      arg=$(echo \"\$line\" | sed -E 's/^- //')")
            appendLine("      if [ \"\${arg#/}\" = \"\$arg\" ]; then")
            appendLine("        arg=\"\$plugin_dir/\$arg\"")
            appendLine("      fi")
            appendLine("      args+=(\"\$arg\")")
            appendLine("    fi")
            appendLine("  done < <(grep -nE '^(args:|- )' \"\$loader_file\")")
            appendLine("  envs=()")
            appendLine("  while IFS= read -r line; do")
            appendLine("    if echo \"\$line\" | grep -qE '^[[:space:]]*[^ ]+: '; then")
            appendLine("      key=$(echo \"\$line\" | awk -F: '{print \$1}' | tr -d '[:space:]')")
            appendLine("      value=$(echo \"\$line\" | cut -d: -f2- | sed 's/^ *//;s/ *$//' | tr -d '\"')")
            appendLine("      envs+=(\"\$key=\$value\")")
            appendLine("    fi")
            appendLine("  done < <(grep -nE '^(env:|[[:space:]]+[^ ]+: )' \"\$loader_file\")")
            appendLine("  export PATH=\"\$PATH:\$APP_SYSTEM_APPS:\$APP_SYSTEM_PLUGINS\"")
            appendLine("  export LD_LIBRARY_PATH=\"\$LD_LIBRARY_PATH:\$APP_SYSTEM_LIBS\"")
            appendLine("  eval \"\${envs[*]}\"")
            appendLine("  if [ -n \"\$resolved_exec\" ] && [ -f \"\$resolved_exec\" ]; then")
            appendLine("    exec_cmd=\"\$resolved_exec\"")
            appendLine("  else")
            appendLine("    exec_cmd=\"\$exec_path\"")
            appendLine("  fi")
            appendLine("  printf \"Launching plugin %s...\\n\" \"\$plugin_name\"")
            appendLine("  \"\$exec_cmd\" \"\${args[@]}\"")
            appendLine("}")
            appendLine("while true; do")
            appendLine("  prompt")
            appendLine("  if ! IFS= read -r line; then")
            appendLine("    break")
            appendLine("  fi")
            appendLine("  case \"\$line\" in")
            appendLine("    list-plugins) list_plugins ;;")
            appendLine("    list-libs) list_libs ;;")
            appendLine("    help) help_text ;;")
            appendLine("    shell) run_shell ;;")
            appendLine("    run-plugin*")
            appendLine("      plugin_name=\${line#run-plugin }")
            appendLine("      run_plugin \"\$plugin_name\"")
            appendLine("      ;;")
            appendLine("    \"\") continue ;;")
            appendLine("    *) printf \"Unknown command: %s\\n\" \"\$line\" ;;")
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
