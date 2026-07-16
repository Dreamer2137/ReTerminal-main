package com.rk.terminal.appsystem

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class TerminalAppSystemActivity : AppCompatActivity() {
    private var process: Process? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuPermissionHandler.ensurePermission(this) { granted ->
            if (!granted) {
                runOnUiThread { onPermissionDenied() }
                return@ensurePermission
            }
            runOnUiThread { onPermissionGranted() }
        }
    }

    private fun onPermissionDenied() {
        Log.w("AppSystem", "Error: Shizuku permission required. Please authorize and restart.")
    }

    private fun onPermissionGranted() {
        startProcess()
    }

    private fun startProcess() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val proc = AppSystemManager.startAppSystemProcess(applicationContext)
                process = proc
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                while (true) {
                    val line = reader.readLine() ?: break
                    onProcessOutput(line + "\n")
                }
            } catch (e: Exception) {
                Log.e("AppSystem", "failed to start process", e)
            }
        }
    }

    fun sendToProcess(line: String) {
        try {
            val out = process?.outputStream ?: return
            out.write((line + "\n").toByteArray())
            out.flush()
        } catch (e: Exception) {
            Log.e("AppSystem", "failed to send", e)
        }
    }

    private fun onProcessOutput(text: String) {
        Log.d("AppSystem", text)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            process?.destroy()
        } catch (ignored: Exception) {
        }
    }
}
