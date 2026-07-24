package com.digvijay.autoscale.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LatencyModelTest {

    // load-per-replica = 50, base latency = 20ms
    private final LatencyModel model = new LatencyModel(50.0, 20.0);

    @Test
    void latencyRisesAsUtilizationClimbs() {
        double lowUtil = model.latencyMs(50.0, 4);   // util 0.25
        double midUtil = model.latencyMs(120.0, 4);  // util 0.60
        double highUtil = model.latencyMs(160.0, 4); // util 0.80

        assertThat(lowUtil).isLessThan(midUtil);
        assertThat(midUtil).isLessThan(highUtil);
    }

    @Test
    void computesTheMM1CurveAtAKnownPoint() {
        // util = 120 / (4*50) = 0.6 -> 20 / (1 - 0.6) = 50ms
        assertThat(model.latencyMs(120.0, 4)).isCloseTo(50.0, within(0.001));
    }

    @Test
    void overloadIsLargeButFiniteFromTheSaturationCap() {
        // util = 2.0 (way over capacity) -> capped at 0.99 -> 20 / 0.01 = 2000ms
        double overloaded = model.latencyMs(400.0, 4);

        assertThat(overloaded).isCloseTo(2000.0, within(0.001));
        assertThat(overloaded).isFinite();
    }

}