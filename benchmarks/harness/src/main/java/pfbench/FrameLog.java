package pfbench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Frame boundaries (System.nanoTime at the end of Minecraft.runTick) in preallocated arrays: no allocation per frame.
 * frame_ns is the interval since the previous boundary; cpu_frame_ns is Minecraft.getFrameTimeNs() (render to
 * blit, without buffer swap and limiter wait), a diagnostic only.
 */
public final class FrameLog {
    private final long[] t;
    private final long[] cpu;
    private final short[] phase;
    private int size;
    private long dropped;
    private long reference = -1;

    FrameLog(int capacity) {
        t = new long[capacity];
        cpu = new long[capacity];
        phase = new short[capacity];
    }

    /** The boundary that starts the first logged frame. Frames are ignored until this is set. */
    void start(long boundary) {
        reference = boundary;
    }

    boolean started() {
        return reference >= 0;
    }

    void frame(long now, long cpuNs, int phaseIndex) {
        if (reference < 0) return;
        if (size == t.length) {
            dropped++;   // reported in harness.json; frametimes.py's sum check would also catch the gap
            return;
        }
        t[size] = now;
        cpu[size] = cpuNs;
        phase[size] = (short) phaseIndex;
        size++;
    }

    int size() { return size; }
    long dropped() { return dropped; }
    int capacity() { return t.length; }

    /** frames.csv: t_ns,frame_ns,cpu_frame_ns,phase (t_ns relative to the run's origin, phase by name). */
    void write(Path file, long origin, String[] phaseNames) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write("t_ns,frame_ns,cpu_frame_ns,phase\n");
            long prev = reference;
            StringBuilder sb = new StringBuilder(64);
            for (int i = 0; i < size; i++) {
                sb.setLength(0);
                sb.append(t[i] - origin).append(',').append(t[i] - prev).append(',').append(cpu[i]).append(',')
                  .append(phaseNames[phase[i]]).append('\n');
                w.write(sb.toString());
                prev = t[i];
            }
        }
    }
}
