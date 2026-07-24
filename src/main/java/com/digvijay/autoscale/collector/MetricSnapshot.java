package com.digvijay.autoscale.collector;

import java.time.Instant;

public record MetricSnapshot(
        String serviceId,
        Instant timestamp,
        double cpuPercent,
        double memoryMB,
        double requestRate,
        int replicaCount
) {
}