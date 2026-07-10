# Farmer AutoHarvest Module

Automatically harvests mature crops inside Farmer regions and replants ageable crops. The module requires Paper-compatible server software and supports Paper, Folia, and Leaf.

## Compatibility

| Minecraft | Java | Server | Farmer |
| --- | --- | --- | --- |
| 1.21.x | 21 | Paper, Folia, Leaf | v6-b113 or newer |
| 26.x | 25 | Paper, Folia, Leaf | v6-b113 or newer |

Plain Bukkit and Spigot are intentionally unsupported. This is an external Farmer module, not a standalone `JavaPlugin`; the Farmer host plugin supplies the Folia metadata while this module uses Paper's region scheduler for all world access.

## Installation

1. Install Farmer v6-b113 or newer on Paper, Folia, or Leaf.
2. Place `Farmer-AutoHarvest-1.2.0.jar` in `plugins/Farmer/modules/`.
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
```

All child settings are ignored while `enable` is `false`.

- `initial-delay-ticks`: delay before the first queued harvest for a chunk.
- `continuation-delay-ticks`: delay between later queue batches.
- `max-jobs-per-run`: upper bound for one region execution.
- `max-pending-jobs`: global memory and burst guard.
- `coalesce-duplicates`: merges repeated growth events for the same block while work is pending.

The optimization path is async-safe, not unsafe asynchronous Bukkit access: concurrent queue bookkeeping may happen across Folia regions, but every world read, block mutation, item drop, Farmer lookup, and inventory update still runs on the owning `RegionScheduler` thread. If the queue reaches its bound, that crop safely falls back to the normal one-tick region task rather than being lost.

## Harvest Guarantees

- Mature crops are revalidated after growth before any drop or block mutation.
- Ageable crops are reset only after real mature-block drops are captured.
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
