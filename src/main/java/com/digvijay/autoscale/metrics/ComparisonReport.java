package com.digvijay.autoscale.metrics;

import com.digvijay.autoscale.forecast.ForecastMethod;
import com.digvijay.autoscale.scaling.Strategy;

import java.util.List;

/**
 * The §3.6 numbers, computed over a full replay. Per-strategy latency/cost
 * metrics, plus forecast accuracy reported <em>independently</em> of scaling so
 * "is the forecaster good" is separated from "did good forecasting help."
 */
public record ComparisonReport(
        int intervals,
        List<StrategyMetrics> strategies,
        List<ForecastAccuracy> forecasts
) {

    public record StrategyMetrics(
            Strategy strategy,
            double p95LatencyMs,
            double p99LatencyMs,
            double avgLatencyMs,
            long containerSeconds,
            int underProvisionedIntervals,
            int overProvisionedIntervals
    ) {
    }

    public record ForecastAccuracy(
            ForecastMethod method,
            double mape,   // mean absolute percentage error (%)
            double rmse,   // root mean squared error (same units as load)
            int samples
    ) {
    }

}