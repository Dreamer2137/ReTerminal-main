package com.rk.terminal.appsystem

import android.app.Activity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dev.rikka.shizuku.Shizuku

class ShizukuPermissionViewModel : ViewModel() {
    private val _granted = MutableLiveData<Boolean>()
    val granted: LiveData<Boolean> = _granted

    fun setGranted(v: Boolean) {
        _granted.postValue(v)
    }
}

object ShizukuPermissionHandler {
    private const val REQUEST_CODE = 0x4A7
    private var listener: Shizuku.OnRequestPermissionResultListener? = null

    fun ensurePermission(activity: Activity, callback: (Boolean) -> Unit) {
        if (Shizuku.isPreV11()) {
            callback(true)
            return
        }
        if (Shizuku.checkSelfPermission() == Shizuku.PERMISSION_GRANTED) {
            callback(true)
            return
        }
        listener = Shizuku.OnRequestPermissionResultListener { req, granted ->
            if (req == REQUEST_CODE) {
                callback(granted)
                Shizuku.removeOnRequestPermissionResultListener(listener)
                listener = null
            }
        }
        Shizuku.addOnRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }
}
