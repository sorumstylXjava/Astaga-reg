package com.javapro.fps

class RingBuffer<T>(private val capacity: Int) {
    private val buffer = ArrayDeque<T>(capacity)

    fun add(value: T) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(value)
    }

    fun toList(): List<T> = buffer.toList()

    fun size(): Int = buffer.size

    fun clear() = buffer.clear()

    fun isEmpty(): Boolean = buffer.isEmpty()
}
