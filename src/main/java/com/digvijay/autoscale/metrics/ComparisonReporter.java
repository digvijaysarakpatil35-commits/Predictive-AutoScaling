package com.digvijay.autoscale.metrics;

import com.digvijay.autoscale.metrics.ComparisonReport.ForecastAccuracy;
import com.digvijay.autoscale.metrics.ComparisonReport.StrategyMetrics;
import com.digvijay.autoscale.replay.ReplayCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * When a replay finishes, compute the comparison and print it as a table so the
 * §3.6 numbers land in the log at the end of every run. Simulation only — the
 * modelled M/M/1 latency doesn't apply to a closed-loop run (which measures it).
 */
@Slf4j
@Component
@Profile("!closed-loop")
public class ComparisonReporter {

    private final ComparisonService comparisonService;
    private final RunTimelineStore timeline;

    public ComparisonReporter(ComparisonService comparisonService, RunTimelineStore timeline) {
        this.comparisonService = comparisonService;
        this.timeline = timeline;
    }

    @EventListener
    public void onReplayCompleted(ReplayCompletedEvent event) {
        if (timeline.isEmpty()) {
            return;
        }
        ComparisonReport report = comparisonService.compute(timeline.all());
        log.info("\n{}", format(report));
    }

    private String format(ComparisonReport report) {
        StringBuilder out = new StringBuilder();
        out.append("===== Comparison over ").append(report.intervals()).append(" intervals =====\n");
        out.append(String.format("%-12s %10s %10s %10s %14s %8s %8s%n",
                "strategy", "p95(ms)", "p99(ms)", "avg(ms)", "container-sec", "under", "over"));
        for (StrategyMetrics m : report.strategies()) {
            out.append(String.format("%-12s %10.1f %10.1f %10.1f %14d %8d %8d%n",
                    m.strategy(), m.p95LatencyMs(), m.p99LatencyMs(), m.avgLatencyMs(),
                    m.containerSeconds(), m.underProvisionedIntervals(), m.overProvisionedIntervals()));
        }
        out.append("--- forecast accuracy (independent of scaling) ---\n");
        out.append(String.format("%-20s %10s %10s %8s%n", "forecaster", "MAPE(%)", "RMSE", "samples"));
        for (ForecastAccuracy f : report.forecasts()) {
            out.append(String.format("%-20s %10.2f %10.2f %8d%n",
                    f.method(), f.mape(), f.rmse(), f.samples()));
        }
        return out.toString();
    }

}