package particlefilter.scan;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnRecorderTest {

    @Test
    void recordBlockedOnTheLockDoesNotWriteIntoAReplacementScan() throws InterruptedException {
        SpawnRecorder recorder = new SpawnRecorder();
        recorder.start(Vec3.ZERO, 10);
        Thread worker = new Thread(() -> recorder.record("minecraft:smoke", true, 1, 1, 1, false), "old-record");
        worker.setDaemon(true);
        try {
            synchronized (recorder) {
                worker.start();
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (worker.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(Thread.State.BLOCKED, worker.getState(), "old record never reached the lock");
                recorder.start(Vec3.ZERO, 10); // same center and radius, new scan
            }
            worker.join(5_000);
            assertFalse(worker.isAlive(), "old record did not finish");
            assertTrue(recorder.collect().isEmpty(), "old record leaked into the new scan");
        } finally {
            worker.interrupt();
            worker.join(5_000);
        }
    }

    @Test
    void recordsInsideRadiusAndSkipsOutsideOrStopped() {
        SpawnRecorder recorder = new SpawnRecorder();
        recorder.record("a", true, 0, 0, 0, false); // not running
        recorder.start(Vec3.ZERO, 2);
        recorder.record("a", true, 1, 0, 0, false);
        recorder.record("a", true, 1, 0, 0, true);
        recorder.record("a", true, 5, 0, 0, false); // out of range
        SpawnRecorder.Recorded r = recorder.collect().get(0);
        assertEquals(1, r.shown().count());
        assertEquals(1, r.hidden().count());
        recorder.stop();
        recorder.record("b", true, 0, 0, 0, false);
        assertEquals(1, recorder.collect().size());
    }
}
