#!/usr/bin/env bash
# Pipeline pilot: launch the repo's NeoForge 1.21.1 dev client under MangoHud, log every frame for a fixed
# window, sample CPU/GPU alongside, then close the client and summarize.
#
# It measures the title screen, which Minecraft caps at 60 FPS while no world is loaded (Minecraft.getFramerateLimit),
# so the expected mean frame time is known in advance (16.67 ms). That makes it a check of the measurement
# pipeline, NOT a measurement of Particle Filter. It uses only the gitignored dev run directory
# (run/1.21.1-neoforge) and changes no Minecraft installation, world or system setting.
#
# Usage: benchmarks/tools/pilot_devclient.sh OUT_DIR [WARMUP_S] [DURATION_S] [--hide]
#   --hide  move the game window to a hidden Hyprland special workspace (records that it did)
#   PILOT_BLOCK=n (environment) sets the block number written to run.json (default 1)
set -euo pipefail

out=${1:?usage: pilot_devclient.sh OUT_DIR [WARMUP_S] [DURATION_S] [--hide]}
warmup=${2:-60}
duration=${3:-30}
hide=0
[[ ${4:-} == --hide ]] && hide=1

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
tools="$repo/benchmarks/tools"
mkdir -p "$out/mangohud"
out=$(cd "$out" && pwd)

if pgrep -f 'net.neoforged.devlaunch.Main' >/dev/null; then
    echo "a dev client is already running; close it first" >&2
    exit 1
fi

python3 "$tools/collect_env.py" --out "$out/env-before.json" --instance "$repo/run/1.21.1-neoforge" \
    --java "$(mise which java)" --note "pilot: dev client title screen"

# fps_only,background_alpha=0: the smallest HUD MangoHud draws. no_display would draw nothing, but MangoHud
# 0.8.4 then writes no log at all (checked in the pilot), so a minimal HUD is kept, identical in every run.
# log_interval=0: one row per frame. autostart_log counts from MangoHud's first frame, which is NeoForge's
# early loading window, so the warmup must cover mod loading as well as JIT warmup.
export MANGOHUD=1
export MANGOHUD_CONFIG="fps_only,background_alpha=0,output_folder=$out/mangohud,log_interval=0,autostart_log=$warmup,log_duration=$duration,log_versioning"

started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
cd "$repo"
setsid mangohud --dlsym mise exec -- ./gradlew --offline --console=plain :1.21.1-neoforge:runClient \
    >"$out/gradle.log" 2>&1 &
launcher=$!

# Wait for the game JVM (not the Gradle JVM): ModDevGradle starts it through DevLaunch.
pid=""
for _ in $(seq 1 180); do
    pid=$(pgrep -f 'net.neoforged.devlaunch.Main' | head -1 || true)
    [[ -n $pid ]] && break
    sleep 1
done
if [[ -z $pid ]]; then
    echo "game process did not start; see $out/gradle.log" >&2
    kill -- -"$launcher" 2>/dev/null || true
    exit 1
fi
echo "game pid $pid"

# The game window's Hyprland state as JSON (empty if not found). Never fails the script.
window_state() {
    command -v hyprctl >/dev/null || return 0
    hyprctl clients -j | python3 -I -c '
import json, sys
pid = int(sys.argv[1])
for c in json.load(sys.stdin):
    if c.get("pid") == pid:
        print(json.dumps({k: c.get(k) for k in ("class", "title", "xwayland", "size", "workspace", "monitor", "floating", "fullscreen")}))
        break' "$1" 2>>"$out/window-lookup.log" || true
}

window_info=""
if command -v hyprctl >/dev/null; then
    for _ in $(seq 1 60); do
        window_info=$(window_state "$pid")
        [[ -n $window_info ]] && break
        sleep 1
    done
    if [[ $hide == 1 && -n $window_info ]]; then
        # Hyprland 0.56+ takes Lua dispatchers; older versions take "movetoworkspacesilent special:pfbench,pid:N".
        hyprctl dispatch "hl.dsp.window.move({ workspace = \"special:pfbench\", window = \"pid:$pid\", follow = false })" \
            >>"$out/window-lookup.log" 2>&1 || true
        sleep 1
        where=$(hyprctl clients -j | python3 -I -c '
import json, sys
pid = int(sys.argv[1])
print(next((c["workspace"]["name"] for c in json.load(sys.stdin) if c.get("pid") == pid), ""))' "$pid" || true)
        echo "window workspace after move: $where" | tee -a "$out/window-lookup.log"
    fi
fi

python3 "$tools/sample_system.py" --pid "$pid" --out-dir "$out/system" --interval 1 \
    --duration $((warmup + duration + 20)) 2>"$out/sampler.log" &   # no --self-cpu: affinity changes are not allowed
sampler=$!

# Wait for MangoHud to finish its timed log. It creates the frame CSV when logging starts and writes
# *_summary.csv only when the log stops, so wait for the summary (stopping earlier truncates the log).
deadline=$(( $(date +%s) + warmup + duration + 120 ))
until compgen -G "$out/mangohud/*_summary.csv" >/dev/null || (( $(date +%s) >= deadline )); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 2
done
sleep 5
window_after=$(window_state "$pid")   # the loading window can differ from the game window, so check again
python3 "$tools/collect_env.py" --out "$out/env-after.json" --pid "$pid" --note "pilot: after measurement"

kill "$pid" 2>/dev/null || true
wait "$sampler" 2>/dev/null || true
for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
kill -- -"$launcher" 2>/dev/null || true

log=$(ls "$out"/mangohud/*.csv 2>/dev/null | grep -v summary | head -1 || true)
python3 - "$out/run.json" "$started" "$warmup" "$duration" "$hide" "${window_info:-null}" "${window_after:-null}" \
    "${log:-}" "${PILOT_BLOCK:-1}" <<'EOF'
import json, sys
path, started, warmup, duration, hide, window, window_after, log, block = sys.argv[1:]
json.dump({
    "campaign": "pilot", "config": "pilot-title-screen", "block": int(block), "order": 1,
    "scenario": "title screen (60 FPS menu cap), dev client NeoForge 1.21.1",
    "started_utc": started, "warmup_s": int(warmup), "duration_s": int(duration),
    "window_hidden_in_special_workspace": hide == "1", "window": json.loads(window), "window_after": json.loads(window_after),
    "frame_log": log.rsplit("/", 2)[-2] + "/" + log.rsplit("/", 1)[-1] if log else None,
    "notes": "pipeline pilot; not evidence about Particle Filter performance",
}, open(path, "w"), indent=2)
EOF

if [[ -z $log ]]; then
    echo "no MangoHud frame log was written; see $out/gradle.log" >&2
    exit 1
fi
python3 "$tools/frametimes.py" summarize "$log" --duration-seconds "$duration" --cap-fps 60 \
    --json "$out/summary.json" || true
