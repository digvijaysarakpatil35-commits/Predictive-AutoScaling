package com.digvijay.autoscale.scaling;

import com.digvijay.autoscale.collector.MetricSnapshot;
import com.digvijay.autoscale.forecast.ForecastMethod;
import com.digvijay.autoscale.forecast.ForecastingService;
import com.digvijay.autoscale.forecast.Prediction;
import com.digvijay.autoscale.scaling.ScalingDecisionEngine.StrategyState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ScalingDecisionEngineTest {

    private static final String SERVICE_ID = "web-service";
    private static final Instant NOW = Instant.parse("2026-07-20T00:10:00Z");

    // load-per-replica=50, up=0.8, down=0.3, target=0.6, min=1, max=10, cooldown=60s, fixed=4
    private ScalingDecisionEngine engineWith(ForecastingService forecaster) {
        return new ScalingDecisionEngine(forecaster, 50.0, 0.8, 0.3, 0.6, 1, 10, 60, 4);
    }

    private ScalingDecisionEngine engine() {
        return engineWith(fixedForecast(0.0));
    }

    @Test
    void baselineNeverScales() {
        ScalingDecision decision = engine().decideBaseline(SERVICE_ID, NOW);

        assertThat(decision.action()).isEqualTo(ScalingAction.NONE);
        assertThat(decision.replicasBefore()).isEqualTo(4);
        assertThat(decision.replicasAfter()).isEqualTo(4);
        assertThat(decision.strategy()).isEqualTo(Strategy.BASELINE);
    }

    @Test
    void reactiveScalesUpWhenCurrentLoadIsHigh() {
        var state = new StrategyState(4, null);
        // util = 500 / (4*50) = 2.5 > 0.8 -> required = ceil(500/30) = 17 -> clamp 10
        ScalingDecision decision = engine().decide(SERVICE_ID, NOW, 500.0, 500.0, state, Strategy.REACTIVE);

        assertThat(decision.action()).isEqualTo(ScalingAction.SCALE_UP);
        assertThat(decision.replicasBefore()).isEqualTo(4);
        assertThat(decision.replicasAfter()).isEqualTo(10);
        assertThat(state.replicas).isEqualTo(10);
        assertThat(state.lastActionAt).isEqualTo(NOW);
    }

    @Test
    void reactiveScalesDownWhenCurrentLoadIsLow() {
        var state = new StrategyState(8, null);
        // util = 40 / (8*50) = 0.1 < 0.3 -> required = ceil(40/30) = 2
        ScalingDecision decision = engine().decide(SERVICE_ID, NOW, 40.0, 40.0, state, Strategy.REACTIVE);

        assertThat(decision.action()).isEqualTo(ScalingAction.SCALE_DOWN);
        assertThat(decision.replicasAfter()).isEqualTo(2);
    }

    @Test
    void holdsWithinTheDeadBand() {
        var state = new StrategyState(4, null);
        // util = 120 / 200 = 0.6, inside [0.3, 0.8] -> no action
        ScalingDecision decision = engine().decide(SERVICE_ID, NOW, 120.0, 120.0, state, Strategy.REACTIVE);

        assertThat(decision.action()).isEqualTo(ScalingAction.NONE);
        assertThat(state.replicas).isEqualTo(4);
        assertThat(state.lastActionAt).isNull();
    }

    @Test
    void cooldownSuppressesActionThenExpires() {
        var engine = engine();

        // Acted 30s ago; cooldown is 60s -> still cooling down -> no action.
        var cooling = new StrategyState(4, NOW.minusSeconds(30));
        ScalingDecision blocked = engine.decide(SERVICE_ID, NOW, 500.0, 500.0, cooling, Strategy.REACTIVE);
        assertThat(blocked.action()).isEqualTo(ScalingAction.NONE);
        assertThat(cooling.replicas).isEqualTo(4);

        // Acted 70s ago -> cooldown expired -> acts.
        var ready = new StrategyState(4, NOW.minusSeconds(70));
        ScalingDecision allowed = engine.decide(SERVICE_ID, NOW, 500.0, 500.0, ready, Strategy.REACTIVE);
        assertThat(allowed.action()).isEqualTo(ScalingAction.SCALE_UP);
    }

    @Test
    void predictiveScalesUpOnForecastWhileCurrentIsStillCalm() {
        var state = new StrategyState(4, null);
        // Current util = 120/200 = 0.6 (calm, dead-band), but forecast = 500 -> anticipate the peak.
        ScalingDecision decision = engine().decide(SERVICE_ID, NOW, 120.0, 500.0, state, Strategy.PREDICTIVE);

        assertThat(decision.action()).isEqualTo(ScalingAction.SCALE_UP);
        assertThat(decision.replicasAfter()).isEqualTo(10);
    }

    @Test
    void predictiveRefusesToScaleDownWhenForecastSaysLoadIsReturning() {
        var predictiveState = new StrategyState(8, null);
        // Current util = 40/400 = 0.1 (< 0.3) but forecast = 300 (util 0.75, not low).
        ScalingDecision predictive = engine()
                .decide(SERVICE_ID, NOW, 40.0, 300.0, predictiveState, Strategy.PREDICTIVE);
        assertThat(predictive.action()).isEqualTo(ScalingAction.NONE);
        assertThat(predictiveState.replicas).isEqualTo(8);

        // Reactive, seeing only the low current load, would have scaled down.
        var reactiveState = new StrategyState(8, null);
        ScalingDecision reactive = engine()
                .decide(SERVICE_ID, NOW, 40.0, 40.0, reactiveState, Strategy.REACTIVE);
        assertThat(reactive.action()).isEqualTo(ScalingAction.SCALE_DOWN);
    }

    @Test
    void predictiveStillCatchesASpikeTheForecastMissed() {
        var state = new StrategyState(4, null);
        // Current spikes to 500 (util 2.5) even though forecast stayed calm at 120.
        ScalingDecision decision = engine().decide(SERVICE_ID, NOW, 500.0, 120.0, state, Strategy.PREDICTIVE);

        assertThat(decision.action()).isEqualTo(ScalingAction.SCALE_UP);
        assertThat(decision.replicasAfter()).isEqualTo(10);
    }

    @Test
    void evaluateReturnsOneDecisionPerStrategy() {
        // Forecast a high load so predictive diverges from baseline; history is
        // irrelevant here because the stub ignores it.
        var engine = engineWith(fixedForecast(500.0));
        MetricSnapshot current = new MetricSnapshot(SERVICE_ID, NOW, 0.0, 0.0, 120.0, 4);

        List<ScalingDecision> decisions = engine.evaluate(current, List.of(current));

        assertThat(decisions).extracting(ScalingDecision::strategy)
                .containsExactly(Strategy.BASELINE, Strategy.REACTIVE, Strategy.PREDICTIVE);
        assertThat(decisions.get(0).action()).isEqualTo(ScalingAction.NONE);       // baseline
        assertThat(decisions.get(1).action()).isEqualTo(ScalingAction.NONE);       // reactive: current calm
        assertThat(decisions.get(2).action()).isEqualTo(ScalingAction.SCALE_UP);   // predictive: forecast hot
    }

    private static ForecastingService fixedForecast(double predictedLoad) {
        return new ForecastingService() {
            @Override
            public Optional<Prediction> predict(String serviceId, List<MetricSnapshot> history) {
                return Optional.of(new Prediction(serviceId, NOW, predictedLoad, method()));
            }

            @Override
            public ForecastMethod method() {
                return ForecastMethod.LINEAR_REGRESSION;
            }
        };
    }

}