package com.digvijay.autoscale.metrics;

import com.digvijay.autoscale.forecast.ForecastMethod;
import com.digvijay.autoscale.forecast.Prediction;
import com.digvijay.autoscale.metrics.ComparisonReport.ForecastAccuracy;
import com.digvijay.autoscale.metrics.ComparisonReport.StrategyMetrics;
import com.digvijay.autoscale.scaling.Strategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the §3.6 comparison from a run timeline. Pure function of its input —
 * no state — so it's straightforward to unit test on a hand-built timeline.
 */
@Service
public class ComparisonService {

    private final LatencyModel latencyModel;
    private final double loadPerReplica;
    private final double scaleUpThreshold;
    private final double scaleDownThreshold;
    private final long intervalSeconds;

    public ComparisonService(
            LatencyModel latencyModel,
            @Value("${autoscale.scaling.load-per-replica}") double loadPerReplica,
            @Value("${autoscale.scaling.scale-up-threshold}") double scaleUpThreshold,
            @Value("${autoscale.scaling.scale-down-threshold}") double scaleDownThreshold,
            @Value("${autoscale.replay.interval-seconds}") long intervalSeconds) {
        this.latencyModel = latencyModel;
        this.loadPerReplica = loadPerReplica;
        this.scaleUpThreshold = scaleUpThreshold;
        this.scaleDownThreshold = scaleDownThreshold;
        this.intervalSeconds = intervalSeconds;
    }

    public ComparisonReport compute(List<IntervalRecord> timeline) {
        List<StrategyMetrics> strategies = new ArrayList<>();
        for (Strategy strategy : Strategy.values()) {
            strategies.add(strategyMetrics(timeline, strategy));
        }
        List<ForecastAccuracy> forecasts = new ArrayList<>();
        for (ForecastMethod method : ForecastMethod.values()) {
            forecasts.add(forecastAccuracy(timeline, method));
        }
        return new ComparisonReport(timeline.size(), strategies, forecasts);
    }

    private StrategyMetrics strategyMetrics(List<IntervalRecord> timeline, Strategy strategy) {
        double[] latencies = new double[timeline.size()];
        long containerSeconds = 0;
        int underProvisioned = 0;
        int overProvisioned = 0;

        for (int i = 0; i < timeline.size(); i++) {
            IntervalRecord record = timeline.get(i);
            int replicas = record.replicas().get(strategy);

            latencies[i] = latencyModel.latencyMs(record.actualLoad(), replicas);
            containerSeconds += (long) replicas * intervalSeconds;

            double utilization = record.actualLoad() / (replicas * loadPerReplica);
            if (utilization > scaleUpThreshold) {
                underProvisioned++; // too few replicas: running hot
            } else if (utilization < scaleDownThreshold) {
                overProvisioned++;  // too many replicas: wasting money
            }
        }

        Arrays.sort(latencies);
        return new StrategyMetrics(
                strategy,
                percentile(latencies, 95),
                percentile(latencies, 99),
                average(latencies),
                containerSeconds,
                underProvisioned,
                overProvisioned);
    }

    private ForecastAccuracy forecastAccuracy(List<IntervalRecord> timeline, ForecastMethod method) {
        // Actual load indexed by timestamp, so a prediction is matched to the
        // real value at the time it was forecasting for (horizon-agnostic).
        Map<Instant, Double> actualByTimestamp = new HashMap<>();
        for (IntervalRecord record : timeline) {
            actualByTimestamp.put(record.timestamp(), record.actualLoad());
        }

        double sumAbsPercent = 0.0;
        double sumSquaredError = 0.0;
        int samples = 0;
        for (IntervalRecord record : timeline) {
            for (Prediction prediction : record.predictions()) {
                if (prediction.method() != method) {
                    continue;
                }
                Double actual = actualByTimestamp.get(prediction.forecastTimestamp());
                if (actual == null) {
                    continue; // forecast lands past the end of the trace
                }
                double error = actual - prediction.predictedLoad();
                if (actual != 0.0) {
                    sumAbsPercent += Math.abs(error / actual);
                }
                sumSquaredError += error * error;
                samples++;
            }
        }

        double mape = samples > 0 ? sumAbsPercent / samples * 100.0 : 0.0;
        double rmse = samples > 0 ? Math.sqrt(sumSquaredError / samples) : 0.0;
        return new ForecastAccuracy(method, mape, rmse, samples);
    }

    /** Nearest-rank percentile over an already-sorted ascending array. */
    static double percentile(double[] sortedAscending, double percentile) {
        if (sortedAscending.length == 0) {
            return 0.0;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sortedAscending.length);
        int index = Math.min(sortedAscending.length - 1, Math.max(0, rank - 1));
        return sortedAscending[index];
    }

    private static double average(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

}