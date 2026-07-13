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
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ignored) {
            return FARMER;
        }
    }
}
