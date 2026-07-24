package com.digvijay.autoscale.collector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class RingBuffer<T> {

    private final int capacity;
    private final Deque<T> buffer = new ArrayDeque<>();

    public RingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized void push(T item) {
        if (buffer.size() == capacity) {
            buffer.removeFirst();
        }
        buffer.addLast(item);
    }

    public synchronized List<T> snapshot() {
        return new ArrayList<>(buffer);
    }

    public synchronized int size() {
        return buffer.size();
    }

    public int capacity() {
        return capacity;
    }

}