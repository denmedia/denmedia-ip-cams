package com.example.denmediaipcams

import android.app.Application
import com.example.denmediaipcams.network.RtspParamSets

class DenMediaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        RtspParamSets.attach(this)
    }
}
