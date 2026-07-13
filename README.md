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
2. Place `Farmer-AutoHarvest-1.2.1.jar` in `plugins/Farmer/modules/`.
3. Restart the server.
4. Configure `plugins/Farmer/modules/autoharvest/config.yml`.

The module refuses to enable when Paper's region scheduler API is unavailable.

## Automatic File Maintenance

At startup and Farmer reload, AutoHarvest validates its config and bundled language files (`en`, `tr`, `de`). Missing known entries are added automatically. Invalid YAML, wrong data types, empty required text, invalid permissions, invalid crop names, duplicate crop names, and invalid optimization limits are repaired.

Before changing an existing file, the original is copied to a local backup directory:

| File | Backup directory |
| --- | --- |
| `config.yml` | `plugins/Farmer/modules/autoharvest/backups/` |
| `lang/*.yml` | `plugins/Farmer/modules/autoharvest/lang/backups/` |

Unknown entries are preserved so later module versions and server-specific additions are not discarded.

## Optimize Module

`optimize-module.enable` is `false` by default. When enabled, mature crops from the same chunk are coalesced into a bounded region queue. The queue delays and batches work to reduce scheduler pressure during large farm bursts.

```yaml
optimize-module:
  enable: false
  queue:
    initial-delay-ticks: 2
    continuation-delay-ticks: 1
    max-jobs-per-run: 32
    max-pending-jobs: 4096
    coalesce-duplicates: true
  tracking:
    reconcile-interval-ticks: 100
    max-chunks-per-cycle: 2
    max-tracked-chunks: 8192
    max-concurrent-scans: 2
    max-pending-scans: 4096
    max-candidates-per-scan: 512
    purchase-radius-chunks: 8
    bootstrap-radius-chunks: 3
```

All child settings are ignored while `enable` is `false`. Correctness tracking remains active with a fixed, conservative baseline; enabling the module applies the configurable production budgets above.

- `initial-delay-ticks`: delay before the first queued harvest for a chunk.
- `continuation-delay-ticks`: delay between later queue batches.
- `max-jobs-per-run`: upper bound for one region execution.
- `max-pending-jobs`: global memory and burst guard.
- `coalesce-duplicates`: merges repeated growth events for the same block while work is pending.
- `reconcile-interval-ticks`: interval for revisiting chunks known to contain crops.
- `max-chunks-per-cycle`: tracked chunks queued on each reconciliation interval.
- `max-tracked-chunks`: strict in-memory bound for crop-bearing chunks.
- `max-concurrent-scans`: maximum immutable snapshots analyzed concurrently.
- `max-pending-scans`: strict bound for coalesced chunk scan requests.
- `max-candidates-per-scan`: maximum mature crops submitted from one snapshot pass.
- `purchase-radius-chunks`: loaded area checked after a Farmer purchase or AutoHarvest enable.
- `bootstrap-radius-chunks`: loaded area checked around online players after enable/reload.

The optimization path is async-safe, not unsafe asynchronous Bukkit access: immutable `ChunkSnapshot` data is analyzed asynchronously, while snapshot capture, live world validation, block mutation, item drops, Farmer lookup, and inventory updates stay on the owning `RegionScheduler` thread. Empty chunk sections are skipped, chunks are never force-loaded, duplicate scans are coalesced, and every queue has a hard memory/concurrency bound. If the harvest queue reaches its bound, that crop safely falls back to the normal one-tick region task rather than being lost.

## Crop Tracking

- `BlockGrowEvent` remains the immediate hot path for normal natural growth.
- Bone-meal growth is observed through `BlockFertilizeEvent`.
- Farmer purchase and enabling AutoHarvest trigger a nearest-first scan of already loaded chunks.
- Chunk load and player bootstrap scans discover mature crops that existed before the module saw a growth event.
- Only chunks proven to contain configured crops enter periodic reconciliation.
- Immature crops keep their chunk tracked and are harvested when a later snapshot sees maturity.
- Chunk unload, module reload, and disable remove tracking state and queued work.

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
