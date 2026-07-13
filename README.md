# Farmer AutoHarvest Module

Automatically harvests mature crops inside Farmer regions and replants ageable crops. It combines growth events with bounded crop reconciliation, so crops that were already mature when a Farmer was purchased are not missed. The module requires Paper-compatible server software and supports Paper, Folia, and Leaf.

## Compatibility

| Minecraft | Java | Server | Farmer |
| --- | --- | --- | --- |
| 1.21.x | 21 | Paper, Folia, Leaf | v6-b113 or newer |
| 26.x | 25 | Paper, Folia, Leaf | v6-b113 or newer |

Plain Bukkit and Spigot are intentionally unsupported. This is an external Farmer module, not a standalone `JavaPlugin`; the Farmer host plugin supplies the Folia metadata while this module uses Paper's region scheduler for all world access.

## Installation

1. Install Farmer v6-b113 or newer on Paper, Folia, or Leaf.
2. Place `Farmer-AutoHarvest-1.6.0.jar` in `plugins/Farmer/modules/`.
3. Restart the server.
4. Configure `plugins/Farmer/modules/autoharvest/config.yml`.

The module refuses to enable when Paper's region scheduler API is unavailable.

Crop block discovery uses an explicit modern-material table. Legacy Bukkit constants are never passed to XMaterial during startup, allowing the module and its management-panel icon to load reliably on current Paper, Folia, and Leaf builds.

Farmer cache access is linked through a one-time MethodHandle adapter. This preserves compatibility with both the `HashMap` return descriptor used by Farmer v6-b113 and the `Map` descriptor used by v6-b117 and newer without reflection in the per-block lookup path.

## Automatic File Maintenance

At startup and Farmer reload, AutoHarvest validates its config and bundled language files (`en`, `tr`, `de`). Missing known entries are added automatically. Invalid YAML, wrong data types, empty required text, invalid permissions, invalid crop names, duplicate crop names, and invalid optimization limits are repaired.

Before changing an existing file, the original is copied to a local backup directory:

| File | Backup directory |
| --- | --- |
| `config.yml` | `plugins/Farmer/modules/autoharvest/backups/` |
| `lang/*.yml` | `plugins/Farmer/modules/autoharvest/lang/backups/` |

Unknown entries are preserved so later module versions and server-specific additions are not discarded.

## Update Checker

AutoHarvest checks the repository's latest stable GitHub release at startup and every six hours by default. The HTTPS request is asynchronous, has strict connection/request timeouts, accepts only release and JAR links under `github.com/siberanka/TwiFarmer-AutoHarvest/releases/`, and is cancelled or invalidated on reload/disable.

Harvest listeners and tracking are activated before this optional network service. A checker initialization or platform-linkage failure is logged and isolated, so it can never prevent a `status: true` AutoHarvest runtime from loading.

When a newer semantic version exists, the localized message includes the `AutoHarvest` module name, installed version, latest version, and direct JAR download link. It is sent once per release to the console and to each online or joining player who is an operator or has `farmer.admin`. Set `update-checker.enable: false` to disable all outbound checks; interval and timeout values are validated and repaired like the other config entries.

## Optimize Module

`optimize-module.enable` is `false` by default. Fixed conservative queue and tracking guards still protect the server in that state, but all configured child values remain inert. Enable it on production servers to select the limits and tracking policy below.

```yaml
optimize-module:
  enable: false
  harvest-control:
    global-max-harvests-per-tick: 32
    scope: "FARMER"
    per-harvest-delay:
      enable: false
      ticks: 2
    batch-pause:
      enable: false
      harvests-before-pause: 64
      pause-ticks: 20
  tracking:
    mode: "EVENT_DRIVEN"
    conditions:
      growth-events: true
      fertilize-events: true
      crop-place-events: true
      scan-on-chunk-load: false
      scan-on-farmer-purchase: true
      scan-on-player-join: true
      scan-on-player-chunk-load: true
      farmer-regions-only: true
    reconcile-interval-ticks: 200
    max-chunks-per-cycle: 2
    max-tracked-chunks: 8192
    max-concurrent-scans: 1
    max-snapshot-captures-per-tick: 1
    max-scan-starts-per-second: 4
    max-sections-per-second: 32
    max-block-checks-per-slice: 8192
    max-pending-scans: 4096
    max-candidates-per-scan: 512
    max-candidate-admissions-per-tick: 32
    purchase-radius-chunks: 8
    bootstrap-radius-chunks: 3
  adaptive-backpressure:
    enable: true
    slowdown-above-mspt: 35.0
    pause-above-mspt: 45.0
    resume-below-mspt: 40.0
    minimum-work-percent: 10
    check-interval-ticks: 20
    pause-above-region-task-delay-millis: 100
    region-cooldown-ticks: 100
  queue:
    initial-delay-ticks: 2
    continuation-delay-ticks: 1
    max-jobs-per-run: 8
    max-scheduler-submissions-per-tick: 8
    max-pending-jobs: 8192
    coalesce-duplicates: true
  telemetry:
    enable: true
    log-interval-seconds: 300
```

The important controls are intentionally first. `global-max-harvests-per-tick` caps harvest actions across every world and Folia region. `scope` selects the independent timing boundary: `FARMER` is the recommended island boundary, `OWNER` shares timing across a player's Farmers, `REGION` uses the integration region ID, and `CHUNK` isolates each chunk.

Both user-facing cooldown systems are disabled by default. `per-harvest-delay` can add a steady delay between individual crops. `batch-pause` instead permits `harvests-before-pause` attempts in one scope, pauses only that scope for `pause-ticks`, and then continues automatically; unrelated Farmers/islands continue normally. Twenty ticks are approximately one second. Disabling both removes artificial Farmer cooldowns while the global queue, scheduler, and adaptive load protections remain active.

Config version 9 migrates the former `queue.global-max-jobs-per-tick` and `queue.per-scope-pacing` values into `harvest-control` after creating a backup. Existing enabled pacing remains enabled with the same scope and delay; new installations default to no artificial pause.

The advanced hard limits remain global and bounded: `max-scheduler-submissions-per-tick` caps region task fan-out, and `max-sections-per-second` caps primary snapshot block reads in 4096-block units. `max-block-checks-per-slice` prevents one async task from monopolizing a worker. Queue overflow leaves the live crop untouched and requests bounded reconciliation; it never bypasses limits with a direct task. Even with thousands of loaded chunks, AutoHarvest never starts an unbounded full-world sweep.

Adaptive backpressure uses Paper's rolling MSPT with hysteresis. Above `slowdown-above-mspt`, it gradually scales harvest jobs, region scheduler submissions, reconciliation chunks, snapshot captures, scan starts, section reads, and async slice sizes down toward `minimum-work-percent`. At `pause-above-mspt` it temporarily stops new work and resumes only below `resume-below-mspt`. It also observes region callback delay, which protects Folia/Leaf when one region is overloaded even if a global average looks healthy. Telemetry logs cumulative queue/scan counters only at the configured low frequency and only when useful work, deferral, or failure exists.

The optimization path is async-safe: immutable `ChunkSnapshot` data is analyzed asynchronously, while snapshot capture, Farmer scope admission, live world validation, block mutation, item drops, Farmer lookup, and inventory updates stay on the owning `RegionScheduler` thread. Candidate admission is sliced by `max-candidate-admissions-per-tick`, empty sections are skipped, chunks are never force-loaded, duplicate jobs/scans are coalesced, unload/reload generations reject stale work, and idle dispatchers park until work or reconciliation is due.

Snapshot section indexes are translated relative to the world's minimum build height. Worlds using negative Y values, including the standard `-64..320` range on Leaf, therefore scan every section without accessing a negative snapshot index. A failed initial discovery is retained in the bounded reconciliation registry so a transient capture or async scan failure cannot permanently hide an already mature crop chunk.

Dense chunks discover up to `max-candidates-per-scan` crops as one bounded batch. Reconciliation skips a chunk while its harvest queue is still draining. When a candidate-limited chunk queue becomes empty, a single drain callback immediately requests the next bounded scan, producing a slow continuous stream without polling every loaded chunk or repeatedly scanning the same still-pending crops.

## Crop Tracking

- `EVENT_DRIVEN` is the default. Growth/fertilize signals harvest immediately, while only known crop or deferred chunks receive slow reconciliation.
- `PERIODIC_LOADED_CHUNKS` disables immediate growth harvesting and rotates through a bounded registry of known loaded chunks.
- `HYBRID` combines immediate events with bounded loaded-chunk rotation.
- Every signal under `tracking.conditions` can be enabled independently. On large servers, keep broad `scan-on-chunk-load: false`; `scan-on-player-chunk-load: true` performs bounded, near-player discovery only when the player is inside an enabled Farmer region.
- With `farmer-regions-only: true`, event and player bootstrap tracking is accepted only around an enabled Farmer (or when `withoutFarmer` is active). Set it to `false` if periodic mode must discover chunk-loader farms with no nearby player.
- Farmer purchase and GUI enable scans are nearest-first and inspect only chunks already sent to the player. Known crop chunks move into a size-bounded dormant registry on unload and are rescanned when loaded again, so leaving and returning to a farm does not require toggling AutoHarvest. Module reload and disable invalidate all queued state.

## Stacked Crops

`stacked-crops` is independent from the ordinary `items` list. It discovers one top candidate per column, finds the base on the owning region thread, preserves that base, and removes every validated segment above it as one globally budgeted harvest job.

```yaml
stacked-crops:
  enable: true
  items:
    - "SUGAR_CANE"
    - "CACTUS"
    - "BAMBOO"
    - "KELP"
  max-segments-per-harvest: 32
```

Each supported column crop can be added or removed independently. `KELP` treats `KELP` and `KELP_PLANT` blocks as one column. The segment limit is validated between 1 and 256; a taller or malformed column is rejected without partial mutation. If a listed crop is absent from Farmer's host-level `items.yml`, it is harvested as natural world drops rather than being incorrectly blocked by `checkStock`.

## Harvest Guarantees

- Mature crops are revalidated after growth before any drop or block mutation.
- Snapshot candidates are revalidated against the live block and current Farmer ownership before harvesting.
- Ageable crops are reset only after real mature-block drops are captured.
- Stacked crops preserve their base and remove each validated segment above it exactly once.
- Stale or duplicate jobs cannot harvest a crop that is no longer mature.
- Farmer-linked operations revalidate ownership, module state, piston requirements, and stock before execution.
- Module-triggered Farmer inventory paths are serialized per Farmer to avoid Folia region races.

## Building

Build the release JAR for 1.21.x with JDK 21:

```bash
mvn clean verify -Ppaper-1.21
```

Compile against the 26.x Paper API with JDK 25:

```bash
mvn clean verify -Ppaper-26
```

## Authors

Geik, Poyraz, and siberanka.
