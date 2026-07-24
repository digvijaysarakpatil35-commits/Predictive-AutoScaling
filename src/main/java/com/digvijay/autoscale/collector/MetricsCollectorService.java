package com.digvijay.autoscale.collector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MetricsCollectorService {

    private final int ringBufferCapacity;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, RingBuffer<MetricSnapshot>> buffersByService = new ConcurrentHashMap<>();

    public MetricsCollectorService(@Value("${autoscale.ring-buffer.capacity}") int ringBufferCapacity,
                                   ApplicationEventPublisher eventPublisher) {
        this.ringBufferCapacity = ringBufferCapacity;
        this.eventPublisher = eventPublisher;
    }

    public void record(MetricSnapshot snapshot) {
        buffersByService
                .computeIfAbsent(snapshot.serviceId(), id -> new RingBuffer<>(ringBufferCapacity))
                .push(snapshot);
        eventPublisher.publishEvent(new MetricRecordedEvent(snapshot));
    }

    public List<MetricSnapshot> history(String serviceId) {
        RingBuffer<MetricSnapshot> buffer = buffersByService.get(serviceId);
        return buffer == null ? List.of() : buffer.snapshot();
    }

    /** Drop all history so a fresh run starts the forecaster warmup from zero. */
    public void clear() {
        buffersByService.clear();
    }

}