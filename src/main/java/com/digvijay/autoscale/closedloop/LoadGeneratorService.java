package com.digvijay.autoscale.closedloop;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fires the interval's demand at the load balancer and measures the real p95
 * response latency. Concurrency is bounded by a semaphore so a burst of requests
 * paces out over the interval rather than opening thousands of sockets at once.
 */
@Slf4j
@Component
@Profile("closed-loop")
public class LoadGeneratorService {

    private final URI target;
    private final int maxConcurrency;
    private final Duration requestTimeout;
    private final ExecutorService executor;
    private final HttpClient httpClient;

    public LoadGeneratorService(
            @Value("${autoscale.closed-loop.lb-url}") String lbUrl,
            @Value("${autoscale.closed-loop.max-concurrency:64}") int maxConcurrency,
            @Value("${autoscale.closed-loop.request-timeout-ms:5000}") long requestTimeoutMs) {
        this.target = URI.create(lbUrl);
        this.maxConcurrency = maxConcurrency;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.executor = Executors.newFixedThreadPool(maxConcurrency);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(executor)
                .build();
    }

    /**
     * Send {@code requestCount} requests, at most {@code maxConcurrency} in flight,
     * and wait up to {@code windowSeconds} for them to drain.
     */
    public LoadResult fire(int requestCount, double windowSeconds) {
        Semaphore permits = new Semaphore(maxConcurrency);
        ConcurrentLinkedQueue<Long> latenciesMs = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[requestCount];

        HttpRequest request = HttpRequest.newBuilder(target).timeout(requestTimeout).GET().build();

        // Pace requests evenly across the window so CPU load is sustained (not a
        // front-loaded burst), which keeps the mid-window CPU sample representative.
        long paceNanos = requestCount > 0 ? (long) (windowSeconds * 1_000_000_000L / requestCount) : 0;
        long nextLaunch = System.nanoTime();

        for (int i = 0; i < requestCount; i++) {
            try {
                permits.acquire();
                nextLaunch += paceNanos;
                long waitNanos = nextLaunch - System.nanoTime();
                if (waitNanos > 0) {
                    Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long start = System.nanoTime();
            futures[i] = httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        permits.release();
                        if (error != null || response.statusCode() >= 500) {
                            errors.incrementAndGet();
                        } else {
                            latenciesMs.add((System.nanoTime() - start) / 1_000_000);
                        }
                    });
        }

        try {
            CompletableFuture.allOf(futures).get((long) (windowSeconds * 1000) + 2000,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Load window did not fully drain: {}", e.getMessage());
        }

        long[] sorted = latenciesMs.stream().mapToLong(Long::longValue).sorted().toArray();
        return new LoadResult(percentile(sorted, 95), errors.get(), requestCount);
    }

    /** Requests to send for a demand of {@code ratePerSecond} over a window. */
    static int requestsForInterval(double ratePerSecond, double windowSeconds) {
        return (int) Math.round(ratePerSecond * windowSeconds);
    }

    /** Nearest-rank percentile over an already-sorted ascending array. */
    static double percentile(long[] sortedAscending, double percentile) {
        if (sortedAscending.length == 0) {
            return 0.0;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sortedAscending.length);
        int index = Math.min(sortedAscending.length - 1, Math.max(0, rank - 1));
        return sortedAscending[index];
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Measured outcome of one interval's load. */
    public record LoadResult(double p95LatencyMs, int errorCount, int sentCount) {
    }

}