# Standard limitations (included verbatim in every generated report)

- **One machine.** Every figure comes from the single computer described under "Hardware and software". Other CPUs,
  GPUs, drivers, operating systems and compositors can give different results, including a different sign: a scene that
  is GPU-bound here can be CPU-bound elsewhere. Nothing here predicts another player's FPS.
- **Within-machine comparisons only.** The results are paired differences between configurations measured on the same
  machine in counterbalanced order. They are not absolute FPS claims, and they can't be compared directly with numbers
  from another machine, another campaign or another tool.
- **Machine state that can't be fully controlled.** CPU and GPU clocks, boost, thermals and power limits vary over time
  on a laptop. They were recorded (snapshots before and after each launch, per-second samples) but not locked, because
  the owner doesn't allow firmware, power or GPU changes. CPU placement was not pinned. Counterbalancing spreads slow
  drift over all configurations; it doesn't remove it from individual windows.
- **Development runtime.** The pfbench harness runs in the mod's development client (ModDevGradle), not a player's
  installation. On Forge 1.20.1 that means Particle Filter's named-mappings jar from the same build as the released
  jar, not the released (reobfuscated) jar itself.
- **Synthetic workload.** Synthetic results use one harness particle (fixed size and lifetime, no physics) from a fixed
  grid in a void world. They isolate Particle Filter's cost and effect; they don't describe a typical game session.
  Extreme load levels are stress tests, not gameplay.
- **Reduced-TPS (saturation) results are a separate class.** When the client falls below 20 ticks per second, fewer
  particles are requested per real second and frame times change for reasons beyond particle rendering. Those pairs are
  reported in their own dataset and never averaged with 20-TPS pairs.
- **Real-world results** (if a report contains any) describe one world, one modpack and one viewpoint, with workloads
  that can't be repeated exactly (machine cycles, chunk loading, server ticks).
- **What the evidence does not prove.** Hashes show that files are unchanged since the manifest was written. A
  successful rebuild shows that the derived numbers follow from the raw files with the published tools. Automated
  checks show that the recorded data is internally consistent (workload counts, mode switches, versions). None of these
  shows that the raw files were recorded honestly, on the machine described, or under the conditions described. Only
  an independent replication on other hardware can corroborate a result; benchmarks/AUDIT.md explains how.
- **Authorship.** The benchmark code, the analysis tools and this report generator were written by Claude (Anthropic's
  AI model). The owner of Particle Filter designed the questions with it, reviewed the method and ran or approved the
  campaigns. The generated sections are produced by code, so they contain no hand-entered numbers; any interpretation
  is in a separate file and is attributed.
