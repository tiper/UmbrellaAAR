package io.github.tiper.sample.aidl1

import android.app.Service
import android.content.Intent
import android.os.IBinder

class MyAidlService : Service() {
    private val binder = object : IMyAidlService.Stub() {
        override fun getMessage(): String {
            return "Hello from AIDL Service 1!"
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}
