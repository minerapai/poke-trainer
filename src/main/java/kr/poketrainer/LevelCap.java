package kr.poketrainer;

import com.gitlab.srcmc.rctmod.api.RCTMod;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;

/**
 * Level correction for gym battles, the same rule as the rct-level-sync mod uses for the player's own RCT battles: the
 * level cap is the opponent's (its ace level + relativeLevelCap), pokemon above it are lowered to it and pokemon below
 * cap + scaleUpOffset are raised to that. The settings come from rct-level-sync when it is installed. Only battle
 * copies are changed, the pokemon handed to the trainer keep their level.
 */
final class LevelCap {
    private LevelCap() {
    }

    /** The level cap of a stage's trainer, or -1 when RCT does not know it. */
    static int of(ServerPlayerEntity player, String trainerId) {
        try {
            return RCTMod.getInstance().getTrainerManager().getData(trainerId).getRequiredLevelCap(player);
        } catch (RuntimeException e) {
            PokeTrainer.LOGGER.warn("no level cap for {}", trainerId, e);
            return -1;
        }
    }

    /** The level a pokemon fights with against a trainer with this cap. */
    static int target(int level, int cap) {
        if (cap <= 0) {
            return level;
        }

        if (setting("scaleDown", true) && level > cap) {
            return cap;
        }

        int up = Math.max(1, Math.min(100, cap + setting("scaleUpOffset", 0)));
        return setting("scaleUp", true) && level < up ? up : level;
    }

    @SuppressWarnings("unchecked")
    private static <T> T setting(String name, T fallback) {
        try {
            Method method = Class.forName("kr.levelsync.LevelSyncConfig").getMethod(name);
            return (T) method.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return fallback;
        }
    }
}
