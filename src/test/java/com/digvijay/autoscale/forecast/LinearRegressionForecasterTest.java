package com.digvijay.autoscale.forecast;

import com.digvijay.autoscale.collector.MetricSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LinearRegressionForecasterTest {

    private static final String SERVICE_ID = "web-service";
    private static final long INTERVAL_SECONDS = 10;
    private static final int LOOKBACK_K = 5;
    private static final int CYCLE_INTERVALS = 12;
    private static final double RIDGE = 1e-6;

    @Test
    void anticipatesARecurringPeakBetterThanExponentialSmoothing() {
        // History ends on the climb toward the peak: last index 38 -> phase 2,
        // so the next step (index 39 -> phase 3) is the sinusoid's peak (=150).
        // SES lags a rising series; regression knows the phase and jumps ahead.
        List<MetricSnapshot> history = sinusoid(39, 100.0, 50.0);
        double trueNext = 100.0 + 50.0 * Math.sin(2 * Math.PI * (39 % CYCLE_INTERVALS) / CYCLE_INTERVALS);

        var regression = new LinearRegressionForecaster(
                LOOKBACK_K, 1, INTERVAL_SECONDS, CYCLE_INTERVALS, RIDGE);
        var smoothing = new ExponentialSmoothingForecaster(0.4, 1, INTERVAL_SECONDS);

        double regForecast = regression.predict(SERVICE_ID, history).orElseThrow().predictedLoad();
        double sesForecast = smoothing.predict(SERVICE_ID, history).orElseThrow().predictedLoad();

        assertThat(regForecast).isCloseTo(trueNext, within(5.0));
        assertThat(Math.abs(regForecast - trueNext))
                .isLessThan(Math.abs(sesForecast - trueNext));
    }

    @Test
    void recoversAConstantLevel() {
        List<MetricSnapshot> history = constant(30, 120.0);

        var regression = new LinearRegressionForecaster(
                LOOKBACK_K, 1, INTERVAL_SECONDS, CYCLE_INTERVALS, RIDGE);

        double forecast = regression.predict(SERVICE_ID, history).orElseThrow().predictedLoad();

        assertThat(forecast).isCloseTo(120.0, within(1.0));
        assertThat(regression.method()).isEqualTo(ForecastMethod.LINEAR_REGRESSION);
    }

    @Test
    void forecastTimestampIsHorizonIntervalsPastTheLastReading() {
        List<MetricSnapshot> history = sinusoid(39, 100.0, 50.0);
        int horizon = 2;

        var regression = new LinearRegressionForecaster(
                LOOKBACK_K, horizon, INTERVAL_SECONDS, CYCLE_INTERVALS, RIDGE);

        Instant lastTimestamp = history.get(history.size() - 1).timestamp();
        Instant forecastTimestamp = regression.predict(SERVICE_ID, history)
                .orElseThrow().forecastTimestamp();

        assertThat(forecastTimestamp).isEqualTo(lastTimestamp.plusSeconds(INTERVAL_SECONDS * horizon));
    }

    @Test
    void tooLittleHistoryYieldsNoPrediction() {
        // Fewer samples than features -> not enough to fit -> empty.
        List<MetricSnapshot> history = sinusoid(10, 100.0, 50.0);

        var regression = new LinearRegressionForecaster(
                LOOKBACK_K, 1, INTERVAL_SECONDS, CYCLE_INTERVALS, RIDGE);

        assertThat(regression.predict(SERVICE_ID, history)).isEmpty();
    }

    private static List<MetricSnapshot> sinusoid(int count, double base, double amplitude) {
        List<MetricSnapshot> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * (i % CYCLE_INTERVALS) / CYCLE_INTERVALS;
            double requestRate = base + amplitude * Math.sin(angle);
            history.add(snapshot(i, requestRate));
        }
        return history;
    }

    private static List<MetricSnapshot> constant(int count, double requestRate) {
        List<MetricSnapshot> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(snapshot(i, requestRate));
        }
        return history;
    }

    private static MetricSnapshot snapshot(int index, double requestRate) {
        // Anchor at the epoch so interval index == snapshot index, keeping the
        // cycle phase in the test identical to what the forecaster computes.
        Instant timestamp = Instant.EPOCH.plusSeconds(INTERVAL_SECONDS * index);
        return new MetricSnapshot(SERVICE_ID, timestamp, 0.0, 0.0, requestRate, 1);
    }

}