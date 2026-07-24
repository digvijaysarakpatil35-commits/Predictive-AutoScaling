package com.digvijay.autoscale.forecast;

import com.digvijay.autoscale.collector.MetricSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExponentialSmoothingForecasterTest {

    private static final String SERVICE_ID = "web-service";
    private static final Instant START = Instant.parse("2026-07-20T00:00:00Z");
    private static final long INTERVAL_SECONDS = 10;

    @Test
    void constantSeriesForecastsTheConstantRegardlessOfAlpha() {
        var forecaster = new ExponentialSmoothingForecaster(0.4, 1, INTERVAL_SECONDS);

        Optional<Prediction> prediction = forecaster.predict(SERVICE_ID, series(100, 100, 100, 100));

        assertThat(prediction).isPresent();
        assertThat(prediction.get().predictedLoad()).isEqualTo(100.0);
    }

    @Test
    void smoothsAccordingToAlphaOnAKnownSequence() {
        // alpha=0.5 over [10, 20, 30]: 10 -> 15 -> 22.5
        var forecaster = new ExponentialSmoothingForecaster(0.5, 1, INTERVAL_SECONDS);

        Optional<Prediction> prediction = forecaster.predict(SERVICE_ID, series(10, 20, 30));

        assertThat(prediction).isPresent();
        assertThat(prediction.get().predictedLoad()).isEqualTo(22.5);
        assertThat(prediction.get().method()).isEqualTo(ForecastMethod.EXP_SMOOTHING);
    }

    @Test
    void forecastTimestampIsHorizonIntervalsPastTheLastReading() {
        var forecaster = new ExponentialSmoothingForecaster(0.4, 3, INTERVAL_SECONDS);

        Optional<Prediction> prediction = forecaster.predict(SERVICE_ID, series(10, 20, 30));

        Instant lastTimestamp = START.plusSeconds(INTERVAL_SECONDS * 2);
        assertThat(prediction).isPresent();
        assertThat(prediction.get().forecastTimestamp())
                .isEqualTo(lastTimestamp.plusSeconds(INTERVAL_SECONDS * 3));
    }

    @Test
    void lagsBehindARisingSeriesRatherThanAnticipatingIt() {
        // The documented weakness: SES trails a steady climb, always under the latest value.
        var forecaster = new ExponentialSmoothingForecaster(0.4, 1, INTERVAL_SECONDS);

        double forecast = forecaster.predict(SERVICE_ID, series(10, 20, 30, 40, 50))
                .orElseThrow().predictedLoad();

        assertThat(forecast).isLessThan(50.0);
    }

    @Test
    void emptyHistoryYieldsNoPrediction() {
        var forecaster = new ExponentialSmoothingForecaster(0.4, 1, INTERVAL_SECONDS);

        assertThat(forecaster.predict(SERVICE_ID, List.of())).isEmpty();
    }

    private static List<MetricSnapshot> series(double... requestRates) {
        List<MetricSnapshot> history = new ArrayList<>();
        for (int i = 0; i < requestRates.length; i++) {
            history.add(new MetricSnapshot(
                    SERVICE_ID,
                    START.plusSeconds(INTERVAL_SECONDS * i),
                    0.0,
                    0.0,
                    requestRates[i],
                    1));
        }
        return history;
    }

}