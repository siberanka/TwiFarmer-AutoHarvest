package xyz.geik.farmer.modules.autoharvest.configuration;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/** Validated settings for upward-growing crop columns. */
public record StackedCropSettings(
        boolean enabled,
        List<String> items,
        int maxSegmentsPerHarvest
) {

    public StackedCropSettings {
        items = List.copyOf(items);
    }

    public static StackedCropSettings from(ConfigurationSection configuration) {
        return new StackedCropSettings(
                configuration.getBoolean("stacked-crops.enable"),
                configuration.getStringList("stacked-crops.items"),
                configuration.getInt("stacked-crops.max-segments-per-harvest")
        );
    }

    public static StackedCropSettings disabled() {
        return new StackedCropSettings(false, List.of(), 32);
    }
}
