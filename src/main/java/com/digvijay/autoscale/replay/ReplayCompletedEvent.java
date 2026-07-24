package com.digvijay.autoscale.replay;

/**
 * Published once the whole trace has been emitted. Signals that the run's
 * timeline is complete and the comparison can be computed.
 */
public record ReplayCompletedEvent(int snapshotsEmitted) {
}