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
        return """
            #!/system/bin/sh
            APP_SYSTEM_ROOT="${appRoot}"
            APP_SYSTEM_APPS="${appRoot}/apps"
            APP_SYSTEM_PLUGINS="${appRoot}/apps/plugins"
            APP_SYSTEM_LIBS="${appRoot}/libs"
            APP_SYSTEM_OUTPUT="${appRoot}/data/output"
            prompt() { printf "app-system> " ; }
            format_entry() { printf "  %s\n" "$$1" ; }
            list_plugins() {
              for file in "$$APP_SYSTEM_PLUGINS"/*; do
                [ -e "$$file" ] || continue
                if [ -d "$$file" ]; then
                  format_entry "$(basename "$$file")/"
                elif [ -f "$$file" ] && [ -x "$$file" ]; then
                  format_entry "$(basename "$$file")"
                elif [ -f "$$file" ] && [ "$${file##*.}" = "sh" ]; then
                  format_entry "$(basename "$$file")"
                fi
              done
            }
            list_libs() {
              for file in "$$APP_SYSTEM_LIBS"/*.so; do
                [ -e "$$file" ] || continue
                format_entry "$(basename "$$file")"
              done
            }
            help_text() {
              cat <<'EOF'
            Available commands:
              list-plugins   List executable plugins and scripts
              list-libs      List shared libraries in libs
              run-plugin     Execute a plugin by directory name
              help           Show this help
              shell          Launch Shizuku-backed Android shell with injected environment
            EOF
            }
            run_shell() {
              export PATH="$$PATH:$$APP_SYSTEM_APPS:$$APP_SYSTEM_PLUGINS"
              export LD_LIBRARY_PATH="$$LD_LIBRARY_PATH:$$APP_SYSTEM_LIBS"
              chmod +x "$$APP_SYSTEM_APPS"/* "$$APP_SYSTEM_PLUGINS"/* 2>/dev/null
              if command -v app-system-remote-shell.sh >/dev/null 2>&1; then
                exec app-system-remote-shell.sh
              fi
              exec /system/bin/sh
            }
            run_plugin() {
              if [ -z "$$1" ]; then
                printf "Usage: run-plugin [name]\n"
                return 1
              fi
              plugin_name="$$1"
              plugin_dir="$$APP_SYSTEM_PLUGINS/$$plugin_name"
              if [ ! -d "$$plugin_dir" ]; then
                printf "AppSystem Error: Plugin directory not found: %s\n" "$$plugin_name"
                return 2
              fi
              loader_file="$$plugin_dir/plugin.yml"
              if [ -f "$$loader_file" ]; then
                loader_path=$(grep -E '^loader:' "$$loader_file" | awk '{print $2}' | tr -d '"')
                if [ -n "$$loader_path" ]; then
                  loader_file="$$plugin_dir/$$loader_path"
                else
                  loader_file="$$plugin_dir/loader.yml"
                fi
              else
                loader_file="$$plugin_dir/loader.yml"
              fi
              if [ ! -f "$$loader_file" ]; then
                printf "AppSystem Error: loader.yml not found for plugin %s\n" "$$plugin_name"
                return 3
              fi
              exec_path=$(grep -E '^exec:' "$$loader_file" | awk '{for (i=2; i<=NF; i++) printf "%s%s", $$i, (i<NF?" ":"\n")}' | sed 's/^ *//;s/ *$//' | tr -d '"')
              if [ -z "$$exec_path" ]; then
                printf "AppSystem Error: exec is required in loader.yml\n"
                return 4
              fi
              if [ "$${exec_path#/}" = "$$exec_path" ]; then
                resolved_exec="$$plugin_dir/$$exec_path"
              else
                resolved_exec="$$exec_path"
              fi
              if [ -f "$$resolved_exec" ]; then
                chmod +x "$$resolved_exec" 2>/dev/null
              fi
              args=()
              while IFS= read -r line; do
                if echo "$$line" | grep -qE '^args:'; then
                  continue
                fi
                if echo "$$line" | grep -qE '^- '; then
                  arg=$(echo "$$line" | sed -E 's/^- //')
                  if [ "$${arg#/}" = "$$arg" ]; then
                    arg="$$plugin_dir/$$arg"
                  fi
                  args+=("$$arg")
                fi
              done < <(grep -nE '^(args:|- )' "$$loader_file")
              envs=()
              while IFS= read -r line; do
                if echo "$$line" | grep -qE '^[[:space:]]*[^ ]+: '; then
                  key=$(echo "$$line" | awk -F: '{print $$1}' | tr -d '[:space:]')
                  value=$(echo "$$line" | cut -d: -f2- | sed 's/^ *//;s/ *$//' | tr -d '"')
                  envs+=("$$key=$$value")
                fi
              done < <(grep -nE '^(env:|[[:space:]]+[^ ]+: )' "$$loader_file")
              export PATH="$$PATH:$$APP_SYSTEM_APPS:$$APP_SYSTEM_PLUGINS"
              export LD_LIBRARY_PATH="$$LD_LIBRARY_PATH:$$APP_SYSTEM_LIBS"
              eval "$${envs[*]}"
              if [ -n "$$resolved_exec" ] && [ -f "$$resolved_exec" ]; then
                exec_cmd="$$resolved_exec"
              else
                exec_cmd="$$exec_path"
              fi
              printf "Launching plugin %s...\n" "$$plugin_name"
              "$$exec_cmd" "${args[@]}"
            }
            while true; do
              prompt
              if ! IFS= read -r line; then
                break
              fi
              case "$$line" in
                list-plugins) list_plugins ;;
                list-libs) list_libs ;;
                help) help_text ;;
                shell) run_shell ;;
                run-plugin*)
                  plugin_name=$${line#run-plugin }
                  run_plugin "$$plugin_name"
                  ;;
                "") continue ;;
                *) printf "Unknown command: %s\n" "$$line" ;;
              esac
            done
        """.trimIndent()
    }

    private fun appSystemRemoteShell(context: Context): String {
        val appRoot = getAppRoot(context).absolutePath
        return """
            #!/system/bin/sh
            APP_SYSTEM_ROOT="${appRoot}"
            APP_SYSTEM_APPS="${appRoot}/apps"
            APP_SYSTEM_PLUGINS="${appRoot}/apps/plugins"
            APP_SYSTEM_LIBS="${appRoot}/libs"
            export PATH="$$PATH:$$APP_SYSTEM_APPS:$$APP_SYSTEM_PLUGINS"
            export LD_LIBRARY_PATH="$$LD_LIBRARY_PATH:$$APP_SYSTEM_LIBS"
            chmod +x "$$APP_SYSTEM_APPS"/* "$$APP_SYSTEM_PLUGINS"/* 2>/dev/null
            if command -v shizuku >/dev/null 2>&1; then
              exec shizuku sh -c "export PATH=\$$PATH:$$APP_SYSTEM_APPS:\$$APP_SYSTEM_PLUGINS; export LD_LIBRARY_PATH=\$$LD_LIBRARY_PATH:$$APP_SYSTEM_LIBS; exec /system/bin/sh"
            fi
            exec /system/bin/sh
        """.trimIndent()
    }
}
