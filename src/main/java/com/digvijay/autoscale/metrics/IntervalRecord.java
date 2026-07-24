package com.digvijay.autoscale.metrics;

import com.digvijay.autoscale.forecast.Prediction;
import com.digvijay.autoscale.scaling.Strategy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One consolidated row of the timeline: what happened this interval (load), what
 * each strategy decided to run (replica counts), and what each forecaster
 * predicted. Feeds both the comparison metrics and the live dashboard.
 *
 * <p>{@code measuredCpuPercent} and {@code measuredLatencyMs} are populated only
 * in closed-loop mode (real containers + real traffic); they are {@code null} in
 * the simulation, where latency is modelled instead.
 */
public record IntervalRecord(
        Instant timestamp,
        double actualLoad,
        Map<Strategy, Integer> replicas,
        List<Prediction> predictions,
        Double measuredCpuPercent,
        Double measuredLatencyMs
) {

    /** Simulation-mode row: no measured CPU/latency. */
    public static IntervalRecord simulated(Instant timestamp, double actualLoad,
                                           Map<Strategy, Integer> replicas, List<Prediction> predictions) {
        return new IntervalRecord(timestamp, actualLoad, replicas, predictions, null, null);
    }

}