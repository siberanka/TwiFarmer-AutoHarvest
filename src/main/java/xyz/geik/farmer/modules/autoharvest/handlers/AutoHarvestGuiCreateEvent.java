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
import xyz.geik.farmer.modules.autoharvest.AutoHarvest;
import xyz.geik.glib.chat.ChatUtils;
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
                                    // If player don't have permission do nothing
                                    if (!e.getPlayer().hasPermission(AutoHarvest.getInstance().getCustomPerm()))
                                        return true;
                                    if (!allowToggle(e.getPlayer().getUniqueId()))
                                        return true;
                                    // Change attribute
                                    synchronized (e.getFarmer()) {
                                        e.getFarmer().changeAttribute("autoharvest");
                                        e.getGui().draw();
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
        ItemStack item = GuiHelper.getItem("moduleGui.icon", AutoHarvest.getInstance().getLang());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        String status = farmer.getAttributeStatus("autoharvest") ?
                AutoHarvest.getInstance().getLang().getString("enabled") :
                AutoHarvest.getInstance().getLang().getString("disabled");
        List<String> lore = meta.getLore();
        if (lore != null) {
            List<String> updatedLore = new ArrayList<>(lore.size());
            for (String line : lore) {
                updatedLore.add(line == null ? "" : line.replace("{status}", ChatUtils.color(status)));
            }
            meta.setLore(updatedLore);
        }
        item.setItemMeta(meta);
        return item;
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
