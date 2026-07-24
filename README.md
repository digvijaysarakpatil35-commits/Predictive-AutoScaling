# Predictive Auto-Scaling Engine

Standard autoscalers are **reactive**: they add servers *after* load crosses a
threshold, so users already felt the slowdown. This engine is **predictive**: it
forecasts load a few minutes ahead and scales *before* the spike hits.

But the engine isn't the point — the **measured comparison** is. The whole system
replays one fixed traffic trace through three strategies side by side and produces
real numbers proving predictive beats reactive, plus a live dashboard that shows
it happening.

> A thermostat *reacts* — heat comes on once the room is already cold. A weather
> forecast *anticipates* — you warm the house before the cold front arrives. This
> is the forecast version of server scaling.

---

## The result

One 180-interval trace (30 minutes of simulated traffic: three recurring load
cycles plus one sharp, unpredictable spike), replayed through all three
strategies. Numbers below are from a run on the default configuration and are
reproducible (`./mvnw spring-boot:run`, then read the table logged at the end or
`GET /api/comparison`).

| Strategy       | p95 latency | p99 latency | avg latency | Container-seconds (cost) | Under-provisioned | Over-provisioned |
|----------------|-------------|-------------|-------------|--------------------------|-------------------|------------------|
| **Baseline** (fixed 4) | 2000 ms | 2000 ms | 328 ms | 7,200 | 62 | 43 |
| **Reactive**   | 253 ms      | 2000 ms     | 105 ms      | 8,390                    | 17                | 3                |
| **Predictive** | **89 ms**   | 2000 ms     | **70 ms**   | 8,780                    | **6**             | 4                |

**Forecast accuracy** (measured independently of scaling — "is the model good?",
separate from "did it help?"):

| Forecaster            | MAPE      | RMSE   | samples |
|-----------------------|-----------|--------|---------|
| Exponential smoothing | 17.7 %    | 30.0   | 179     |
| Linear regression     | **11.7 %**| **23.8** | 167   |

### What the numbers say

- **Predictive cut p95 latency ~65 % vs reactive** (253 ms → 89 ms) for only
  **~4.6 % more cost** (8,390 → 8,780 container-seconds). Nearly the same spend,
  dramatically better tail latency.
- **Predictive cut under-provisioned intervals ~65 %** (17 → 6) — far fewer
  moments where users felt slowness.
- **Regression forecasts ~34 % more accurately than smoothing** (MAPE 11.7 % vs
  17.7 %), independent of the scaling outcome.
- **p99 is 2000 ms for everyone** — that's the one-off spike. It isn't part of the
  recurring cycle, so *no* forecaster can anticipate it; all three saturate for a
  moment. Predictive wins on the **predictable** load (p95), not on random shocks.
  Reporting p95 *and* p99 is what makes that distinction visible.

---

## How it works

Every interval (10 s of simulated time) the system runs one pass of a pipeline:

```
TrafficReplayService          reads the next row of the CSV trace
        │
        ▼
MetricsCollectorService       appends it to a per-service ring buffer
        │  (publishes MetricRecordedEvent)
        ▼
ScalingOrchestrator           the per-interval conductor:
        ├── both forecasters read the buffer → predictions
        ├── ScalingDecisionEngine evaluates ALL THREE strategies (shadow mode)
        ├── records a timeline row (load + replicas + forecasts)
        ├── DockerScalingClient scales real containers (chosen strategy only, optional)
        └── broadcasts the row to dashboards over WebSocket
        ▼
On replay completion → ComparisonService computes the §"result" metrics
```

### Shadow mode — one trace, three decision logs

The three strategies do **not** run three live scaling loops fighting over the
same containers. Each keeps its *own* replica count and computes what it *would*
do each interval, tagged with its strategy. Only the chosen strategy ever touches
real infrastructure. This removes a whole class of race conditions and keeps the
comparison apples-to-apples: all three saw the exact same input.

### The three strategies

- **Baseline** — never scales; a fixed replica count. The control group. Without
  it, "reactive vs predictive" is two numbers with nothing to anchor them.
- **Reactive** — scales on the **current** load only. The industry-standard behaviour.
- **Predictive** — scales on the **forecast**, with a deliberately asymmetric rule:
  - **Scale up** on whichever is higher, forecast *or* current — so it gets ahead
    of a recurring peak (forecast leads) yet is never blinder than reactive to a
    surprise spike (current still counts).
  - **Scale down** only when the forecast *and* the current reading both agree load
    is low. A bad up-forecast wastes a little money; a bad down-forecast causes
    real latency pain, so down needs confirmation.

Reactive turns out to be exactly predictive with the forecast pinned to the
current value, so both run through one code path.

### The two forecasters

- **Exponential smoothing** — a weighted running average. Simple, no training,
  instant. Its blind spot: it only knows "where load is now, smoothed," so it
  can't anticipate a recurring peak before the climb starts. It's the honest
  baseline forecaster.
- **Linear regression on lagged + time-of-day features** — the upgrade.
  Implemented from scratch (ordinary least squares via the normal equations with
  a small ridge term, solved with Gaussian elimination — no library `.fit()`).
  It's fed the last *K* readings **plus** the `sin`/`cos` of the current cycle
  position. That time-of-day signal is what lets it recognise "we're at the part
  of the cycle where load always climbs" and scale up *before* it does.

Why not ARIMA or an LSTM? The data is a short synthetic series with little
history and a need for real-time updates. Regression trains in milliseconds,
needs almost no data, and is explainable in a sentence. ARIMA is fiddlier and
harder to explain; an LSTM is the wrong tool at this data scale. "The data didn't
justify the complexity" is itself a design signal.

---

## Metrics, explained

- **Latency** is modelled from utilization with an M/M/1-style curve,
  `latency = base / (1 − utilization)`: flat while there's headroom, climbing
  steeply as load approaches capacity. That's why an average misleads and we
  report **p95 / p99** — most intervals are fast; the rare slow ones (tail latency)
  are what users remember.
- **Container-seconds** = replicas × seconds, summed over the run. A proxy for
  cost — it lets us say "better latency *at what price*."
- **Under- / over-provisioned intervals** count how often each strategy sat
  outside the healthy utilization band (too hot → under; too cold → over).
- **Forecast error (MAPE / RMSE)** is computed by matching each prediction to the
  actual load at the time it was forecasting *for* — reported separately from
  scaling so "is the forecaster accurate?" is isolated from "did accuracy help?"

---

## Running it

Requires a JDK (17+). No database, no internet, no Docker needed — everything is
in-memory and self-contained.

```bash
# real time: one interval every 10s (full run = 30 min)
./mvnw spring-boot:run

# faster replay for a demo (full run ≈ 4.5 min; trace-time logic is unaffected)
./mvnw spring-boot:run -Dspring-boot.run.arguments="--autoscale.replay.emit-delay-millis=1500"
```

Then open **http://localhost:8080** for the live dashboard:

- **Top chart** — predicted vs. actual load: actual (solid), regression (dashed),
  smoothing (dotted). Each forecast is drawn at the time it was predicting *for*,
  so you read it against the value that arrived.
- **Bottom chart** — replicas per strategy: baseline flat, reactive following
  after the fact, predictive (dots mark scaling actions) getting ahead of the peaks.
- **Restart run** button — replays from scratch without restarting the app.

The dashboard opens correctly even mid-run or after completion — a fresh client is
replayed the full timeline on connect.

### Endpoints

| Method | Path              | Purpose                                             |
|--------|-------------------|-----------------------------------------------------|
| GET    | `/`               | the dashboard                                       |
| GET    | `/api/timeline`   | every interval's load, replicas, and forecasts      |
| GET    | `/api/comparison` | the computed comparison (the results table as JSON) |
| POST   | `/api/reset`      | wipe state, rewind the trace, replay from scratch   |
| WS     | `/ws/dashboard`   | live per-interval stream                            |

### Real container scaling (optional)

Off by default — the comparison needs no Docker. To have the chosen strategy drive
real containers, set `autoscale.docker.enabled: true`; it shells out to
`docker compose --scale` against `docker-compose.yml` (a scalable dummy workload).

### Regenerating the trace

The trace is checked in at `traffic-traces/sample-load.csv`. To regenerate it
(sine + noise, three cycles, one spike):

```bash
./mvnw -q compile && java -cp target/classes com.digvijay.autoscale.tools.TraceGenerator
```

---

## Closed-loop mode — real traffic, measured CPU

Everything above is a **simulation**: the load is a number from the CSV and latency
is *modelled*. Closed-loop mode closes the gap — a load generator fires the trace's
demand at **real CPU-limited containers behind a load balancer**, the app **reads
real CPU back** from them via `docker stats`, forecasts it, and scales. It's a
genuine self-correcting loop on one machine, activated with the `closed-loop`
Spring profile.

```
load generator ──HTTP──▶ nginx LB ──▶ N CPU-limited workload containers
                                              │  docker stats (real CPU)
                                              ▼
                    collector ─▶ forecast ─▶ engine ─▶ docker compose --scale
```

### Load generation — how the CSV is used

Both modes read the **same** `sample-load.csv`; what differs is how it's consumed,
which is why the two modes' numbers don't line up. In the simulation the CSV's
`requestRate` is fed **directly** into the engine as the load. In closed-loop the
CSV row is instead the **demand to physically generate**, and it runs through a
real pipeline before the engine sees anything:

```
CSV requestRate = 129
   × demand-scale (0.9)             = 116 req/s      # scaled to what one box can serve
   × load-window-millis (1.8 s)     ≈ 209 requests   # LoadGeneratorService.requestsForInterval
   │
   ├─▶ fire 209 real HTTP GETs at the nginx LB, paced across the window
   │        each → a workload container → 120× sha256 of a 256 KB buffer
   │        → measure the real p95 latency of the responses
   │
   └─▶ read real CPU back (docker stats, avg across replicas, e.g. 49%)
            L = (49 / (0.5 × 100)) × replicas          # the signal the engine sees
```

So the engine's "load" in closed-loop is `L` (a utilization×replicas value, ~1–4),
**not** the raw CSV number (req/s, 0–440) — the CSV only decides how much real
traffic to fire. Consequences: the demand is scaled down (`demand-scale`), latency
is **measured** (real scale-up transients, not the smooth model), and the signal is
noisier. That's why absolute latencies, container-seconds, and forecast RMSE differ
from the simulation (RMSE isn't even the same units); MAPE, being a percentage,
stays comparable. The gap is the point — it shows reality runs hotter and messier
than any model.

**The load signal.** Raw CPU% depends on how many replicas you're running, so it's
normalized to `L = averageCPU% × replicas` (total work, ∝ demand). Feeding `L` in
as the load, the *unchanged* engine behaves exactly like a Kubernetes HPA
(`ceil(replicas × currentCPU / targetCPU)`) — see `application-closed-loop.yml`.

**Single strategy, real + shadow.** Only **predictive** drives real containers
(three strategies can't share one pool). Baseline and reactive run in **shadow** on
the same measured load, so the dashboard still shows all three replica lines and
both forecasts.

### Running it

Requires Docker (e.g. `brew install colima docker`). The VM needs enough CPU — the
default 2 vCPUs would starve it:

```bash
colima start --cpu 8 --memory 8
docker compose -f docker-compose.closedloop.yml up -d --build --scale workload=2
./mvnw spring-boot:run -Dspring-boot.run.profiles=closed-loop
```

Open `http://localhost:8080` and watch real CPU track the load while predictive
pre-scales. It runs **near real-time** (~2 s/interval, full run ≈ 6 min): real HTTP
and `docker stats` (~1 s per CPU sample) can't be fast-forwarded like the CSV
simulation. Tear down with `docker compose -f docker-compose.closedloop.yml down`
and `colima stop`.

### Measured results

A full 180-interval closed-loop run (`demand-scale: 0.9`, load tuned to force
scaling), on a 10-core machine with `cpus: "0.5"` per replica:

| Strategy | p95 latency † | p99 latency † | Container-sec | Under-prov. | Over-prov. |
|----------|---------------|---------------|---------------|-------------|------------|
| Baseline (fixed 4) | 158 ms | 2000 ms | 7,200 | 25 | 52 |
| Reactive   | 84 ms   | 190 ms  | 7,250 | 3 | 2 |
| Predictive | **65 ms** | **144 ms** | 7,390 | **2** | 3 |

† modelled (M/M/1), apples-to-apples across strategies. **Measured** latency for the
real predictive run: p95 **185 ms**, p99 **1045 ms**, avg 54 ms.

| Forecaster (on the measured signal) | MAPE | RMSE |
|-------------------------------------|------|------|
| Exponential smoothing | 16.7 % | 0.52 |
| Linear regression     | **13.7 %** | 0.68 |

**What it shows — and what it honestly reveals:**

- **Predictive beats reactive on real infrastructure:** ~23 % lower p95 (65 vs
  84 ms) for ~2 % more cost. It got *ahead* of reactive in **19 intervals** (and
  behind in only 7) by pre-scaling the recurring peaks once regression had learned
  the cycle.
- **Baseline is wrong in both directions:** under-provisioned at 25 peaks (p99
  pegged at 2000 ms) *and* over-provisioned at 52 troughs.
- **Measured ≫ modelled at the tail:** predictive's modelled p95 is 65 ms but the
  *measured* p95 was 185 ms and p99 hit 1045 ms. Real systems spike during scale-up
  transients (a fresh replica idles while others queue) — the smooth model can't see
  this. Exposing that gap is exactly what a closed loop adds over a simulation.
- **Regression vs smoothing on noisy data:** regression wins MAPE (13.7 % vs
  16.7 %) but smoothing edges RMSE — on real, noisy CPU the two are closer than on
  the clean synthetic trace, and regression's occasional larger misses cost it on
  RMSE.

> ⚠️ The numbers above come from a *single* run where reactive was computed in
> *shadow* on the load predictive produced — convenient, but not a clean
> comparison. The rigorous version is below.

### Rigorous comparison: 5 independent pairs

The clean way to compare is to run the trace **once per driving strategy** — each
strategy drives its *own* real containers (set `autoscale.docker.driving-strategy`)
— so neither reacts to the other's load. Repeated **5 times** (alternating order) at
`demand-scale: 1.2`, `horizon-intervals: 3`, comparing **real measured** latency:

| Pair | Predictive p95 / p99 | Reactive p95 / p99 | p95 win | p99 win |
|------|----------------------|--------------------|---------|---------|
| 1 | 380 / 1062 | 420 / 1070 | P | P |
| 2 | 379 / 1161 | 462 / 1842 | P | P |
| 3 | 343 / 676  | 444 / 2055 | P | P |
| 4 | **958** / 1725 | 431 / 2201 | R | P |
| 5 | 553 / 1790 | 650 / 2007 | P | P |
| **median** | **380 / 1161** | **444 / 2007** | **P (4/5)** | **P (5/5)** |

**The honest conclusion (this is the defensible one):**

- **Predictive reliably tames the worst-case tail:** it won **p99 in all 5 pairs**
  (median 1161 vs 2007 ms, ~42 % lower). Anticipation's clearest, most consistent
  payoff is preventing severe saturation.
- **It usually wins p95 (4/5), median ~14 % lower** — but with **higher variance**.
  Pair 4 spiked to 958 ms, dragging predictive's *mean* p95 slightly above
  reactive's even though the median favors predictive.
- **No difference under normal load** (p50/avg tied); predictive costs **~6 % more**
  container-seconds. The entire advantage is in the tail.
- **Why the single run overstated it:** it happened to be a good-for-predictive
  draw (32 % gap). Repeating revealed the truth — *usually* better, *reliably* better
  at the extreme tail, but noisier and not a guaranteed per-run win. Repeating
  experiments is what turns a cherry-picked number into an honest claim.

**What went wrong in pair 4 (the outlier):** it was **not** under-provisioning —
predictive had *more* replicas than reactive and low CPU (18–26 %) at its worst
moments. The cause was **scaling churn**: a noisy over-forecast (regression predicted
`L=10.25` when actual was `3.65`) slammed it to max replicas, and every scale-up
**boots a fresh container** that the load balancer briefly routes to before it's warm
→ requests queue → latency spikes *despite* ample capacity. The fixes are known:
**clamp/confidence-gate the forecast** (don't act on wild predictions) and a
**readiness grace** (don't send traffic to a booting container — exactly what a real
Kubernetes HPA does).

---

## Configuration

Every tunable lives in `src/main/resources/application.yml`, so numbers can be
retuned during a demo without recompiling. Highlights:

| Key                                     | Default | Meaning                                              |
|-----------------------------------------|---------|------------------------------------------------------|
| `autoscale.replay.interval-seconds`     | 10      | logical (trace-time) spacing; drives forecast/cooldown |
| `autoscale.replay.emit-delay-millis`    | 10000   | wall-clock replay speed (decoupled from the above)   |
| `autoscale.forecast.exp-smoothing-alpha`| 0.4     | smoothing weight on the latest reading               |
| `autoscale.forecast.lookback-k`         | 5       | lagged readings fed to the regression                |
| `autoscale.forecast.cycle-intervals`    | 60      | period of the recurring pattern (the sin/cos clock)  |
| `autoscale.scaling.scale-up-threshold`  | 0.8     | utilization above which we scale up                  |
| `autoscale.scaling.scale-down-threshold`| 0.3     | utilization below which we scale down                |
| `autoscale.scaling.cooldown-seconds`    | 60      | quiet period after acting (anti-flapping)            |
| `autoscale.baseline.fixed-replicas`     | 4       | baseline's constant replica count                    |

**Trace-time vs wall-clock:** `interval-seconds` is the story's clock (the CSV rows
are 10 s apart) and drives all the logic — forecast horizon, cycle position,
cooldown. `emit-delay-millis` is just playback speed. Fast-forwarding the demo
changes how quickly rows are read, not what a row *means*.

---

## Project structure

```
src/main/java/com/digvijay/autoscale/
├── AutoscaleApplication.java
├── collector/   MetricSnapshot, RingBuffer, MetricsCollectorService, MetricRecordedEvent
├── forecast/    ForecastingService, ExponentialSmoothingForecaster, LinearRegressionForecaster
├── scaling/     ScalingDecisionEngine (3 strategies), ScalingOrchestrator,
│                DecisionLogStore, DockerScalingClient, ScalingDecision/Action, Strategy
├── replay/      TraceSource, TrafficReplayService, ReplayCompletedEvent
├── metrics/     LatencyModel, ComparisonService, ComparisonReport, ComparisonReporter,
│                RunTimelineStore, IntervalRecord
├── closedloop/  ClosedLoopDriver, LoadGeneratorService, DockerStatsService  (closed-loop profile)
├── api/         MetricsController, RunController, DashboardWebSocketHandler, WebSocketConfig
└── tools/       TraceGenerator
src/main/resources/
├── application.yml              # simulation tunables
├── application-closed-loop.yml  # closed-loop overrides (real containers)
└── static/index.html            # the dashboard (self-contained, canvas charts)
workload/                    # CPU-bound workload app + Dockerfile (closed-loop)
traffic-traces/sample-load.csv
docker-compose.yml               # simple scalable workload (simulation drive)
docker-compose.closedloop.yml    # workload + nginx LB (closed-loop)
nginx.conf                       # LB with dynamic upstream
```

## Testing

32 unit tests cover the pure logic — the interview-defensible core:

- `RingBuffer` — fixed capacity, oldest evicted on overflow.
- Both forecasters — known sequences; the headline test proves regression
  anticipates a sinusoid's peak closer than smoothing lags it.
- `ScalingDecisionEngine` — each strategy, the dead-band, cooldown expiry, and
  both halves of the asymmetric rule (up on forecast, refuse down when the
  forecast disagrees).
- `DockerStatsService` / `LoadGeneratorService` — CPU-average parsing and the
  request-rate / percentile maths for the closed-loop path.
- `LatencyModel` and `ComparisonService` — metric maths verified against
  hand-computed values.

```bash
./mvnw test
```

---

## Design decisions & production tradeoffs

Stating these honestly is the point — it's the difference between a demo and
knowing where a demo ends.

- **In-memory only.** Ring buffers and lists, no database — it doesn't survive a
  restart. Production would use a time-series store (Prometheus / InfluxDB). This
  was a deliberate scope choice, not an oversight.
- **A simple model, on purpose.** Considered ARIMA and LSTM; chose regression for
  explainability and real-time updates with little data.
- **Cooldown over an idempotency flag** for anti-flapping — a few lines that
  handle the noisy-load case well enough.
- **Asymmetric scaling** reflects a real asymmetry in cost: over-provisioning
  wastes money, under-provisioning hurts users.
- **No distributed scaling races** — shadow mode sidesteps them for the comparison;
  a real multi-node controller would need to handle them.
- **Synthetic trace.** A clean, repeatable benchmark. Real traffic is messier;
  the recurring-cycle assumption that makes regression shine wouldn't always hold —
  which is exactly why the honest forecast-error and p99 numbers matter.
- **Simulation vs. reality.** The comparison is modelled, on purpose (it isolates
  the *decision quality*). The [closed-loop mode](#closed-loop-mode--real-traffic-measured-cpu)
  runs it on real containers and confirms the finding — while honestly showing that
  measured tail latency runs far hotter than the model, and that predictive's edge
  depends on load actually crossing the scaling thresholds. Even the closed loop is
  single-host and single-strategy; a true production system spans nodes (see the
  Kubernetes/HPA mapping the closed-loop mirrors).