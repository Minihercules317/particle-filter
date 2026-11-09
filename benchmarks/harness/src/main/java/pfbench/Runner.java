package pfbench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.platform.GlUtil;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.client.ParticleStatus;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Executes a plan.json: waits for the world, locks the camera, runs the phases (spawning the workload, switching
 * Particle Filter's mode at phase starts), logs frames and ticks into memory, writes everything at the end and
 * quits the game. Client thread only. No file I/O while phases run, except screenshots in functional plans.
 */
public final class Runner {
    /** Read by the particle providers; set from the plan before the world loads. */
    static volatile int targetLifetime = 40;
    static volatile int controlLifetime = 40;

    private static Runner instance;

    private enum State { WAIT_WORLD, SETUP, RUNNING, DONE }

    /** Ticks between camera setup and the first phase, so the server's teleport and game mode change have landed. */
    private static final int SETUP_TICKS = 20;
    /** Template creation: ticks to stay in the new world before quitting (the quit saves it). */
    private static final int TEMPLATE_TICKS = 100;

    final Plan plan;
    private final Path planFile;
    private final String planSha256;
    private final Path out;
    private final Minecraft mc = Minecraft.getInstance();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    /** Total JIT compilation time: still rising during a measured window means warmup wasn't finished. */
    private final CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
    private final ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();

    private State state = State.WAIT_WORLD;
    private boolean templateRequested;
    private int setupTicks;
    private long tickCount;

    private FrameLog frames;
    private TickLog ticks;
    private String[] phaseNames;
    private long origin;
    private int phaseIndex = -1;
    private int phaseTick;
    private long[] phaseStart;
    private long[] phaseEnd;
    private String[] modeAfter;

    private double[][] emitters;
    private int targetCursor;
    private int controlCursor;
    private double targetAcc;
    private double controlAcc;
    private ParticleOptions targetOptions;
    private ParticleOptions controlOptions;

    private PfControl pf;
    private final List<String> notes = new ArrayList<>();
    private final JsonObject setupInfo = new JsonObject();
    private final String startedUtc = Instant.now().toString();

    private Runner(Plan plan, Path planFile, String planSha256) {
        this.plan = plan;
        this.planFile = planFile;
        this.planSha256 = planSha256;
        this.out = Path.of(plan.output_dir);
        targetLifetime = plan.target_lifetime_ticks;
        controlLifetime = plan.control_lifetime_ticks;
    }

    /** The plan named by -Dpfbench.plan, or null if there is none. A plan that can't be read stops the game. */
    static Runner fromSystemProperty() {
        String path = System.getProperty("pfbench.plan");
        if (path == null || path.isBlank()) return null;
        try {
            Path file = Path.of(path);
            byte[] bytes = Files.readAllBytes(file);
            Plan plan = new Gson().fromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Plan.class);
            if (plan.format != 1) throw new IllegalArgumentException("unsupported plan format " + plan.format);
            if (plan.output_dir == null) throw new IllegalArgumentException("plan has no output_dir");
            if ("run".equals(plan.kind) && plan.phases.isEmpty()) throw new IllegalArgumentException("plan has no phases");
            Files.createDirectories(Path.of(plan.output_dir));
            instance = new Runner(plan, file, sha256(bytes));
            return instance;
        } catch (Exception e) {
            throw new IllegalStateException("pfbench: can't read plan " + path, e);
        }
    }

    // ---- hooks ----

    /** ClientTickEvent.Pre: phase changes, camera lock and spawning happen here. */
    void clientTickStart() {
        try {
            if ("create_template".equals(plan.kind)) templateTick();
            else runTick();
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** End of Minecraft.tick (mixin): record this tick's counters. */
    public static void tickEnd() {
        Runner r = instance;
        if (r == null || r.state != State.RUNNING) {
            Counters.resetTick();
            return;
        }
        r.recordTick();
    }

    /** End of Minecraft.runTick (mixin): one frame boundary. */
    public static void frameEnd() {
        Runner r = instance;
        if (r != null && r.state == State.RUNNING) r.frames.frame(System.nanoTime(), r.mc.getFrameTimeNs(), r.phaseIndex);
    }

    // ---- benchmark run ----

    private void runTick() {
        switch (state) {
            case WAIT_WORLD -> {
                if (worldReady()) {
                    setup();
                    state = State.SETUP;
                }
            }
            case SETUP -> {
                lockCamera();
                if (++setupTicks >= SETUP_TICKS) {
                    begin();
                    phaseTick();   // this tick is the first of phase 0, so every phase gets exactly its ticks
                }
            }
            case RUNNING -> {
                lockCamera();
                phaseTick();
            }
            case DONE -> { }
        }
    }

    private void phaseTick() {
        Plan.Phase phase = plan.phases.get(phaseIndex);
        if (phaseTick >= phase.ticks) {
            endPhase(phase);
            if (phaseIndex + 1 >= plan.phases.size()) {
                finish(null);
                return;
            }
            startPhase(phaseIndex + 1);
            phase = plan.phases.get(phaseIndex);
        }
        spawn(phase);
        phaseTick++;
    }

    private boolean functional() {
        return "functional".equals(plan.label);
    }

    private boolean worldReady() {
        return mc.level != null && mc.player != null && mc.screen == null && mc.getSingleplayerServer() != null;
    }

    private void setup() {
        mc.options.hideGui = plan.camera.hide_hud;
        ParticleStatus particles = mc.options.particles().get();
        setupInfo.addProperty("particles_setting_found", particles.name());
        if (particles != ParticleStatus.ALL) {
            mc.options.particles().set(ParticleStatus.ALL);
            notes.add("Particles setting was " + particles + "; forced to ALL");
        }
        placePlayerOnServer(false);

        pf = PfControl.find();
        setupInfo.addProperty("particlefilter_present", pf != null);
        if (pf != null) {
            setupInfo.addProperty("particlefilter_version", pf.version);
            setupInfo.addProperty("particlefilter_mode_at_load", pf.mode());
        }
        // The GPU and driver the game renders with: part of the rendering environment every compared run must share.
        JsonObject gl = new JsonObject();
        gl.addProperty("renderer", GlUtil.getRenderer());
        gl.addProperty("vendor", GlUtil.getVendor());
        gl.addProperty("version", GlUtil.getOpenGLVersion());
        setupInfo.add("gl", gl);

        Random random = new Random(plan.seed);
        Plan.Emitters e = plan.emitters;
        int n = e.grid;
        emitters = new double[n * n][];
        double dx = (e.x_range[1] - e.x_range[0]) / n;
        double dy = (e.y_range[1] - e.y_range[0]) / n;
        for (int iy = 0; iy < n; iy++) {
            for (int ix = 0; ix < n; ix++) {
                // Jitter stays inside the grid cell, so the plane's bounds hold exactly.
                emitters[iy * n + ix] = new double[] {
                    e.x_range[0] + (ix + random.nextDouble()) * dx,
                    e.y_range[0] + (iy + random.nextDouble()) * dy,
                    e.plane_z,
                };
            }
        }
        controlCursor = emitters.length / 2;   // control and target start at different points of the grid
        targetOptions = switch (plan.variant) {
            case "typed", "untyped" -> PfBench.TARGET.get();
            case "block_dust" -> new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
            case "vanilla" -> ParticleTypes.END_ROD;
            default -> throw new IllegalArgumentException("unknown variant " + plan.variant);
        };
        controlOptions = PfBench.CONTROL.get();

        int capacityTicks = plan.totalTicks() + 200;
        frames = new FrameLog(plan.frame_capacity);
        ticks = new TickLog(capacityTicks);
        phaseNames = new String[plan.phases.size()];
        for (int i = 0; i < phaseNames.length; i++) phaseNames[i] = plan.phases.get(i).name;
        phaseStart = new long[phaseNames.length];
        phaseEnd = new long[phaseNames.length];
        modeAfter = new String[phaseNames.length];
    }

    /**
     * Spectator at the plan's camera position, on the integrated server. With template=true it also sets time and
     * weather (the template is made once; runs only record them).
     */
    private void placePlayerOnServer(boolean template) {
        IntegratedServer server = mc.getSingleplayerServer();
        UUID id = mc.player.getUUID();
        double[] p = plan.camera.position;
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            ServerLevel overworld = server.overworld();
            if (sp == null) return;
            sp.setGameMode(GameType.SPECTATOR);
            sp.teleportTo(overworld, p[0], p[1], p[2], plan.camera.yaw, plan.camera.pitch);
            if (template) {
                overworld.setDayTime(plan.world.time);
                overworld.setWeatherParameters(6000, 0, false, false);
            }
        });
    }

    /** Client side: rotation every tick; position too once the server made us a spectator (no fall, no collision). */
    private void lockCamera() {
        LocalPlayer player = mc.player;
        if (player == null) return;
        float yaw = plan.camera.yaw;
        float pitch = plan.camera.pitch;
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);
        if (player.isSpectator()) {
            double[] p = plan.camera.position;
            player.setDeltaMovement(0, 0, 0);
            if (player.getX() != p[0] || player.getY() != p[1] || player.getZ() != p[2]) player.setPos(p[0], p[1], p[2]);
        }
        mc.options.hideGui = plan.camera.hide_hud;
    }

    private void begin() {
        recordSetupState();
        origin = System.nanoTime();
        frames.start(origin);
        Counters.resetTick();
        Counters.enabled = true;
        state = State.RUNNING;
        startPhase(0);
    }

    private void startPhase(int index) {
        phaseIndex = index;
        phaseTick = 0;
        targetAcc = 0;
        controlAcc = 0;
        // Every phase walks the emitter grid from the same starting points, so phases with the same rate spawn the
        // same positions in the same order, whatever came before them.
        targetCursor = 0;
        controlCursor = emitters.length / 2;
        Plan.Phase phase = plan.phases.get(index);
        if (phase.gc_at_start) System.gc();   // in a settle phase: the pause and the previous phase's garbage stay out of the window
        if (phase.mode != null) {
            if (pf == null) {
                notes.add("phase " + phase.name + " asks for mode " + phase.mode + " but Particle Filter isn't installed; ignored");
            } else {
                pf.setMode(phase.mode);
            }
        }
        modeAfter[index] = pf == null ? null : pf.mode();
        phaseStart[index] = System.nanoTime();
    }

    private void endPhase(Plan.Phase phase) {
        phaseEnd[phaseIndex] = System.nanoTime();
        if (functional() && plan.screenshots && phase.screenshot) {   // never in performance plans (file I/O)
            Screenshot.grab(mc.gameDirectory, "pfbench-" + plan.run_id + "-" + phaseIndex + "-" + phase.name + ".png",
                mc.getMainRenderTarget(), message -> { });
        }
    }

    private void spawn(Plan.Phase phase) {
        ClientLevel level = mc.level;
        if (level == null) return;
        targetAcc += phase.target_per_tick;
        int n = (int) targetAcc;
        targetAcc -= n;
        for (int i = 0; i < n; i++) {
            double[] e = emitters[targetCursor];
            targetCursor = (targetCursor + 1) % emitters.length;
            Counters.attemptsTarget++;
            if ("untyped".equals(plan.variant)) {
                mc.particleEngine.add(new UntypedParticle(level, e[0], e[1], e[2]));
            } else {
                level.addParticle(targetOptions, e[0], e[1], e[2], 0.0, 0.0, 0.0);
            }
        }
        if (phase.control) {
            controlAcc += plan.control_attempts_per_tick;
            int c = (int) controlAcc;
            controlAcc -= c;
            for (int i = 0; i < c; i++) {
                double[] e = emitters[controlCursor];
                controlCursor = (controlCursor + 1) % emitters.length;
                Counters.attemptsControl++;
                level.addParticle(controlOptions, e[0], e[1], e[2] + plan.control_z_offset, 0.0, 0.0, 0.0);
            }
        }
    }

    private void recordTick() {
        AliveCounts alive = (AliveCounts) mc.particleEngine;
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean bean : gcBeans) {
            gcCount += Math.max(0, bean.getCollectionCount());
            gcTime += Math.max(0, bean.getCollectionTime());
        }
        Runtime rt = Runtime.getRuntime();
        IntegratedServer server = mc.getSingleplayerServer();
        ticks.add(
            tickCount++, System.nanoTime(), phaseIndex,
            Counters.attemptsTarget, Counters.attemptsControl,
            Counters.createdTarget, Counters.createdControl, Counters.createdUntyped, Counters.createdBlock,
            Counters.createdVanilla, Counters.createdOther,
            alive.pfbench$alive(ParticleRenderType.PARTICLE_SHEET_OPAQUE),
            alive.pfbench$alive(ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT),
            alive.pfbench$alive(ParticleRenderType.TERRAIN_SHEET),
            alive.pfbench$aliveTotal(),
            Counters.clientTickNs, server == null ? -1 : server.getAverageTickTimeNanos(),
            gcCount, gcTime, rt.totalMemory() - rt.freeMemory(),
            Counters.controlLive, jit.isCompilationTimeMonitoringSupported() ? jit.getTotalCompilationTime() : -1,
            classes.getTotalLoadedClassCount(), Counters.serverSaves.get());
        Counters.resetTick();
    }

    private void recordSetupState() {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            GameRules rules = server.getGameRules();
            JsonObject r = new JsonObject();
            r.addProperty("doDaylightCycle", rules.getBoolean(GameRules.RULE_DAYLIGHT));
            r.addProperty("doWeatherCycle", rules.getBoolean(GameRules.RULE_WEATHER_CYCLE));
            r.addProperty("doMobSpawning", rules.getBoolean(GameRules.RULE_DOMOBSPAWNING));
            r.addProperty("randomTickSpeed", rules.getInt(GameRules.RULE_RANDOMTICKING));
            setupInfo.add("gamerules", r);
            setupInfo.addProperty("level_name", server.getWorldData().getLevelName());
            setupInfo.addProperty("day_time", server.overworld().getDayTime());
            setupInfo.addProperty("raining", server.overworld().isRaining());
        }
        if (mc.player != null) {
            setupInfo.addProperty("player_spectator", mc.player.isSpectator());
            setupInfo.add("player_position", array(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
            setupInfo.addProperty("player_eye_y", mc.player.getEyeY());
        }
        setupInfo.add("window_at_start", windowInfo());
        setupInfo.add("options", optionsInfo());
    }

    // ---- template world ----

    private void templateTick() {
        switch (state) {
            case WAIT_WORLD -> {
                if (!templateRequested && mc.screen instanceof TitleScreen) {
                    templateRequested = true;
                    mc.tell(this::createTemplateWorld);   // outside the tick, like a button press would be
                } else if (worldReady()) {
                    placePlayerOnServer(true);
                    state = State.SETUP;
                }
            }
            case SETUP -> {
                lockCamera();
                if (++setupTicks >= TEMPLATE_TICKS) {
                    recordSetupState();
                    finish(null);   // quitting saves the world (Minecraft.destroy disconnects first)
                }
            }
            default -> { }
        }
    }

    private void createTemplateWorld() {
        Plan.World w = plan.world;
        if (!"minecraft:the_void".equals(w.preset)) throw new IllegalArgumentException("only minecraft:the_void is supported, not " + w.preset);
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(w.doDaylightCycle, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(w.doWeatherCycle, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(w.doMobSpawning, null);
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(w.randomTickSpeed, null);
        LevelSettings settings = new LevelSettings(w.level_name, GameType.SPECTATOR, false, Difficulty.PEACEFUL, true, rules,
            WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(plan.seed, false, false);
        mc.createWorldOpenFlows().createFreshLevel(w.level_name, settings, options, registries -> {
            var voidPreset = registries.registryOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
                .getHolderOrThrow(FlatLevelGeneratorPresets.THE_VOID).value();
            return WorldPresets.createNormalWorldDimensions(registries)
                .replaceOverworldGenerator(registries, new FlatLevelSource(voidPreset.settings()));
        }, new TitleScreen());
    }

    // ---- output ----

    private void finish(Throwable error) {
        state = State.DONE;
        Counters.enabled = false;
        try {
            if (frames != null) {
                frames.write(out.resolve("frames.csv"), origin, phaseNames);
                ticks.write(out.resolve("ticks.csv"), origin, phaseNames);
                writePhases();
            }
            writeHarnessJson(error);
        } catch (IOException e) {
            PfBench.LOGGER.error("pfbench: couldn't write outputs to {}", out, e);
        }
        PfBench.LOGGER.info("pfbench: plan {} finished{}; quitting", plan.run_id, error == null ? "" : " WITH AN ERROR");
        mc.stop();
    }

    private void fail(Throwable t) {
        PfBench.LOGGER.error("pfbench: run failed", t);
        if (state != State.DONE) finish(t);
    }

    private void writePhases() throws IOException {
        StringBuilder sb = new StringBuilder("index,name,role,config,level,mode_requested,mode_after_start,start_t_ns,end_t_ns,ticks,target_per_tick,control\n");
        for (int i = 0; i < plan.phases.size(); i++) {
            Plan.Phase p = plan.phases.get(i);
            sb.append(i).append(',').append(p.name).append(',').append(nz(p.role)).append(',').append(nz(p.config)).append(',')
              .append(nz(p.level)).append(',').append(nz(p.mode)).append(',').append(nz(modeAfter[i])).append(',')
              .append(phaseStart[i] == 0 ? "" : String.valueOf(phaseStart[i] - origin)).append(',')
              .append(phaseEnd[i] == 0 ? "" : String.valueOf(phaseEnd[i] - origin)).append(',')
              .append(p.ticks).append(',').append(p.target_per_tick).append(',').append(p.control).append('\n');
        }
        Files.writeString(out.resolve("phases.csv"), sb);
    }

    private void writeHarnessJson(Throwable error) throws IOException {
        JsonObject j = new JsonObject();
        j.addProperty("label", plan.label);
        j.addProperty("note", "functional".equals(plan.label)
            ? "Functional bring-up run: checks that the harness works. NOT a performance result; don't quote its timings."
            : null);
        j.addProperty("run_id", plan.run_id);
        j.addProperty("kind", plan.kind);
        j.addProperty("plan_file", planFile.getFileName().toString());
        j.addProperty("plan_sha256", planSha256);
        JsonObject sc = new JsonObject();
        sc.addProperty("id", plan.scenario.id);
        sc.addProperty("version", plan.scenario.version);
        sc.addProperty("sha256", plan.scenario.sha256);
        j.add("scenario", sc);
        j.addProperty("started_utc", startedUtc);
        j.addProperty("finished_utc", Instant.now().toString());
        j.addProperty("ok", error == null);
        if (error != null) j.addProperty("error", error.toString());

        JsonObject v = new JsonObject();
        v.addProperty("minecraft", SharedConstants.getCurrentVersion().getName());
        v.addProperty("neoforge", modVersion("neoforge"));
        v.addProperty("pfbench", modVersion("pfbench"));
        v.addProperty("particlefilter", ModList.get().isLoaded("particlefilter") ? modVersion("particlefilter") : null);
        v.addProperty("java", System.getProperty("java.runtime.version"));
        v.addProperty("java_vendor", System.getProperty("java.vendor"));
        v.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        j.add("versions", v);
        JsonArray mods = new JsonArray();
        ModList.get().getMods().forEach(m -> mods.add(m.getModId() + " " + m.getVersion()));
        j.add("mods", mods);
        j.add("mods_folder", modsFolder());
        j.add("mod_files", loadedModFiles());
        JsonArray jvm = new JsonArray();
        ManagementFactory.getRuntimeMXBean().getInputArguments().forEach(jvm::add);
        j.add("jvm_args", jvm);
        j.add("provenance", plan.provenance);

        j.addProperty("seed", plan.seed);
        j.addProperty("variant", plan.variant);
        j.addProperty("target_lifetime_ticks", plan.target_lifetime_ticks);
        j.addProperty("control_lifetime_ticks", plan.control_lifetime_ticks);
        j.addProperty("control_attempts_per_tick", plan.control_attempts_per_tick);
        j.addProperty("control_z_offset", plan.control_z_offset);
        j.addProperty("emitters", emitters == null ? 0 : emitters.length);
        j.add("setup", setupInfo);
        j.add("window_at_end", windowInfo());
        if (frames != null) {
            j.addProperty("frames_logged", frames.size());
            j.addProperty("frames_dropped_capacity", frames.dropped());
            j.addProperty("frame_capacity", frames.capacity());
            j.addProperty("ticks_logged", ticks.size());
            j.addProperty("ticks_dropped_capacity", ticks.dropped());
            JsonArray modes = new JsonArray();
            for (int i = 0; i < phaseNames.length; i++) {
                JsonObject m = new JsonObject();
                m.addProperty("phase", phaseNames[i]);
                m.addProperty("mode_requested", plan.phases.get(i).mode);
                m.addProperty("mode_after_start", modeAfter[i]);
                modes.add(m);
            }
            j.add("particlefilter_modes", modes);
        }
        JsonArray n = new JsonArray();
        notes.forEach(n::add);
        j.add("notes", n);
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        Files.writeString(out.resolve("harness.json"), gson.toJson(j));
    }

    /**
     * Every jar in the run's mods/ folder with its SHA-256, computed after the phases (no I/O while they run). The
     * launcher compares these with the jars it copied. Jars can hold other mods (jar-in-jar: Sodium nests its mod and
     * four Fabric API modules), so the loaded mod files are listed separately by {@link #loadedModFiles()}.
     */
    private JsonArray modsFolder() {
        JsonArray files = new JsonArray();
        Path modsDir = mc.gameDirectory.toPath().resolve("mods");
        if (!Files.isDirectory(modsDir)) return files;
        try (var stream = Files.list(modsDir)) {
            stream.filter(Files::isRegularFile).sorted().forEach(file -> {
                JsonObject f = new JsonObject();
                f.addProperty("file", file.getFileName().toString());
                try {
                    f.addProperty("sha256", sha256(Files.readAllBytes(file)));
                } catch (Exception e) {
                    f.addProperty("sha256_error", e.toString());
                }
                files.add(f);
            });
        } catch (IOException e) {
            notes.add("couldn't list mods/: " + e);
        }
        return files;
    }

    /** Every mod file NeoForge loaded (including nested jar-in-jar files and the game itself), with its mods. */
    private JsonArray loadedModFiles() {
        JsonArray files = new JsonArray();
        ModList.get().getModFiles().forEach(info -> {
            JsonObject f = new JsonObject();
            Path file = info.getFile().getFilePath();
            f.addProperty("file", file.getFileName() == null ? file.toString() : file.getFileName().toString());
            f.addProperty("nested", file.getFileSystem() != java.nio.file.FileSystems.getDefault());
            JsonArray ids = new JsonArray();
            info.getMods().forEach(m -> ids.add(m.getModId() + " " + m.getVersion()));
            f.add("mods", ids);
            files.add(f);
        });
        return files;
    }

    private JsonObject windowInfo() {
        JsonObject w = new JsonObject();
        w.addProperty("width", mc.getWindow().getWidth());
        w.addProperty("height", mc.getWindow().getHeight());
        w.addProperty("fullscreen", mc.getWindow().isFullscreen());
        return w;
    }

    private JsonObject optionsInfo() {
        JsonObject o = new JsonObject();
        o.addProperty("renderDistance", mc.options.renderDistance().get());
        o.addProperty("simulationDistance", mc.options.simulationDistance().get());
        o.addProperty("maxFps", mc.options.framerateLimit().get());
        o.addProperty("enableVsync", mc.options.enableVsync().get());
        o.addProperty("particles", mc.options.particles().get().name());
        o.addProperty("graphicsMode", mc.options.graphicsMode().get().name());
        o.addProperty("fullscreen", mc.options.fullscreen().get());
        o.addProperty("ao", mc.options.ambientOcclusion().get());
        o.addProperty("renderClouds", mc.options.cloudStatus().get().name());
        o.addProperty("fov", mc.options.fov().get());
        o.addProperty("guiScale", mc.options.guiScale().get());
        o.addProperty("pauseOnLostFocus", mc.options.pauseOnLostFocus);
        o.addProperty("hideGui", mc.options.hideGui);
        return o;
    }

    private static String modVersion(String id) {
        return ModList.get().getModContainerById(id).map(c -> c.getModInfo().getVersion().toString()).orElse(null);
    }

    private static JsonArray array(double... values) {
        JsonArray a = new JsonArray();
        for (double d : values) a.add(d);
        return a;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
