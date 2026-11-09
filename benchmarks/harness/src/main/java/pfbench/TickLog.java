package pfbench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One row per client tick while phases run, in preallocated columns; written as ticks.csv at the end. */
final class TickLog {
    static final String[] COLUMNS = {
        "tick", "t_ns", "phase",
        "attempts_target", "attempts_control",
        "created_target", "created_control", "created_untyped", "created_block", "created_vanilla", "created_other",
        "alive_opaque", "alive_translucent", "alive_terrain", "alive_total",
        "client_tick_ns", "server_tick_avg_ns", "gc_count", "gc_time_ms", "heap_used_bytes",
        "alive_control", "jit_time_ms", "classes_loaded", "server_saves",
    };
    private static final int PHASE = 2;

    private final long[][] cols;
    private int size;
    private long dropped;

    TickLog(int capacity) {
        cols = new long[COLUMNS.length][capacity];
    }

    /** Values in COLUMNS order. */
    void add(long... values) {
        if (size == cols[0].length) {
            dropped++;
            return;
        }
        for (int c = 0; c < values.length; c++) cols[c][size] = values[c];
        size++;
    }

    int size() { return size; }
    long dropped() { return dropped; }

    void write(Path file, long origin, String[] phaseNames) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(String.join(",", COLUMNS));
            w.write('\n');
            StringBuilder sb = new StringBuilder(256);
            for (int r = 0; r < size; r++) {
                sb.setLength(0);
                for (int c = 0; c < cols.length; c++) {
                    if (c > 0) sb.append(',');
                    if (c == 1) sb.append(cols[c][r] - origin);
                    else if (c == PHASE) sb.append(phaseNames[(int) cols[c][r]]);
                    else sb.append(cols[c][r]);
                }
                sb.append('\n');
                w.write(sb.toString());
            }
        }
    }
}
