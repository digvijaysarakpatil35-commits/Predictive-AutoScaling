package com.digvijay.autoscale.scaling;

import com.digvijay.autoscale.collector.MetricSnapshot;
import com.digvijay.autoscale.forecast.ForecastingService;
import com.digvijay.autoscale.forecast.Prediction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates all three strategies every interval in <b>shadow mode</b>: each one
 * keeps its own replica count and produces the {@link ScalingDecision} it
 * <em>would</em> make, without any of them touching real containers. That lets
 * us replay one trace and compare three decision logs apples-to-apples.
 *
 * <p>Strategies:
 * <ul>
 *   <li><b>BASELINE</b> — never scales; fixed replica count. The control group.</li>
 *   <li><b>REACTIVE</b> — acts on the <em>current</em> load only.</li>
 *   <li><b>PREDICTIVE</b> — acts on the forecast, with an asymmetric rule
 *       (§3.5): scale <em>up</em> proactively on whichever of forecast/current
 *       is higher, but scale <em>down</em> only when the current reading also
 *       confirms low load. A bad up-forecast just wastes a little money; a bad
 *       down-forecast causes real latency pain, so down needs confirmation.</li>
 * </ul>
 *
 * <p>Reactive is just predictive with the forecast pinned to the current value,
 * so both run through the same {@link #decide} method. A per-strategy
 * <b>cooldown</b> (§3.4) suppresses further action for N seconds of trace time
 * after acting, which prevents flapping on noisy load.
 */
@Service
public class ScalingDecisionEngine {

    private final ForecastingService predictiveForecaster;
    private final double loadPerReplica;
    private final double scaleUpThreshold;
    private final double scaleDownThreshold;
    private final double targetUtilization;
    private final int minReplicas;
    private final int maxReplicas;
    private final long cooldownSeconds;
    private final int fixedReplicas;

    private final Map<StateKey, StrategyState> states = new ConcurrentHashMap<>();

    public ScalingDecisionEngine(
            @Qualifier("linearRegressionForecaster") ForecastingService predictiveForecaster,
            @Value("${autoscale.scaling.load-per-replica}") double loadPerReplica,
            @Value("${autoscale.scaling.scale-up-threshold}") double scaleUpThreshold,
            @Value("${autoscale.scaling.scale-down-threshold}") double scaleDownThreshold,
            @Value("${autoscale.scaling.target-utilization}") double targetUtilization,
            @Value("${autoscale.scaling.min-replicas}") int minReplicas,
            @Value("${autoscale.scaling.max-replicas}") int maxReplicas,
            @Value("${autoscale.scaling.cooldown-seconds}") long cooldownSeconds,
            @Value("${autoscale.baseline.fixed-replicas}") int fixedReplicas) {
        this.predictiveForecaster = predictiveForecaster;
        this.loadPerReplica = loadPerReplica;
        this.scaleUpThreshold = scaleUpThreshold;
        this.scaleDownThreshold = scaleDownThreshold;
        this.targetUtilization = targetUtilization;
        this.minReplicas = minReplicas;
        this.maxReplicas = maxReplicas;
        this.cooldownSeconds = cooldownSeconds;
        this.fixedReplicas = fixedReplicas;
    }

    /**
     * Produce one {@link ScalingDecision} per strategy for this interval and
     * advance each strategy's replica state.
     */
    public List<ScalingDecision> evaluate(MetricSnapshot current, List<MetricSnapshot> history) {
        String serviceId = current.serviceId();
        Instant now = current.timestamp();
        double currentLoad = current.requestRate();

        ScalingDecision baseline = decideBaseline(serviceId, now);

        ScalingDecision reactive = decide(serviceId, now, currentLoad, currentLoad,
                stateFor(serviceId, Strategy.REACTIVE), Strategy.REACTIVE);

        double predictedLoad = predictiveForecaster.predict(serviceId, history)
                .map(Prediction::predictedLoad)
                .orElse(currentLoad); // no forecast yet -> fall back to reactive behavior
        ScalingDecision predictive = decide(serviceId, now, currentLoad, predictedLoad,
                stateFor(serviceId, Strategy.PREDICTIVE), Strategy.PREDICTIVE);

        return List.of(baseline, reactive, predictive);
    }

    ScalingDecision decideBaseline(String serviceId, Instant now) {
        return new ScalingDecision(serviceId, now, ScalingAction.NONE,
                fixedReplicas, fixedReplicas, Strategy.BASELINE);
    }

    /**
     * Core decision shared by reactive and predictive. Reactive passes
     * {@code predictedLoad == currentLoad}, which collapses the asymmetric rule
     * back to plain current-load behavior.
     */
    ScalingDecision decide(String serviceId, Instant now, double currentLoad, double predictedLoad,
                           StrategyState state, Strategy strategy) {
        int before = state.replicas;
        ScalingAction action = ScalingAction.NONE;
        int after = before;

        if (!inCooldown(now, state.lastActionAt)) {
            // Scale up on whichever is higher, so predictive is never blinder
            // than reactive to a spike the forecast missed, and gets ahead when
            // the forecast leads.
            double upLoad = Math.max(currentLoad, predictedLoad);
            if (utilization(upLoad, before) > scaleUpThreshold) {
                int target = requiredReplicas(upLoad);
                if (target > before) {
                    action = ScalingAction.SCALE_UP;
                    after = target;
                }
            } else if (utilization(currentLoad, before) < scaleDownThreshold
                    && utilization(predictedLoad, before) < scaleDownThreshold) {
                // Asymmetric: down needs both the current reading and the
                // forecast to agree load is low.
                int target = requiredReplicas(Math.max(currentLoad, predictedLoad));
                if (target < before) {
                    action = ScalingAction.SCALE_DOWN;
                    after = target;
                }
            }
        }

        if (action != ScalingAction.NONE) {
            state.replicas = after;
            state.lastActionAt = now;
        }
        return new ScalingDecision(serviceId, now, action, before, after, strategy);
    }

    private boolean inCooldown(Instant now, Instant lastActionAt) {
        if (lastActionAt == null) {
            return false;
        }
        return Duration.between(lastActionAt, now).getSeconds() < cooldownSeconds;
    }

    private double utilization(double load, int replicas) {
        return load / (replicas * loadPerReplica);
    }

    /** Replicas needed to run the given load at the target utilization, clamped to bounds. */
    private int requiredReplicas(double load) {
        int required = (int) Math.ceil(load / (loadPerReplica * targetUtilization));
        return Math.max(minReplicas, Math.min(maxReplicas, required));
    }

    StrategyState stateFor(String serviceId, Strategy strategy) {
        return states.computeIfAbsent(new StateKey(serviceId, strategy),
                key -> new StrategyState(fixedReplicas, null));
    }

    /** Forget every strategy's replica count and cooldown so a fresh run starts clean. */
    public void reset() {
        states.clear();
    }

    /**
     * Closed-loop entry point: decide for one strategy, seeding its replica count
     * from the real (measured) count so the engine's view can't drift from what's
     * actually running. Cooldown state is preserved across calls.
     */
    public ScalingDecision decideWithActualReplicas(Strategy strategy, String serviceId, Instant now,
                                                    double currentLoad, double predictedLoad,
                                                    int actualReplicas) {
        if (strategy == Strategy.BASELINE) {
            return decideBaseline(serviceId, now);
        }
        StrategyState state = stateFor(serviceId, strategy);
        state.replicas = actualReplicas;
        return decide(serviceId, now, currentLoad, predictedLoad, state, strategy);
    }

    /**
     * Closed-loop comparison: run a strategy in shadow on its own evolving replica
     * state (never touches real containers). Used for the baseline/reactive lines
     * while predictive drives the real pool.
     */
    public ScalingDecision decideShadow(Strategy strategy, String serviceId, Instant now,
                                        double currentLoad, double predictedLoad) {
        if (strategy == Strategy.BASELINE) {
            return decideBaseline(serviceId, now);
        }
        return decide(serviceId, now, currentLoad, predictedLoad, stateFor(serviceId, strategy), strategy);
    }

    /** Mutable per-strategy scaling state. Package-visible for direct unit testing. */
    static final class StrategyState {
        int replicas;
        Instant lastActionAt;

        StrategyState(int replicas, Instant lastActionAt) {
            this.replicas = replicas;
            this.lastActionAt = lastActionAt;
        }
    }

    private record StateKey(String serviceId, Strategy strategy) {
    }

}