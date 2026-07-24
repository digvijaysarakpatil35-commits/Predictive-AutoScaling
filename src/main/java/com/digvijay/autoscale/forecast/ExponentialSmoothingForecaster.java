package com.digvijay.autoscale.forecast;

import com.digvijay.autoscale.collector.MetricSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Simple exponential smoothing (SES) over the requestRate series.
 *
 * <p>Level update: {@code S_t = alpha * y_t + (1 - alpha) * S_{t-1}}. The
 * forecast for every future step is the latest smoothed level, so this
 * extrapolates flat: it tracks where load currently sits but cannot anticipate
 * a recurring peak before the climb has started. That blind spot is the whole
 * reason the regression forecaster earns its keep.
 */
@Component
public class ExponentialSmoothingForecaster implements ForecastingService {

    private final double alpha;
    private final int horizonIntervals;
    private final long intervalSeconds;

    public ExponentialSmoothingForecaster(
            @Value("${autoscale.forecast.exp-smoothing-alpha}") double alpha,
            @Value("${autoscale.forecast.horizon-intervals}") int horizonIntervals,
            @Value("${autoscale.replay.interval-seconds}") long intervalSeconds) {
        if (alpha <= 0.0 || alpha > 1.0) {
            throw new IllegalArgumentException("alpha must be in (0, 1], got " + alpha);
        }
        this.alpha = alpha;
        this.horizonIntervals = horizonIntervals;
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public Optional<Prediction> predict(String serviceId, List<MetricSnapshot> history) {
        if (history.isEmpty()) {
            return Optional.empty();
        }

        double level = history.get(0).requestRate();
        for (int i = 1; i < history.size(); i++) {
            level = alpha * history.get(i).requestRate() + (1 - alpha) * level;
        }

        Instant lastTimestamp = history.get(history.size() - 1).timestamp();
        Instant forecastTimestamp = lastTimestamp.plusSeconds(intervalSeconds * horizonIntervals);
        return Optional.of(new Prediction(serviceId, forecastTimestamp, level, method()));
    }

    @Override
    public ForecastMethod method() {
        return ForecastMethod.EXP_SMOOTHING;
    }

}