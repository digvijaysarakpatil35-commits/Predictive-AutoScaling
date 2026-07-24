# Predictive Auto-Scaling Engine — Project Spec

This file is the source of truth for the project. Read it at the start of every
working session before writing or changing code. It describes *what* we're
building and *why* the key decisions were made — it deliberately leaves room to
explore *how*, so treat structure and naming as suggestions unless stated as a
constraint.

---

## 1. The one-line pitch

Standard autoscalers are **reactive**: they add servers *after* load crosses a
threshold, so users already felt the slowdown. This engine is **predictive**: it
forecasts load a few minutes ahead and scales *before* the spike hits. The
deliverable isn't just the engine — it's a **measured comparison** proving
predictive beats reactive on the same traffic.

Analogy to keep the intent clear: a thermostat *reacts* (heat turns on once the
room is cold); a weather forecast *anticipates* (warm the house before the cold
front arrives). We're building the forecast version of server scaling.

---

## 2. What "done" looks like

By the end, running the app should:
1. Replay one fixed synthetic traffic trace.
2. Run **three strategies** against that identical trace: no-scaling baseline,
   reactive, predictive.
3. Produce a **comparison** with real numbers (latency, cost proxy, forecast
   error) and a **live dashboard** visualizing it.

The comparison chart with actual numbers is the thing that makes this
interview-defensible — more than any individual piece of code. Optimize for
getting to a clean, quantified result.

---

## 3. Core design decisions (the important part)

These are the decisions that make this version better than a naive build. Keep
them unless there's a concrete reason to change; if you do change one, note why.

### 3.1 Three strategies, not two
Reactive-vs-predictive alone gives two numbers with nothing to anchor them. Add a
**no-scaling baseline** (fixed replica count — literally "never scale"). It's the
control group that makes the other two numbers meaningful. Nearly free to add.

### 3.2 Shadow mode — one trace, three decision logs
Do **not** run three live scaling loops fighting over the same containers. Each
strategy computes what it *would* do each interval and logs a `ScalingDecision`.
Only the chosen strategy actually calls Docker (and for the comparison itself we
don't even need real scaling). This removes a whole class of race-condition bugs
from the critical path and keeps the comparison apples-to-apples.

### 3.3 Two forecasters, and why linear regression
- **Exponential smoothing** — simple, no training, updates instantly. Good
  baseline forecaster. Weakness: only extrapolates the current trend; can't
  anticipate a turn it hasn't started climbing.
- **Linear regression on lagged + time-of-day features** — the "upgrade." Feed it
  the last K readings *plus* where we are in the cycle (sin/cos of cycle
  position) so it can anticipate a recurring peak *before* it starts. This is the
  change that gives regression a real reason to beat smoothing on smooth data.

Why this model and not something fancier: the data is a short synthetic series
with little training data and a need for real-time updates. Regression is
explainable in one sentence, trains in milliseconds, and needs almost no data.
ARIMA is academically "more correct" but fiddly and harder to explain — worth
*mentioning* as considered, not implementing. LSTM/deep learning is the wrong
tool here (needs lots of data, slow, black-box) — being able to say "the data
didn't justify the complexity" is itself a strong signal. **Implement the
regression from scratch in plain Java** (~30 lines) so it's genuinely understood,
not a library `.fit()` call.

### 3.4 Cooldown over idempotency
A scaling **cooldown** (don't act again for N seconds after acting) prevents
flapping on noisy traffic and is a ~5-line change. Prioritize it over the
idempotency/in-progress flag the original guide mentioned.

### 3.5 Asymmetric scaling policy
Scaling *up* on a bad forecast wastes money; scaling *down* on a bad forecast
causes real latency pain. So: **predictive triggers scale-up; require reactive
confirmation (current value, not forecast) before scale-down.** Also a good
tradeoffs talking point.

### 3.6 Metrics that survive scrutiny
Report, per strategy:
- **p95 / p99 latency** (not just average — averages hide the spikes autoscaling
  exists to fix). Model latency as a function of load-vs-capacity.
- **Container-seconds** used — a cost proxy. Lets you say "same latency, X% fewer
  resources."
- **Over- / under-provisioned interval counts.**
- **Forecast error (MAPE or RMSE)** reported *independently* of the scaling
  outcome — separates "is the forecaster good" from "did good forecasting help."

### 3.7 In-memory only for v1
Ring buffers and simple lists. No database — it's not the point and costs time.
Tradeoff to state in the README: doesn't survive restart; production would use a
time-series DB (InfluxDB/Prometheus).

---

## 4. Data model (starting shapes — adjust as needed)

```
MetricSnapshot   { serviceId, timestamp, cpuPercent, memoryMB, requestRate, replicaCount }
Prediction       { serviceId, forecastTimestamp, predictedLoad, method }   // EXP_SMOOTHING | LINEAR_REGRESSION
ScalingDecision  { serviceId, timestamp, action, replicasBefore, replicasAfter, strategy }
                 // action:   SCALE_UP | SCALE_DOWN | NONE
                 // strategy: BASELINE | REACTIVE | PREDICTIVE   <-- the field that enables shadow mode
```

---

## 5. Suggested structure (not binding)

```
autoscale-engine/
├── docker-compose.yml          # app + 2-3 dummy workload containers (add when wiring DockerScalingClient, not day 1)
├── application.yml             # ALL tunables: poll interval, alpha, lookback K, thresholds, cooldown seconds
├── README.md                   # architecture + comparison results with real numbers
├── traffic-traces/
│   └── sample-load.csv         # synthetic trace: peaks/troughs + one sharp spike
└── src/main/java/com/yourname/autoscale/
    ├── AutoscaleApplication.java
    ├── collector/   MetricsCollectorService.java, RingBuffer.java, MetricSnapshot.java
    ├── forecast/    ForecastingService.java (interface), ExponentialSmoothingForecaster.java, LinearRegressionForecaster.java
    ├── scaling/     ScalingDecisionEngine.java (all 3 strategies), DockerScalingClient.java, ScalingDecision.java
    ├── replay/      TrafficReplayService.java
    └── api/         MetricsController.java, DashboardWebSocketHandler.java
```

Config-driven: every magic number (interval, alpha, K, thresholds, cooldown)
lives in `application.yml` so it can be retuned live during a demo without
recompiling.

---

## 6. How it runs, each interval (~every 10s)

1. `TrafficReplayService` emits the next `MetricSnapshot` from the CSV on schedule.
2. Collector appends it to the per-service ring buffer.
3. Both forecasters read the buffer and produce a `Prediction`.
4. `ScalingDecisionEngine` evaluates **all three** strategies and logs a
   `ScalingDecision` for each (shadow mode).
5. Only the chosen strategy calls `DockerScalingClient` (optional for the
   comparison run).
6. Dashboard broadcasts snapshot + predictions + decisions over WebSocket —
   off the critical path.

At the end of a run, dump the three decision logs and compute the section-3.6
metrics.

---

## 7. Build order (three days)

**Day 1 — plumbing only (no intelligence).**
Scaffold Spring Boot (Web, WebSocket, Lombok). Package skeleton + empty stubs.
`application.yml` with tunables. Data-model classes (incl. `strategy` field).
`RingBuffer` + one unit test (push N+5, assert size stays N, oldest evicted).
Write a small generator for `sample-load.csv` (sine + noise, 2-3 clear cycles,
one sharp spike). `TrafficReplayService` reads CSV and emits snapshots on
schedule → collector → ring buffer → **log each snapshot**. Finish line: app
boots, replays on schedule, logs snapshots in order. Defer Docker to Day 2.

**Day 2 — the brains.**
`ExponentialSmoothingForecaster` first (verify predictions track the trace).
`LinearRegressionForecaster` with lagged + time-of-day features (from scratch,
plain Java), with unit tests on known sequences. `ScalingDecisionEngine` with all
three strategies + cooldown + asymmetric scale-down rule. Wire
`DockerScalingClient` + `docker-compose.yml` (real container scaling for the
chosen strategy).

**Day 3 — proof + polish.**
WebSocket dashboard: single page, live chart, **predicted vs actual overlaid**
with scaling-decision markers (this one visual sells the project). Run the
three-way comparison, capture the section-3.6 numbers. Write the README:
architecture, problem, comparison results with real numbers, design decisions
(why regression, why in-memory, why cooldown), and production tradeoffs.

---

## 8. Working principles for this repo

- Go **module by module** in the Day 1-3 order. One module per change, review the
  diff, then move on — keeps bugs isolated.
- **Test the pure logic** (ring buffer, both forecasters) with small unit tests.
  "I tested my forecasting logic" is a real interview line.
- Front-load the **CSV generator and forecasters** — pure logic, no Docker
  dependency — so forecasting quality can be validated visually before anything
  live is wired up.
- Keep all tunables in **config**, never hardcoded.
- Write the **README last**, with the *actual* numbers from the comparison run —
  no placeholder results.

---

## 9. Interview framing (keep this current as you build)

- **Problem:** reactive autoscalers act after load already hit users; this
  predicts ahead.
- **Approach:** exponential smoothing vs. linear regression on lagged +
  time-of-day features, evaluated on a replayed trace across three strategies.
- **Result:** quote real numbers — e.g. "predictive cut time-to-scale by X s" /
  "Y% fewer over-provisioned intervals for the same p95 latency."
- **Tradeoffs:** in-memory (no restart survival → time-series DB in prod); simple
  model by design (considered ARIMA/LSTM, chose regression for explainability and
  real-time updates with little data); cooldown handles flapping; no distributed
  scaling races yet.

Being able to state tradeoffs honestly is itself the signal — it shows you know
the difference between a demo and a production system.
