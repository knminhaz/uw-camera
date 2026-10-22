package com.example.referencecamera.camera

data class TimestampedFrame<out T>(val timestamp: Long, val value: T)

/** Called on the camera callback thread. Image and result callbacks may arrive in either order. */
class FrameMatcher<T>(private val capacity: Int = 4) {
    private val pending = linkedMapOf<Long, T>()
    private var resultKnown = false
    private var expected: Long? = null
    private val observed = ArrayDeque<Long>()

    fun diagnostic(): String = "expected=${if (resultKnown) expected else "metadata pending"}, image timestamps=${observed.toList()}"

    fun image(timestamp: Long, value: T): TimestampedFrame<T>? {
        observed.addLast(timestamp)
        while (observed.size > capacity) observed.removeFirst()
        if (resultKnown) return if (expected == null || expected == timestamp) TimestampedFrame(timestamp, value) else null
        pending[timestamp] = value
        while (pending.size > capacity) pending.remove(pending.keys.first())
        return null
    }

    fun result(timestamp: Long?): TimestampedFrame<T>? {
        resultKnown = true; expected = timestamp
        val selected = if (timestamp == null) pending.entries.lastOrNull()?.let { TimestampedFrame(it.key, it.value) }
            else pending[timestamp]?.let { TimestampedFrame(timestamp, it) }
        pending.clear()
        return selected
    }
}
