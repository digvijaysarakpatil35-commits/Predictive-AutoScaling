package com.digvijay.autoscale.collector;

/**
 * Published by {@link MetricsCollectorService} once a snapshot has been appended
 * to its ring buffer. Downstream listeners (scaling, dashboard) react to this
 * rather than the replay loop calling them directly, which keeps replay ignorant
 * of what happens after collection.
 */
public record MetricRecordedEvent(MetricSnapshot snapshot) {
}