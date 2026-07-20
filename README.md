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
2. Place `Farmer-AutoHarvest-1.9.0.jar` in `plugins/Farmer/modules/`.
3. Restart the server.
4. Configure `plugins/Farmer/modules/autoharvest/config.yml`.

The module refuses to enable when Paper's region scheduler API is unavailable.

Crop block discovery uses an explicit modern-material table. Legacy Bukkit constants are never passed to XMaterial during startup, allowing the module and its management-panel icon to load reliably on current Paper, Folia, and Leaf builds.

Farmer cache access is linked through a one-time MethodHandle adapter. This preserves compatibility with both the `HashMap` return descriptor used by Farmer v6-b113 and the `Map` descriptor used by v6-b117 and newer without reflection in the per-block lookup path.

Farmer v6-b117's SuperiorSkyblock integration can throw when a scanned location is outside every island. AutoHarvest identifies that active integration even after HotSpot omits repeated exception stack traces, treats a missing island as a normal negative lookup, and avoids owner lookups for Farmer- and land-scoped harvest pacing. Crops outside a Farmer area are denied without console spam, while unrelated integration faults remain visible in the bounded error log.

## Automatic File Maintenance

At startup and Farmer reload, AutoHarvest validates its config and bundled language files (`en`, `tr`, `de`). Missing known entries are added automatically. Invalid YAML, wrong data types, empty required text, invalid permissions, invalid crop names, duplicate crop names, and invalid optimization limits are repaired.

Before changing an existing file, the original is copied to a local backup directory:

| File | Backup directory |
| --- | --- |
| `config.yml` | `plugins/Farmer/modules/autoharvest/backups/` |
| `lang/*.yml` | `plugins/Farmer/modules/autoharvest/lang/backups/` |

Unknown entries are preserved so later module versions and server-specific additions are not discarded.

## Logging

Routine queue saturation messages and periodic tracking/queue counters are hidden by default. Set `logging.debug: true` to show them in the console; `debug-interval-seconds` controls the minimum statistics interval and works independently from `optimize-module.enable`.

Unexpected AutoHarvest exceptions are written asynchronously to `plugins/Farmer/modules/autoharvest/error.log`. The writer uses a fixed 256-entry memory queue, limits each stack-trace entry, and never performs file I/O on a Folia region thread. `max-size-megabytes` limits each file and `history-files` limits retained rotations (`error.log.1`, `error.log.2`, and so on), bounding both memory and disk use.

```yaml
logging:
  debug: false
  debug-interval-seconds: 300
  error-file:
    max-size-megabytes: 5
    history-files: 2
```

## Update Checker

AutoHarvest checks the repository's latest stable GitHub release at startup and every six hours by default. The HTTPS request is asynchronous, has strict connection/request timeouts, accepts only release and JAR links under `github.com/siberanka/TwiFarmer-AutoHarvest/releases/`, and is cancelled or invalidated on reload/disable.

Harvest listeners and tracking are activated before this optional network service. A checker initialization or platform-linkage failure is logged and isolated, so it can never prevent a `status: true` AutoHarvest runtime from loading.

When a newer semantic version exists, the localized message includes the `AutoHarvest` module name, installed version, latest version, and direct JAR download link. It is sent once per release to the console and to each online or joining player who is an operator or has `farmer.admin`. Set `update-checker.enable: false` to disable all outbound checks; interval and timeout values are validated and repaired like the other config entries.

## Optimize Module

`optimize-module.enable` is `false` by default. Fixed conservative queue and tracking guards still protect the server in that state, but all configured child values remain inert. Enable it on production servers to select the limits and tracking policy below.

```yaml
optimize-module:
  enable: false
  harvest:
    max-harvests-per-tick: 32
    separate-speed-for: "FARMER"
    delay-between-harvests:
      enable: false
      ticks: 2
    pause-after-batch:
      enable: false
      after-harvests: 64
      ticks: 20
  crop-search:
    mode: "EVENTS"
    triggers:
      natural-growth: true
      bone-meal: true
      crop-placement: true
      chunk-load: false
      new-farmer: true
      player-join: true
      player-sees-chunk: true
      entire-loaded-farmer-area: true
      farmer-areas-only: true
    scan-radius:
      new-farmer-radius-chunks: 8
      player-radius-chunks: 3
    repeat-search:
      every-ticks: 200
      chunks-per-run: 2
    priority:
      enable: true
      prioritized-scans-before-normal: 3
    limits:
      remembered-chunks: 8192
      scans-at-once: 1
      snapshots-per-tick: 1
      new-scans-per-second: 4
      sections-per-second: 32
      blocks-per-async-task: 8192
      waiting-scans: 4096
      crops-found-per-scan: 512
      crops-queued-per-tick: 32
  server-load-protection:
    enable: true
    slow-down-at-mspt: 35.0
    stop-at-mspt: 45.0
    resume-below-mspt: 40.0
    minimum-speed-percent: 10
    check-every-ticks: 20
    region-delay-limit-millis: 100
    region-recovery-ticks: 100
  advanced:
    harvest-queue:
      first-run-delay-ticks: 2
      next-run-delay-ticks: 1
      harvests-per-run: 8
      region-runs-per-tick: 8
      waiting-harvests: 8192
      merge-duplicate-blocks: true
```

The important controls are intentionally first. `max-harvests-per-tick` caps harvest actions across every world and Folia region. `separate-speed-for` selects the independent timing boundary: `FARMER` is the recommended island boundary, `PLAYER` shares timing across a player's Farmers, `LAND` uses the integration land/region ID, and `CHUNK` isolates each chunk.

Both user-facing cooldown systems are disabled by default. `delay-between-harvests` can add a steady delay between individual crops. `pause-after-batch` instead permits `after-harvests` attempts in one scope, pauses only that scope for `ticks`, and then continues automatically; unrelated Farmers/islands continue normally. Twenty ticks are approximately one second. Disabling both removes artificial Farmer cooldowns while the global queue, scheduler, and server load protections remain active.

Config version 12 adds complete loaded-Farmer-area discovery and fair crop-pressure priority. Existing files are backed up and migrated automatically; valid optimization values, enabled modes, custom unknown entries, and runtime behavior are preserved. Version 11 introduced independent debug and bounded error-file settings, while version 10's retired telemetry switch remains disabled until `logging.debug` is explicitly enabled. Old `OWNER`, `REGION`, `EVENT_DRIVEN`, `PERIODIC_LOADED_CHUNKS`, and `HYBRID` values become the clearer `PLAYER`, `LAND`, `EVENTS`, `TIMER`, and `BOTH` names.

The advanced hard limits remain global and bounded: `region-runs-per-tick` caps region task fan-out, and `sections-per-second` caps primary snapshot block reads in 4096-block units. `blocks-per-async-task` prevents one async task from monopolizing a worker. Queue overflow leaves the live crop untouched and requests another bounded search; it never bypasses limits with a direct task. Reaching `remembered-chunks` only limits the hot chunk cache, while reaching `waiting-scans` keeps the affected Farmer-area cursor at the same chunk for a later retry.

Server load protection uses Paper's rolling MSPT with hysteresis. Above `slow-down-at-mspt`, it gradually scales harvest jobs, region scheduler submissions, repeat searches, snapshot captures, scan starts, section reads, and async task sizes down toward `minimum-speed-percent`. At `stop-at-mspt` it temporarily stops new work and resumes only below `resume-below-mspt`. It also observes region callback delay, which protects Folia/Leaf when one region is overloaded even if a global average looks healthy. Debug logging emits cumulative counters only at the configured low frequency and only when useful work, deferral, or failure exists.

The optimization path is async-safe: immutable `ChunkSnapshot` data is analyzed asynchronously, while snapshot capture, Farmer scope admission, live world validation, block mutation, item drops, Farmer lookup, and inventory updates stay on the owning `RegionScheduler` thread. Discovered crop queueing is sliced by `crops-queued-per-tick`, empty sections are skipped, chunks are never force-loaded, duplicate jobs/scans are coalesced, unload/reload generations reject stale work, and idle dispatchers park until work or a repeat search is due.

Snapshot section indexes are translated relative to the world's minimum build height. Worlds using negative Y values, including the standard `-64..320` range on Leaf, therefore scan every section without accessing a negative snapshot index. A failed initial discovery is retained in the bounded reconciliation registry so a transient capture or async scan failure cannot permanently hide an already mature crop chunk.

Dense chunks discover up to `crops-found-per-scan` crops as one bounded batch. Their observed mature-crop count becomes bounded scan pressure, so accumulated fields move ahead of empty discovery chunks. `prioritized-scans-before-normal` still forces one normal discovery scan after each priority burst. A separate per-Farmer round-robin cursor guarantees that scan limits slow all registered active islands instead of permanently excluding islands that arrive after a global queue fills. Repeat search skips a chunk while its harvest queue is still draining. When a candidate-limited chunk queue becomes empty, a single drain callback immediately requests the next bounded scan.

## Crop Tracking

- `EVENTS` is the default. Growth and bone-meal signals harvest immediately, while only known crop or deferred chunks receive slow repeat searches.
- `TIMER` disables immediate growth harvesting and rotates through a bounded registry of known loaded chunks.
- `BOTH` combines immediate events with bounded loaded-chunk rotation.
- Every signal under `crop-search.triggers` can be enabled independently. `entire-loaded-farmer-area: true` enumerates all currently loaded chunks of a SuperiorSkyblock Farmer island on join, reload, purchase, or GUI enable; it never loads an inactive chunk. On large servers, keep broad `chunk-load: false` unless every newly loaded Farmer chunk should be admitted immediately.
- With `farmer-areas-only: true`, event and player search triggers are accepted only around an enabled Farmer (or when `withoutFarmer` is active). Set it to `false` if timer mode must discover chunk-loader farms with no nearby player.
- Complete Farmer-area scans seed nearby chunks first, then retain only a small cursor per active Farmer and lazily rotate its currently loaded chunks. `chunks-per-run` is a global per-pass budget distributed round-robin across Farmer areas; increasing island count increases completion time but does not remove a registered active island from service. Chunks are never force-loaded. If the optional island API is unavailable, the module falls back to the configured player radius. Module reload and disable invalidate all queued state.

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
