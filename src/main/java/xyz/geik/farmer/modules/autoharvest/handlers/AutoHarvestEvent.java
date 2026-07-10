package xyz.geik.farmer.modules.autoharvest.handlers;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.api.managers.FarmerManager;
import xyz.geik.farmer.helpers.WorldHelper;
import xyz.geik.farmer.model.Farmer;
import xyz.geik.farmer.model.inventory.FarmerInv;
import xyz.geik.farmer.model.inventory.FarmerItem;
import xyz.geik.farmer.modules.autoharvest.AutoHarvest;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Detects mature crops and performs an idempotent harvest on the owning region.
 *
 * @author poyraz
 * @author siberanka
 * @since 1.0.0
 */
public class AutoHarvestEvent implements Listener {

    private final AtomicBoolean lookupFailureLogged = new AtomicBoolean();
    private final AtomicBoolean harvestFailureLogged = new AtomicBoolean();

    /**
     * Defers harvesting by one region tick. At MONITOR priority the event's final
     * cancellation state is known, and the delayed task sees the applied growth.
     *
     * @param event block growth event
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHarvestGrowEvent(@NotNull BlockGrowEvent event) {
        AutoHarvest module = AutoHarvest.getInstance();
        if (module == null) {
            return;
        }

        BlockState newState = event.getNewState();
        XMaterial material = CropHarvesting.normalize(XMaterial.matchXMaterial(newState.getType()));
        if (!AutoHarvest.checkCrop(material)
                || !CropHarvesting.isHarvestableGrowth(newState.getBlockData(), material)
                || !WorldHelper.isFarmerAllowed(newState.getWorld().getName())) {
            return;
        }

        Location location = newState.getLocation();
        long generation = module.getLifecycleGeneration();
        module.scheduleHarvest(location, () -> harvestIfEligible(location, material, generation));
    }

    private void harvestIfEligible(@NotNull Location location, @NotNull XMaterial material, long generation) {
        AutoHarvest module = AutoHarvest.getInstance();
        if (module == null || !module.isActiveGeneration(generation)) {
            return;
        }

        try {
            Block block = location.getBlock();
            if (!WorldHelper.isFarmerAllowed(block.getWorld().getName())
                    || !AutoHarvest.checkCrop(material)
                    || !CropHarvesting.isStillHarvestable(block, material)
                    || (module.isRequirePiston() && !pistonCheck(block, module.isCheckAllDirections()))) {
                return;
            }

            if (module.isWithoutFarmer()) {
                harvest(block, material);
                return;
            }

            Farmer farmer = findFarmer(location);
            if (farmer == null) {
                return;
            }

            // A Farmer region can span multiple Folia regions. Serializing this
            // module's stock/drop path prevents concurrent inventory mutations.
            synchronized (farmer) {
                if (!isCurrentFarmer(location, farmer)
                        || !farmer.getAttributeStatus("autoharvest")
                        || !hasStock(farmer, material)) {
                    return;
                }
                harvest(block, material);
            }
        }
        catch (RuntimeException exception) {
            if (harvestFailureLogged.compareAndSet(false, true)) {
                Main.getInstance().getLogger().log(Level.WARNING,
                        "AutoHarvest rejected a crop operation after an unexpected runtime error.", exception);
            }
        }
    }

    private void harvest(@NotNull Block block, @NotNull XMaterial material) {
        List<ItemStack> drops = CropHarvesting.snapshotDrops(block);
        if (drops.isEmpty()) {
            ItemStack fallback = material.parseItem();
            if (fallback != null) {
                drops.add(fallback);
            }
        }
        CropHarvesting.harvest(block, material, drops);
    }

    private Farmer findFarmer(@NotNull Location location) {
        try {
            String regionId = Main.getIntegration().getRegionID(location);
            return regionId == null ? null : FarmerManager.getFarmers().get(regionId);
        }
        catch (RuntimeException exception) {
            if (lookupFailureLogged.compareAndSet(false, true)) {
                Main.getInstance().getLogger().log(Level.WARNING,
                        "AutoHarvest could not resolve the Farmer region; harvesting was denied.", exception);
            }
            return null;
        }
    }

    private boolean isCurrentFarmer(@NotNull Location location, @NotNull Farmer expected) {
        return findFarmer(location) == expected;
    }

    private boolean hasStock(@NotNull Farmer farmer, @NotNull XMaterial material) {
        AutoHarvest module = AutoHarvest.getInstance();
        if (module == null || !module.isCheckStock() || farmer.getAttributeStatus("autoseller")) {
            return true;
        }

        if (!isStockAvailable(farmer, material)) {
            return false;
        }

        XMaterial seed = seedFor(material);
        ItemStack seedItem = seed.parseItem();
        return seed == XMaterial.AIR
                || seedItem == null
                || !FarmerInv.checkMaterial(seedItem)
                || isStockAvailable(farmer, seed);
    }

    private boolean isStockAvailable(@NotNull Farmer farmer, @NotNull XMaterial material) {
        ItemStack item = material.parseItem();
        if (item == null || !FarmerInv.checkMaterial(item)) {
            return false;
        }

        try {
            FarmerItem stockedItem = farmer.getInv().getStockedItem(material);
            return stockedItem.getAmount() < farmer.getInv().getCapacity();
        }
        catch (NoSuchElementException exception) {
            return false;
        }
    }

    private XMaterial seedFor(@NotNull XMaterial material) {
        return switch (material.name()) {
            case "WHEAT" -> XMaterial.WHEAT_SEEDS;
            case "BEETROOT" -> XMaterial.BEETROOT_SEEDS;
            default -> XMaterial.AIR;
        };
    }

    private boolean pistonCheck(@NotNull Block block, boolean allDirections) {
        if (allDirections) {
            return isPiston(block.getRelative(BlockFace.NORTH))
                    || isPiston(block.getRelative(BlockFace.SOUTH))
                    || isPiston(block.getRelative(BlockFace.EAST))
                    || isPiston(block.getRelative(BlockFace.WEST))
                    || isPiston(block.getRelative(BlockFace.UP));
        }
        return isPiston(block.getRelative(BlockFace.UP));
    }

    private boolean isPiston(@NotNull Block block) {
        return block.getType().name().contains("PISTON");
    }
}
