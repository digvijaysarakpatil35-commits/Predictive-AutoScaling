package com.digvijay.autoscale.collector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RingBufferTest {

    @Test
    void evictsOldestWhenPushedBeyondCapacity() {
        int capacity = 10;
        RingBuffer<Integer> buffer = new RingBuffer<>(capacity);

        for (int i = 0; i < capacity + 5; i++) {
            buffer.push(i);
        }

        assertThat(buffer.size()).isEqualTo(capacity);

        List<Integer> snapshot = buffer.snapshot();
        assertThat(snapshot).containsExactly(5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
    }

}