package kr.poketrainer;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.ai.StrongBattleAI;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.gitlab.srcmc.rctapi.api.battle.BattleRules;
import com.gitlab.srcmc.rctapi.api.trainer.Trainer;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerBag;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerPlayer;
import com.gitlab.srcmc.rctmod.ModCommon;
import fr.harmex.cobbledollars.common.utils.CobbleDollarsPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Player gyms: a player sets one up near their home and their trainer stands there as its leader, with the trainer's
 * team (all of the gym's type). Other players right click the leader and fight it themselves (RCT battle like the
 * battle tower, both sides at level 50, healed copies). The challenger pays the fee up front: a win returns it plus
 * the prize (out of the vault the owner filled) and one reward item (out of the owner's stock), a loss turns the fee
 * into the owner's income. Nothing is ever paid out of thin air, so beating a gym again and again gains nothing the
 * owner did not put in.
 */
public final class Gyms {
    /** Marks gym leader NPCs; the second tag "poketrainer_gym:<owner>" says whose gym. */
    public static final String TAG = "poketrainer_gym";
    public static final int LEVEL = 50;
    public static final long MAX_AMOUNT = 10_000_000L;

    /** Elemental type id -> Korean name, in the menu's order. */
    public static final Map<String, String> TYPES = new java.util.LinkedHashMap<>();

    static {
        String[][] types = {{"normal", "노말"}, {"fire", "불꽃"}, {"water", "물"}, {"grass", "풀"}, {"electric", "전기"},
            {"ice", "얼음"}, {"fighting", "격투"}, {"poison", "독"}, {"ground", "땅"}, {"flying", "비행"},
            {"psychic", "에스퍼"}, {"bug", "벌레"}, {"rock", "바위"}, {"ghost", "고스트"}, {"dragon", "드래곤"},
            {"dark", "악"}, {"steel", "강철"}, {"fairy", "페어리"}};

        for (String[] type : types) {
            TYPES.put(type[0], type[1]);
        }
    }

    /** A running gym battle. */
    private record Running(UUID owner, UUID challenger, long fee, long startedAt) {
    }

    private static final Map<UUID, Running> RUNNING = new HashMap<>();
    /**
     * Leader NPCs spawned since the server started. A leader loaded from the world save is not in here and is
     * removed (tick puts a fresh one): the save may load after tick already put one, which would make two.
     */
    private static final java.util.Set<UUID> SPAWNED = new java.util.HashSet<>();
    private static final long TIMEOUT_MILLIS = 30 * 60_000L;

    private Gyms() {
    }

    public static String typeName(String type) {
        return TYPES.getOrDefault(type, type);
    }

    // money (CobbleDollars keeps it on the player, synced to the client)

    static long balance(ServerPlayerEntity player) {
        BigInteger money = ((CobbleDollarsPlayer) player).cobbleDollars$getCobbleDollars();
        return money.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    static void addMoney(ServerPlayerEntity player, long amount) {
        CobbleDollarsPlayer wallet = (CobbleDollarsPlayer) player;
        wallet.cobbleDollars$setCobbleDollars(wallet.cobbleDollars$getCobbleDollars().add(BigInteger.valueOf(amount)));
    }

    /** Takes money if the player has that much. */
    static boolean takeMoney(ServerPlayerEntity player, long amount) {
        if (balance(player) < amount) {
            return false;
        }

        addMoney(player, -amount);
        return true;
    }

    // the team

    /** Problem with the owner's team for the gym's type, or null when it may defend. */
    static String teamProblem(TrainerData.Record record) {
        if (record.team.isEmpty()) {
            return "트레이너에게 맡긴 포켓몬이 없습니다.";
        }

        for (Pokemon pokemon : record.team) {
            if (!hasType(pokemon, record.gym.type)) {
                return pokemon.getDisplayName(false).getString() + " 은(는) " + typeName(record.gym.type) + " 타입이 아닙니다.";
            }
        }

        return null;
    }

    static boolean hasType(Pokemon pokemon, String type) {
        for (ElementalType own : pokemon.getTypes()) {
            if (own.getName().equalsIgnoreCase(type) || own.getShowdownId().equalsIgnoreCase(type)) {
                return true;
            }
        }

        return false;
    }

    // leader NPCs

    static boolean isLeader(Entity entity) {
        return entity.getCommandTags().contains(TAG);
    }

    static boolean isLeftover(Entity entity) {
        return isLeader(entity) && !SPAWNED.contains(entity.getUuid());
    }

    /** The owner of the gym a leader NPC stands for, or null. */
    static UUID ownerOf(Entity entity) {
        for (String tag : entity.getCommandTags()) {
            if (tag.startsWith(TAG + ":")) {
                try {
                    return UUID.fromString(tag.substring(TAG.length() + 1));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }

        return null;
    }

    static ServerWorld world(MinecraftServer server, TrainerData.Gym gym) {
        Identifier id = Identifier.tryParse(gym.world);
        return id != null ? server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id)) : null;
    }

    /** The leader NPC standing for a gym, or null when it is not loaded / not there. */
    static NPCEntity leader(MinecraftServer server, UUID owner, TrainerData.Gym gym) {
        ServerWorld world = world(server, gym);

        if (world == null || !world.isChunkLoaded((int) Math.floor(gym.x) >> 4, (int) Math.floor(gym.z) >> 4)) {
            return null;
        }

        List<NPCEntity> found = world.getEntitiesByClass(NPCEntity.class, new Box(gym.x - 4, gym.y - 4, gym.z - 4,
            gym.x + 4, gym.y + 4, gym.z + 4), e -> owner.equals(ownerOf(e)));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Every few seconds: puts the leader NPCs of loaded gyms in place (they come back after a restart or a kill). */
    static void tick(MinecraftServer server, TrainerData data) {
        for (Map.Entry<UUID, Running> entry : List.copyOf(RUNNING.entrySet())) {
            PokemonBattle battle = BattleRegistry.getBattle(entry.getKey());

            if (battle == null || System.currentTimeMillis() - entry.getValue().startedAt() > TIMEOUT_MILLIS) {
                // ended without a winner (left, crashed): the challenger loses the fee, as if they gave up
                if (battle != null) {
                    battle.end();
                }

                settle(server, data, RUNNING.remove(entry.getKey()), false);
            }
        }

        for (Map.Entry<UUID, TrainerData.Gym> entry : data.gyms().entrySet()) {
            TrainerData.Gym gym = entry.getValue();
            ServerWorld world = world(server, gym);

            if (world == null || !world.isChunkLoaded((int) Math.floor(gym.x) >> 4, (int) Math.floor(gym.z) >> 4)) {
                continue;
            }

            List<NPCEntity> found = world.getEntitiesByClass(NPCEntity.class, new Box(gym.x - 4, gym.y - 4, gym.z - 4,
                gym.x + 4, gym.y + 4, gym.z + 4), e -> entry.getKey().equals(ownerOf(e)));

            for (int i = 1; i < found.size(); i++) {
                found.get(i).discard(); // one leader per gym
            }

            TrainerData.Record record = data.get(entry.getKey());
            String name = leaderName(record);

            if (found.isEmpty()) {
                spawnLeader(world, entry.getKey(), gym, name);
            } else if (!name.equals(found.get(0).getCustomName() != null ? found.get(0).getCustomName().getString() : "")) {
                found.get(0).setCustomName(Text.literal(name));
            }
        }
    }

    private static String leaderName(TrainerData.Record record) {
        return record.name + " (" + typeName(record.gym.type) + " 체육관)";
    }

    private static void spawnLeader(ServerWorld world, UUID owner, TrainerData.Gym gym, String name) {
        NPCClass npcClass = NPCClasses.getByIdentifier(Identifier.of("cobblemon", "standard"));

        if (npcClass == null) {
            return;
        }

        NPCEntity npc = new NPCEntity(world);
        npc.setNpc(npcClass);
        npc.initialize(1);
        npc.setMovable(false);
        npc.setInvulnerable(true);
        TrainerBattles.giveRing(npc);
        npc.setPersistent();
        npc.setCustomName(Text.literal(name));
        npc.setCustomNameVisible(true);
        npc.addCommandTag(TAG);
        npc.addCommandTag(TAG + ":" + owner);
        npc.refreshPositionAndAngles(gym.x, gym.y, gym.z, gym.yaw, 0);
        npc.setHeadYaw(gym.yaw);
        npc.setBodyYaw(gym.yaw);
        SPAWNED.add(npc.getUuid());

        if (!world.spawnEntity(npc)) {
            SPAWNED.remove(npc.getUuid());
        }
    }

    /** Removes the leader NPC of a gym (moved or closed). */
    static void removeLeader(MinecraftServer server, UUID owner, TrainerData.Gym gym) {
        NPCEntity npc;

        while ((npc = leader(server, owner, gym)) != null) {
            npc.discard();
        }
    }

    static boolean busy(UUID owner) {
        return RUNNING.values().stream().anyMatch(r -> r.owner().equals(owner));
    }

    // battles

    /** The challenger against a gym's leader. Returns a problem, or null when the battle started. */
    static String challenge(ServerPlayerEntity challenger, UUID owner, TrainerData data) {
        TrainerData.Record record = data.get(owner);
        TrainerData.Gym gym = record != null ? record.gym : null;

        if (gym == null) {
            return "이 체육관은 문을 닫았습니다.";
        }

        if (owner.equals(challenger.getUuid())) {
            return "내 체육관에는 도전할 수 없습니다.";
        }

        String team = teamProblem(record);

        if (team != null) {
            return "이 체육관은 지금 도전을 받을 수 없습니다. (" + team + ")";
        }

        if (busy(owner)) {
            return "다른 도전자가 배틀 중입니다. 끝난 뒤에 도전해 주세요.";
        }

        if (BattleRegistry.getBattleByParticipatingPlayer(challenger) != null) {
            return "배틀 중에는 도전할 수 없습니다.";
        }

        if (Cobblemon.INSTANCE.getStorage().getParty(challenger).toGappyList().stream().noneMatch(p -> p != null)) {
            return "파티에 포켓몬이 있어야 합니다.";
        }

        NPCEntity leader = leader(challenger.getServer(), owner, gym);

        if (leader == null) {
            return "체육관 관장이 자리에 없습니다. 잠시 뒤에 다시 시도해 주세요.";
        }

        if (!takeMoney(challenger, gym.fee)) {
            return "도전료 " + String.format("%,d", gym.fee) + " 이(가) 부족합니다.";
        }

        List<Pokemon> copies = new ArrayList<>();

        for (Pokemon pokemon : record.team) {
            if (copies.size() >= TrainerData.TEAM_SIZE) {
                break;
            }

            Pokemon copy = pokemon.clone(true, challenger.getServer().getRegistryManager());
            copy.heal();
            copies.add(copy);
        }

        BattleFormat base = BattleFormat.Companion.getGEN_9_SINGLES();
        BattleFormat format = base.copy(base.getMod(), base.getBattleType(), base.getRuleSet(), base.getGen(), LEVEL);
        BattleRules rules = new BattleRules.Builder().withHealPlayers(true).withAdjustPlayerLevels(true)
            .withAdjustNPCLevels(true).withMaxItemUses(0).build();
        TrainerNPC npc = new TrainerNPC(com.gitlab.srcmc.rctapi.api.util.Text.literal(leaderName(record)),
            copies.toArray(new Pokemon[0]), new TrainerBag(), new GimmickAI(new StrongBattleAI(5)), leader);
        UUID battle = ModCommon.RCT.getBattleManager().startBattle(List.<Trainer>of(new TrainerPlayer(challenger)),
            List.<Trainer>of(npc), () -> format, rules);

        if (battle == null) {
            addMoney(challenger, gym.fee);
            return "배틀을 시작하지 못했습니다. (서버 로그 참고)";
        }

        RUNNING.put(battle, new Running(owner, challenger.getUuid(), gym.fee, System.currentTimeMillis()));
        challenger.sendMessage(Text.literal("[체육관] ").formatted(Formatting.GOLD)
            .append(Text.literal(leaderName(record) + " 에게 도전! (Lv." + LEVEL + ", 도전료 "
                + String.format("%,d", gym.fee) + ")").formatted(Formatting.YELLOW)));
        PokeTrainer.LOGGER.info("gym battle: {} challenges the gym of {}", challenger.getGameProfile().getName(), owner);
        return null;
    }

    /** Cobblemon's victory event: settle the gym battle it belongs to. */
    static void onVictory(MinecraftServer server, TrainerData data, PokemonBattle battle, List<BattleActor> winners) {
        Running running = RUNNING.remove(battle.getBattleId());

        if (running != null) {
            boolean challengerWon = winners.stream().anyMatch(a -> a.getPlayerUUIDs().iterator().hasNext()
                && running.challenger().equals(a.getPlayerUUIDs().iterator().next()));
            settle(server, data, running, challengerWon);
        }
    }

    private static void settle(MinecraftServer server, TrainerData data, Running running, boolean challengerWon) {
        TrainerData.Record owner = data.get(running.owner());
        TrainerData.Gym gym = owner != null ? owner.gym : null;
        ServerPlayerEntity challenger = server.getPlayerManager().getPlayer(running.challenger());
        ServerPlayerEntity ownerPlayer = server.getPlayerManager().getPlayer(running.owner());
        String challengerName = challenger != null ? challenger.getGameProfile().getName() : "도전자";
        PokeTrainer.LOGGER.info("gym battle of {} against the gym of {}: {}", challengerName, running.owner(),
            challengerWon ? "challenger won" : "gym won");

        if (!challengerWon) {
            if (gym != null) {
                gym.income = Math.min(MAX_AMOUNT * 10, gym.income + running.fee());
                gym.defenses++;
                data.save(running.owner());
            }

            if (challenger != null) {
                challenger.sendMessage(Text.literal("[체육관] 졌습니다... 도전료 " + String.format("%,d", running.fee())
                    + " 은(는) 체육관 주인에게 갔습니다.").formatted(Formatting.GRAY));
            }

            if (ownerPlayer != null) {
                ownerPlayer.sendMessage(Text.literal("[체육관] 내 체육관이 " + challengerName + " 님의 도전을 막아냈습니다! 수익 +"
                    + String.format("%,d", running.fee())).formatted(Formatting.GREEN));
            }

            return;
        }

        long prize = 0;
        ItemStack reward = ItemStack.EMPTY;

        if (gym != null) {
            prize = Math.min(gym.prize, gym.vault);
            gym.vault -= prize;
            gym.rewards.removeIf(ItemStack::isEmpty);

            if (!gym.rewards.isEmpty()) {
                reward = gym.rewards.get(0).split(1);
                gym.rewards.removeIf(ItemStack::isEmpty);
            }

            gym.defeats++;
            data.save(running.owner());
        }

        if (challenger != null) {
            addMoney(challenger, running.fee() + prize);
            TrainerData.Record own = data.get(challenger.getUuid());

            if (own != null) {
                own.gymWins++;
                data.save(challenger.getUuid());
            }

            if (!reward.isEmpty()) {
                Text name = reward.getName();

                if (!challenger.getInventory().insertStack(reward)) {
                    challenger.dropItem(reward, false);
                }

                challenger.sendMessage(Text.literal("[체육관] 보상 아이템: ").formatted(Formatting.GOLD).append(name));
            }

            challenger.sendMessage(Text.literal("[체육관] 승리! 도전료를 돌려받고 상금 " + String.format("%,d", prize)
                + " 을(를) 받았습니다.").formatted(Formatting.GREEN));
        } else {
            // offline at the end: nothing to hand over, the prize and the reward stay with the gym
            if (gym != null) {
                gym.vault += prize;

                if (!reward.isEmpty()) {
                    gym.rewards.add(0, reward);
                }

                data.save(running.owner());
            }
        }

        if (ownerPlayer != null) {
            ownerPlayer.sendMessage(Text.literal("[체육관] " + challengerName + " 님이 내 체육관을 이겼습니다. (상금 "
                + String.format("%,d", prize) + " 지급)").formatted(Formatting.YELLOW));
        }
    }
}
