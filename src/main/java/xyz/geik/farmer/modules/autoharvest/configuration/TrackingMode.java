package xyz.geik.farmer.modules.autoharvest.configuration;

import java.util.Locale;

/** Selects how crop-bearing chunks enter the bounded reconciliation pipeline. */
public enum TrackingMode {
    EVENT_DRIVEN(true, false),
    PERIODIC_LOADED_CHUNKS(false, true),
    HYBRID(true, true);

    private final boolean events;
    private final boolean loadedChunks;

    TrackingMode(boolean events, boolean loadedChunks) {
        this.events = events;
        this.loadedChunks = loadedChunks;
    }

    public boolean usesEvents() {
        return events;
    }

    public boolean scansLoadedChunks() {
        return loadedChunks;
    }

    public static TrackingMode parse(String value) {
        if (value == null) {
            return EVENT_DRIVEN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ignored) {
            return EVENT_DRIVEN;
        }
    }
}
