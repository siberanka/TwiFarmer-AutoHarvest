package xyz.geik.farmer.modules.autoharvest.tracking;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Minimal per-Farmer cursor state with strict round-robin admission. */
final class FairFarmerAreaQueue {

    private final Map<String, State> states = new HashMap<>();
    private final ArrayDeque<Entry> order = new ArrayDeque<>();
    private long revision;

    synchronized boolean register(@NotNull String regionId) {
        if (states.containsKey(regionId)) {
            return false;
        }
        State state = new State(++revision, 0, false);
        states.put(regionId, state);
        order.offerLast(new Entry(regionId, state.revision()));
        return true;
    }

    synchronized Lease poll() {
        while (true) {
            Entry entry = order.pollFirst();
            if (entry == null) {
                return null;
            }
            State state = states.get(entry.regionId());
            if (state == null || state.active() || state.revision() != entry.revision()) {
                continue;
            }
            states.put(entry.regionId(), new State(state.revision(), state.cursor(), true));
            return new Lease(entry.regionId(), state.revision(), state.cursor());
        }
    }

    synchronized boolean complete(@NotNull Lease lease, int nextCursor, boolean keep) {
        State state = states.get(lease.regionId());
        if (state == null || !state.active() || state.revision() != lease.revision()) {
            return false;
        }
        if (!keep) {
            states.remove(lease.regionId());
            return true;
        }
        State next = new State(++revision, Math.max(0, nextCursor), false);
        states.put(lease.regionId(), next);
        order.offerLast(new Entry(lease.regionId(), next.revision()));
        return true;
    }

    synchronized int size() {
        return states.size();
    }

    synchronized void clear() {
        states.clear();
        order.clear();
    }

    record Lease(@NotNull String regionId, long revision, int cursor) {
    }

    private record State(long revision, int cursor, boolean active) {
    }

    private record Entry(String regionId, long revision) {
    }
}
