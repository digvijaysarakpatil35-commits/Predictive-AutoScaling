package com.digvijay.autoscale.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Random;

/**
 * Standalone dev utility (not a Spring bean) that writes
 * traffic-traces/sample-load.csv. Run it directly:
 * java -cp target/classes com.digvijay.autoscale.tools.TraceGenerator
 */
public final class TraceGenerator {

    private static final String SERVICE_ID = "web-service";
    private static final long SEED = 42L;
    private static final int INTERVAL_SECONDS = 10;
    private static final int CYCLE_INTERVALS = 60;   // one full sine cycle
    private static final int NUM_CYCLES = 3;
    private static final int TOTAL_INTERVALS = CYCLE_INTERVALS * NUM_CYCLES;

    private static final double BASE_LOAD = 120.0;
    private static final double LOAD_AMPLITUDE = 80.0;
    private static final double NOISE_STDDEV = 8.0;

    private static final int SPIKE_START_INTERVAL = 130;
    private static final int SPIKE_DURATION = 6;
    private static final double SPIKE_PEAK = 300.0;

    private static final int FIXED_REPLICAS = 4;

    public static void main(String[] args) throws IOException {
        Path output = Path.of("traffic-traces", "sample-load.csv");
        Files.createDirectories(output.getParent());

        Random random = new Random(SEED);
        Instant start = Instant.parse("2026-07-20T00:00:00Z");

        StringBuilder csv = new StringBuilder();
        csv.append("serviceId,timestamp,cpuPercent,memoryMB,requestRate,replicaCount\n");

        for (int i = 0; i < TOTAL_INTERVALS; i++) {
            Instant timestamp = start.plusSeconds((long) i * INTERVAL_SECONDS);

            double cyclePosition = 2 * Math.PI * i / CYCLE_INTERVALS;
            double requestRate = BASE_LOAD + LOAD_AMPLITUDE * Math.sin(cyclePosition)
                    + random.nextGaussian() * NOISE_STDDEV
                    + spikeContribution(i);
            requestRate = Math.max(0.0, requestRate);

            double cpuPercent = clamp(10.0 + requestRate / 3.0 + random.nextGaussian() * 3.0, 0.0, 100.0);
            double memoryMB = 400.0 + requestRate * 0.3 + random.nextGaussian() * 10.0;

            csv.append(SERVICE_ID).append(',')
                    .append(timestamp).append(',')
                    .append(round(cpuPercent)).append(',')
                    .append(round(memoryMB)).append(',')
                    .append(round(requestRate)).append(',')
                    .append(FIXED_REPLICAS)
                    .append('\n');
        }

        Files.writeString(output, csv.toString());
        System.out.println("Wrote " + TOTAL_INTERVALS + " rows to " + output.toAbsolutePath());
    }

    // Sharp triangular pulse layered on top of the sine trend, isolated from the regular cycle.
    private static double spikeContribution(int interval) {
        int offset = interval - SPIKE_START_INTERVAL;
        if (offset < 0 || offset >= SPIKE_DURATION) {
            return 0.0;
        }
        double half = (SPIKE_DURATION - 1) / 2.0;
        double distanceFromPeak = Math.abs(offset - half);
        return SPIKE_PEAK * (1.0 - distanceFromPeak / (half + 1));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

}