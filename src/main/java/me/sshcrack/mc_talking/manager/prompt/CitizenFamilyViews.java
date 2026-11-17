package me.sshcrack.mc_talking.manager.prompt;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.permissions.Rank;
import com.minecolonies.api.util.Tuple;
import me.sshcrack.mc_talking.api.prompt.view.PlayerRelationView;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Builds the family part of the prompt context (moved from CitizenPromptViewFactory). */
public final class CitizenFamilyViews {
    private CitizenFamilyViews() {
    }

    public static List<String> extractParents(ICitizenData data) {
        List<String> parents = new ArrayList<>();
        Tuple<String, String> parentTuple = data.getParents();
        if (parentTuple != null) {
            if (parentTuple.getA() != null && !parentTuple.getA().isEmpty()) {
                parents.add(parentTuple.getA());
            }
            if (parentTuple.getB() != null && !parentTuple.getB().isEmpty()) {
                parents.add(parentTuple.getB());
            }
        }
        return parents;
    }

    public static List<String> extractChildrenNames(ICitizenData data) {
        List<String> names = new ArrayList<>();
        if (data.getChildren() != null) {
            for (int childId : data.getChildren()) {
                var child = data.getColony().getCitizen(childId);
                // A relative can be gone from the colony while the id is still on the list.
                if (child != null) names.add(child.getName());
            }
        }
        return names;
    }

    public static List<String> extractSiblingNames(ICitizenData data) {
        List<String> names = new ArrayList<>();
        if (data.getSiblings() != null) {
            for (int siblingId : data.getSiblings()) {
                var sibling = data.getColony().getCitizen(siblingId);
                if (sibling != null) names.add(sibling.getName());
            }
        }
        return names;
    }

    @Nullable
    public static PlayerRelationView extractPlayerRelation(ICitizenData data, @Nullable ServerPlayer speakingTo) {
        if (speakingTo == null) {
            return null;
        }
        String speakingName = speakingTo.getName().getString();
        var perms = data.getColony().getPermissions().getPlayers().get(speakingTo.getUUID());
        if (perms == null) {
            return null;
        }
        var rank = perms.getRank();
        String rankName = getRankName(rank);
        return new PlayerRelationView(speakingName, rankName, rank.isHostile(), rank.isColonyManager() || rank.isInitial());
    }

    @NotNull
    private static String getRankName(Rank rank) {
        if (rank.isHostile()) {
            return "enemy";
        }

        if (rank.isColonyManager())
            return "manager";

        if (rank.isInitial())
            return "leader";

        return "visitor";
    }
}
