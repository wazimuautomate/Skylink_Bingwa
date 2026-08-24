package com.example.core.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * CLAUDE.md §8 / §7 language guard for the whole personalization feature.
 *
 * Skylink Bingwa knows what this customer bought from Skylink Bingwa and nothing else. It
 * must never imply it knows their data usage, their needs or their browsing, and
 * it must never claim a bundle was delivered. This test fails the build if a
 * banned phrase ever appears in a personalization label or anywhere in the
 * `core/personalization` sources.
 */
class PersonalizationLanguageTest {

    private val bannedPhrases = listOf(
        "you are running out of data",
        "recommended for your usage",
        "based on your browsing",
        "you need more data"
    )

    /** Words that would turn a purchase fact into a claim the app cannot make. */
    private val bannedWordsInLabels = listOf(
        "recommend",
        "delivered",
        "activated",
        "confirmed",
        "browsing",
        "usage"
    )

    @Test
    fun `personal badges use only the approved familiar wording`() {
        assertEquals(
            listOf("Buy again", "Your usual bundle", "Bought yesterday", "Favourite"),
            PersonalBadge.values().map { it.label }
        )
    }

    @Test
    fun `no personal badge label contains a banned phrase or claim`() {
        PersonalBadge.values().forEach { badge ->
            val label = badge.label.lowercase()
            bannedPhrases.forEach { phrase ->
                assertFalse("${badge.name} label contains \"$phrase\"", label.contains(phrase))
            }
            bannedWordsInLabels.forEach { word ->
                assertFalse("${badge.name} label contains \"$word\"", label.contains(word))
            }
        }
    }

    @Test
    fun `no personalization source file contains a banned phrase`() {
        val sourceDir = File("src/main/java/com/example/core/personalization")
        // Guarded: if the unit test working directory is not the module root the
        // scan is skipped rather than failing for an unrelated reason. The label
        // assertions above still hold in every environment.
        if (!sourceDir.isDirectory) return

        val kotlinFiles = sourceDir.listFiles().orEmpty().filter { it.extension == "kt" }
        assertFalse("no personalization sources were found to scan", kotlinFiles.isEmpty())

        kotlinFiles.forEach { file ->
            val text = file.readText().lowercase()
            bannedPhrases.forEach { phrase ->
                assertFalse("${file.name} contains \"$phrase\"", text.contains(phrase))
            }
        }
    }

    @Test
    fun `habit nudges are decisions about buying habit only`() {
        assertEquals(
            listOf("NONE", "USUAL_TIME_PASSED", "INACTIVE_SEVERAL_DAYS"),
            HabitNudge.values().map { it.name }
        )
    }
}
