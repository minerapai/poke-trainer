package kr.poketrainer;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The trainer's pokemon grow from gym battles (won or lost): every opponent pokemon knocked out gives the whole team
 * experience, as a trainer battle with an exp share would, and EVs in the stat the player chose for each pokemon.
 * Levels stop at the level cap of the opponent fought, so the road has to be walked gym by gym.
 */
final class Growth {
    /** EVs per opponent knocked out, into the chosen stat. */
    static final int EVS_PER_KNOCKOUT = 8;
    /** The order the menu cycles through (null: no EV training). */
    static final List<Stats> FOCUS = List.of(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK,
        Stats.SPECIAL_DEFENCE, Stats.SPEED);
    private static final SidemodExperienceSource SOURCE = new SidemodExperienceSource("poketrainer");

    private Growth() {
    }

    static String statName(Stats stat) {
        return switch (stat) {
            case HP -> "HP";
            case ATTACK -> "공격";
            case DEFENCE -> "방어";
            case SPECIAL_ATTACK -> "특수공격";
            case SPECIAL_DEFENCE -> "특수방어";
            case SPEED -> "스피드";
            default -> stat.name();
        };
    }

    /** The next EV focus after this one (none -> HP -> ... -> speed -> none). */
    static Stats next(Stats focus) {
        int index = focus == null ? -1 : FOCUS.indexOf(focus);
        return index + 1 < FOCUS.size() ? FOCUS.get(index + 1) : null;
    }

    /** The highest ace level among the opponents the trainer has beaten (0 for none). */
    static int peakLevel(TrainerData.Record record) {
        int peak = 0;

        for (Stages.Stage stage : Stages.ALL) {
            if (record.defeated.contains(stage.id())) {
                peak = Math.max(peak, Stages.aceLevel(stage));
            }
        }

        return peak;
    }

    /** Grows the stored team after a gym battle. Returns a line per pokemon for the owner. */
    static List<Text> apply(TrainerData.Record record, PokemonBattle battle, UUID opponentActor, int cap) {
        BattleActor opponent = null;

        for (BattleActor actor : battle.getActors()) {
            if (actor.getUuid().equals(opponentActor)) {
                opponent = actor;
            }
        }

        int experience = 0;
        int knockouts = 0;
        int floor = peakLevel(record);

        if (opponent != null) {
            for (BattlePokemon fainted : opponent.getPokemonList()) {
                if (fainted.getHealth() <= 0) {
                    Pokemon pokemon = fainted.getEffectedPokemon();
                    // the main series formula for a trainer's pokemon (x1.5), shared with the whole team; the level
                    // never counts lower than the strongest opponent beaten so far, so a new region (its first gym
                    // around level 20 again) does not cut the experience down
                    int level = Math.max(pokemon.getLevel(), floor);
                    experience += (int) Math.round(pokemon.getSpecies().getBaseExperienceYield() * level / 7.0 * 1.5);
                    knockouts++;
                }
            }
        }

        List<Text> lines = new ArrayList<>();

        if (knockouts == 0) {
            lines.add(Text.literal(" 상대 포켓몬을 쓰러뜨리지 못해 성장하지 못했습니다.").formatted(Formatting.GRAY));
            return lines;
        }

        lines.add(Text.literal(" 성장 (상대 " + knockouts + "마리 쓰러뜨림):").formatted(Formatting.DARK_AQUA));

        for (Pokemon pokemon : record.team) {
            int before = pokemon.getLevel();
            MutableText line = Text.literal("  ").append(pokemon.getDisplayName(false).copy().formatted(Formatting.WHITE));

            if (cap > 0 && before >= cap) {
                line.append(Text.literal(" Lv." + before + " (레벨 제한 " + cap + ")").formatted(Formatting.GRAY));
            } else {
                pokemon.addExperience(SOURCE, experience);

                if (cap > 0 && pokemon.getLevel() > cap) {
                    pokemon.setLevel(cap);
                }

                line.append(Text.literal(pokemon.getLevel() > before ? " Lv." + before + " → " + pokemon.getLevel()
                    : " Lv." + before + " (경험치 +" + experience + ")").formatted(pokemon.getLevel() > before ? Formatting.GREEN : Formatting.GRAY));
            }

            Stats focus = record.focus(pokemon);

            if (focus != null) {
                int added = pokemon.getEvs().add(focus, EVS_PER_KNOCKOUT * knockouts);
                line.append(Text.literal(added > 0 ? ", " + statName(focus) + " 노력치 +" + added
                    : ", " + statName(focus) + " 노력치 최대").formatted(added > 0 ? Formatting.AQUA : Formatting.GRAY));
            }

            lines.add(line);
        }

        return lines;
    }
}
