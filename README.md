# Farmer AutoHarvest Module

Automatically harvests mature crops inside Farmer regions and immediately replants ageable crops. The module is built for Paper-compatible servers and uses Paper's region scheduler for Paper, Folia, and Leaf.

## Compatibility

| Minecraft | Java | Server | Farmer |
| --- | --- | --- | --- |
| 1.21.x | 21 | Paper, Folia, Leaf | v6-b113 or newer |
| 26.x | 25 | Paper, Folia, Leaf | v6-b113 or newer |

Plain Bukkit and Spigot servers are intentionally unsupported. This project is an external Farmer module rather than a standalone `JavaPlugin`, so Folia capability metadata is supplied by the Farmer host plugin. Farmer v6-b113 declares Folia support and the module performs block work through Paper's `RegionScheduler`.

## Installation

1. Install Farmer v6-b113 or newer on a compatible Paper, Folia, or Leaf server.
2. Place `Farmer-AutoHarvest-1.1.0.jar` in `plugins/Farmer/modules/`.
3. Restart the server.
4. Enable the module in `plugins/Farmer/modules/autoharvest/config.yml`.

The module refuses to enable when Paper's region scheduler API is unavailable.

## Behavior

- Mature crop handling is delayed by one region tick so the final `BlockGrowEvent` state is applied first.
- The delayed operation revalidates the world, crop type, maturity, piston requirement, Farmer ownership, module state, and stock state.
- Ageable crops are reset to age zero after their real mature-block drops are captured.
- Fruit and vertical-growth blocks are removed only after their real drops are captured.
- A stale or duplicate scheduled operation cannot harvest a crop that is no longer mature.
- Farmer inventory access triggered by this module is serialized per Farmer object for Folia regions.

## Configuration

The generated `config.yml` documents every setting:

- `status`: enables the module.
- `requirePiston`: requires a piston near the crop.
- `checkAllDirections`: checks horizontal sides in addition to the block above.
- `withoutFarmer`: allows harvesting outside Farmer-backed regions.
- `checkStock`: prevents harvesting when the relevant Farmer stock is full.
- `defaultStatus`: initial AutoHarvest state for a Farmer.
- `customPerm`: permission required for the GUI toggle.
- `items`: base item names to harvest. Invalid names are rejected once during configuration loading.

## Building

The release JAR targets Java 21 bytecode so the same artifact runs on 1.21.x and 26.x:

```bash
mvn clean verify -Ppaper-1.21
```

Compile against the 26.x Paper API with JDK 25:

```bash
mvn clean verify -Ppaper-26
```

## Authors

Geik, Poyraz, and siberanka.
