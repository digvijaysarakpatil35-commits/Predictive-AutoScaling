package com.digvijay.autoscale.metrics;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory timeline of the run (§3.7). Appended to each interval by the
 * orchestrator; read by the comparison service and the dashboard.
 */
@Component
public class RunTimelineStore {

    private final List<IntervalRecord> records = Collections.synchronizedList(new ArrayList<>());

    public void record(IntervalRecord record) {
        records.add(record);
    }

    public List<IntervalRecord> all() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    public void clear() {
        records.clear();
    }

}