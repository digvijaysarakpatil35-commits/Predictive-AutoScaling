package com.digvijay.autoscale.closedloop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Reads real per-container CPU from {@code docker stats} and averages it across
 * the workload replicas — the same "average utilization across pods" a Kubernetes
 * HPA uses. Averaging absorbs the per-replica variance (uneven load-balancing,
 * P/E-core scheduling, a just-started replica at 0%).
 *
 * <p>{@code docker stats} reports CPU% where 100% == one full core; the caller
 * normalizes that against the per-replica CPU limit to get a utilization fraction.
 */
@Slf4j
@Component
@Profile("closed-loop")
public class DockerStatsService {

    private final String workloadService;
    private final long commandTimeoutSeconds;

    public DockerStatsService(
            @Value("${autoscale.docker.workload-service}") String workloadService,
            @Value("${autoscale.closed-loop.stats-timeout-seconds:10}") long commandTimeoutSeconds) {
        this.workloadService = workloadService;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    /** Average CPU% and count over the currently running workload containers. */
    public CpuReading read() {
        try {
            Process process = new ProcessBuilder(
                    "docker", "stats", "--no-stream", "--format", "{{.Name}} {{.CPUPerc}}")
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(process);
            if (!process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("docker stats timed out after {}s", commandTimeoutSeconds);
                return CpuReading.EMPTY;
            }
            return parse(output, workloadService);
        } catch (IOException e) {
            log.warn("Could not run docker stats: {}", e.getMessage());
            return CpuReading.EMPTY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CpuReading.EMPTY;
        }
    }

    /** Pure parser: average the CPU% of lines whose name contains the workload service. */
    static CpuReading parse(String rawOutput, String workloadService) {
        double sum = 0.0;
        int count = 0;
        for (String line : rawOutput.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || !trimmed.contains(workloadService)) {
                continue;
            }
            int lastSpace = trimmed.lastIndexOf(' ');
            if (lastSpace < 0) {
                continue;
            }
            String percent = trimmed.substring(lastSpace + 1).replace("%", "").strip();
            try {
                sum += Double.parseDouble(percent);
                count++;
            } catch (NumberFormatException ignored) {
                // header row or malformed line — skip
            }
        }
        return count == 0 ? CpuReading.EMPTY : new CpuReading(sum / count, count);
    }

    private String readOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
            return output.toString();
        }
    }

    /** Average CPU% (docker semantics: 100% == one core) over {@code replicaCount} containers. */
    public record CpuReading(double avgCpuPercent, int replicaCount) {
        static final CpuReading EMPTY = new CpuReading(0.0, 0);
    }

}