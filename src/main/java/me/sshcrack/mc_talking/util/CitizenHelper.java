package me.sshcrack.mc_talking.util;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class CitizenHelper {
    private CitizenHelper() {
        /* This utility class should not be instantiated */
    }

    public static boolean isCitizenGuard(AbstractEntityCitizen citizen) {
        var data = citizen.getCitizenData();
        if (data == null)
            return false;

        var job = data.getJob();
        if (job == null)
            return false;

        return job.isGuard();
    }

    /**
     * Finds a citizen entity by UUID across all loaded levels.
     * Prefer this over per-player-level lookups to avoid missing citizens
     * in dimensions with no online players.
     */
    @Nullable
    public static AbstractEntityCitizen findCitizen(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof AbstractEntityCitizen citizen) {
                return citizen;
            }
        }
        return null;
    }
}
