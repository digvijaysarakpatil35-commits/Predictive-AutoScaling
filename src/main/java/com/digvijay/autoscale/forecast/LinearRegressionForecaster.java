package com.digvijay.autoscale.forecast;

import com.digvijay.autoscale.collector.MetricSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Multiple linear regression fit from scratch (ordinary least squares via the
 * normal equations) over two kinds of features:
 *
 * <ul>
 *   <li><b>Lagged readings</b> — the last {@code K} requestRate values, so the
 *       model can follow short-term momentum.</li>
 *   <li><b>Time-of-day</b> — {@code sin} and {@code cos} of the target time's
 *       position within the recurring load cycle. This is the piece that lets
 *       regression <em>anticipate</em> a recurring peak before the climb starts,
 *       which plain exponential smoothing cannot.</li>
 * </ul>
 *
 * <p>Each interval we rebuild the (features &rarr; target) training set straight
 * from the ring-buffer history, solve
 * {@code (XᵀX + λI) w = Xᵀy} with a small ridge term {@code λ} for numerical
 * stability, then apply the weights to the most recent features to forecast
 * {@code horizon} intervals ahead. No library {@code .fit()} call — the math is
 * all here so it's genuinely understood.
 */
@Component
public class LinearRegressionForecaster implements ForecastingService {

    private final int lookbackK;
    private final int horizonIntervals;
    private final long intervalSeconds;
    private final int cycleIntervals;
    private final double ridgeLambda;

    public LinearRegressionForecaster(
            @Value("${autoscale.forecast.lookback-k}") int lookbackK,
            @Value("${autoscale.forecast.horizon-intervals}") int horizonIntervals,
            @Value("${autoscale.replay.interval-seconds}") long intervalSeconds,
            @Value("${autoscale.forecast.cycle-intervals}") int cycleIntervals,
            @Value("${autoscale.forecast.ridge-lambda}") double ridgeLambda) {
        this.lookbackK = lookbackK;
        this.horizonIntervals = horizonIntervals;
        this.intervalSeconds = intervalSeconds;
        this.cycleIntervals = cycleIntervals;
        this.ridgeLambda = ridgeLambda;
    }

    @Override
    public Optional<Prediction> predict(String serviceId, List<MetricSnapshot> history) {
        int n = history.size();
        int dimension = lookbackK + 3; // intercept + K lags + sin + cos

        // A training sample exists for each index t with K lags behind it and a
        // target horizon ahead: t in [K-1, n-1-horizon]. Need at least as many
        // samples as features for a well-posed fit.
        int sampleCount = n - horizonIntervals - lookbackK + 1;
        if (sampleCount < dimension) {
            return Optional.empty();
        }

        double[] values = new double[n];
        long[] epochSeconds = new long[n];
        for (int i = 0; i < n; i++) {
            values[i] = history.get(i).requestRate();
            epochSeconds[i] = history.get(i).timestamp().getEpochSecond();
        }

        // Accumulate the normal equations A = XᵀX, b = Xᵀy.
        double[][] a = new double[dimension][dimension];
        double[] b = new double[dimension];
        for (int t = lookbackK - 1; t <= n - 1 - horizonIntervals; t++) {
            long targetEpoch = epochSeconds[t] + intervalSeconds * horizonIntervals;
            double[] x = features(values, t, targetEpoch);
            double target = values[t + horizonIntervals];
            for (int r = 0; r < dimension; r++) {
                for (int c = 0; c < dimension; c++) {
                    a[r][c] += x[r] * x[c];
                }
                b[r] += x[r] * target;
            }
        }
        for (int i = 0; i < dimension; i++) {
            a[i][i] += ridgeLambda;
        }

        double[] weights;
        try {
            weights = solve(a, b);
        } catch (ArithmeticException singular) {
            return Optional.empty();
        }

        // Forecast from the most recent features, one horizon past the last reading.
        long targetEpoch = epochSeconds[n - 1] + intervalSeconds * horizonIntervals;
        double[] xPredict = features(values, n - 1, targetEpoch);
        double predicted = 0.0;
        for (int i = 0; i < dimension; i++) {
            predicted += weights[i] * xPredict[i];
        }
        predicted = Math.max(0.0, predicted); // load can't be negative

        Instant forecastTimestamp = history.get(n - 1).timestamp()
                .plusSeconds(intervalSeconds * horizonIntervals);
        return Optional.of(new Prediction(serviceId, forecastTimestamp, predicted, method()));
    }

    private double[] features(double[] values, int t, long targetEpochSecond) {
        double[] x = new double[lookbackK + 3];
        x[0] = 1.0; // intercept
        for (int j = 0; j < lookbackK; j++) {
            x[1 + j] = values[t - j];
        }
        long intervalIndex = Math.floorDiv(targetEpochSecond, intervalSeconds);
        double phase = Math.floorMod(intervalIndex, (long) cycleIntervals) / (double) cycleIntervals;
        double angle = 2 * Math.PI * phase;
        x[lookbackK + 1] = Math.sin(angle);
        x[lookbackK + 2] = Math.cos(angle);
        return x;
    }

    /** Gaussian elimination with partial pivoting for the (small) D×D system. */
    private static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            double[] tmpRow = a[col];
            a[col] = a[pivot];
            a[pivot] = tmpRow;
            double tmpB = b[col];
            b[col] = b[pivot];
            b[pivot] = tmpB;

            double diagonal = a[col][col];
            if (Math.abs(diagonal) < 1e-12) {
                throw new ArithmeticException("singular matrix");
            }
            for (int r = col + 1; r < n; r++) {
                double factor = a[r][col] / diagonal;
                for (int c = col; c < n; c++) {
                    a[r][c] -= factor * a[col][c];
                }
                b[r] -= factor * b[col];
            }
        }

        double[] w = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            double sum = b[row];
            for (int c = row + 1; c < n; c++) {
                sum -= a[row][c] * w[c];
            }
            w[row] = sum / a[row][row];
        }
        return w;
    }

    @Override
    public ForecastMethod method() {
        return ForecastMethod.LINEAR_REGRESSION;
    }

}