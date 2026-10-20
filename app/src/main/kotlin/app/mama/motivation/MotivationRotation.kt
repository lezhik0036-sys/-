package app.mama.motivation

import android.content.Context

/**
 * Picks phrases without constant repetition: one phrase per place and
 * period (a series day, a lock, a failure) that stays the same while the
 * screen is redrawn; a new period gets a phrase that was not shown lately.
 */
class MotivationRotation(private val store: Store, private val historySize: Int = HISTORY) {

    /** Where the rotation is remembered. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    /**
     * The phrase for [placement] in the period [periodKey] (e.g. the series
     * id and day). Profile: always [ProfileType.GENERAL] in the pilot.
     */
    fun phrase(placement: Placement, periodKey: String, profile: ProfileType = ProfileType.GENERAL, maxLength: Int = Int.MAX_VALUE): Phrase {
        val slot = "slot.${placement.name}"
        store.get(slot)?.split('|', limit = 2)?.takeIf { it.size == 2 }?.let { (key, id) ->
            if (key == periodKey) {
                MotivationCatalog.byId(id)
                    ?.takeIf { it.fits(profile) && it.category in placement.categories && it.text.length <= maxLength }
                    ?.let { return it }
            }
        }
        val recent = store.get(RECENT)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        val phrase = MotivationCatalog.pick(placement, periodKey, profile, recent, maxLength)
        store.put(slot, "$periodKey|${phrase.id}")
        store.put(RECENT, (listOf(phrase.id) + recent.filter { it != phrase.id }).take(historySize).joinToString(","))
        return phrase
    }

    companion object {
        private const val RECENT = "recent"

        /** Phrases kept out of rotation after being shown. */
        const val HISTORY = 30

        /** Rotation kept in the app's own preferences. */
        fun of(context: Context): MotivationRotation {
            val prefs = context.getSharedPreferences("mama_motivation", Context.MODE_PRIVATE)
            return MotivationRotation(object : Store {
                override fun get(key: String): String? = prefs.getString(key, null)
                override fun put(key: String, value: String) {
                    prefs.edit().putString(key, value).apply()
                }
            })
        }
    }
}
