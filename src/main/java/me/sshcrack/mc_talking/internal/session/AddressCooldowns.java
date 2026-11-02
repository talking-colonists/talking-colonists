package me.sshcrack.mc_talking.internal.session;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Keeps citizens from walking up to or calling out to the same player too often. One shared
 * cooldown per player covers every unprompted line addressed to them (urgent contact, casual and
 * pregenerated greetings), and one per citizen stops the same person from returning with the same
 * complaint a minute later.
 */
public final class AddressCooldowns {
    private final LongSupplier clockMillis;
    private final Map<UUID, Long> playerAddressedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> citizenContactedAt = new ConcurrentHashMap<>();

    public AddressCooldowns(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** Whether a citizen may address {@code playerId} unprompted now. */
    public boolean mayAddress(UUID playerId, int cooldownSeconds) {
        return elapsed(playerAddressedAt.get(playerId), cooldownSeconds);
    }

    /** Whether {@code citizenId} may seek out a player again. */
    public boolean citizenMayContact(UUID citizenId, int cooldownSeconds) {
        return elapsed(citizenContactedAt.get(citizenId), cooldownSeconds);
    }

    public void recordAddressed(UUID playerId) {
        playerAddressedAt.put(playerId, clockMillis.getAsLong());
    }

    public void recordCitizenContact(UUID citizenId) {
        citizenContactedAt.put(citizenId, clockMillis.getAsLong());
    }

    public void forgetPlayer(UUID playerId) {
        playerAddressedAt.remove(playerId);
    }

    public void clear() {
        playerAddressedAt.clear();
        citizenContactedAt.clear();
    }

    private boolean elapsed(@Nullable Long since, int cooldownSeconds) {
        return cooldownSeconds <= 0 || since == null || clockMillis.getAsLong() - since >= cooldownSeconds * 1000L;
    }
}
