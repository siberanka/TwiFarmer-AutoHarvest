package xyz.geik.farmer.modules.autoharvest.tracking;

import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.LinkedHashSet;

/** Small synchronized FIFO set used for bounded unload/reload memory. */
final class BoundedRememberedSet<T> {

    private final LinkedHashSet<T> entries = new LinkedHashSet<>();

    synchronized void remember(@NotNull T value, int maximumSize) {
        if (maximumSize <= 0) {
            return;
        }
        entries.remove(value);
        entries.add(value);
        while (entries.size() > maximumSize) {
            Iterator<T> iterator = entries.iterator();
            iterator.next();
            iterator.remove();
        }
    }

    synchronized boolean take(@NotNull T value) {
        return entries.remove(value);
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized void clear() {
        entries.clear();
    }
}
