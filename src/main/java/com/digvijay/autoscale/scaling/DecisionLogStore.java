package com.digvijay.autoscale.scaling;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory accumulation of every {@link ScalingDecision}, kept per strategy.
 * This is the raw material the Day 3 comparison reads to compute the metrics
 * (container-seconds, over/under-provisioned intervals, etc.). In-memory only by
 * design (§3.7) — no persistence.
 */
@Component
public class DecisionLogStore {

    private final Map<Strategy, List<ScalingDecision>> byStrategy = new ConcurrentHashMap<>();

    public void record(ScalingDecision decision) {
        byStrategy
                .computeIfAbsent(decision.strategy(), key -> Collections.synchronizedList(new ArrayList<>()))
                .add(decision);
    }

    public List<ScalingDecision> forStrategy(Strategy strategy) {
        List<ScalingDecision> decisions = byStrategy.get(strategy);
        if (decisions == null) {
            return List.of();
        }
        synchronized (decisions) {
            return List.copyOf(decisions);
        }
    }

    public int count() {
        return byStrategy.values().stream().mapToInt(List::size).sum();
    }

    public void clear() {
        byStrategy.clear();
    }

}