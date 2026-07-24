package com.digvijay.autoscale.forecast;

import com.digvijay.autoscale.collector.MetricSnapshot;

import java.util.List;
import java.util.Optional;

public interface ForecastingService {

    /**
     * Forecast the requestRate {@code horizon} intervals ahead from the given
     * ordered history (oldest first). Returns empty when there isn't enough
     * data to produce a forecast.
     */
    Optional<Prediction> predict(String serviceId, List<MetricSnapshot> history);

    ForecastMethod method();

}