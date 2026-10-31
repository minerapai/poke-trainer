package kr.poketrainer;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.ai.BattleAI;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.moves.categories.DamageCategories;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.ai.strongBattleAI.AIUtility;
import com.cobblemon.mod.common.pokemon.Pokemon;

import java.util.List;

/**
 * The trainers' AI, on top of Cobblemon's strong AI:
 * - fewer switches: at skill 5 Cobblemon's AI switches out every time it thinks the matchup is bad, so trainers kept
 *   switching back and forth. A voluntary switch is now only kept now and then (SWITCH_CHANCE, at most once every
 *   SWITCH_TURNS turns per trainer), otherwise the same AI without switching (skill 2) picks a move instead.
 *   Switches after a faint are untouched.
 * - a second look at the chosen attack: Cobblemon's AI sometimes picks a weak move over a much stronger one (a fire
 *   move against a water type with solar beam at hand). Every usable attack is scored (power, STAB, type matchup,
 *   attacking stat, two-turn / recharge moves at half) and a move scoring at least BETTER times the chosen one
 *   replaces it. Status moves the AI chose are kept.
 * - battle gimmicks, which Cobblemon's AI never uses: mega evolution and ultra burst right away, terastallization for
 *   the last pokemon standing (once per battle, showdown stops offering it after that).
 */
final class GimmickAI implements BattleAI {
    private static final double SWITCH_CHANCE = 0.3;
    private static final int SWITCH_TURNS = 3;
    private static final java.util.Random RANDOM = new java.util.Random();

    private final BattleAI moves;
    private final BattleAI noSwitch = new com.cobblemon.mod.common.battles.ai.StrongBattleAI(2);
    private int lastSwitchTurn = -100;
    private static final double BETTER = 1.5;
    /** Moves that charge a turn first or have to recharge after: half as good per turn. */
    private static final java.util.Set<String> SLOW_MOVES = java.util.Set.of("solarbeam", "solarblade", "skyattack",
        "razorwind", "skullbash", "freezeshock", "iceburn", "meteorbeam", "electroshot", "fly", "dig", "dive", "bounce",
        "phantomforce", "shadowforce", "hyperbeam", "gigaimpact", "blastburn", "frenzyplant", "hydrocannon",
        "rockwrecker", "roaroftime", "prismaticlaser", "eternabeam", "meteorassault");

    GimmickAI(BattleAI moves) {
        this.moves = moves;
    }

    @Override
    public ShowdownActionResponse choose(ActiveBattlePokemon active, PokemonBattle battle, BattleSide side,
                                         ShowdownMoveset moveset, boolean forceSwitch) {
        ShowdownActionResponse response = moves.choose(active, battle, side, moveset, forceSwitch);

        if (!forceSwitch && moveset != null && response instanceof com.cobblemon.mod.common.battles.SwitchActionResponse) {
            if (battle.getTurn() - lastSwitchTurn >= SWITCH_TURNS && RANDOM.nextDouble() < SWITCH_CHANCE) {
                lastSwitchTurn = battle.getTurn();
            } else {
                response = noSwitch.choose(active, battle, side, moveset, false);
            }
        }

        if (response instanceof MoveActionResponse chosen && moveset != null) {
            response = betterMove(active, battle, side, moveset, chosen);
        }

        if (!(response instanceof MoveActionResponse move) || moveset == null || move.getGimmickID() != null) {
            return response;
        }

        BattlePokemon self = active.getBattlePokemon();
        boolean gmax = self != null && self.getEffectedPokemon().getGmaxFactor();
        InBattleMove zMove = zMoveSlot(moveset);

        if (moveset.getCanMegaEvo()) {
            move.setGimmickID(ShowdownMoveset.Gimmick.MEGA_EVOLUTION.getId());
        } else if (moveset.getCanUltraBurst()) {
            move.setGimmickID(ShowdownMoveset.Gimmick.ULTRA_BURST.getId());
        } else if (hasZMove(moveset, move.getMoveName())) {
            // a Z crystal held: its Z move as soon as the chosen move has one (once a battle, showdown keeps count)
            move.setGimmickID(ShowdownMoveset.Gimmick.Z_POWER.getId());
        } else if (zMove != null && lastStanding(active)) {
            // the crystal fits another move only (an exclusive Z crystal): the ace uses that one as its Z move
            List<com.cobblemon.mod.common.battles.Targetable> targets = zMove.getTargets(active);
            String pnx = targets != null && !targets.isEmpty() ? targets.get(0).getPNX() : null;
            return new MoveActionResponse(zMove.getId(), pnx, ShowdownMoveset.Gimmick.Z_POWER.getId());
        } else if (zMove != null) {
            return response; // keep the Z move for later instead of terastallizing
        } else if (gmax && moveset.getCanDynamax() && lastStanding(active)) {
            // a gigantamax ace (Galar): dynamax instead of terastallizing
            move.setGimmickID(ShowdownMoveset.Gimmick.DYNAMAX.getId());
        } else if (!gmax && moveset.getCanTerastallize() != null && lastStanding(active)) {
            move.setGimmickID(ShowdownMoveset.Gimmick.TERASTALLIZATION.getId());
        }

        return response;
    }

    @Override
    public void onHealthChange(BattleHealthChangePacket packet) {
        moves.onHealthChange(packet);
        noSwitch.onHealthChange(packet);
    }

    /** The chosen move, or a usable attack that scores clearly better against the opponent in front. */
    private static ShowdownActionResponse betterMove(ActiveBattlePokemon active, PokemonBattle battle, BattleSide side,
                                                     ShowdownMoveset moveset, MoveActionResponse chosen) {
        try {
            BattlePokemon self = active.getBattlePokemon();
            List<ActiveBattlePokemon> opponents = side.getOppositeSide().getActivePokemon();
            BattlePokemon target = opponents.isEmpty() ? null : opponents.get(0).getBattlePokemon();

            if (self == null || target == null) {
                return chosen;
            }

            Pokemon attacker = self.getEffectedPokemon();
            Pokemon defender = target.getEffectedPokemon();
            boolean sun = sunny(battle, attacker, moveset);
            double chosenScore = score(chosen.getMoveName(), attacker, defender, sun);

            if (chosenScore <= 0) {
                return chosen; // a status move: the AI's own idea
            }

            InBattleMove best = null;
            double bestScore = chosenScore * BETTER;

            for (InBattleMove move : moveset.getMoves()) {
                if (move.getDisabled() || move.getPp() <= 0) {
                    continue;
                }

                double score = score(move.getId(), attacker, defender, sun);

                if (score >= bestScore) {
                    best = move;
                    bestScore = score;
                }
            }

            if (best == null) {
                return chosen;
            }

            // the move's own target: the opponent in front when it has to be aimed, none when it does not
            List<com.cobblemon.mod.common.battles.Targetable> targets = best.getTargets(active);
            String pnx = null;

            if (targets != null && !targets.isEmpty()) {
                pnx = targets.stream().filter(t -> t == opponents.get(0)).findFirst().orElse(targets.get(0)).getPNX();
            }

            return new MoveActionResponse(best.getId(), pnx, null);
        } catch (RuntimeException e) {
            PokeTrainer.LOGGER.warn("could not check the chosen move", e);
            return chosen;
        }
    }

    /**
     * Whether the move comes out in sun: the weather is sunny already, the attacker brings it (drought, like mega
     * Charizard Y), or it mega evolves this turn holding Charizardite Y (drought starts before it moves).
     */
    private static boolean sunny(PokemonBattle battle, Pokemon attacker, ShowdownMoveset moveset) {
        String ability = attacker.getAbility().getName();

        if (ability.equals("drought") || ability.equals("desolateland")) {
            return true;
        }

        if (moveset.getCanMegaEvo() && attacker.heldItem().getItem().toString().endsWith("charizardite_y")) {
            return true;
        }

        List<String> log = battle.getBattleLog();

        for (int i = log.size() - 1; i >= 0; i--) {
            int at = log.get(i).lastIndexOf("|-weather|");

            if (at >= 0) {
                String weather = log.get(i).substring(at + "|-weather|".length());
                return weather.startsWith("SunnyDay") || weather.startsWith("DesolateLand");
            }
        }

        return false;
    }

    /** A rough damage score of a move (0 for status moves). */
    private static double score(String moveId, Pokemon attacker, Pokemon defender, boolean sun) {
        MoveTemplate move = Moves.INSTANCE.getByName(moveId);

        if (move == null || move.getPower() <= 0 || move.getDamageCategory() == DamageCategories.INSTANCE.getSTATUS()) {
            return 0;
        }

        ElementalType type = move.getEffectiveElementalType(attacker);
        double score = move.getPower();

        for (ElementalType own : attacker.getTypes()) {
            if (own.equals(type)) {
                score *= 1.5;
                break;
            }
        }

        for (ElementalType defending : defender.getTypes()) {
            score *= AIUtility.INSTANCE.getDamageMultiplier(type, defending);
        }

        boolean physical = move.getDamageCategory() == DamageCategories.INSTANCE.getPHYSICAL();
        score *= attacker.getStat(physical ? Stats.ATTACK : Stats.SPECIAL_ATTACK);

        if (sun) {
            String typeName = type.getName();
            score *= typeName.equals("fire") ? 1.5 : typeName.equals("water") ? 0.5 : 1;

            if (moveId.equals("solarbeam") || moveId.equals("solarblade")) {
                return score; // no charging turn in the sun
            }
        }

        return SLOW_MOVES.contains(moveId) ? score / 2 : score;
    }

    /** Whether showdown offers a Z move for the chosen move (the list follows the moveset, null where there is none). */
    private static boolean hasZMove(ShowdownMoveset moveset, String moveName) {
        List<com.cobblemon.mod.common.battles.InBattleGimmickMove> zMoves = moveset.getCanZMove();

        if (zMoves == null) {
            return false;
        }

        List<InBattleMove> moves = moveset.getMoves();

        for (int i = 0; i < moves.size() && i < zMoves.size(); i++) {
            if (moves.get(i).getId().equals(moveName) && zMoves.get(i) != null && !zMoves.get(i).getDisabled()) {
                return true;
            }
        }

        return false;
    }

    /** A usable move of the moveset that showdown offers as a Z move, or null. */
    private static InBattleMove zMoveSlot(ShowdownMoveset moveset) {
        List<com.cobblemon.mod.common.battles.InBattleGimmickMove> zMoves = moveset.getCanZMove();
        List<InBattleMove> moves = moveset.getMoves();

        for (int i = 0; zMoves != null && i < moves.size() && i < zMoves.size(); i++) {
            if (zMoves.get(i) != null && !zMoves.get(i).getDisabled() && !moves.get(i).getDisabled() && moves.get(i).getPp() > 0) {
                return moves.get(i);
            }
        }

        return null;
    }

    private static boolean lastStanding(ActiveBattlePokemon active) {
        long alive = active.getActor().getPokemonList().stream().filter(p -> p.getHealth() > 0).count();
        BattlePokemon pokemon = active.getBattlePokemon();
        return alive <= 1 && pokemon != null && pokemon.getHealth() > 0;
    }
}
