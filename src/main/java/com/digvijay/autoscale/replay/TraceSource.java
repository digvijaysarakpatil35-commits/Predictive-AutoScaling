package com.digvijay.autoscale.replay;

import com.digvijay.autoscale.collector.MetricSnapshot;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads the CSV trace once and hands out its rows in order. Both drivers consume
 * it: the simulation replays each row as a metric; the closed-loop driver uses
 * each row's request-rate as the demand to actually generate (and its timestamp
 * for trace-time forecast/cooldown accounting). Always active, independent of mode.
 */
@Slf4j
@Component
public class TraceSource {

    private final Path traceFile;
    private final AtomicInteger cursor = new AtomicInteger(0);
    private List<MetricSnapshot> trace = List.of();

    public TraceSource(@Value("${autoscale.replay.trace-file}") String traceFilePath) {
        this.traceFile = Path.of(traceFilePath);
    }

    @PostConstruct
    void load() throws IOException {
        List<MetricSnapshot> snapshots = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(traceFile)) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                snapshots.add(parseLine(line));
            }
        }
        this.trace = snapshots;
        log.info("Loaded {} snapshots from {}", trace.size(), traceFile);
    }

    /** Next row, or empty once the trace is exhausted. */
    public Optional<MetricSnapshot> next() {
        int index = cursor.getAndIncrement();
        return index < trace.size() ? Optional.of(trace.get(index)) : Optional.empty();
    }

    /** 1-based position of the last row returned by {@link #next()} (for logging). */
    public int position() {
        return Math.min(cursor.get(), trace.size());
    }

    public int size() {
        return trace.size();
    }

    public void reset() {
        cursor.set(0);
    }

    private MetricSnapshot parseLine(String line) {
        String[] fields = line.split(",");
        return new MetricSnapshot(
                fields[0],
                Instant.parse(fields[1]),
                Double.parseDouble(fields[2]),
                Double.parseDouble(fields[3]),
                Double.parseDouble(fields[4]),
                Integer.parseInt(fields[5]));
    }

}