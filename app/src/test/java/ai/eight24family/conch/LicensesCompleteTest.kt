package ai.eight24family.conch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every third-party library in the build has to appear on the licenses screen.
 *
 * It is a hand-written list, which means it silently goes stale the moment
 * someone adds a dependency — and it did: two libraries were added on
 * 2026-08-29 and the screen still claimed the old set until the owner asked
 * whether it was complete. A hand-written list is fine; a hand-written list
 * with nothing checking it is a promise nobody is keeping.
 *
 * This reads the version catalogue and the screen's source, so it fails at
 * build time rather than in an app store review.
 */
class LicensesCompleteTest {

    /** Tests run from the module directory; the catalogue is one level up. */
    private fun repoFile(path: String): File {
        val here = File(".").absoluteFile
        var dir: File? = here
        repeat(4) {
            val candidate = File(dir, path)
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError("cannot find $path from ${here.absolutePath}")
    }

    /**
     * Artifacts that are NOT separately listed on purpose, with the reason.
     * Anything else new must be added to the screen, not to this set.
     */
    private val listedUnderAParent = mapOf(
        // Kotlin, kotlinx and the whole AndroidX/Compose family are listed as
        // two grouped entries rather than a hundred lines of artifact ids.
        "kotlinx" to "grouped as \"Kotlin & kotlinx\"",
        "androidx" to "grouped as \"AndroidX & Jetpack Compose\"",
        "compose" to "grouped as \"AndroidX & Jetpack Compose\"",
        "material3" to "grouped as \"AndroidX & Jetpack Compose\"",
    )

    /**
     * Maven GROUPS covered by a grouped entry on the screen.
     *
     * ⚠ Matching on the artifact NAME alone is not enough, and said so out loud:
     * `ui-graphics`, `ui-tooling-preview` and the four `camera-*` artifacts are
     * every bit as much AndroidX as `androidx-core-ktx`, but their names never
     * say the word, so the name check reported six libraries as unlisted while
     * the screen's grouped AndroidX entry covered all of them. A false alarm in
     * this test is not harmless: the next person to see it red learns to ignore
     * it, and then it stops catching the real omission it exists for.
     */
    private val groupsCoveredByAGroupedEntry = listOf(
        "androidx.",
        "org.jetbrains.kotlin",
        "org.jetbrains.kotlinx",
    )

    @Test
    fun `every library that SHIPS reaches the licenses screen`() {
        val catalogue = repoFile("gradle/libs.versions.toml").readText()
        val build = repoFile("app/build.gradle.kts").readText()
        val screen = repoFile("app/src/main/java/ai/eight24family/conch/ui/screens/LicensesScreen.kt")
            .readText()
            .lowercase()

        // Only what actually lands in the APK: `implementation(libs.x.y)`.
        // Test and debug-only configurations are excluded on purpose — a user
        // never receives those, so listing them would be noise, not honesty.
        val shippedAliases = Regex("""(?<!\w)implementation\(libs\.([a-zA-Z0-9.]+)\)""")
            .findAll(build)
            .map { it.groupValues[1].replace('.', '-') }
            .toSet()

        // alias → (group, artifact id) as the catalogue declares them. The GROUP
        // is what says which family a library belongs to; the artifact id often
        // does not.
        val aliasToCoordinates = Regex(
            """(?m)^\s*([a-zA-Z0-9-]+)\s*=\s*\{[^}]*group\s*=\s*"([a-zA-Z0-9._-]+)"[^}]*name\s*=\s*"([a-zA-Z0-9._-]+)"""",
        ).findAll(catalogue).associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }

        val shipped = shippedAliases.mapNotNull { aliasToCoordinates[it] }.toSet()
        assertTrue("could not read any shipped library from the build file", shipped.isNotEmpty())

        val missing = shipped.filter { (group, artifact) ->
            val g = group.lowercase()
            if (groupsCoveredByAGroupedEntry.any { g.startsWith(it) }) return@filter false
            val a = artifact.lowercase()
            if (listedUnderAParent.keys.any { a.contains(it) }) return@filter false
            // A library counts as listed if its id, or the distinctive head of
            // it, is named anywhere on the screen.
            val head = a.substringBefore('-').substringBefore('.')
            !screen.contains(a) && !(head.length >= 4 && screen.contains(head))
        }

        assertTrue(
            "these libraries ship in the app but are not on the licenses screen: " +
                missing.joinToString { (group, artifact) -> "$group:$artifact" },
            missing.isEmpty(),
        )
    }

    @Test
    fun `every license the screen offers has its full text bundled`() {
        val screen = repoFile("app/src/main/java/ai/eight24family/conch/ui/screens/LicensesScreen.kt").readText()
        val assets = repoFile("app/src/main/assets/licenses")
        val referenced = Regex(""""([A-Za-z0-9._+-]+\.txt)"""").findAll(screen).map { it.groupValues[1] }.toSet()
        assertTrue("the screen names no license files at all", referenced.isNotEmpty())
        val absent = referenced.filterNot { File(assets, it).exists() }
        assertTrue("license text missing from assets: $absent", absent.isEmpty())
    }
}
