package com.khmerdubber.app

import android.content.Context

class Settings(
    var voice: String = "female",      // "female" or "male"
    var ratePct: Int = 0,              // base speaking speed, -20..+50 %
    var maxSpeed: Float = 1.6f,        // max extra speed-up so a line fits its time
    var originalVolume: Int = 25,      // original audio, 0..100 %
    var duckOriginal: Boolean = true,  // lower original audio under the Khmer voice
    var translate: Boolean = true,     // translate to Khmer (skipped if already Khmer)
    var skipSongs: Boolean = true,     // don't speak ♪ lines
    var pitchHz: Int = 0,              // voice pitch, -20..+20 Hz
    var dubVolume: Int = 100,          // Khmer voice volume, 50..200 %
    var parallel: Int = 16,            // voice lines made at the same time
) {
    val rate: String get() = (if (ratePct >= 0) "+" else "") + "$ratePct%"
    val pitch: String get() = (if (pitchHz >= 0) "+" else "") + "${pitchHz}Hz"

    /** Back to defaults (keeps the chosen voice). */
    fun reset() {
        val d = Settings()
        ratePct = d.ratePct; maxSpeed = d.maxSpeed; originalVolume = d.originalVolume
        duckOriginal = d.duckOriginal; translate = d.translate; skipSongs = d.skipSongs
        pitchHz = d.pitchHz; dubVolume = d.dubVolume; parallel = d.parallel
    }

    fun voiceName(tag: String = ""): String =
        when (tag.ifEmpty { voice }) { "male" -> EdgeTts.MALE; else -> EdgeTts.FEMALE }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("voice", voice)
            .putInt("rate", ratePct)
            .putFloat("maxSpeed", maxSpeed)
            .putInt("origVol", originalVolume)
            .putBoolean("duck", duckOriginal)
            .putBoolean("translate", translate)
            .putBoolean("skipSongs", skipSongs)
            .putInt("pitch", pitchHz)
            .putInt("dubVol", dubVolume)
            .putInt("parallel", parallel)
            .apply()
    }

    companion object {
        private const val PREFS = "settings"

        fun load(ctx: Context): Settings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Settings()
            return Settings(
                voice = p.getString("voice", d.voice) ?: d.voice,
                ratePct = p.getInt("rate", d.ratePct),
                maxSpeed = p.getFloat("maxSpeed", d.maxSpeed),
                originalVolume = p.getInt("origVol", d.originalVolume),
                duckOriginal = p.getBoolean("duck", d.duckOriginal),
                translate = p.getBoolean("translate", d.translate),
                skipSongs = p.getBoolean("skipSongs", d.skipSongs),
                pitchHz = p.getInt("pitch", d.pitchHz),
                dubVolume = p.getInt("dubVol", d.dubVolume),
                parallel = p.getInt("parallel", d.parallel),
            )
        }
    }
}
