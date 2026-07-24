package com.digvijay.autoscale.scaling;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DockerScalingClientTest {

    @Test
    void buildsComposeScaleCommand() {
        var client = new DockerScalingClient(false, "docker-compose.yml", "workload", 60);

        assertThat(client.buildCommand(5)).containsExactly(
                "docker", "compose", "-f", "docker-compose.yml",
                "up", "-d", "--no-recreate", "--scale", "workload=5");
    }

    @Test
    void disabledScaleIsANoOpAndNeverTouchesDocker() {
        var client = new DockerScalingClient(false, "docker-compose.yml", "workload", 60);

        // Disabled: must return quietly even on a machine with no Docker installed.
        assertThatCode(() -> client.scaleTo(3)).doesNotThrowAnyException();
    }

}