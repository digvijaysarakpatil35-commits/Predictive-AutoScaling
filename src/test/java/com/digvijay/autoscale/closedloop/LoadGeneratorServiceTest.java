package com.digvijay.autoscale.closedloop;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoadGeneratorServiceTest {

    @Test
    void requestsForIntervalScalesRateByWindow() {
        // 200 req/s over a 1.5s window -> 300 requests
        assertThat(LoadGeneratorService.requestsForInterval(200.0, 1.5)).isEqualTo(300);
        // rounds to the nearest whole request
        assertThat(LoadGeneratorService.requestsForInterval(33.0, 1.0)).isEqualTo(33);
    }

    @Test
    void percentilePicksTheNearestRankValue() {
        long[] sorted = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        // p95 of 10 values -> ceil(0.95*10)=10 -> index 9
        assertThat(LoadGeneratorService.percentile(sorted, 95)).isEqualTo(100.0);
        // p50 -> ceil(5)=5 -> index 4
        assertThat(LoadGeneratorService.percentile(sorted, 50)).isEqualTo(50.0);
    }

    @Test
    void percentileOfEmptyIsZero() {
        assertThat(LoadGeneratorService.percentile(new long[0], 95)).isZero();
    }

}