package com.digvijay.autoscale.metrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a (load, replicas) pair into a modelled response time. We use an
 * M/M/1-style curve: {@code latency = base / (1 - utilization)}. Latency is flat
 * and cheap while there's headroom, then climbs steeply as utilization
 * approaches 1 — which is the whole reason autoscaling exists, and why a plain
 * average is misleading (it hides the spikes). Utilization is capped just below
 * 1 so an overloaded interval reports a large but finite latency rather than
 * infinity.
 */
@Component
public class LatencyModel {

    /** Beyond this utilization the queue is effectively saturated; caps the curve. */
    private static final double SATURATION_UTILIZATION = 0.99;

    private final double loadPerReplica;
    private final double baseLatencyMs;

    public LatencyModel(@Value("${autoscale.scaling.load-per-replica}") double loadPerReplica,
                        @Value("${autoscale.metrics.base-latency-ms}") double baseLatencyMs) {
        this.loadPerReplica = loadPerReplica;
        this.baseLatencyMs = baseLatencyMs;
    }

    public double latencyMs(double load, int replicas) {
        double utilization = load / (replicas * loadPerReplica);
        double capped = Math.min(utilization, SATURATION_UTILIZATION);
        return baseLatencyMs / (1.0 - capped);
    }

}