package br.com.cinemajunto;

/** Estimates media cadence from PTS, never from bursty network arrival times. */
final class FrameCadence {
    private final long[] intervals = new long[48];
    private long previous = Long.MIN_VALUE;
    private int count;
    private float rate;

    float add(long pts) {
        long delta = previous == Long.MIN_VALUE ? 0 : pts - previous;
        previous = pts;
        if (delta <= 0 || delta > 100000) { count = 0; return rate; }
        intervals[count++] = delta;
        if (count < intervals.length) return rate;
        count = 0;
        long sum = 0;
        for (long interval : intervals) sum += interval;
        double mean = sum / (double)intervals.length;
        float candidate = (float)(1000000.0 / mean);
        boolean stable = candidate >= 23 && candidate <= 61;
        for (long interval : intervals)
            if (Math.abs(interval - mean) > mean * 0.08) stable = false;
        if (!stable) rate = 0;
        else if (Math.abs(candidate - rate) > 0.1f) rate = candidate;
        return rate;
    }

    void reset() { previous = Long.MIN_VALUE; count = 0; rate = 0; }
}
