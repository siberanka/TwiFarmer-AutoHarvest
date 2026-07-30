package xyz.geik.farmer.modules.autoharvest.handlers;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.api.handlers.FarmerModuleGuiCreateEvent;
import xyz.geik.farmer.helpers.gui.GuiHelper;
import xyz.geik.farmer.model.Farmer;
import xyz.geik.farmer.model.FarmerLevel;
import xyz.geik.farmer.modules.autoharvest.AutoHarvest;
import xyz.geik.glib.chat.ChatUtils;
import xyz.geik.glib.chat.Placeholder;
import xyz.geik.glib.shades.inventorygui.DynamicGuiElement;
import xyz.geik.glib.shades.inventorygui.StaticGuiElement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Auto Harvest GUI events.
 *
 * @author poyraz
 * @author siberanka
 */
public class AutoHarvestGuiCreateEvent implements Listener {

    private static final long CLICK_COOLDOWN_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private final ConcurrentMap<UUID, Long> lastToggle = new ConcurrentHashMap<>();

    /**
     * Creates the GUI element for the farmer GUI for the module
     *
     * @param e of event
     */
    @EventHandler
    public void onGuiCreateEvent(@NotNull FarmerModuleGuiCreateEvent e) {
        char icon = AutoHarvest.getInstance()
                .getLang().getString("moduleGui.icon.guiInterface").charAt(0);
        e.getGui().addElement(
                new DynamicGuiElement(icon, (viewer) ->
                        new StaticGuiElement(
                                icon,
                                // Item here
                                getGuiItem(e.getFarmer()),
                                1,
                                // Event written bottom
                                click -> {
                                    AutoHarvest module = AutoHarvest.getInstance();
                                    if (!allowToggle(e.getPlayer().getUniqueId()))
                                        return true;
                                    if (!module.isAvailableFor(e.getFarmer())) {
                                        sendLevelRequired(e.getFarmer(), e.getPlayer());
                                        return true;
                                    }
                                    // If player don't have permission do nothing
                                    if (!e.getPlayer().hasPermission(module.getCustomPerm()))
                                        return true;
                                    // Change attribute
                                    synchronized (e.getFarmer()) {
                                        boolean enabled = e.getFarmer().changeAttribute("autoharvest");
                                        e.getGui().draw();
                                        if (enabled) {
                                            AutoHarvest.getInstance().scanAround(e.getPlayer());
                                        }
                                    }
                                    return true;
                                })
                )
        );
    }

    /**
     * Gets item of gui
     *
     * @param farmer of region
     * @return ItemStack of auto harvest gui
     */
    @SuppressWarnings("deprecation")
    private @NotNull ItemStack getGuiItem(@NotNull Farmer farmer) {
        AutoHarvest module = AutoHarvest.getInstance();
        ItemStack item = GuiHelper.getItem("moduleGui.icon", module.getLang());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        int currentLevel = FarmerLevel.getLevelNumber(farmer.getLevel());
        boolean available = module.isAvailableFor(farmer);
        String status = available
                ? (farmer.getAttributeStatus("autoharvest")
                    ? module.getLang().getString("enabled")
                    : module.getLang().getString("disabled"))
                : module.getLang().getString("locked");
        String action = available
                ? module.getLang().getString("moduleGui.click-to-toggle")
                : ChatUtils.replacePlaceholders(
                        module.getLang().getString("moduleGui.upgrade-to-unlock"),
                        new Placeholder("{required_level}", String.valueOf(module.getRequiredFarmerLevel())));
        List<String> lore = meta.getLore();
        if (lore != null) {
            List<String> updatedLore = new ArrayList<>(lore.size());
            for (String line : lore) {
                updatedLore.add(line == null ? "" : ChatUtils.replacePlaceholders(
                        line,
                        new Placeholder("{status}", ChatUtils.color(status)),
                        new Placeholder("{required_level}", String.valueOf(module.getRequiredFarmerLevel())),
                        new Placeholder("{current_level}", String.valueOf(currentLevel)),
                        new Placeholder("{action}", ChatUtils.color(action))));
            }
            meta.setLore(updatedLore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private void sendLevelRequired(@NotNull Farmer farmer, org.bukkit.entity.Player player) {
        AutoHarvest module = AutoHarvest.getInstance();
        ChatUtils.sendMessage(player, ChatUtils.replacePlaceholders(
                module.getLang().getString("level-required"),
                new Placeholder("{required_level}", String.valueOf(module.getRequiredFarmerLevel())),
                new Placeholder("{current_level}", String.valueOf(FarmerLevel.getLevelNumber(farmer.getLevel())))));
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        lastToggle.remove(event.getPlayer().getUniqueId());
    }

    private boolean allowToggle(UUID playerId) {
        long now = System.nanoTime();
        Long previous = lastToggle.put(playerId, now);
        return previous == null || now - previous >= CLICK_COOLDOWN_NANOS;
    }

    public void clear() {
        lastToggle.clear();
    }
}
