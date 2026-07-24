package com.digvijay.autoscale.api;

import com.digvijay.autoscale.collector.MetricsCollectorService;
import com.digvijay.autoscale.metrics.RunTimelineStore;
import com.digvijay.autoscale.replay.TraceSource;
import com.digvijay.autoscale.scaling.DecisionLogStore;
import com.digvijay.autoscale.scaling.ScalingDecisionEngine;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Run control. {@code POST /api/reset} wipes all in-memory run state and rewinds
 * the trace, so the whole comparison can be replayed on demand without bouncing
 * the JVM — handy in a live demo.
 */
@RestController
@RequestMapping("/api")
public class RunController {

    private final TraceSource traceSource;
    private final MetricsCollectorService collector;
    private final ScalingDecisionEngine engine;
    private final RunTimelineStore timeline;
    private final DecisionLogStore decisionLog;
    private final DashboardWebSocketHandler dashboard;

    public RunController(TraceSource traceSource,
                         MetricsCollectorService collector,
                         ScalingDecisionEngine engine,
                         RunTimelineStore timeline,
                         DecisionLogStore decisionLog,
                         DashboardWebSocketHandler dashboard) {
        this.traceSource = traceSource;
        this.collector = collector;
        this.engine = engine;
        this.timeline = timeline;
        this.decisionLog = decisionLog;
        this.dashboard = dashboard;
    }

    @PostMapping("/reset")
    public void reset() {
        timeline.clear();
        decisionLog.clear();
        collector.clear();
        engine.reset();
        dashboard.broadcastReset();
        traceSource.reset();
    }

}