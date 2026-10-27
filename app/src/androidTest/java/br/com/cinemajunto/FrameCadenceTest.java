package br.com.cinemajunto;

import org.junit.Test;
import static org.junit.Assert.*;

public class FrameCadenceTest {
    @Test public void fractionalCadencesAndBurstsPreserveSourceRate() {
        for (double fps : new double[]{23.976, 24, 25, 29.97, 30, 59.94, 60}) {
            FrameCadence cadence = new FrameCadence();
            float measured = 0;
            // All packets can arrive at once; only source PTS defines the cadence.
            for (int i = 0; i < 100; i++) measured = cadence.add(1000000 + (long)(i * 1000000.0 / fps));
            assertEquals(fps, measured, 0.02);
        }
    }
    @Test public void irregularCaptureDoesNotAdvertiseFalseFixedRate() {
        FrameCadence cadence = new FrameCadence(); long pts = 1000000;
        float measured = 0;
        for (int i = 0; i < 100; i++) {
            measured = cadence.add(pts); pts += i % 2 == 0 ? 16667 : 33333;
        }
        assertEquals(0, measured, 0);
        cadence.reset();
        assertEquals(0, cadence.add(10), 0);
    }
}
