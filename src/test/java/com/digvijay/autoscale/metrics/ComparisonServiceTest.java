package com.digvijay.autoscale.metrics;

import com.digvijay.autoscale.forecast.ForecastMethod;
import com.digvijay.autoscale.forecast.Prediction;
import com.digvijay.autoscale.metrics.ComparisonReport.ForecastAccuracy;
import com.digvijay.autoscale.metrics.ComparisonReport.StrategyMetrics;
import com.digvijay.autoscale.scaling.Strategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ComparisonServiceTest {

    private static final Instant T0 = Instant.parse("2026-07-20T00:00:00Z");
    private static final long STEP = 10;

    // load-per-replica=50, scaleUp=0.8, scaleDown=0.3, interval=10s, base latency=20ms
    private final ComparisonService service =
            new ComparisonService(new LatencyModel(50.0, 20.0), 50.0, 0.8, 0.3, 10);

    @Test
    void computesPerStrategyLatencyCostAndProvisioningCounts() {
        List<IntervalRecord> timeline = List.of(
                record(0, 50.0, 4, prediction(1, 100.0)),   // util 0.25 -> over-provisioned
                record(1, 120.0, 4, prediction(2, 180.0)),  // util 0.60 -> healthy
                record(2, 200.0, 4, prediction(3, 400.0)),  // util 1.00 -> under-provisioned
                record(3, 400.0, 4, prediction(4, 500.0)));  // util 2.00 -> under-provisioned

        ComparisonReport report = service.compute(timeline);
        assertThat(report.intervals()).isEqualTo(4);
        assertThat(report.strategies()).hasSize(3);

        StrategyMetrics baseline = metricsFor(report, Strategy.BASELINE);
        // container-seconds = 4 replicas * 4 intervals * 10s
        assertThat(baseline.containerSeconds()).isEqualTo(160);
        assertThat(baseline.underProvisionedIntervals()).isEqualTo(2);
        assertThat(baseline.overProvisionedIntervals()).isEqualTo(1);
        // latencies: 26.67, 50, 2000, 2000 -> p95/p99 land on the overloaded tail
        assertThat(baseline.p95LatencyMs()).isCloseTo(2000.0, within(0.001));
        assertThat(baseline.avgLatencyMs()).isCloseTo(1019.167, within(0.01));
    }

    @Test
    void computesForecastErrorIndependentlyAndSkipsForecastsPastTheTrace() {
        List<IntervalRecord> timeline = List.of(
                record(0, 50.0, 4, prediction(1, 100.0)),   // actual@1=120, err 20
                record(1, 120.0, 4, prediction(2, 180.0)),  // actual@2=200, err 20
                record(2, 200.0, 4, prediction(3, 400.0)),  // actual@3=400, err 0
                record(3, 400.0, 4, prediction(4, 500.0)));  // targets t4 -> no actual, skipped

        ComparisonReport report = service.compute(timeline);

        ForecastAccuracy regression = forecastFor(report, ForecastMethod.LINEAR_REGRESSION);
        assertThat(regression.samples()).isEqualTo(3);
        // MAPE = mean(20/120, 20/200, 0)*100 = 8.889%
        assertThat(regression.mape()).isCloseTo(8.889, within(0.01));
        // RMSE = sqrt((400+400+0)/3) = 16.33
        assertThat(regression.rmse()).isCloseTo(16.330, within(0.01));

        // No exponential-smoothing predictions were supplied.
        assertThat(forecastFor(report, ForecastMethod.EXP_SMOOTHING).samples()).isZero();
    }

    private IntervalRecord record(int step, double load, int replicas, Prediction prediction) {
        Map<Strategy, Integer> replicaCounts = Map.of(
                Strategy.BASELINE, replicas,
                Strategy.REACTIVE, replicas,
                Strategy.PREDICTIVE, replicas);
        return IntervalRecord.simulated(T0.plusSeconds(STEP * step), load, replicaCounts, List.of(prediction));
    }

    private Prediction prediction(int targetStep, double predictedLoad) {
        return new Prediction("web-service", T0.plusSeconds(STEP * targetStep),
                predictedLoad, ForecastMethod.LINEAR_REGRESSION);
    }

    private static StrategyMetrics metricsFor(ComparisonReport report, Strategy strategy) {
        return report.strategies().stream()
                .filter(m -> m.strategy() == strategy)
                .findFirst().orElseThrow();
    }

    private static ForecastAccuracy forecastFor(ComparisonReport report, ForecastMethod method) {
        return report.forecasts().stream()
                .filter(f -> f.method() == method)
                .findFirst().orElseThrow();
    }

}