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
2. Place `Farmer-AutoHarvest-1.2.5.jar` in `plugins/Farmer/modules/`.
3. Restart the server.
4. Configure `plugins/Farmer/modules/autoharvest/config.yml`.

The module refuses to enable when Paper's region scheduler API is unavailable.

Crop block discovery uses an explicit modern-material table. Legacy Bukkit constants are never passed to XMaterial during startup, allowing the module and its management-panel icon to load reliably on current Paper, Folia, and Leaf builds.

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
  queue:
    initial-delay-ticks: 2
    continuation-delay-ticks: 1
    max-jobs-per-run: 8
    global-max-jobs-per-tick: 32
    max-scheduler-submissions-per-tick: 8
    max-pending-jobs: 4096
    coalesce-duplicates: true
  tracking:
    mode: "EVENT_DRIVEN"
    conditions:
      growth-events: true
      fertilize-events: true
      crop-place-events: true
      scan-on-chunk-load: false
      scan-on-farmer-purchase: true
      scan-on-player-join: true
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
    max-candidates-per-scan: 128
    purchase-radius-chunks: 8
    bootstrap-radius-chunks: 3
  adaptive-backpressure:
    enable: true
    pause-above-mspt: 45.0
    resume-below-mspt: 40.0
    check-interval-ticks: 20
    pause-above-region-task-delay-millis: 100
    region-cooldown-ticks: 100
  telemetry:
    enable: true
    log-interval-seconds: 300
```

The most important hard limits are global, not per chunk: `global-max-jobs-per-tick` caps harvest actions across every world and Folia region, `max-scheduler-submissions-per-tick` caps region task fan-out, and `max-sections-per-second` caps primary snapshot block reads in 4096-block units. `max-block-checks-per-slice` prevents one async task from monopolizing a worker. Queue overflow leaves the live crop untouched and requests bounded reconciliation; it never bypasses limits with a direct task.

Adaptive backpressure uses Paper's rolling MSPT with hysteresis. It also observes region callback delay, which protects Folia/Leaf when one region is overloaded even if a global average looks healthy. Telemetry logs cumulative queue/scan counters only at the configured low frequency and only when useful work, deferral, or failure exists.

The optimization path is async-safe: immutable `ChunkSnapshot` data is analyzed asynchronously, while snapshot capture, live world validation, block mutation, item drops, Farmer lookup, and inventory updates stay on the owning `RegionScheduler` thread. Empty sections are skipped, chunks are never force-loaded, duplicate jobs/scans are coalesced, unload/reload generations reject stale work, and idle dispatchers park until work or reconciliation is due.

## Crop Tracking

- `EVENT_DRIVEN` is the default. Growth/fertilize signals harvest immediately, while only known crop or deferred chunks receive slow reconciliation.
- `PERIODIC_LOADED_CHUNKS` disables immediate growth harvesting and rotates through a bounded registry of known loaded chunks.
- `HYBRID` combines immediate events with bounded loaded-chunk rotation.
- Every signal under `tracking.conditions` can be enabled independently. On large servers, keep `scan-on-chunk-load: false` unless periodic discovery is explicitly required.
- With `farmer-regions-only: true`, event and player bootstrap tracking is accepted only around an enabled Farmer (or when `withoutFarmer` is active). Set it to `false` if periodic mode must discover chunk-loader farms with no nearby player.
- Farmer purchase and GUI enable scans are nearest-first and inspect only chunks already sent to the player. Chunk unload, module reload, and disable remove or invalidate queued state.

## Harvest Guarantees

- Mature crops are revalidated after growth before any drop or block mutation.
- Snapshot candidates are revalidated against the live block and current Farmer ownership before harvesting.
- Ageable crops are reset only after real mature-block drops are captured.
- Sugar cane and cactus preserve their base and only remove the current top segment.
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
