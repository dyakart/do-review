package dev.doreview.core;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** Small LRU of derived immutable values; key equality must never traverse a history graph. */
final class BoundedIdentityCache<K, V> {
    private final int maxEntries;
    private final long maxWeight;
    private final ToLongFunction<V> weigh;
    private final LinkedHashMap<IdentityKey<K>, V> values = new LinkedHashMap<>(16, 0.75f, true);
    private long weight;

    BoundedIdentityCache(int maxEntries, long maxWeight, ToLongFunction<V> weigh) {
        if (maxEntries < 0 || maxWeight < 0) throw new IllegalArgumentException("Negative cache bound");
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
        this.weigh = Objects.requireNonNull(weigh, "weigh");
    }

    V get(K key, Runnable cancellationCheck, Function<K, V> build) {
        Objects.requireNonNull(key, "key");
        cancellationCheck.run();
        var identity = new IdentityKey<>(key);
        V cached;
        synchronized (this) { cached = values.get(identity); }
        if (cached != null) {
            cancellationCheck.run();
            return cached;
        }
        // Do not hold a cache lock while scanning source text or calling cancellation hooks.
        V prepared = Objects.requireNonNull(build.apply(key), "cache value");
        cancellationCheck.run();
        long preparedWeight = weigh.applyAsLong(prepared);
        if (preparedWeight < 0) throw new IllegalArgumentException("Negative cache weight");
        synchronized (this) {
            cached = values.get(identity);
            if (cached != null) return cached;
            if (maxEntries == 0 || preparedWeight > maxWeight) return prepared;
            var oldest = values.entrySet().iterator();
            while ((values.size() >= maxEntries || weight > maxWeight - preparedWeight) && oldest.hasNext()) {
                weight -= weigh.applyAsLong(oldest.next().getValue());
                oldest.remove();
            }
            values.put(identity, prepared);
            weight += preparedWeight;
        }
        return prepared;
    }

    synchronized int size() { return values.size(); }
    synchronized long weight() { return weight; }

    private static final class IdentityKey<K> {
        private final K value;
        private IdentityKey(K value) { this.value = value; }
        @Override public int hashCode() { return System.identityHashCode(value); }
        @Override public boolean equals(Object other) {
            return other instanceof IdentityKey<?> key && value == key.value;
        }
    }
}
