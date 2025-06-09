# Farmer AutoHarvest Module

Automatically harvests mature crops for your Farmer regions, with flexible piston requirements, stock handling, and performance tuning.

---

## 📦 Installation

1. Place the `AutoHarvest` folder into `plugins/Farmer/modules/`.  
2. Restart your server.  
3. A `config.yml` and matching language file will be created under `plugins/Farmer/modules/AutoHarvest/`.

---

## ⚙️ Features

- **Automatic Crop Harvesting**  
  Detects when configured crops reach maturity and harvests them for the farm owner.

- **Optional Piston Requirement**  
  Only harvest when a piston is placed above (or in any direction) of the crop, reducing accidental pickups.

- **Stock-Aware Growth**  
  Prevent drops when your inventory is full, or allow crops to drop on the ground instead.

- **Farmer-Linked or Standalone**  
  Operates within a Farmer region or globally if no Farmer is present.

- **Performance Controls**  
  Toggle per-crop piston checks and multi-direction scans to balance performance on large farms.

---

## 🔧 Configuration

All options are documented inline in `config.yml`. Key settings include:

- **status**  
  Enable or disable automatic harvesting.

- **requirePiston** & **checkAllDirections**  
  Define whether a piston must be present (and in which directions) for harvest to occur.

- **withoutFarmer**  
  Allow harvesting even if no Farmer region is active.

- **checkStock**  
  Prevent harvesting when stock is full, or allow ground drops.

- **defaultStatus**  
  Default enabled state for new farms.

- **items**  
  List of crop types (by base item name) to harvest.

---

## 🤝 Contributing

1. Fork the repository.  
2. Add your improvements or fixes.  
3. Open a pull request against `develop`.  

Please follow existing code style and update documentation as needed.

---
