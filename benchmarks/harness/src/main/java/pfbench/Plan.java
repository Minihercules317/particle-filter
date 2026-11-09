package pfbench;

import java.util.ArrayList;
import java.util.List;

/**
 * plan.json, written by benchmarks/harness/pfbench.py from a scenario file. The harness executes it literally and
 * makes no decisions of its own. Field names match the JSON (read with Gson).
 */
public final class Plan {
    public int format;
    /** "run" executes phases; "create_template" creates the template world and quits. */
    public String kind = "run";
    public String run_id;
    /** "functional" for bring-up runs. Copied into every output so nobody mistakes them for performance data. */
    public String label;
    public String output_dir;
    public Scenario scenario = new Scenario();
    public long seed;
    /** typed, untyped, block_dust or vanilla. */
    public String variant = "typed";
    public Camera camera = new Camera();
    public World world = new World();
    public Emitters emitters = new Emitters();
    public int target_lifetime_ticks = 40;
    public int control_lifetime_ticks = 40;
    public double control_attempts_per_tick = 25;
    /** Control particles spawn this far behind the target plane (+z), so the two never share a depth. */
    public double control_z_offset = 1.0;
    public int frame_capacity = 1_000_000;
    /** Functional bring-up only: save a screenshot at the end of phases that ask for one (file I/O). */
    public boolean screenshots;
    public List<Phase> phases = new ArrayList<>();
    /** Launcher-side provenance (git commit, harness source hash...), copied into harness.json unchanged. */
    public com.google.gson.JsonObject provenance;

    public static final class Scenario {
        public String id;
        public int version;
        public String sha256;
    }

    public static final class Camera {
        public double[] position = {0.5, 100.0, 0.5};
        public float yaw;
        public float pitch;
        public boolean hide_hud = true;
    }

    public static final class World {
        public String level_name = "pfbench";
        public long time = 6000;
        public String preset = "minecraft:the_void";
        public boolean doDaylightCycle;
        public boolean doWeatherCycle;
        public boolean doMobSpawning;
        public int randomTickSpeed;
    }

    public static final class Emitters {
        public double plane_z = 14.0;
        public double[] x_range = {-8.0, 8.0};
        public double[] y_range = {96.0, 112.0};
        public int grid = 16;
    }

    public static final class Phase {
        public String name;
        public int ticks;
        public double target_per_tick;
        public boolean control = true;
        /** Particle Filter mode (FilterMode name) to switch to when the phase starts; null keeps the current one. */
        public String mode;
        /** Configuration id this phase measures (A, B, C, D, C-miss...), null for settle/warmup phases. */
        public String config;
        public String level;
        /** "settle", "warmup", "measure" or "switch-settle". */
        public String role;
        /** Functional plans only: a screenshot at the end of the phase. Ignored in performance plans. */
        public boolean screenshot;
        /** Run a full GC (System.gc()) when the phase starts, so every configuration's window starts from a similar heap. */
        public boolean gc_at_start;
    }

    int totalTicks() {
        int sum = 0;
        for (Phase p : phases) sum += p.ticks;
        return sum;
    }
}
