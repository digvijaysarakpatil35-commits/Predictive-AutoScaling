package com.digvijay.autoscale.closedloop;

import com.digvijay.autoscale.closedloop.DockerStatsService.CpuReading;
import com.digvijay.autoscale.closedloop.LoadGeneratorService.LoadResult;
import com.digvijay.autoscale.collector.MetricSnapshot;
import com.digvijay.autoscale.collector.MetricsCollectorService;
import com.digvijay.autoscale.forecast.ExponentialSmoothingForecaster;
import com.digvijay.autoscale.forecast.LinearRegressionForecaster;
import com.digvijay.autoscale.forecast.Prediction;
import com.digvijay.autoscale.metrics.IntervalRecord;
import com.digvijay.autoscale.metrics.RunTimelineStore;
import com.digvijay.autoscale.replay.ReplayCompletedEvent;
import com.digvijay.autoscale.replay.TraceSource;
import com.digvijay.autoscale.scaling.DockerScalingClient;
import com.digvijay.autoscale.scaling.ScalingAction;
import com.digvijay.autoscale.scaling.ScalingDecision;
import com.digvijay.autoscale.scaling.ScalingDecisionEngine;
import com.digvijay.autoscale.scaling.Strategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The closed-loop conductor (replaces the simulation's orchestrator when the
 * {@code closed-loop} profile is active). Each tick it: fires the trace's demand
 * at the real load balancer, reads real CPU back from the workload containers,
 * turns it into a replica-count-independent load signal
 * {@code L = perReplicaUtilization × replicas}, forecasts it, lets the predictive
 * strategy decide, and scales real containers — a genuine self-correcting loop.
 */
@Slf4j
@Component
@Profile("closed-loop")
public class ClosedLoopDriver {

    private final TraceSource traceSource;
    private final LoadGeneratorService loadGenerator;
    private final DockerStatsService dockerStats;
    private final MetricsCollectorService collector;
    private final LinearRegressionForecaster regressionForecaster;
    private final ExponentialSmoothingForecaster smoothingForecaster;
    private final ScalingDecisionEngine engine;
    private final DockerScalingClient dockerScalingClient;
    private final RunTimelineStore timeline;
    private final ApplicationEventPublisher eventPublisher;

    private final double windowSeconds;
    private final double demandScale;
    private final double workloadCpuLimit;
    private final int startReplicas;
    private final Strategy drivingStrategy;

    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private boolean completed = false;

    public ClosedLoopDriver(TraceSource traceSource,
                            LoadGeneratorService loadGenerator,
                            DockerStatsService dockerStats,
                            MetricsCollectorService collector,
                            LinearRegressionForecaster regressionForecaster,
                            ExponentialSmoothingForecaster smoothingForecaster,
                            ScalingDecisionEngine engine,
                            DockerScalingClient dockerScalingClient,
                            RunTimelineStore timeline,
                            ApplicationEventPublisher eventPublisher,
                            @Value("${autoscale.closed-loop.load-window-millis}") long loadWindowMillis,
                            @Value("${autoscale.closed-loop.demand-scale}") double demandScale,
                            @Value("${autoscale.closed-loop.workload-cpu-limit}") double workloadCpuLimit,
                            @Value("${autoscale.closed-loop.start-replicas}") int startReplicas,
                            @Value("${autoscale.docker.driving-strategy}") Strategy drivingStrategy) {
        this.traceSource = traceSource;
        this.loadGenerator = loadGenerator;
        this.dockerStats = dockerStats;
        this.collector = collector;
        this.regressionForecaster = regressionForecaster;
        this.smoothingForecaster = smoothingForecaster;
        this.engine = engine;
        this.dockerScalingClient = dockerScalingClient;
        this.timeline = timeline;
        this.eventPublisher = eventPublisher;
        this.windowSeconds = loadWindowMillis / 1000.0;
        this.demandScale = demandScale;
        this.workloadCpuLimit = workloadCpuLimit;
        this.startReplicas = startReplicas;
        this.drivingStrategy = drivingStrategy;
    }

    @Scheduled(fixedDelayString = "${autoscale.closed-loop.tick-delay-millis}")
    void tick() {
        Optional<MetricSnapshot> next = traceSource.next();
        if (next.isEmpty()) {
            if (!completed) {
                completed = true;
                log.info("Closed-loop run complete: {} intervals", traceSource.size());
                eventPublisher.publishEvent(new ReplayCompletedEvent(traceSource.size()));
            }
            return;
        }
        completed = false;

        MetricSnapshot demand = next.get();
        String serviceId = demand.serviceId();
        Instant now = demand.timestamp();

        // 1. Fire the demand at the load balancer on a background thread, and read
        //    CPU WHILE it's running (docker stats samples over ~1s of live load) —
        //    reading after the burst would just catch idle containers.
        int requestCount = LoadGeneratorService.requestsForInterval(
                demand.requestRate() * demandScale, windowSeconds);
        Future<LoadResult> loadFuture = loadExecutor.submit(() -> loadGenerator.fire(requestCount, windowSeconds));
        sleepMillis(200); // brief lead-in so docker stats samples during steady load, within the window

        // 2. Read real CPU back from the workload containers (during the load), then
        //    wait for the load window to finish and collect the measured latency.
        CpuReading cpu = dockerStats.read();
        LoadResult load = awaitLoad(loadFuture);
        if (cpu.replicaCount() == 0) {
            // Containers not up yet — seed the pool and wait for the next tick.
            dockerScalingClient.scaleTo(startReplicas);
            log.info("Waiting for workload containers; seeded {} replicas", startReplicas);
            return;
        }

        // 3. Normalize to a replica-count-independent load signal.
        double perReplicaUtil = cpu.avgCpuPercent() / (workloadCpuLimit * 100.0);
        double loadSignal = perReplicaUtil * cpu.replicaCount();

        MetricSnapshot snapshot = new MetricSnapshot(
                serviceId, now, cpu.avgCpuPercent(), 0.0, loadSignal, cpu.replicaCount());
        collector.record(snapshot);
        List<MetricSnapshot> history = collector.history(serviceId);

        // 4. Forecasts for the overlay.
        Optional<Prediction> regression = regressionForecaster.predict(serviceId, history);
        Optional<Prediction> smoothing = smoothingForecaster.predict(serviceId, history);
        double predicted = regression.map(Prediction::predictedLoad).orElse(loadSignal);

        // The configured strategy drives the REAL containers (synced to the real
        // replica count); the others run in shadow. Run the trace once per driving
        // strategy for a clean, independent measured comparison — each strategy then
        // faces the real load IT produced, with no cross-contamination.
        Map<Strategy, Integer> replicas = new EnumMap<>(Strategy.class);
        ScalingDecision driverDecision = null;
        for (Strategy s : Strategy.values()) {
            double pred = (s == Strategy.PREDICTIVE) ? predicted : loadSignal; // only predictive uses the forecast
            ScalingDecision decision = (s == drivingStrategy)
                    ? engine.decideWithActualReplicas(s, serviceId, now, loadSignal, pred, cpu.replicaCount())
                    : engine.decideShadow(s, serviceId, now, loadSignal, pred);
            replicas.put(s, decision.replicasAfter());
            if (s == drivingStrategy) {
                driverDecision = decision;
            }
        }

        // 5. Only the driver touches real containers.
        if (driverDecision.action() != ScalingAction.NONE) {
            dockerScalingClient.scaleTo(driverDecision.replicasAfter());
        }

        // 6. Publish for the dashboard / timeline.
        List<Prediction> predictions = new ArrayList<>();
        smoothing.ifPresent(predictions::add);
        regression.ifPresent(predictions::add);
        IntervalRecord record = new IntervalRecord(now, loadSignal, replicas, predictions,
                cpu.avgCpuPercent(), load.p95LatencyMs());
        timeline.record(record);
        eventPublisher.publishEvent(record);

        log.info("Closed-loop @ {} demand={} cpu={}% driver={} B/R/P={}/{}/{} p95={}ms {}",
                now, String.format("%.0f", demand.requestRate() * demandScale),
                String.format("%.1f", cpu.avgCpuPercent()), drivingStrategy,
                replicas.get(Strategy.BASELINE), replicas.get(Strategy.REACTIVE), replicas.get(Strategy.PREDICTIVE),
                String.format("%.0f", load.p95LatencyMs()),
                driverDecision.action() == ScalingAction.NONE ? "" : drivingStrategy + ":" + driverDecision.action() + "->" + driverDecision.replicasAfter());
    }

    @PreDestroy
    void shutdown() {
        loadExecutor.shutdownNow();
    }

    private void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private LoadResult awaitLoad(Future<LoadResult> future) {
        try {
            return future.get((long) (windowSeconds * 1000) + 4000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Load window did not complete: {}", e.getMessage());
            return new LoadResult(0.0, 0, 0);
        }
    }

}