package com.digvijay.autoscale.closedloop;

import com.digvijay.autoscale.closedloop.DockerStatsService.CpuReading;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DockerStatsServiceTest {

    @Test
    void averagesWorkloadCpuAndIgnoresOtherContainers() {
        String output = """
                predictive-autoscaling-workload-1 45.30%
                predictive-autoscaling-workload-2 50.10%
                predictive-autoscaling-workload-3 40.60%
                predictive-autoscaling-lb-1 0.05%
                """;

        CpuReading reading = DockerStatsService.parse(output, "workload");

        assertThat(reading.replicaCount()).isEqualTo(3);
        assertThat(reading.avgCpuPercent()).isCloseTo(45.333, within(0.01));
    }

    @Test
    void skipsHeaderAndBlankLines() {
        String output = """
                NAME CPU %
                predictive-autoscaling-workload-1 80.00%

                predictive-autoscaling-workload-2 60.00%
                """;

        CpuReading reading = DockerStatsService.parse(output, "workload");

        assertThat(reading.replicaCount()).isEqualTo(2);
        assertThat(reading.avgCpuPercent()).isCloseTo(70.0, within(0.01));
    }

    @Test
    void noWorkloadContainersYieldsEmpty() {
        String output = "predictive-autoscaling-lb-1 0.10%\n";

        CpuReading reading = DockerStatsService.parse(output, "workload");

        assertThat(reading.replicaCount()).isZero();
        assertThat(reading.avgCpuPercent()).isZero();
    }

}