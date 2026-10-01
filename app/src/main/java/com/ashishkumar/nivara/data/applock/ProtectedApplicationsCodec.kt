package com.ashishkumar.nivara.data.applock

import com.ashishkumar.nivara.domain.applock.ProtectedApplication

/** Deterministic newline encoding is safe because package identifiers cannot contain line breaks. */
internal object ProtectedApplicationsCodec {
    fun encode(applications: Collection<ProtectedApplication>): String =
        applications.map(ProtectedApplication::packageName).distinct().sorted().joinToString("\n")

    /** Returns null for malformed data; callers must treat it as unavailable, never as an empty set. */
    fun decode(encoded: String): Set<ProtectedApplication>? {
        if (encoded.isEmpty()) return emptySet()
        val packageNames = encoded.split('\n')
        if (packageNames.any(String::isBlank)) return null
        return try {
            packageNames.map(::ProtectedApplication).toCollection(LinkedHashSet())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
