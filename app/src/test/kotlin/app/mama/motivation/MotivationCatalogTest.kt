package app.mama.motivation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MotivationCatalog: size, categories, tone, rotation without repeats (DESIGN_SYSTEM_V1 §13). */
class MotivationCatalogTest {

    @Test
    fun `at least 100 phrases and at least 10 in every approved category`() {
        assertTrue("only ${MotivationCatalog.phrases.size} phrases", MotivationCatalog.phrases.size >= 100)
        for (c in MotivationCategory.entries) {
            val n = MotivationCatalog.byCategory(c).size
            assertTrue("$c has only $n phrases", n >= 10)
        }
    }

    @Test
    fun `ids and texts are unique and never empty`() {
        val ids = MotivationCatalog.phrases.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val texts = MotivationCatalog.phrases.map { it.text.lowercase() }
        assertEquals("duplicate phrase", texts.size, texts.toSet().size)
        assertTrue(MotivationCatalog.phrases.all { it.text.isNotBlank() && it.text.length <= 90 })
    }

    @Test
    fun `the product owner's examples are in the catalog`() {
        val texts = MotivationCatalog.phrases.map { it.text }
        listOf(
            "Ты уже принял решение. Теперь просто останься ему верен.",
            "Желание проверить телефон пройдёт. Твоё решение останется.",
            "Начать снова — тоже форма силы.",
            "Тебе не нужно быть идеальным. Тебе нужно продолжать.",
            "Ты не отказываешься от телефона. Ты возвращаешь себе время.",
            "Срыв — это часть пути.",
            "Одна попытка ничего не решает.",
            "Ты можешь начать снова.",
            "Этот путь не закончился.",
            "Сегодня получилось не всё. Но решение всё ещё твоё.",
        ).forEach { assertTrue("missing: $it", it in texts) }
    }

    @Test
    fun `no toxic words, no money, no promise of a return`() {
        val banned = listOf(
            "провал", "неудач", "проиграл", "слабак", "позор", "стыд", "ты должен", "лузер", "никчём",
            "₽", "%", "возврат", "вернёт", "деньг", "штраф",
        )
        for (p in MotivationCatalog.phrases) {
            val t = p.text.lowercase()
            banned.forEach { assertFalse("«${p.text}» contains «$it»", it in t) }
        }
    }

    @Test
    fun `recovery shows only recovery and second-chance phrases`() {
        val c = MotivationCatalog.candidates(Placement.RECOVERY)
        assertTrue(c.isNotEmpty())
        assertTrue(c.all { it.category == MotivationCategory.RECOVERY || it.category == MotivationCategory.SECOND_CHANCE })
    }

    @Test
    fun `every placement has enough phrases to rotate`() {
        for (p in Placement.entries) assertTrue("$p", MotivationCatalog.candidates(p).size >= 10)
    }

    @Test
    fun `the same key gives the same phrase, recent phrases are avoided`() {
        val a = MotivationCatalog.pick(Placement.LOCK_NIGHT, "s1:3")
        assertEquals(a, MotivationCatalog.pick(Placement.LOCK_NIGHT, "s1:3"))
        val b = MotivationCatalog.pick(Placement.LOCK_NIGHT, "s1:3", recent = listOf(a.id))
        assertNotEquals(a, b)
    }

    @Test
    fun `pilot profile is general and future profiles fall back to general phrases`() {
        for (profile in ProfileType.entries) {
            val c = MotivationCatalog.candidates(Placement.ACTIVE, profile)
            assertTrue("$profile", c.size >= 10)
        }
    }

    private class MemoryStore : MotivationRotation.Store {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun `rotation keeps a phrase for its period and does not repeat it in the next ones`() {
        val rotation = MotivationRotation(MemoryStore())
        val first = rotation.phrase(Placement.ACTIVE, "series-1:day-1")
        assertEquals("redraws keep the phrase", first, rotation.phrase(Placement.ACTIVE, "series-1:day-1"))
        val shown = mutableListOf(first)
        for (day in 2..10) {
            val p = rotation.phrase(Placement.ACTIVE, "series-1:day-$day")
            assertFalse("day $day repeats «${p.text}»", p in shown)
            shown += p
        }
    }

    @Test
    fun `rotation survives a damaged stored value`() {
        val store = MemoryStore().apply { map["slot.LOCK_DAY"] = "garbage"; map["recent"] = ",,," }
        val p = MotivationRotation(store).phrase(Placement.LOCK_DAY, "x")
        assertTrue(p.category in Placement.LOCK_DAY.categories)
    }
}
