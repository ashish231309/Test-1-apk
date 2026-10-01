package com.ashishkumar.nivara.data.applock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.ashishkumar.nivara.domain.applock.ForegroundApplicationDetector
import com.ashishkumar.nivara.domain.applock.ForegroundDetectionResult
import com.ashishkumar.nivara.domain.applock.ForegroundEventKind
import com.ashishkumar.nivara.domain.applock.ForegroundEventReducer
import com.ashishkumar.nivara.domain.applock.ForegroundUnavailableReason
import com.ashishkumar.nivara.domain.applock.ForegroundUsageEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashSet

/** Incrementally folds UsageEvents; it never retains a usage history or writes events to disk. */
class AndroidForegroundApplicationDetector(context: Context) : ForegroundApplicationDetector {
    private val applicationContext = context.applicationContext
    private val stateLock = Any()
    private val eventReducer = ForegroundEventReducer()
    private val seenEventKeys = LinkedHashSet<EventKey>()
    private var lastSuccessfulQueryAtMillis = 0L
    private var generation = 0L

    override suspend fun detect(): ForegroundDetectionResult = withContext(Dispatchers.IO) {
        val manager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext platformUnavailable()
        val now = System.currentTimeMillis()
        val plan = synchronized(stateLock) {
            if (lastSuccessfulQueryAtMillis > now) resetLocked()
            val begin = if (lastSuccessfulQueryAtMillis == 0L) {
                (now - STARTUP_LOOKBACK_MILLIS).coerceAtLeast(0)
            } else {
                (lastSuccessfulQueryAtMillis - QUERY_OVERLAP_MILLIS).coerceAtLeast(0)
            }
            QueryPlan(generation, begin)
        }

        try {
            @Suppress("DEPRECATION")
            val events = manager.queryEvents(plan.beginTimeMillis, now)
                ?: return@withContext platformUnavailable()
            val batch = ArrayList<Pair<EventKey, ForegroundUsageEvent>>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val kind = event.foregroundEventKind() ?: continue
                val packageName = event.packageName
                batch += EventKey(
                    timestampMillis = event.timeStamp,
                    eventType = event.eventType,
                    packageName = packageName,
                    className = event.className,
                ) to ForegroundUsageEvent(
                    timestampMillis = event.timeStamp,
                    packageName = packageName,
                    kind = kind,
                )
            }
            synchronized(stateLock) {
                if (plan.generation != generation) return@synchronized platformUnavailable()
                val freshEvents = batch.filter { seenEventKeys.add(it.first) }.map { it.second }
                while (seenEventKeys.size > MAX_RETAINED_EVENT_KEYS) {
                    seenEventKeys.remove(seenEventKeys.first())
                }
                lastSuccessfulQueryAtMillis = now
                eventReducer.reduce(freshEvents)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: SecurityException) {
            platformUnavailable()
        } catch (_: RuntimeException) {
            platformUnavailable()
        }
    }

    override fun reset() {
        synchronized(stateLock) { resetLocked() }
    }

    @Suppress("DEPRECATION")
    private fun UsageEvents.Event.foregroundEventKind(): ForegroundEventKind? = when (eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED,
        UsageEvents.Event.MOVE_TO_FOREGROUND -> ForegroundEventKind.ACTIVITY_RESUMED

        UsageEvents.Event.ACTIVITY_PAUSED,
        UsageEvents.Event.ACTIVITY_STOPPED,
        UsageEvents.Event.MOVE_TO_BACKGROUND -> ForegroundEventKind.ACTIVITY_PAUSED

        UsageEvents.Event.SCREEN_NON_INTERACTIVE,
        UsageEvents.Event.KEYGUARD_SHOWN -> ForegroundEventKind.DEVICE_LOCKED_OR_NON_INTERACTIVE
        else -> null
    }

    private fun resetLocked() {
        generation = if (generation == Long.MAX_VALUE) 0L else generation + 1L
        lastSuccessfulQueryAtMillis = 0L
        seenEventKeys.clear()
        eventReducer.reset()
    }

    private fun platformUnavailable() = ForegroundDetectionResult.Unavailable(
        ForegroundUnavailableReason.PLATFORM_QUERY_FAILED,
    )

    private data class QueryPlan(val generation: Long, val beginTimeMillis: Long)

    private data class EventKey(
        val timestampMillis: Long,
        val eventType: Int,
        val packageName: String?,
        val className: String?,
    )

    private companion object {
        const val STARTUP_LOOKBACK_MILLIS = 5 * 60_000L
        const val QUERY_OVERLAP_MILLIS = 1_000L
        const val MAX_RETAINED_EVENT_KEYS = 512
    }
}
