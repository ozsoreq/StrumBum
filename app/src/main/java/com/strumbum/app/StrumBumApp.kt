package com.strumbum.app

import android.app.Application
import com.strumbum.app.audio.PythonRuntime

class StrumBumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Start Python + NumPy on a background thread now, so the engine is ready by the time the
        // tuner screen asks for it (spec: first reading within 1.5 s of a cold start).
        PythonRuntime.warmUp(this)
    }
}
