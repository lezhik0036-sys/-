package app.mama.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import app.mama.family.MamaFamily
import app.mama.platform.Mama
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Family screen is a thin, fail-closed face over the server-authoritative
 * [app.mama.family.FamilyClient]. These tests prove the two guarantees that matter:
 *
 *  - With NO configured server (the default field build until an HTTPS backend is
 *    supplied), the screen shows an honest "unavailable" state and offers NO pairing
 *    action at all — there is no local pairing path and no fabricated link.
 *  - With a configured server, both the parent (link a child) and child (create a
 *    code) entry points are present.
 *
 * Opening the screen is pure UI: it never starts a lock, a series or any privilege.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyScreenTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After fun tearDown() = MamaFamily.configure(context, "")

    private fun content(a: MainActivity) = a.findViewById<ViewGroup>(android.R.id.content)!!

    private fun tag(v: View, t: String): View? =
        if (v.tag == t) v else if (v is ViewGroup) (0 until v.childCount).firstNotNullOfOrNull { tag(v.getChildAt(it), t) } else null

    @Test
    fun `without a configured server the family screen is fail-closed with no pairing action`() {
        MamaFamily.configure(context, "") // unconfigured: no real backend
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.openFamily()

        val root = content(a)
        assertNotNull("the family screen is shown", tag(root, FAMILY_SCREEN))
        assertNotNull("it says, honestly, that family is unavailable", tag(root, FAMILY_UNAVAILABLE))
        assertNull("no local parent-link action exists without a server", tag(root, FAMILY_PARENT_LINK))
        assertNull("no local child-code action exists without a server", tag(root, FAMILY_CHILD_CREATE))
    }

    @Test
    fun `with a configured server both parent and child pairing entry points appear`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        MamaFamily.configure(context, "https://api.mama-app.ru")
        a.openFamily()

        val root = content(a)
        assertNotNull("the family screen is shown", tag(root, FAMILY_SCREEN))
        assertNull("no unavailable card when configured", tag(root, FAMILY_UNAVAILABLE))
        assertNotNull("parent can link a child device", tag(root, FAMILY_PARENT_LINK))
        assertNotNull("child can create a pairing code", tag(root, FAMILY_CHILD_CREATE))
    }

    @Test
    fun `the welcome screen offers a quiet Family entry, and Back returns to it`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val entry = content(a).findViewWithTag<View>(FAMILY_ENTRY)
        assertNotNull("a quiet «Семья» entry on the welcome screen", entry)

        entry.performClick()
        assertNotNull("tapping it opens the family screen", tag(content(a), FAMILY_SCREEN))

        a.onBackPressed()
        assertNull("the family screen is closed", tag(content(a), FAMILY_SCREEN))
        assertNotNull("back on the welcome, the entry is there again", content(a).findViewWithTag<View>(FAMILY_ENTRY))
    }

    @Test
    fun `opening family grants no local privilege - no lock or series is started`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull("no series before", Mama.series(context))
        a.openFamily()
        assertNotNull(tag(content(a), FAMILY_SCREEN))
        assertNull("opening family never starts a series", Mama.series(context))
        assertTrue("opening family never starts a lock session", Mama.state(context) == app.mama.core.LockState.Free)
    }
}
