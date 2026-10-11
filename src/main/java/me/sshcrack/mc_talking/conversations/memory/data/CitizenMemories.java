package me.sshcrack.mc_talking.conversations.memory.data;

import me.sshcrack.mc_talking.api.memory.AddonConfirmedOutcome;
import me.sshcrack.mc_talking.api.memory.AddonMemoryWriteResult;
import me.sshcrack.mc_talking.api.memory.CitizenMemoryEntryView;
import me.sshcrack.mc_talking.api.memory.CitizenRelationshipChangeView;
import me.sshcrack.mc_talking.api.memory.CitizenRelationshipDimension;
import me.sshcrack.mc_talking.api.memory.MemoryEntryType;
import me.sshcrack.mc_talking.api.memory.MemoryProvenance;
import me.sshcrack.mc_talking.broadcast.ColonyBroadcast;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.conversations.complaints.ComplaintHistory;
import me.sshcrack.mc_talking.conversations.complaints.ComplaintTopic;
import me.sshcrack.mc_talking.rumor.Rumor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public class CitizenMemories {
    public static final String SYSTEM_EVENT_PREFIX = "[SYSTEM]";

    private static final String TAG_FACTS_KEY = "facts";
    private static final String TAG_EVENTS_KEY = "events";
    private static final String TAG_MEMORY_ENTRIES_KEY = "memory_entries_v2";
    private static final String TAG_RELATIONSHIPS_KEY = "relationships";
    private static final String TAG_RELATIONSHIP_CHANGES_KEY = "relationship_changes_v2";
    private static final String TAG_ADDON_OUTCOME_IDS_KEY = "addon_outcome_ids_v2";
    private static final String TAG_SESSION_TOKEN = "gemini_session_token";
    private static final String TAG_SUMMARIZED_MEMORY = "summarized_memory";
    private static final String TAG_BROADCASTS = "mc_talking_broadcasts";
    private static final String TAG_RUMORS = "mc_talking_rumors";
    private static final String TAG_VISITOR_SINCE_DAY = "visitor_since_day";
    private static final String TAG_COMPLAINTS = "complaints";
    private static final String TAG_COMPLAINT_RESIDUES = "complaint_residues";

    private final List<String> facts = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<CitizenMemoryEntryView> entries = new ArrayList<>();
    private final List<CitizenRelationshipMemory> relationships = new ArrayList<>();
    private final List<CitizenRelationshipChangeView> relationshipChanges = new ArrayList<>();
    private final Set<String> knownAddonOutcomeIds = new HashSet<>();
    private final List<ColonyBroadcast> receivedBroadcasts = new ArrayList<>();
    private final Set<String> knownBroadcastIds = new HashSet<>();
    private final List<Rumor> receivedRumors = new ArrayList<>();
    private final Set<String> knownRumorIds = new HashSet<>();
    private String sessionToken = "";
    private String summarizedMemory = "";
    private long compactionRevision;
    private int visitorSinceDay = -1;
    private final ComplaintHistory complaints = new ComplaintHistory();

    public record CompactionSnapshot(long revision, String summary, List<String> facts,
                                     List<String> events, List<CitizenMemoryEntryView> entries) {
        public CompactionSnapshot {
            facts = List.copyOf(facts);
            events = List.copyOf(events);
            entries = List.copyOf(entries);
        }
    }

    /** Capture on the server thread before starting provider work. */
    public synchronized CompactionSnapshot snapshotCompaction() {
        return new CompactionSnapshot(compactionRevision, summarizedMemory, facts, events, entries);
    }

    /** Commit only the captured corpus; concurrent additions and their provenance survive. */
    public synchronized boolean applyCompaction(CompactionSnapshot snapshot, String summary) {
        if (summary == null || summary.isBlank() || snapshot.revision() != compactionRevision
                || !summarizedMemory.equals(snapshot.summary())) return false;
        // Identity matters: deleting a recollection and adding an equal one invalidates the
        // old request instead of letting its completion erase the new recollection.
        for (var old : snapshot.entries()) {
            if (entries.stream().noneMatch(current -> current == old)) return false;
        }
        summarizedMemory = summary;
        snapshot.facts().forEach(facts::remove);
        snapshot.events().forEach(events::remove);
        entries.removeIf(current -> snapshot.entries().stream().anyMatch(old -> current == old));
        compactionRevision++;
        return true;
    }

    public synchronized List<String> getFacts() {
        return List.copyOf(facts);
    }

    public synchronized List<String> getEvents() {
        return List.copyOf(events);
    }

    public List<CitizenMemoryEntryView> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public List<CitizenRelationshipMemory> getRelationships() {
        return relationships;
    }

    public List<CitizenRelationshipChangeView> getRelationshipChanges() {
        return Collections.unmodifiableList(relationshipChanges);
    }

    public void addFact(String fact) {
        addFact(fact, MemoryProvenance.LEGACY_UNATTRIBUTED, null, null, null);
    }

    public synchronized void addFact(
            String fact,
            @NotNull MemoryProvenance provenance,
            @Nullable UUID participantId,
            @Nullable String source,
            @Nullable String idempotencyId
    ) {
        facts.add(fact);
        entries.add(new CitizenMemoryEntryView(
                MemoryEntryType.FACT, fact, provenance, participantId, source, idempotencyId));
        rememberAddonId(provenance, source, idempotencyId);
    }

    public synchronized boolean removeFact(String fact) {
        boolean removed = facts.remove(fact);
        if (removed) removeFirstEntry(MemoryEntryType.FACT, fact);
        return removed;
    }

    public void addEvent(String event) {
        addEvent(event, MemoryProvenance.LEGACY_UNATTRIBUTED, null, null, null);
    }

    public synchronized void addEvent(
            String event,
            @NotNull MemoryProvenance provenance,
            @Nullable UUID participantId,
            @Nullable String source,
            @Nullable String idempotencyId
    ) {
        events.add(event);
        entries.add(new CitizenMemoryEntryView(
                MemoryEntryType.EVENT, event, provenance, participantId, source, idempotencyId));
        rememberAddonId(provenance, source, idempotencyId);
    }

    /**
     * Whether an event is only the citizen's own business and not news for others: an introduction
     * they gave a player ("told Dev about the Colony Gazette") made dull, repetitive gossip.
     */
    public synchronized boolean isPrivateEvent(String event) {
        for (CitizenMemoryEntryView entry : entries) {
            if (entry.type() != MemoryEntryType.EVENT || !entry.content().equals(event)) continue;
            String id = entry.idempotencyId();
            return entry.provenance() == MemoryProvenance.ADDON_CONFIRMED_OUTCOME && id != null
                    && id.startsWith(PRIVATE_OUTCOME_PREFIX);
        }
        return false;
    }

    /** Idempotency ids of confirmed outcomes that stay private (see {@link #isPrivateEvent}). */
    public static final String PRIVATE_OUTCOME_PREFIX = "introduction:";

    public synchronized boolean removeEvent(String event) {
        boolean removed = events.remove(event);
        if (removed) removeFirstEntry(MemoryEntryType.EVENT, event);
        return removed;
    }

    public synchronized void removeEventsIf(Predicate<String> predicate) {
        events.removeIf(predicate);
        entries.removeIf(entry -> entry.type() == MemoryEntryType.EVENT && predicate.test(entry.content()));
    }

    private void removeFirstEntry(MemoryEntryType type, String content) {
        for (int i = 0; i < entries.size(); i++) {
            CitizenMemoryEntryView entry = entries.get(i);
            if (entry.type() == type && entry.content().equals(content)) {
                entries.remove(i);
                return;
            }
        }
    }

    /**
     * Colony days a visitor has stayed, counted from the first time this is asked; the first day is
     * saved, so the count survives reloads.
     */
    public synchronized int visitorDays(int colonyDay) {
        if (visitorSinceDay < 0 || visitorSinceDay > colonyDay) visitorSinceDay = colonyDay;
        return colonyDay - visitorSinceDay;
    }

    /** Keeps only the newest {@code max} facts and events, a visitor's short-term memory. */
    public synchronized void keepNewest(int max) {
        while (facts.size() > max) removeFirstEntry(MemoryEntryType.FACT, facts.remove(0));
        while (events.size() > max) removeFirstEntry(MemoryEntryType.EVENT, events.remove(0));
    }

    public synchronized void setSummarizedMemory(String summarizedMemory) {
        this.summarizedMemory = summarizedMemory == null ? "" : summarizedMemory;
        compactionRevision++;
    }

    public String getSummarizedMemory() {
        return summarizedMemory;
    }

    public void addRelationshipChange(@NotNull UUID targetUUID, @NotNull CitizenRelationshipDimension type, float change) {
        addRelationshipChange(targetUUID, type, change, MemoryProvenance.LEGACY_UNATTRIBUTED, null, null, null);
    }

    public void addRelationshipChange(
            @NotNull UUID targetUUID,
            @NotNull CitizenRelationshipDimension type,
            float change,
            @NotNull MemoryProvenance provenance,
            @Nullable UUID participantId,
            @Nullable String source,
            @Nullable String idempotencyId
    ) {
        applyRelationshipAggregate(targetUUID, type, change);
        relationshipChanges.add(new CitizenRelationshipChangeView(
                targetUUID, type, change, provenance, participantId, source, idempotencyId));
    }

    private void applyRelationshipAggregate(UUID targetUUID, CitizenRelationshipDimension type, float change) {
        for (CitizenRelationshipMemory relationship : relationships) {
            if (relationship.getTargetUUID().equals(targetUUID) && relationship.getType() == type) {
                relationship.addChange(change);
                return;
            }
        }
        relationships.add(new CitizenRelationshipMemory(targetUUID, type, change));
    }

    /** Atomically persists an addon-confirmed outcome and its relationship effects exactly once. */
    public synchronized AddonMemoryWriteResult addConfirmedOutcome(@NotNull AddonConfirmedOutcome outcome) {
        String key = addonKey(outcome.source(), outcome.idempotencyId());
        if (knownAddonOutcomeIds.contains(key)) return AddonMemoryWriteResult.DUPLICATE;

        addEvent(
                outcome.event(),
                MemoryProvenance.ADDON_CONFIRMED_OUTCOME,
                outcome.participantId(),
                outcome.source(),
                outcome.idempotencyId()
        );
        for (String fact : outcome.facts()) {
            addFact(
                    fact,
                    MemoryProvenance.ADDON_CONFIRMED_OUTCOME,
                    outcome.participantId(),
                    outcome.source(),
                    outcome.idempotencyId()
            );
        }
        for (var change : outcome.relationshipChanges()) {
            addRelationshipChange(
                    change.targetId(),
                    change.dimension(),
                    change.delta(),
                    MemoryProvenance.ADDON_CONFIRMED_OUTCOME,
                    outcome.participantId(),
                    outcome.source(),
                    outcome.idempotencyId()
            );
        }
        knownAddonOutcomeIds.add(key);
        return AddonMemoryWriteResult.ADDED;
    }

    public void addBroadcast(ColonyBroadcast broadcast) {
        addBroadcast(broadcast, McTalkingConfig.INSTANCE.instance().maxBroadcastsStored);
    }

    /** Returns whether the broadcast was new to this citizen. */
    public boolean addBroadcast(ColonyBroadcast broadcast, int max) {
        if (knownBroadcastIds.contains(broadcast.getId())) return false;
        knownBroadcastIds.add(broadcast.getId());
        receivedBroadcasts.add(broadcast);
        receivedBroadcasts.sort(Comparator.comparingLong(ColonyBroadcast::getCreatedAtMs).reversed());
        while (receivedBroadcasts.size() > max) {
            ColonyBroadcast removed = receivedBroadcasts.remove(receivedBroadcasts.size() - 1);
            knownBroadcastIds.remove(removed.getId());
        }
        return knownBroadcastIds.contains(broadcast.getId());
    }

    /**
     * Forgets a broadcast. Its id stays known for this session so propagation cannot hand it back;
     * after a reload no carrier is left to spread it.
     */
    public boolean removeBroadcast(String id) {
        return receivedBroadcasts.removeIf(b -> b.getId().equals(id));
    }

    /** Forgets expired broadcasts; returns how many were removed. */
    public int purgeExpiredBroadcasts(long nowMs) {
        int before = receivedBroadcasts.size();
        receivedBroadcasts.removeIf(b -> b.isExpired(nowMs));
        return before - receivedBroadcasts.size();
    }

    public boolean hasHeardBroadcast(String id) {
        return knownBroadcastIds.contains(id);
    }

    public List<ColonyBroadcast> getReceivedBroadcasts() {
        return Collections.unmodifiableList(receivedBroadcasts);
    }

    public void addRumor(Rumor rumor) {
        if (knownRumorIds.contains(rumor.getId())) return;
        knownRumorIds.add(rumor.getId());
        receivedRumors.add(0, rumor);
        int max = McTalkingConfig.INSTANCE.instance().maxRumorsStored;
        while (receivedRumors.size() > max) {
            Rumor removed = receivedRumors.remove(receivedRumors.size() - 1);
            knownRumorIds.remove(removed.getId());
        }
    }

    public boolean hasHeardRumor(String id) {
        return knownRumorIds.contains(id);
    }

    public List<Rumor> getReceivedRumors() {
        return Collections.unmodifiableList(receivedRumors);
    }

    public CompoundTag serializeNbt() {
        CompoundTag tag = new CompoundTag();
        ListTag factsTag = new ListTag();
        for (String fact : facts) factsTag.add(StringTag.valueOf(fact));
        tag.put(TAG_FACTS_KEY, factsTag);

        ListTag eventsTag = new ListTag();
        for (String event : events) eventsTag.add(StringTag.valueOf(event));
        tag.put(TAG_EVENTS_KEY, eventsTag);

        ListTag entriesTag = new ListTag();
        for (CitizenMemoryEntryView entry : entries) entriesTag.add(serializeEntry(entry));
        tag.put(TAG_MEMORY_ENTRIES_KEY, entriesTag);

        ListTag relationshipsNbt = new ListTag();
        for (CitizenRelationshipMemory relationship : relationships) relationshipsNbt.add(relationship.serializeNbt());
        tag.put(TAG_RELATIONSHIPS_KEY, relationshipsNbt);

        ListTag relationshipChangesTag = new ListTag();
        for (CitizenRelationshipChangeView change : relationshipChanges) {
            relationshipChangesTag.add(serializeRelationshipChange(change));
        }
        tag.put(TAG_RELATIONSHIP_CHANGES_KEY, relationshipChangesTag);

        ListTag addonOutcomeIdsTag = new ListTag();
        for (String id : knownAddonOutcomeIds) addonOutcomeIdsTag.add(StringTag.valueOf(id));
        tag.put(TAG_ADDON_OUTCOME_IDS_KEY, addonOutcomeIdsTag);

        ListTag broadcastsNbt = new ListTag();
        for (ColonyBroadcast broadcast : receivedBroadcasts) broadcastsNbt.add(broadcast.serialize());
        tag.put(TAG_BROADCASTS, broadcastsNbt);

        ListTag rumorsNbt = new ListTag();
        for (Rumor rumor : receivedRumors) rumorsNbt.add(rumor.serialize());
        tag.put(TAG_RUMORS, rumorsNbt);

        if (sessionToken != null && !sessionToken.isBlank()) tag.putString(TAG_SESSION_TOKEN, sessionToken);
        if (!summarizedMemory.isBlank()) tag.putString(TAG_SUMMARIZED_MEMORY, summarizedMemory);
        if (visitorSinceDay >= 0) tag.putInt(TAG_VISITOR_SINCE_DAY, visitorSinceDay);
        serializeComplaints(tag);
        return tag;
    }

    public void deserializeNbt(CompoundTag tag) {
        facts.clear();
        events.clear();
        entries.clear();
        relationships.clear();
        relationshipChanges.clear();
        knownAddonOutcomeIds.clear();
        receivedBroadcasts.clear();
        knownBroadcastIds.clear();
        receivedRumors.clear();
        knownRumorIds.clear();

        ListTag factsNbt = tag.getList(TAG_FACTS_KEY, Tag.TAG_STRING);
        for (int i = 0; i < factsNbt.size(); i++) facts.add(factsNbt.getString(i));
        ListTag eventsNbt = tag.getList(TAG_EVENTS_KEY, Tag.TAG_STRING);
        for (int i = 0; i < eventsNbt.size(); i++) events.add(eventsNbt.getString(i));

        if (tag.contains(TAG_MEMORY_ENTRIES_KEY, Tag.TAG_LIST)) {
            ListTag entriesNbt = tag.getList(TAG_MEMORY_ENTRIES_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < entriesNbt.size(); i++) entries.add(deserializeEntry(entriesNbt.getCompound(i)));
        } else {
            for (String fact : facts) {
                entries.add(new CitizenMemoryEntryView(
                        MemoryEntryType.FACT, fact, MemoryProvenance.LEGACY_UNATTRIBUTED, null, null, null));
            }
            for (String event : events) {
                entries.add(new CitizenMemoryEntryView(
                        MemoryEntryType.EVENT, event, MemoryProvenance.LEGACY_UNATTRIBUTED, null, null, null));
            }
        }
        if (tag.contains(TAG_ADDON_OUTCOME_IDS_KEY, Tag.TAG_LIST)) {
            ListTag addonIds = tag.getList(TAG_ADDON_OUTCOME_IDS_KEY, Tag.TAG_STRING);
            for (int i = 0; i < addonIds.size(); i++) knownAddonOutcomeIds.add(addonIds.getString(i));
        } else {
            rebuildAddonIds();
        }

        ListTag relationshipsNbt = tag.getList(TAG_RELATIONSHIPS_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < relationshipsNbt.size(); i++) {
            relationships.add(new CitizenRelationshipMemory(relationshipsNbt.getCompound(i)));
        }
        if (tag.contains(TAG_RELATIONSHIP_CHANGES_KEY, Tag.TAG_LIST)) {
            ListTag changesNbt = tag.getList(TAG_RELATIONSHIP_CHANGES_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < changesNbt.size(); i++) {
                relationshipChanges.add(deserializeRelationshipChange(changesNbt.getCompound(i)));
            }
        } else {
            for (CitizenRelationshipMemory relationship : relationships) {
                relationshipChanges.add(new CitizenRelationshipChangeView(
                        relationship.getTargetUUID(),
                        relationship.getType(),
                        relationship.getFactor(),
                        MemoryProvenance.LEGACY_UNATTRIBUTED,
                        null,
                        null,
                        null
                ));
            }
        }

        ListTag broadcastsNbt = tag.getList(TAG_BROADCASTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < broadcastsNbt.size(); i++) {
            ColonyBroadcast broadcast = ColonyBroadcast.deserialize(broadcastsNbt.getCompound(i));
            receivedBroadcasts.add(broadcast);
            knownBroadcastIds.add(broadcast.getId());
        }
        ListTag rumorsNbt = tag.getList(TAG_RUMORS, Tag.TAG_COMPOUND);
        for (int i = 0; i < rumorsNbt.size(); i++) {
            Rumor rumor = Rumor.deserialize(rumorsNbt.getCompound(i));
            receivedRumors.add(rumor);
            knownRumorIds.add(rumor.getId());
        }

        sessionToken = tag.contains(TAG_SESSION_TOKEN) ? tag.getString(TAG_SESSION_TOKEN) : "";
        summarizedMemory = tag.contains(TAG_SUMMARIZED_MEMORY) ? tag.getString(TAG_SUMMARIZED_MEMORY) : "";
        visitorSinceDay = tag.contains(TAG_VISITOR_SINCE_DAY) ? tag.getInt(TAG_VISITOR_SINCE_DAY) : -1;
        deserializeComplaints(tag);
    }

    private static CompoundTag serializeEntry(CitizenMemoryEntryView entry) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", entry.type().name());
        tag.putString("content", entry.content());
        tag.putString("provenance", entry.provenance().name());
        putOptional(tag, "participant", entry.participantId() == null ? null : entry.participantId().toString());
        putOptional(tag, "source", entry.source());
        putOptional(tag, "idempotency_id", entry.idempotencyId());
        return tag;
    }

    private static CitizenMemoryEntryView deserializeEntry(CompoundTag tag) {
        return new CitizenMemoryEntryView(
                MemoryEntryType.valueOf(tag.getString("type")),
                tag.getString("content"),
                readProvenance(tag),
                readUuid(tag, "participant"),
                readOptional(tag, "source"),
                readOptional(tag, "idempotency_id")
        );
    }

    private static CompoundTag serializeRelationshipChange(CitizenRelationshipChangeView change) {
        CompoundTag tag = new CompoundTag();
        tag.putString("target", change.targetId().toString());
        tag.putString("dimension", change.dimension().name());
        tag.putFloat("delta", change.delta());
        tag.putString("provenance", change.provenance().name());
        putOptional(tag, "participant", change.participantId() == null ? null : change.participantId().toString());
        putOptional(tag, "source", change.source());
        putOptional(tag, "idempotency_id", change.idempotencyId());
        return tag;
    }

    private static CitizenRelationshipChangeView deserializeRelationshipChange(CompoundTag tag) {
        return new CitizenRelationshipChangeView(
                UUID.fromString(tag.getString("target")),
                CitizenRelationshipDimension.valueOf(tag.getString("dimension")),
                tag.getFloat("delta"),
                readProvenance(tag),
                readUuid(tag, "participant"),
                readOptional(tag, "source"),
                readOptional(tag, "idempotency_id")
        );
    }

    private static MemoryProvenance readProvenance(CompoundTag tag) {
        String value = tag.getString("provenance");
        if (value.isBlank()) return MemoryProvenance.LEGACY_UNATTRIBUTED;
        try {
            return MemoryProvenance.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return MemoryProvenance.LEGACY_UNATTRIBUTED;
        }
    }

    private static void putOptional(CompoundTag tag, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) tag.putString(key, value);
    }

    @Nullable
    private static String readOptional(CompoundTag tag, String key) {
        String value = tag.getString(key);
        return value.isBlank() ? null : value;
    }

    @Nullable
    private static UUID readUuid(CompoundTag tag, String key) {
        String value = tag.getString(key);
        if (value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void rememberAddonId(MemoryProvenance provenance, @Nullable String source, @Nullable String idempotencyId) {
        if (provenance == MemoryProvenance.ADDON_CONFIRMED_OUTCOME && source != null && idempotencyId != null) {
            knownAddonOutcomeIds.add(addonKey(source, idempotencyId));
        }
    }

    private void rebuildAddonIds() {
        knownAddonOutcomeIds.clear();
        for (CitizenMemoryEntryView entry : entries) {
            rememberAddonId(entry.provenance(), entry.source(), entry.idempotencyId());
        }
    }

    private static String addonKey(String source, String idempotencyId) {
        return source + "\u0000" + idempotencyId;
    }

    public String getSessionToken() {
        return sessionToken == null ? "" : sessionToken;
    }

    public void setSessionToken(String token) {
        sessionToken = token == null ? "" : token;
    }

    /** What this citizen has told which player about which problem. */
    public ComplaintHistory complaints() {
        return complaints;
    }

    private void serializeComplaints(CompoundTag tag) {
        if (complaints.isEmpty()) return;
        ListTag entriesTag = new ListTag();
        for (ComplaintHistory.Entry entry : complaints.entries()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("topic", entry.topic().id());
            entryTag.putString("player", entry.player().toString());
            entryTag.putInt("raised", entry.raised());
            entryTag.putInt("answered", entry.answered());
            entryTag.putBoolean("answered_last", entry.answeredLast());
            entryTag.putInt("first_day", entry.firstDay());
            entryTag.putInt("last_day", entry.lastDay());
            entriesTag.add(entryTag);
        }
        tag.put(TAG_COMPLAINTS, entriesTag);
        ListTag residuesTag = new ListTag();
        for (ComplaintHistory.Residue residue : complaints.allResidues()) {
            CompoundTag residueTag = new CompoundTag();
            residueTag.putString("topic", residue.topic().id());
            residueTag.putString("player", residue.player().toString());
            residueTag.putInt("raised", residue.raised());
            residueTag.putInt("fixed_day", residue.fixedDay());
            residuesTag.add(residueTag);
        }
        tag.put(TAG_COMPLAINT_RESIDUES, residuesTag);
    }

    private void deserializeComplaints(CompoundTag tag) {
        List<ComplaintHistory.Entry> entries = new ArrayList<>();
        ListTag entriesTag = tag.getList(TAG_COMPLAINTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < entriesTag.size(); i++) {
            CompoundTag entryTag = entriesTag.getCompound(i);
            ComplaintTopic topic = ComplaintTopic.byId(entryTag.getString("topic"));
            UUID player = readUuid(entryTag, "player");
            if (topic == null || player == null) continue;
            entries.add(new ComplaintHistory.Entry(topic, player, entryTag.getInt("raised"),
                    entryTag.getInt("answered"), entryTag.getBoolean("answered_last"), entryTag.getInt("first_day"),
                    entryTag.getInt("last_day")));
        }
        List<ComplaintHistory.Residue> residues = new ArrayList<>();
        ListTag residuesTag = tag.getList(TAG_COMPLAINT_RESIDUES, Tag.TAG_COMPOUND);
        for (int i = 0; i < residuesTag.size(); i++) {
            CompoundTag residueTag = residuesTag.getCompound(i);
            ComplaintTopic topic = ComplaintTopic.byId(residueTag.getString("topic"));
            UUID player = readUuid(residueTag, "player");
            if (topic == null || player == null) continue;
            residues.add(new ComplaintHistory.Residue(topic, player, residueTag.getInt("raised"),
                    residueTag.getInt("fixed_day")));
        }
        complaints.load(entries, residues);
    }
}
