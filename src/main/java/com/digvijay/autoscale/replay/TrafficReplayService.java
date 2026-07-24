package com.digvijay.autoscale.replay;

import com.digvijay.autoscale.collector.MetricSnapshot;
import com.digvijay.autoscale.collector.MetricsCollectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Simulation driver: on each tick it replays the next trace row straight into the
 * collector (the load is the CSV's request-rate, latency is modelled). Disabled in
 * closed-loop mode, where {@code ClosedLoopDriver} takes over.
 */
@Slf4j
@Service
@Profile("!closed-loop")
public class TrafficReplayService {

    private final TraceSource traceSource;
    private final MetricsCollectorService metricsCollectorService;
    private final ApplicationEventPublisher eventPublisher;
    private boolean completed = false;

    public TrafficReplayService(TraceSource traceSource,
                                MetricsCollectorService metricsCollectorService,
                                ApplicationEventPublisher eventPublisher) {
        this.traceSource = traceSource;
        this.metricsCollectorService = metricsCollectorService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${autoscale.replay.emit-delay-millis}")
    void emitNext() {
        Optional<MetricSnapshot> next = traceSource.next();
        if (next.isEmpty()) {
            if (!completed) {
                completed = true;
                log.info("Trace replay complete: {} snapshots emitted", traceSource.size());
                eventPublisher.publishEvent(new ReplayCompletedEvent(traceSource.size()));
            }
            return;
        }

        completed = false;
        MetricSnapshot snapshot = next.get();
        metricsCollectorService.record(snapshot);
        log.info("Emitted snapshot [{}/{}]: {}", traceSource.position(), traceSource.size(), snapshot);
    }

}