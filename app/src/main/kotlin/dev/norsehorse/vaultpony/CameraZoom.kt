package dev.norsehorse.vaultpony

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Zoom steps and labels for the in-app camera (2.1.0 plan 1). */
object CameraZoom {
    // Offered above 1x when the camera reaches them.
    private val STOPS = floatArrayOf(2f, 5f, 10f)

    /**
     * The steps to offer for a camera whose zoom runs from [min] to [max]:
     * the widest ratio when it is below 1x (an ultrawide lens the phone
     * exposes through the same camera), 1x, then 2x, 5x and 10x as far as
     * [max] goes. Fewer than two steps means there is nothing to choose.
     */
    fun steps(min: Float, max: Float): List<Float> {
        if (!(min > 0f) || !(max >= min)) return emptyList()
        val out = ArrayList<Float>()
        if (min < 0.95f) out += min
        if (max >= 1f) out += maxOf(min, 1f)
        for (s in STOPS) {
            if (s <= max + 0.001f && s > (out.lastOrNull() ?: 0f)) out += s
        }
        return out
    }

    /** [ratio] to one decimal place, in [locale]'s digits: "0.5", "1", "2.3". */
    fun number(ratio: Float, locale: Locale): String {
        val tenths = Math.round(ratio * 10f) / 10.0
        return DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale)).format(tenths)
    }
}
