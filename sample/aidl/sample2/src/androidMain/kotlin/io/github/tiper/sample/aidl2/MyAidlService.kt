package io.github.tiper.sample.aidl2

import android.app.Service
import android.content.Intent
import android.os.IBinder

class MyAidlService : Service() {
    private val binder = object : IMyAidlService.Stub() {
        override fun getMessage(): String = "Hello from AIDL Service 2!"
    }

    override fun onBind(intent: Intent): IBinder = binder
}
