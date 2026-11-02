package me.sshcrack.mc_talking.util;

import com.minecolonies.api.colony.IColony;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.api.colony.AddonColonyEvent;
import me.sshcrack.mc_talking.api.colony.ColonyEventListener;
import me.sshcrack.mc_talking.api.colony.ColonyEventType;
import me.sshcrack.mc_talking.api.colony.ColonyEventView;
import me.sshcrack.mc_talking.api.registration.AddonRegistration;
import me.sshcrack.mc_talking.duck.ColonyEventDataProvider;
import me.sshcrack.mc_talking.internal.registration.RegistrationRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class ColonyEventBuffer {

    private ColonyEventBuffer() {}

    public enum EventType {
        RAID,
        CITIZEN_DEATH,
        CITIZEN_BORN,
        CITIZEN_HIRED,
        CITIZEN_RESURRECTED,
        CITIZEN_JOB_CHANGE,
        BUILDING_ADDED,
        BUILDING_REMOVED,
        BUILDING_UPGRADED,
        COLONY_FOUNDED,
        ADDON
    }

    /** One recorded event; addon events carry their namespace and key. */
    public record ColonyEvent(EventType type, String description, long timestampTicks,
                              @Nullable String addonNamespace, @Nullable String addonKey) {
        public ColonyEvent(EventType type, String description, long timestampTicks) {
            this(type, description, timestampTicks, null, null);
        }

        public boolean isAddon() {
            return type == EventType.ADDON;
        }

        public ColonyEventView toView() {
            return new ColonyEventView(ColonyEventType.valueOf(type.name()), addonNamespace, addonKey, description, timestampTicks);
        }

        public CompoundTag serialize() {
            CompoundTag tag = new CompoundTag();
            tag.putString("type", type.name());
            tag.putString("description", description);
            tag.putLong("timestampTicks", timestampTicks);
            if (addonNamespace != null) tag.putString("addonNamespace", addonNamespace);
            if (addonKey != null) tag.putString("addonKey", addonKey);
            return tag;
        }

        @Nullable
        public static ColonyEvent deserialize(CompoundTag tag) {
            try {
                EventType type = EventType.valueOf(tag.getString("type"));
                String namespace = tag.contains("addonNamespace") ? tag.getString("addonNamespace") : null;
                String key = tag.contains("addonKey") ? tag.getString("addonKey") : null;
                if (type == EventType.ADDON && (namespace == null || key == null)) {
                    McTalking.LOGGER.warn("Skipping corrupt addon colony event without namespace/key");
                    return null;
                }
                return new ColonyEvent(type, tag.getString("description"), tag.getLong("timestampTicks"), namespace, key);
            } catch (IllegalArgumentException e) {
                McTalking.LOGGER.warn("Skipping corrupt colony event: unknown type '{}'", tag.getString("type"));
                return null;
            }
        }
    }

    /** Core events kept per colony. */
    static final int MAX_EVENTS = 20;
    /** Addon events kept per colony and namespace; separate from the core budget. */
    static final int MAX_ADDON_EVENTS_PER_NAMESPACE = 10;

    private static final RegistrationRegistry<ColonyEventListener> LISTENERS =
            new RegistrationRegistry<>("Colony event listener");

    /** Drops the oldest events beyond the core budget and each addon namespace's budget. Newest events are first. */
    public static void trimEvents(Deque<ColonyEvent> buffer) {
        int core = 0;
        Map<String, Integer> perNamespace = new HashMap<>();
        for (Iterator<ColonyEvent> it = buffer.iterator(); it.hasNext(); ) {
            ColonyEvent event = it.next();
            boolean keep = event.isAddon()
                    ? perNamespace.merge(event.addonNamespace(), 1, Integer::sum) <= MAX_ADDON_EVENTS_PER_NAMESPACE
                    : ++core <= MAX_EVENTS;
            if (!keep) it.remove();
        }
    }

    public static AddonRegistration registerListener(String id, int order, ColonyEventListener listener) {
        return LISTENERS.register(id, order, listener);
    }

    /** Calls every listener in order; one failing listener does not affect the others. */
    static void dispatch(IColony colony, ColonyEvent event) {
        var snapshot = LISTENERS.orderedSnapshot();
        if (snapshot.isEmpty()) return;
        ColonyEventView view = event.toView();
        for (var registration : snapshot) {
            try {
                registration.value().onEvent(colony, view);
            } catch (Throwable t) {
                McTalking.LOGGER.error("Colony event listener {} failed and was skipped", registration.id(), t);
            }
        }
    }

    private static ColonyEventDataProvider getProvider(IColony colony) {
        return (ColonyEventDataProvider) colony;
    }

    private static long currentTick(IColony colony) {
        Level world = colony.getWorld();
        if (world == null) return 0;
        return world.getGameTime();
    }

    public static void recordRaid(IColony colony, int lostCitizens) {
        long now = currentTick(colony);
        var provider = getProvider(colony);
        provider.mc_talking$setLastRaidEndTime(now);
        provider.mc_talking$setLastRaidLostCitizens(lostCitizens);
        recordEvent(colony, EventType.RAID, lostCitizens + " citizens lost in raid");
    }

    public static void recordEvent(IColony colony, EventType type, String description) {
        if (type == EventType.ADDON) throw new IllegalArgumentException("use recordAddonEvent");
        record(colony, new ColonyEvent(type, description, currentTick(colony)));
    }

    public static void recordAddonEvent(IColony colony, AddonColonyEvent event) {
        record(colony, new ColonyEvent(EventType.ADDON, event.description(), currentTick(colony),
                event.namespace(), event.key()));
    }

    private static void record(IColony colony, ColonyEvent event) {
        var buffer = getProvider(colony).mc_talking$getOrCreateEvents();
        buffer.addFirst(event);
        trimEvents(buffer);
        dispatch(colony, event);
    }

    public static List<ColonyEvent> getRecentEvents(IColony colony, int maxAgeSeconds) {
        var provider = getProvider(colony);
        var buffer = provider.mc_talking$getOrCreateEvents();
        if (buffer.isEmpty()) return List.of();
        long gameTime = currentTick(colony);
        long cutoff = gameTime - (maxAgeSeconds * 20L);
        List<ColonyEvent> result = new ArrayList<>();
        for (ColonyEvent event : buffer) {
            if (event.timestampTicks() >= cutoff) {
                result.add(event);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean isInTrauma(IColony colony, int durationSeconds) {
        if (durationSeconds <= 0) return false;
        var provider = getProvider(colony);
        long lastEnd = provider.mc_talking$getLastRaidEndTime();
        if (lastEnd == Long.MAX_VALUE) return false;
        long gameTime = currentTick(colony);
        return (gameTime - lastEnd) < (durationSeconds * 20L);
    }

    public static long ticksSinceRaid(IColony colony) {
        var provider = getProvider(colony);
        long lastEnd = provider.mc_talking$getLastRaidEndTime();
        if (lastEnd == Long.MAX_VALUE) return Long.MAX_VALUE;
        long gameTime = currentTick(colony);
        return gameTime - lastEnd;
    }

    public static int getLostCitizens(IColony colony) {
        var provider = getProvider(colony);
        return provider.mc_talking$getLastRaidLostCitizens();
    }

    public static long getLastRaidEndTime(IColony colony) {
        var provider = getProvider(colony);
        return provider.mc_talking$getLastRaidEndTime();
    }
}
