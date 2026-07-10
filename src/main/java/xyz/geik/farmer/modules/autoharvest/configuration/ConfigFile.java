package xyz.geik.farmer.modules.autoharvest.configuration;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * Validated module configuration snapshot.
 *
 * @author geik
 * @author siberanka
 * @since 1.2.0
 */
public final class ConfigFile {

    private final boolean status;
    private final boolean requirePiston;
    private final boolean checkAllDirections;
    private final boolean withoutFarmer;
    private final boolean checkStock;
    private final boolean defaultStatus;
    private final String customPerm;
    private final List<String> items;

    public ConfigFile(
            boolean status,
            boolean requirePiston,
            boolean checkAllDirections,
            boolean withoutFarmer,
            boolean checkStock,
            boolean defaultStatus,
            String customPerm,
            List<String> items
    ) {
        this.status = status;
        this.requirePiston = requirePiston;
        this.checkAllDirections = checkAllDirections;
        this.withoutFarmer = withoutFarmer;
        this.checkStock = checkStock;
        this.defaultStatus = defaultStatus;
        this.customPerm = customPerm;
        this.items = List.copyOf(items);
    }

    public static ConfigFile from(ConfigurationSection configuration) {
        return new ConfigFile(
                configuration.getBoolean("status"),
                configuration.getBoolean("requirePiston"),
                configuration.getBoolean("checkAllDirections"),
                configuration.getBoolean("withoutFarmer"),
                configuration.getBoolean("checkStock"),
                configuration.getBoolean("defaultStatus"),
                configuration.getString("customPerm", "farmer.autoharvest"),
                configuration.getStringList("items")
        );
    }

    public boolean isStatus() {
        return status;
    }

    public boolean isRequirePiston() {
        return requirePiston;
    }

    public boolean isCheckAllDirections() {
        return checkAllDirections;
    }

    public boolean isWithoutFarmer() {
        return withoutFarmer;
    }

    public boolean isCheckStock() {
        return checkStock;
    }

    public boolean isDefaultStatus() {
        return defaultStatus;
    }

    public String getCustomPerm() {
        return customPerm;
    }

    public List<String> getItems() {
        return items;
    }
}
