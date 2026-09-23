package com.strumbum.app.audio

import android.content.Context
import com.chaquo.python.Kwarg
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlin.concurrent.thread

/** Owns the embedded Python runtime. Starting it imports NumPy, so do it off the main thread. */
object PythonRuntime {
    private const val ENGINE_MODULE = "strumbum_engine.engine"

    @Synchronized
    fun module(context: Context): PyObject {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance().getModule(ENGINE_MODULE)
    }

    /** Start Python and import the engine in the background, so the tuner is ready sooner. */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        thread(name = "strumbum-python-warmup", isDaemon = true) { runCatching { module(app) } }
    }
}

/** Thin Kotlin face of the Python `Engine`. Not thread-safe: use it from the audio thread only. */
class PitchEngine private constructor(private val engine: PyObject) {

    fun feed(samples: FloatArray): PitchReading {
        val t = engine.callAttr("feed", samples).asList()
        return PitchReading(
            hasPitch = t[HAS_PITCH].toDouble() > 0.5,
            hz = t[SMOOTHED_HZ].toDouble(),
            rawHz = t[RAW_HZ].toDouble(),
            clarity = t[CLARITY].toDouble(),
            rmsDb = t[RMS_DB].toDouble(),
            silentFrames = t[SILENT_FRAMES].toDouble().toInt(),
        )
    }

    fun reset() {
        engine.callAttr("reset")
    }

    companion object {
        const val WINDOW = 2048
        const val HOP = 512

        // Indices into the tuple returned by Engine.feed (see engine.py).
        private const val HAS_PITCH = 0
        private const val SMOOTHED_HZ = 1
        private const val RAW_HZ = 2
        private const val CLARITY = 3
        private const val RMS_DB = 4
        private const val SILENT_FRAMES = 5

        fun create(context: Context, sampleRate: Int): PitchEngine {
            val module = PythonRuntime.module(context)
            return PitchEngine(
                module.callAttr(
                    "Engine",
                    Kwarg("sample_rate", sampleRate),
                    Kwarg("window", WINDOW),
                    Kwarg("hop", HOP),
                ),
            )
        }
    }
}
