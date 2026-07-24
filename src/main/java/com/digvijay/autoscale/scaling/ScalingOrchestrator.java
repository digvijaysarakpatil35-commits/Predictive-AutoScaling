package com.digvijay.autoscale.scaling;

import com.digvijay.autoscale.collector.MetricRecordedEvent;
import com.digvijay.autoscale.collector.MetricSnapshot;
import com.digvijay.autoscale.collector.MetricsCollectorService;
import com.digvijay.autoscale.forecast.ExponentialSmoothingForecaster;
import com.digvijay.autoscale.forecast.LinearRegressionForecaster;
import com.digvijay.autoscale.forecast.Prediction;
import com.digvijay.autoscale.metrics.IntervalRecord;
import com.digvijay.autoscale.metrics.RunTimelineStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Drives the per-interval pipeline. On each {@link MetricRecordedEvent} it pulls
 * the current history from the collector, asks the {@link ScalingDecisionEngine}
 * to evaluate all three strategies (shadow mode), stores the decisions, records
 * a consolidated timeline row (load + replicas + both forecasts) for the metrics
 * and dashboard, optionally drives real containers, and logs a live summary.
 */
@Slf4j
@Component
@Profile("!closed-loop")
public class ScalingOrchestrator {

    private final MetricsCollectorService collector;
    private final ScalingDecisionEngine engine;
    private final DecisionLogStore decisionLog;
    private final DockerScalingClient dockerScalingClient;
    private final ExponentialSmoothingForecaster smoothingForecaster;
    private final LinearRegressionForecaster regressionForecaster;
    private final RunTimelineStore timeline;
    private final ApplicationEventPublisher eventPublisher;
    private final Strategy drivingStrategy;

    public ScalingOrchestrator(MetricsCollectorService collector,
                               ScalingDecisionEngine engine,
                               DecisionLogStore decisionLog,
                               DockerScalingClient dockerScalingClient,
                               ExponentialSmoothingForecaster smoothingForecaster,
                               LinearRegressionForecaster regressionForecaster,
                               RunTimelineStore timeline,
                               ApplicationEventPublisher eventPublisher,
                               @Value("${autoscale.docker.driving-strategy}") Strategy drivingStrategy) {
        this.collector = collector;
        this.engine = engine;
        this.decisionLog = decisionLog;
        this.dockerScalingClient = dockerScalingClient;
        this.smoothingForecaster = smoothingForecaster;
        this.regressionForecaster = regressionForecaster;
        this.timeline = timeline;
        this.eventPublisher = eventPublisher;
        this.drivingStrategy = drivingStrategy;
    }

    @EventListener
    public void onMetricRecorded(MetricRecordedEvent event) {
        MetricSnapshot current = event.snapshot();
        String serviceId = current.serviceId();
        List<MetricSnapshot> history = collector.history(serviceId);

        List<ScalingDecision> decisions = engine.evaluate(current, history);
        decisions.forEach(decisionLog::record);

        // Both forecasts are captured every interval for the dashboard overlay
        // and the independent forecast-error metric.
        List<Prediction> predictions = new ArrayList<>();
        smoothingForecaster.predict(serviceId, history).ifPresent(predictions::add);
        regressionForecaster.predict(serviceId, history).ifPresent(predictions::add);

        IntervalRecord record = IntervalRecord.simulated(
                current.timestamp(),
                current.requestRate(),
                decisions.stream().collect(Collectors.toMap(
                        ScalingDecision::strategy, ScalingDecision::replicasAfter)),
                predictions);
        timeline.record(record);
        // Off the critical path: hand the completed row to the dashboard.
        eventPublisher.publishEvent(record);

        // Shadow mode: all three are logged, but only the chosen strategy's
        // action ever touches real containers (a no-op unless Docker is enabled).
        decisions.stream()
                .filter(d -> d.strategy() == drivingStrategy && d.action() != ScalingAction.NONE)
                .findFirst()
                .ifPresent(d -> dockerScalingClient.scaleTo(d.replicasAfter()));

        log.info("Decisions @ {} load={}: {}",
                current.timestamp(),
                String.format("%.1f", current.requestRate()),
                decisions.stream().map(this::format).collect(Collectors.joining("  ")));
    }

    private String format(ScalingDecision decision) {
        String suffix = decision.action() == ScalingAction.NONE ? "" : " " + decision.action();
        return decision.strategy() + "=" + decision.replicasAfter() + suffix;
    }

}