package me.sshcrack.mc_talking.internal.registration;

import me.sshcrack.mc_talking.api.registration.AddonRegistration;
import me.sshcrack.mc_talking.api.registration.NamespacedAddonId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime-only shared registration machinery. Excluded from the developer API artifact. */
public final class RegistrationRegistry<T> {
    private final String label;
    private final Map<String, Entry<T>> entries = new LinkedHashMap<>();

    public RegistrationRegistry(String label) {
        this.label = Objects.requireNonNull(label, "label");
    }

    public synchronized @NotNull AddonRegistration register(@NotNull String id, int order, @NotNull T value) {
        requireNamespacedId(id, label + " id");
        Objects.requireNonNull(value, label);
        if (entries.containsKey(id)) {
            throw new IllegalArgumentException(label + " already registered: " + id);
        }
        Entry<T> entry = new Entry<>(id, order, value);
        entries.put(id, entry);
        return new Handle(entry);
    }

    public synchronized @NotNull List<Entry<T>> orderedSnapshot() {
        List<Entry<T>> result = new ArrayList<>(entries.values());
        result.sort(Comparator.comparingInt(Entry<T>::order).thenComparing(Entry<T>::id));
        return List.copyOf(result);
    }

    public synchronized @NotNull List<Entry<T>> registrationSnapshot() {
        return List.copyOf(entries.values());
    }

    public synchronized @Nullable Entry<T> find(@NotNull String id) {
        return entries.get(id);
    }

    public static @NotNull String requireNamespacedId(@NotNull String id, @NotNull String label) {
        return NamespacedAddonId.require(id, label);
    }

    public record Entry<T>(@NotNull String id, int order, @NotNull T value) {
    }

    private final class Handle implements AddonRegistration {
        private final Entry<T> entry;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Handle(Entry<T> entry) {
            this.entry = entry;
        }

        @Override
        public @NotNull String id() {
            return entry.id();
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (RegistrationRegistry.this) {
                if (entries.get(entry.id()) == entry) {
                    entries.remove(entry.id());
                }
            }
        }
    }
}
