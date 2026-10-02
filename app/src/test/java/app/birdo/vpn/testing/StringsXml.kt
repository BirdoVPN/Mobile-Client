package app.birdo.vpn.testing

import app.birdo.vpn.R
import app.birdo.vpn.data.repository.StringLookup
import java.io.File

/**
 * The app's REAL English copy for JVM tests: `values/strings.xml`, read from
 * disk, looked up by the `R.string` / `R.plurals` id the code under test asks
 * for.
 *
 * A fake that returned the resource NAME would let a test pass while the user
 * saw something else; this one makes an assertion about wording an assertion
 * about what ships. Format arguments go through `String.format`, as
 * `Context.getString` does, and plurals pick `one` for 1 and `other` otherwise,
 * which is the whole of the English rule set.
 */
object StringsXml : StringLookup {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private val xml: String by lazy { File(repoRoot, "app/src/main/res/values/strings.xml").readText() }

    /** Android's escapes undone. */
    private fun unescape(raw: String): String = raw
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    /** name → text. */
    private val byName: Map<String, String> by lazy {
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m -> m.groupValues[1] to unescape(m.groupValues[2]) }
    }

    /** plurals name → (quantity → text). */
    private val pluralsByName: Map<String, Map<String, String>> by lazy {
        Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m ->
                m.groupValues[1] to Regex("""<item quantity="([a-z]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(m.groupValues[2])
                    .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            }
    }

    /** id → name, from the generated R classes. */
    private val nameById: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }
    private val pluralNameById: Map<Int, String> by lazy {
        R.plurals::class.java.fields.associate { it.getInt(null) to it.name }
    }

    override fun get(id: Int, vararg args: Any): String {
        val name = nameById[id] ?: error("no R.string field has id $id")
        val text = byName[name] ?: error("strings.xml has no <string name=\"$name\">")
        return if (args.isEmpty()) text else text.format(*args)
    }

    override fun plural(id: Int, count: Int, vararg args: Any): String {
        val name = pluralNameById[id] ?: error("no R.plurals field has id $id")
        val items = pluralsByName[name] ?: error("strings.xml has no <plurals name=\"$name\">")
        val text = items[if (count == 1) "one" else "other"] ?: error("$name has no quantity for $count")
        return text.format(*args)
    }

    /** The shipped text for a resource name, for assertions. */
    fun text(name: String): String = byName[name] ?: error("strings.xml has no <string name=\"$name\">")

    /** The shipped `<plurals>` text for [count], for assertions. */
    fun plural(name: String, count: Int): String {
        val items = pluralsByName[name] ?: error("strings.xml has no <plurals name=\"$name\">")
        return (items[if (count == 1) "one" else "other"] ?: error("$name has no quantity for $count")).format(count)
    }
}
