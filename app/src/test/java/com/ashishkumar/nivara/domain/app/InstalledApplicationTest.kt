package com.ashishkumar.nivara.domain.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledApplicationTest {
    @Test
    fun packageNameIsStableIdentityAndLabelsDoNotChangeEquality() {
        val first = InstalledApplication("com.example.reader", "Reader", isLaunchable = true)
        val relabeled = InstalledApplication("com.example.reader", "Books", isLaunchable = true)
        val otherPackage = InstalledApplication("com.example.notes", "Reader", isLaunchable = true)

        assertEquals(first, relabeled)
        assertEquals(first.hashCode(), relabeled.hashCode())
        assertNotEquals(first, otherPackage)
    }

    @Test
    fun blankLabelsFallBackToPackageName() {
        assertEquals("com.example.reader", InstalledApplication("com.example.reader", "  ", true).label)
        assertEquals("com.example.reader", InstalledApplication("com.example.reader", null, true).label)
    }

    @Test
    fun packageNameMustBeNonBlankAndTrimmed() {
        assertTrue(runCatching { InstalledApplication("com.example.reader", "Reader", true) }.isSuccess)
        assertFalse(runCatching { InstalledApplication("", "Reader", true) }.isSuccess)
        assertFalse(runCatching { InstalledApplication(" com.example.reader ", "Reader", true) }.isSuccess)
    }
}

class InstalledApplicationOrderingTest {
    @Test
    fun orderingIsCaseInsensitiveByLabelThenStableByPackageName() {
        val apps = listOf(
            InstalledApplication("com.zeta", "beta", true),
            InstalledApplication("com.beta", "ALPHA", true),
            InstalledApplication("com.alpha", "alpha", true),
        )

        assertEquals(
            listOf("com.alpha", "com.beta", "com.zeta"),
            InstalledApplicationOrdering.deterministic(apps).map(InstalledApplication::packageName),
        )
    }

    @Test
    fun packageTieBreakIsCaseInsensitiveAndThenFullyDeterministic() {
        val apps = listOf(
            InstalledApplication("com.zeta", "Tools", true),
            InstalledApplication("com.alpha", "tools", true),
            InstalledApplication("com.Alpha", "TOOLS", true),
        )

        assertEquals(
            listOf("com.Alpha", "com.alpha", "com.zeta"),
            InstalledApplicationOrdering.deterministic(apps).map(InstalledApplication::packageName),
        )
    }

    @Test
    fun orderingProducesTheSameSequenceForTheSameSet() {
        val apps = listOf(
            InstalledApplication("com.two", "Tools", true),
            InstalledApplication("com.one", "tools", true),
        )

        val first = InstalledApplicationOrdering.deterministic(apps)
        val second = InstalledApplicationOrdering.deterministic(apps.reversed())

        assertEquals(first.map(InstalledApplication::packageName), second.map(InstalledApplication::packageName))
    }

    @Test
    fun reverseAlphabeticalIsDeterministicAndKeepsPackageTieBreakAscending() {
        val apps = listOf(
            InstalledApplication("com.zeta", "Alpha", true),
            InstalledApplication("com.beta", "Beta", true),
            InstalledApplication("com.alpha", "ALPHA", true),
        )

        assertEquals(
            listOf("com.beta", "com.alpha", "com.zeta"),
            InstalledApplicationOrdering.reverseAlphabetical(apps).map(InstalledApplication::packageName),
        )
        assertEquals(
            InstalledApplicationOrdering.reverseAlphabetical(apps).map(InstalledApplication::packageName),
            InstalledApplicationOrdering.reverseAlphabetical(apps.reversed()).map(InstalledApplication::packageName),
        )
        assertEquals(listOf("com.zeta", "com.beta", "com.alpha"), apps.map(InstalledApplication::packageName))
    }
}

class InstalledApplicationSearchTest {
    private val application = InstalledApplication("com.example.photoeditor", "Photo Editor", true)

    @Test
    fun searchMatchesLabelCaseInsensitivelyAndTrimsQuery() {
        assertTrue(InstalledApplicationSearch.matches(application, "  PHOTO "))
    }

    @Test
    fun searchMatchesStablePackageIdentity() {
        assertTrue(InstalledApplicationSearch.matches(application, "EXAMPLE.PHOTO"))
    }

    @Test
    fun blankSearchMatchesEveryApplication() {
        assertTrue(InstalledApplicationSearch.matches(application, "  "))
    }

    @Test
    fun unmatchedQueryIsRejected() {
        assertFalse(InstalledApplicationSearch.matches(application, "calendar"))
    }

    @Test
    fun multipleMatchesCanBeFilteredInDeterministicOrderAndBlankQueryKeepsAll() {
        val applications = listOf(
            InstalledApplication("com.example.photoz", "Photo Z", true),
            InstalledApplication("com.example.photoa", "Photo A", true),
            InstalledApplication("com.example.calendar", "Calendar", true),
        )
        val ordered = InstalledApplicationOrdering.deterministic(applications)
        val matches = ordered.filter { InstalledApplicationSearch.matches(it, "photo") }

        assertEquals(listOf("com.example.photoa", "com.example.photoz"), matches.map(InstalledApplication::packageName))
        assertEquals(ordered, ordered.filter { InstalledApplicationSearch.matches(it, "  ") })
        assertTrue(matches.all { it.isLaunchable })
    }
}
