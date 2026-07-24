package com.digvijay.autoscale.forecast;

import java.time.Instant;

public record Prediction(
        String serviceId,
        Instant forecastTimestamp,
        double predictedLoad,
        ForecastMethod method
) {
}