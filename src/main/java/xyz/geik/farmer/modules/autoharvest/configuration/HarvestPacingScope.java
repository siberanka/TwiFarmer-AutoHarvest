package xyz.geik.farmer.modules.autoharvest.configuration;

import java.util.Locale;

/** Selects which Farmer boundary receives an independent steady-harvest pace. */
public enum HarvestPacingScope {
    OWNER,
    FARMER,
    REGION,
    CHUNK;

    public static HarvestPacingScope parse(String value) {
        if (value == null) {
            return FARMER;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("PLAYER".equals(normalized)) {
            return OWNER;
        }
        if ("LAND".equals(normalized)) {
            return REGION;
        }
        try {
            return valueOf(normalized);
        }
        catch (IllegalArgumentException ignored) {
            return FARMER;
        }
    }
}
