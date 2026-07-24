package com.digvijay.autoscale.scaling;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drives real container counts by shelling out to {@code docker compose --scale}.
 * Only the single strategy chosen in config ever reaches this client (§3.2), and
 * even then it's a no-op unless {@code autoscale.docker.enabled=true} — the
 * three-way comparison runs in shadow mode and needs no Docker at all.
 *
 * <p>We shell out to the docker CLI rather than pull in a Docker Java client: one
 * fewer heavy dependency, and the command is trivial to explain.
 */
@Slf4j
@Component
public class DockerScalingClient {

    private final boolean enabled;
    private final String composeFile;
    private final String workloadService;
    private final long commandTimeoutSeconds;

    public DockerScalingClient(
            @Value("${autoscale.docker.enabled}") boolean enabled,
            @Value("${autoscale.docker.compose-file}") String composeFile,
            @Value("${autoscale.docker.workload-service}") String workloadService,
            @Value("${autoscale.docker.command-timeout-seconds}") long commandTimeoutSeconds) {
        this.enabled = enabled;
        this.composeFile = composeFile;
        this.workloadService = workloadService;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    /** Scale the workload service to the given replica count. No-op when disabled. */
    public void scaleTo(int replicas) {
        if (!enabled) {
            log.debug("Docker scaling disabled; would scale '{}' to {} replicas", workloadService, replicas);
            return;
        }

        List<String> command = buildCommand(replicas);
        log.info("Scaling '{}' to {} replicas via: {}", workloadService, replicas, String.join(" ", command));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Docker scale command timed out after {}s", commandTimeoutSeconds);
                return;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("Docker scale command failed (exit {}): {}", exitCode, readOutput(process).strip());
            }
        } catch (IOException e) {
            log.warn("Could not run docker scale command: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while scaling containers");
        }
    }

    List<String> buildCommand(int replicas) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("compose");
        command.add("-f");
        command.add(composeFile);
        command.add("up");
        command.add("-d");
        command.add("--no-recreate");
        command.add("--scale");
        command.add(workloadService + "=" + replicas);
        return command;
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

}