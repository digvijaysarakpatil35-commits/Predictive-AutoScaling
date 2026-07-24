package com.digvijay.autoscale.api;

import com.digvijay.autoscale.metrics.ComparisonReport;
import com.digvijay.autoscale.metrics.ComparisonService;
import com.digvijay.autoscale.metrics.IntervalRecord;
import com.digvijay.autoscale.metrics.RunTimelineStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only endpoints over the current run: the raw timeline (for the dashboard
 * overlay), the computed comparison (the §3.6 numbers), and the run mode.
 */
@RestController
@RequestMapping("/api")
public class MetricsController {

    private final RunTimelineStore timeline;
    private final ComparisonService comparisonService;
    private final Environment environment;
    private final String drivingStrategy;

    public MetricsController(RunTimelineStore timeline,
                            ComparisonService comparisonService,
                            Environment environment,
                            @Value("${autoscale.docker.driving-strategy}") String drivingStrategy) {
        this.timeline = timeline;
        this.comparisonService = comparisonService;
        this.environment = environment;
        this.drivingStrategy = drivingStrategy;
    }

    @GetMapping("/timeline")
    public List<IntervalRecord> timeline() {
        return timeline.all();
    }

    @GetMapping("/comparison")
    public ComparisonReport comparison() {
        return comparisonService.compute(timeline.all());
    }

    /** Which mode is running, and (in closed-loop) which strategy drives real containers. */
    @GetMapping("/mode")
    public Map<String, String> mode() {
        boolean closedLoop = List.of(environment.getActiveProfiles()).contains("closed-loop");
        return Map.of(
                "mode", closedLoop ? "closed-loop" : "simulation",
                "drivingStrategy", closedLoop ? drivingStrategy : "");
    }

}
