package xyz.geik.farmer.modules.autoharvest.tracking;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/** Crop-pressure priority with a guaranteed FIFO discovery slot. */
final class FairScanQueue<T> {

    private final Map<T, State> states = new HashMap<>();
    private final ArrayDeque<Entry<T>> discovery = new ArrayDeque<>();
    private final PriorityQueue<Entry<T>> crops = new PriorityQueue<>(Comparator
            .<Entry<T>>comparingInt(Entry::pressure).reversed()
            .thenComparingLong(Entry::sequence));

    private long revision;
    private long sequence;
    private int prioritizedStreak;

    synchronized OfferResult offer(@NotNull T value, int pressure, int maximumSize) {
        int normalizedPressure = Math.max(0, pressure);
        State current = states.get(value);
        if (current != null) {
            if (!current.active() && normalizedPressure > current.pressure()) {
                State promoted = new State(++revision, normalizedPressure, false);
                states.put(value, promoted);
                crops.offer(new Entry<>(value, promoted.revision(), normalizedPressure, ++sequence));
                return OfferResult.PROMOTED;
            }
            return OfferResult.DUPLICATE;
        }
        if (maximumSize <= 0 || states.size() >= maximumSize) {
            return OfferResult.FULL;
        }
        State created = new State(++revision, normalizedPressure, false);
        states.put(value, created);
        Entry<T> entry = new Entry<>(value, created.revision(), normalizedPressure, ++sequence);
        if (normalizedPressure > 0) {
            crops.offer(entry);
        }
        else {
            discovery.offer(entry);
        }
        return OfferResult.ENQUEUED;
    }

    synchronized T poll(int prioritizedBeforeDiscovery) {
        int burst = Math.max(1, prioritizedBeforeDiscovery);
        Entry<T> entry = null;
        if (prioritizedStreak >= burst) {
            entry = pollValid(discovery);
        }
        if (entry == null) {
            entry = pollValid(crops);
        }
        if (entry == null) {
            entry = pollValid(discovery);
        }
        if (entry == null) {
            return null;
        }
        State state = states.get(entry.value());
        states.put(entry.value(), new State(state.revision(), state.pressure(), true));
        if (entry.pressure() > 0) {
            prioritizedStreak++;
        }
        else {
            prioritizedStreak = 0;
        }
        return entry.value();
    }

    synchronized boolean contains(@NotNull T value) {
        return states.containsKey(value);
    }

    synchronized boolean complete(@NotNull T value) {
        return states.remove(value) != null;
    }

    synchronized int size() {
        return states.size();
    }

    synchronized int prioritizedSize() {
        int count = 0;
        for (State state : states.values()) {
            if (!state.active() && state.pressure() > 0) {
                count++;
            }
        }
        return count;
    }

    synchronized boolean hasQueued() {
        discardStale(crops);
        discardStale(discovery);
        return !crops.isEmpty() || !discovery.isEmpty();
    }

    synchronized void clear() {
        states.clear();
        discovery.clear();
        crops.clear();
        prioritizedStreak = 0;
    }

    private Entry<T> pollValid(java.util.Queue<Entry<T>> queue) {
        while (true) {
            Entry<T> entry = queue.poll();
            if (entry == null) {
                return null;
            }
            if (isValid(entry)) {
                return entry;
            }
        }
    }

    private void discardStale(java.util.Queue<Entry<T>> queue) {
        while (queue.peek() != null && !isValid(queue.peek())) {
            queue.poll();
        }
    }

    private boolean isValid(Entry<T> entry) {
        State state = states.get(entry.value());
        return state != null && !state.active() && state.revision() == entry.revision();
    }

    enum OfferResult {
        ENQUEUED,
        PROMOTED,
        DUPLICATE,
        FULL
    }

    private record State(long revision, int pressure, boolean active) {
    }

    private record Entry<T>(T value, long revision, int pressure, long sequence) {
    }
}
