package app.birdo.vpn.testing

import app.birdo.vpn.R
import app.birdo.vpn.data.repository.StringLookup
import java.io.File

/**
 * The app's REAL English copy for JVM tests: `values/strings.xml`, read from
 * disk, looked up by the `R.string` id the code under test asks for.
 *
 * A fake that returned the resource NAME would let a test pass while the user
 * saw something else; this one makes an assertion about wording an assertion
 * about what ships. Only plain strings are supported (no plurals, no format
 * arguments), which is all the error mapper uses.
 */
object StringsXml : StringLookup {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    /** name → text, with Android's escapes undone. */
    private val byName: Map<String, String> by lazy {
        val xml = File(repoRoot, "app/src/main/res/values/strings.xml").readText()
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m ->
                m.groupValues[1] to m.groupValues[2]
                    .replace("\\'", "'")
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
            }
    }

    /** id → name, from the generated R class. */
    private val nameById: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    override fun get(id: Int): String {
        val name = nameById[id] ?: error("no R.string field has id $id")
        return byName[name] ?: error("strings.xml has no <string name=\"$name\">")
    }

    /** The shipped text for a resource name, for assertions. */
    fun text(name: String): String = byName[name] ?: error("strings.xml has no <string name=\"$name\">")
}
