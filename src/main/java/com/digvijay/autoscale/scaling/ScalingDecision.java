package com.digvijay.autoscale.scaling;

import java.time.Instant;

public record ScalingDecision(
        String serviceId,
        Instant timestamp,
        ScalingAction action,
        int replicasBefore,
        int replicasAfter,
        Strategy strategy
) {
}