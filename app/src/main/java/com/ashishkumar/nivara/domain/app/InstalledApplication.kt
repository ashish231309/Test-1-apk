package com.ashishkumar.nivara.domain.app

import java.util.Locale

/** A launcher-visible app; packageName is its stable identity, not the presentation label. */
class InstalledApplication(
    packageName: String,
    label: String?,
    val isLaunchable: Boolean,
) {
    val packageName: String = packageName.also {
        require(it.isNotBlank() && it == it.trim()) { "Package name must be non-blank and normalized." }
    }
    val label: String = label?.trim()?.takeIf(String::isNotEmpty) ?: this.packageName

    override fun equals(other: Any?): Boolean =
        other is InstalledApplication && packageName == other.packageName

    override fun hashCode(): Int = packageName.hashCode()

    override fun toString(): String = "InstalledApplication(packageName=$packageName, label=$label, isLaunchable=$isLaunchable)"
}

sealed interface ApplicationDiscoveryResult {
    data class Available(val applications: List<InstalledApplication>) : ApplicationDiscoveryResult
    data object Unavailable : ApplicationDiscoveryResult
}

interface ApplicationRepository {
    suspend fun discoverLaunchableApplications(): ApplicationDiscoveryResult
}

/** Predictable Android-free name orderings shared by discovery and App Lock management. */
object InstalledApplicationOrdering {
    fun deterministic(applications: Iterable<InstalledApplication>): List<InstalledApplication> =
        applications.sortedWith(
            compareBy<InstalledApplication> { it.label.lowercase(Locale.ROOT) }
                .thenBy { it.packageName.lowercase(Locale.ROOT) }
                .thenBy { it.packageName },
        )

    /** Z–A by label; equal labels keep a stable ascending package-name tie break. */
    fun reverseAlphabetical(applications: Iterable<InstalledApplication>): List<InstalledApplication> =
        applications.sortedWith(
            compareByDescending<InstalledApplication> { it.label.lowercase(Locale.ROOT) }
                .thenBy { it.packageName.lowercase(Locale.ROOT) }
                .thenBy { it.packageName },
        )
}

/** Android-free case-insensitive label and package matching used by App Lock management. */
object InstalledApplicationSearch {
    fun matches(application: InstalledApplication, query: String): Boolean {
        val normalized = query.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return true
        return application.label.lowercase(Locale.ROOT).contains(normalized) ||
            application.packageName.lowercase(Locale.ROOT).contains(normalized)
    }
}
