package com.example.referencecamera.camera

data class CollectedPair<T, M>(val first: TimestampedFrame<T>, val second: TimestampedFrame<T>, val metadata: M)

/** Bounded rolling window. Never pins delivery to a first result whose buffers may be gone. */
class PairFrameCollector<T, M>(private val capacity: Int = 3) {
    private val images = List(2) { linkedMapOf<Long, T>() }
    private data class Candidate<M>(val key: Long, val first: Long, val second: Long, val metadata: M)
    private val candidates = ArrayDeque<Candidate<M>>()

    fun image(index: Int, timestamp: Long, value: T): CollectedPair<T, M>? {
        images[index][timestamp] = value
        while (images[index].size > capacity) images[index].remove(images[index].keys.first())
        return match()
    }

    fun result(key: Long, first: Long, second: Long, metadata: M): CollectedPair<T, M>? {
        if (kotlin.math.abs(first - second) <= 50_000_000L) candidates.addLast(Candidate(key, first, second, metadata))
        while (candidates.size > capacity * 4) candidates.removeFirst()
        return match()
    }

    fun reject(key: Long) { candidates.removeAll { it.key == key } }
    fun diagnostic() = "image timestamps=${images.map { it.keys.toList() }}, metadata timestamps=${candidates.map { it.first to it.second }}"

    private fun match(): CollectedPair<T, M>? {
        for (candidate in candidates) {
            val first = images[0][candidate.first] ?: continue
            val second = images[1][candidate.second] ?: continue
            val pair = CollectedPair<T, M>(TimestampedFrame(candidate.first, first), TimestampedFrame(candidate.second, second), candidate.metadata)
            images.forEach { it.clear() }; candidates.clear()
            return pair
        }
        return null
    }
}
