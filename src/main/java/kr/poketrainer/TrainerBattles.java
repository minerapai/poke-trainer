package kr.poketrainer;

import com.cobblemon.mod.common.CobblemonNetwork;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.ErroredBattleStart;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.battles.ai.StrongBattleAI;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.gitlab.srcmc.rctmod.world.entities.TrainerMob;
import net.minecraft.entity.LivingEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.gitlab.srcmc.rctapi.api.battle.BattleManager;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerBag;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC;
import com.gitlab.srcmc.rctmod.ModCommon;
import net.minecraft.entity.Entity;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Battles between two AI trainers standing in front of the player who started them: a player's trainer against a
 * gym leader copy, or two players' trainers (friendly). Both sides fight with copies, the stored pokemon are never
 * touched.
 *
 * RCT's battle manager only starts battles with a player on side one, so these go to Cobblemon directly with RCT's
 * entity backed NPC actor.
 */
public final class TrainerBattles {
    /** Marks the NPCs standing in for the trainers (no talking, hitting or battling them). */
    public static final String TAG = "poketrainer";
    private static final int FRIENDLY_LEVEL = 50;
    private static final int FRIENDLY_SIZE = 3;
    private static final long TIMEOUT_MILLIS = 20 * 60_000L;
    private static final double FORWARD = 6;
    private static final double SIDE = 4;

    /** A running battle. stage: index into Stages.ALL for a gym battle, -1 for a friendly one. */
    record Pending(UUID owner, UUID ownerActor, UUID opponent, UUID opponentActor, int stage, int cap, List<Entity> npcs,
                   ServerWorld world, ChunkPos chunk, long startedAt) {
        boolean friendly() {
            return stage < 0;
        }
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    /** NPCs of running battles (also while spawning, before the battle is registered). */
    private static final Set<Entity> NPCS = new HashSet<>();
    /** AI choices waiting for the next tick, see NpcActor. */
    private static final List<Runnable> DEFERRED = new ArrayList<>();

    private TrainerBattles() {
    }

    /**
     * RCT's NPC actor, but choosing a tick later: when both sides are AIs they answer within the same call, and
     * Cobblemon then sends only the first side's choice to showdown and the battle waits forever for the other.
     */
    static final class NpcActor extends BattleManager.TrainerEntityBattleActor {
        NpcActor(String name, LivingEntity entity, List<BattlePokemon> team, int skill) {
            super(name, entity, UUID.randomUUID(), team, new TrainerBag(), new GimmickAI(new StrongBattleAI(skill)));
        }

        @Override
        public void onChoiceRequested() {
            DEFERRED.add(super::onChoiceRequested);
        }
    }

    public static boolean busy(UUID player) {
        return PENDING.values().stream().anyMatch(p -> p.owner().equals(player) || player.equals(p.opponent()));
    }

    /**
     * Lets a player watch the battle a trainer NPC is fighting, the way Cobblemon lets players watch other players'
     * battles (its own spectating only finds battles by a participating player).
     */
    static void spectate(ServerPlayerEntity player, Entity npc) {
        UUID battleId = PENDING.entrySet().stream().filter(e -> e.getValue().npcs().contains(npc))
            .map(Map.Entry::getKey).findFirst().orElse(null);
        PokemonBattle battle = battleId != null ? BattleRegistry.getBattle(battleId) : null;

        if (battle == null || battle.getEnded()) {
            return;
        }

        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.sendMessage(Text.literal("배틀 중에는 관전할 수 없습니다.").formatted(Formatting.RED));
            return;
        }

        battle.getSpectators().add(player.getUuid());
        CobblemonNetwork.INSTANCE.sendPacket(player, new BattleInitializePacket(battle, null));
        CobblemonNetwork.INSTANCE.sendPacket(player, new BattleMessagePacket(battle.getChatLog()));
    }

    public static boolean isLeftover(Entity entity) {
        return entity.getCommandTags().contains(TAG) && !NPCS.contains(entity);
    }

    /** Called every tick: deferred AI choices, and battles that never ended. */
    static void tick(MinecraftServer server) {
        List<Runnable> due = new ArrayList<>(DEFERRED);
        DEFERRED.clear();

        for (Runnable choice : due) {
            try {
                choice.run();
            } catch (RuntimeException e) {
                PokeTrainer.LOGGER.warn("trainer AI choice failed", e);
            }
        }

        if (server.getTicks() % 200 != 0) {
            return;
        }

        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, Pending> entry : List.copyOf(PENDING.entrySet())) {
            PokemonBattle battle = BattleRegistry.getBattle(entry.getKey());

            if (battle == null || now - entry.getValue().startedAt() > TIMEOUT_MILLIS) {
                PokeTrainer.LOGGER.warn("trainer battle {} {}, closed without result", entry.getKey(),
                    battle == null ? "is gone" : "took too long");

                if (battle != null) {
                    battle.end();
                }

                Pending pending = PENDING.remove(entry.getKey());
                cleanUp(pending);
                notify(server, pending, Text.literal("트레이너 배틀이 끝나지 않아 무효 처리했습니다.").formatted(Formatting.GRAY));
            }
        }
    }

    /** The player's trainer against its next stage. Returns a problem, or null when it started. */
    static String startGym(ServerPlayerEntity player, TrainerData.Record record) {
        return startGym(player.getServerWorld(), player.getPos(), player.getYaw(), player.getUuid(), player, record);
    }

    /** startGym at a place; player: for the level cap, null for none (console tests). */
    static String startGym(ServerWorld world, Vec3d origin, float facing, UUID owner, ServerPlayerEntity player,
                           TrainerData.Record record) {
        if (record.stage >= Stages.ALL.size()) {
            return "모든 지방의 챔피언을 이겼습니다! 더 도전할 상대가 없습니다.";
        }

        Stages.Stage stage = Stages.ALL.get(record.stage);
        TrainerNPC leader = ModCommon.RCT.getTrainerRegistry().getById(stage.id(), TrainerNPC.class);

        if (leader == null || leader.getTeam() == null || leader.getTeam().length == 0) {
            return Stages.label(stage) + "의 팀 정보를 찾을 수 없습니다. (RCT 데이터팩 확인)";
        }

        DynamicRegistryManager registries = world.getServer().getRegistryManager();
        int cap = player != null ? LevelCap.of(player, stage.id()) : -1;
        List<BattlePokemon> mine = copies(record.team, record.team.size(), registries, cap);
        List<BattlePokemon> theirs = copies(List.of(leader.getTeam()), TrainerData.TEAM_SIZE, registries, -1);
        int skill = stage.role().equals("관장") ? 4 : 5;
        Text intro = Text.literal("[내 트레이너] ").formatted(Formatting.GOLD)
            .append(Text.literal(record.name).formatted(Formatting.AQUA))
            .append(Text.literal(" vs ").formatted(Formatting.GRAY))
            .append(Text.literal(Stages.label(stage)).formatted(Formatting.RED))
            .append(Text.literal(" 배틀 시작!").formatted(Formatting.GOLD));
        return start(world, origin, facing, owner, record.name, mine, null, Stages.label(stage), stage.id(), theirs, skill, record.stage,
            cap, BattleFormat.Companion.getGEN_9_SINGLES(), intro);
    }

    /** Two players' trainers, 3 against 3 at level 50, in front of the challenger. */
    static String startFriendly(ServerPlayerEntity challenger, TrainerData.Record mine, ServerPlayerEntity opponent,
                                TrainerData.Record theirs) {
        DynamicRegistryManager registries = challenger.getServer().getRegistryManager();
        BattleFormat base = BattleFormat.Companion.getGEN_9_SINGLES();
        BattleFormat format = base.copy(base.getMod(), base.getBattleType(), base.getRuleSet(), base.getGen(), FRIENDLY_LEVEL);
        Text intro = Text.literal("[친선전] ").formatted(Formatting.LIGHT_PURPLE)
            .append(Text.literal(mine.name + " (" + challenger.getGameProfile().getName() + ")").formatted(Formatting.AQUA))
            .append(Text.literal(" vs ").formatted(Formatting.GRAY))
            .append(Text.literal(theirs.name + " (" + opponent.getGameProfile().getName() + ")").formatted(Formatting.RED))
            .append(Text.literal(" 배틀 시작! (Lv." + FRIENDLY_LEVEL + ", " + FRIENDLY_SIZE + ":" + FRIENDLY_SIZE + ")").formatted(Formatting.LIGHT_PURPLE));
        return start(challenger.getServerWorld(), challenger.getPos(), challenger.getYaw(), challenger.getUuid(), mine.name,
            copies(mine.team, FRIENDLY_SIZE, registries, -1), opponent,
            theirs.name, null, copies(theirs.team, FRIENDLY_SIZE, registries, -1), 5, -1, -1, format, intro);
    }

    private static String start(ServerWorld world, Vec3d origin, float facing, UUID owner, String ownName,
                                List<BattlePokemon> ownTeam,
                                ServerPlayerEntity opponent, String otherName, String otherModel, List<BattlePokemon> otherTeam,
                                int otherSkill,
                                int stage, int cap, BattleFormat format, Text intro) {
        if (ownTeam.isEmpty() || otherTeam.isEmpty()) {
            return "양쪽 모두 포켓몬이 1마리 이상 있어야 합니다.";
        }

        double yaw = Math.toRadians(facing);
        Vec3d forward = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3d side = new Vec3d(-forward.z, 0, forward.x);
        Vec3d center = origin.add(forward.multiply(FORWARD));
        Vec3d ownPos = center.subtract(side.multiply(SIDE));
        Vec3d otherPos = center.add(side.multiply(SIDE));
        ChunkPos chunk = new ChunkPos(BlockPos.ofFloored(center));
        List<Entity> npcs = new ArrayList<>();
        LivingEntity own = npc(world, ownName, null, ownPos, otherPos, npcs);
        LivingEntity other = npc(world, otherName, otherModel, otherPos, ownPos, npcs);

        if (own == null || other == null) {
            discard(npcs);
            return "트레이너 NPC 를 만들 수 없습니다. (서버 로그 참고)";
        }

        NpcActor ownActor = new NpcActor(ownName, own, ownTeam, 5);
        NpcActor otherActor = new NpcActor(otherName, other, otherTeam, otherSkill);
        BattleStartResult result = BattleRegistry.startBattle(format, new BattleSide(ownActor), new BattleSide(otherActor), true);

        if (!(result instanceof SuccessfulBattleStart started)) {
            if (result instanceof ErroredBattleStart errored) {
                errored.getErrors().forEach(e -> PokeTrainer.LOGGER.warn("trainer battle could not start: {}", e));
            }

            discard(npcs);
            return "배틀을 시작하지 못했습니다. (서버 로그 참고)";
        }

        // the battle must go on when the player walks away
        world.setChunkForced(chunk.x, chunk.z, true);
        PENDING.put(started.getBattle().getBattleId(), new Pending(owner, ownActor.getUuid(),
            opponent != null ? opponent.getUuid() : null, otherActor.getUuid(), stage, cap, npcs, world, chunk,
            System.currentTimeMillis()));
        notify(world.getServer(), PENDING.get(started.getBattle().getBattleId()), intro.copy()
            .append(Text.literal(" (트레이너를 우클릭하면 관전)").formatted(Formatting.GRAY)));
        PokeTrainer.LOGGER.info("trainer battle: {} vs {} ({})", ownName, otherName, owner);
        return null;
    }

    /** Healed copies of up to max pokemon, level corrected for a level cap above 0 (the originals stay as they are). */
    private static List<BattlePokemon> copies(List<Pokemon> team, int max, DynamicRegistryManager registries, int cap) {
        List<BattlePokemon> copies = new ArrayList<>();

        for (Pokemon pokemon : team) {
            if (copies.size() >= max) {
                break;
            }

            Pokemon copy = pokemon.clone(true, registries);
            int level = LevelCap.target(copy.getLevel(), cap);

            if (level != copy.getLevel()) {
                copy.setLevel(level);
            }

            copy.heal();
            copies.add(BattlePokemon.Companion.safeCopyOf(copy));
        }

        return copies;
    }

    /**
     * A trainer standing there that does not move or take damage, facing the other trainer: the RCT trainer itself
     * (its own model) when model is an RCT trainer id, else a Cobblemon NPC (player model).
     */
    private static LivingEntity npc(ServerWorld world, String name, String model, Vec3d pos, Vec3d facing, List<Entity> spawned) {
        try {
            LivingEntity npc = model != null ? rctTrainer(world, model) : null;

            if (npc == null) {
                NPCClass npcClass = NPCClasses.getByIdentifier(Identifier.of("cobblemon", "standard"));

                if (npcClass == null) {
                    return null;
                }

                NPCEntity cobblemonNpc = new NPCEntity(world);
                cobblemonNpc.setNpc(npcClass);
                cobblemonNpc.initialize(1);
                cobblemonNpc.setMovable(false);
                npc = cobblemonNpc;
            }

            npc.setInvulnerable(true);
            giveRing(npc);
            npc.setCustomName(Text.literal(name));
            npc.setCustomNameVisible(true);
            npc.addCommandTag(TAG);
            float yaw = (float) Math.toDegrees(Math.atan2(-(facing.x - pos.x), facing.z - pos.z));
            npc.refreshPositionAndAngles(pos.x, ground(world, pos), pos.z, yaw, 0);
            npc.setHeadYaw(yaw);
            npc.setBodyYaw(yaw);
            NPCS.add(npc);

            if (!world.spawnEntity(npc)) {
                NPCS.remove(npc);
                return null;
            }

            spawned.add(npc);
            return npc;
        } catch (RuntimeException e) {
            PokeTrainer.LOGGER.warn("could not create a trainer NPC", e);
            return null;
        }
    }

    /**
     * The RCT trainer mob of a trainer id (its model and name tag), standing still. Marked persistent without telling
     * RCT's spawner, only so it does not despawn while players are away; or null when RCT can't make it.
     */
    private static LivingEntity rctTrainer(ServerWorld world, String trainerId) {
        try {
            TrainerMob mob = TrainerMob.getEntityType().create(world);

            if (mob == null) {
                return null;
            }

            mob.setTrainerId(trainerId);
            mob.setPersistent(true, true);
            mob.setAiDisabled(true);
            return mob;
        } catch (RuntimeException e) {
            PokeTrainer.LOGGER.warn("could not create the RCT trainer {}", trainerId, e);
            return null;
        }
    }

    /**
     * Mega Showdown only lets trainers holding a ring (hand or accessory slot) use Z moves and the like: an omni ring
     * in the off hand opens all of them (each still needs its stone / crystal / tera type on the pokemon).
     */
    static void giveRing(LivingEntity trainer) {
        net.minecraft.registry.Registries.ITEM.getOrEmpty(Identifier.of("mega_showdown", "omni_ring")).ifPresent(ring ->
            trainer.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, new net.minecraft.item.ItemStack(ring)));
    }

    /** The y to stand on near a spot: the first block with a solid top and room above. */
    private static double ground(ServerWorld world, Vec3d pos) {
        BlockPos.Mutable at = new BlockPos.Mutable();

        for (int dy = 3; dy >= -6; dy--) {
            at.set(MathHelper.floor(pos.x), MathHelper.floor(pos.y) + dy, MathHelper.floor(pos.z));

            if (world.getBlockState(at.down()).isSideSolidFullSquare(world, at.down(), Direction.UP)
                && world.getBlockState(at).isAir() && world.getBlockState(at.up()).isAir()) {
                return at.getY();
            }
        }

        return pos.y;
    }

    private static void cleanUp(Pending pending) {
        discard(pending.npcs());

        boolean chunkStillUsed = PENDING.values().stream().anyMatch(p -> p.world() == pending.world() && p.chunk().equals(pending.chunk()));

        if (!chunkStillUsed) {
            pending.world().setChunkForced(pending.chunk().x, pending.chunk().z, false);
        }
    }

    private static void discard(List<Entity> npcs) {
        npcs.forEach(Entity::discard);
        npcs.forEach(NPCS::remove);
    }

    private static void notify(MinecraftServer server, Pending pending, Text text) {
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(pending.owner());

        if (owner != null) {
            owner.sendMessage(text);
        }

        if (pending.opponent() != null) {
            ServerPlayerEntity opponent = server.getPlayerManager().getPlayer(pending.opponent());

            if (opponent != null) {
                opponent.sendMessage(text);
            }
        }
    }

    private static int count(String text, String part) {
        int count = 0;

        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }

        return count;
    }

    /** Cobblemon's victory event: settle the trainer battle it belongs to. */
    static void onVictory(MinecraftServer server, TrainerData data, PokemonBattle battle, List<BattleActor> winners) {
        Pending pending = PENDING.remove(battle.getBattleId());

        if (pending == null) {
            return;
        }

        cleanUp(pending);
        boolean ownWon = winners.stream().anyMatch(a -> a.getUuid().equals(pending.ownerActor()));
        String log = String.join("\n", battle.getBattleLog());
        // each switch is logged twice by showdown (exact and percent HP)
        PokeTrainer.LOGGER.info("trainer battle of {} ended after {} turns: {} (mega {}, tera {}, z {}, dynamax {}, switches {}, faints {})",
            pending.owner(), battle.getTurn(), ownWon ? "won" : "lost", count(log, "|-mega|"), count(log, "|-terastallize|"),
            count(log, "|-zpower|"), count(log, "|-start|") > 0 ? count(log, "Dynamax") : 0,
            count(log, "|switch|") / 2, count(log, "|faint|"));
        TrainerData.Record own = data.get(pending.owner());

        if (own == null) {
            return;
        }

        if (pending.friendly()) {
            TrainerData.Record other = pending.opponent() != null ? data.get(pending.opponent()) : null;
            String winner = ownWon ? own.name : other != null ? other.name : "상대";

            if (ownWon) {
                own.friendlyWins++;
            } else {
                own.friendlyLosses++;
            }

            if (other != null) {
                if (ownWon) {
                    other.friendlyLosses++;
                } else {
                    other.friendlyWins++;
                }

                data.save(pending.opponent());
            }

            data.save(pending.owner());
            notify(server, pending, Text.literal("[친선전] ").formatted(Formatting.LIGHT_PURPLE)
                .append(Text.literal(winner).formatted(Formatting.AQUA))
                .append(Text.literal(" 의 승리! (" + battle.getTurn() + "턴)").formatted(Formatting.LIGHT_PURPLE)));
            return;
        }

        Stages.Stage stage = Stages.ALL.get(pending.stage());
        List<Text> growth = Growth.apply(own, battle, pending.opponentActor(), pending.cap());
        PokeTrainer.LOGGER.info("growth of trainer {}:{}", own.name, String.join(" /", growth.stream().map(Text::getString).toList()));

        if (!ownWon) {
            own.losses++;
            data.save(pending.owner());
            growth.forEach(line -> notify(server, pending, line));
            notify(server, pending, Text.literal("[내 트레이너] ").formatted(Formatting.GOLD)
                .append(Text.literal(own.name + " 이(가) " + Stages.label(stage) + "에게 졌습니다...").formatted(Formatting.GRAY))
                .append(Text.literal(" 팀을 바꿔 다시 도전해 보세요. ").formatted(Formatting.GRAY))
                .append(PokeTrainer.button("[내 트레이너]", "/trainer", Formatting.YELLOW)));
            return;
        }

        own.wins++;
        own.defeated.add(stage.id());

        if (own.stage == pending.stage()) {
            own.stage++;
        }

        data.save(pending.owner());
        Text won = Text.literal("[내 트레이너] ").formatted(Formatting.GOLD)
            .append(Text.literal(own.name).formatted(Formatting.AQUA))
            .append(Text.literal(" 이(가) " + Stages.label(stage) + "에게 이겼습니다! (" + battle.getTurn() + "턴)").formatted(Formatting.GREEN));
        notify(server, pending, won);
        growth.forEach(line -> notify(server, pending, line));

        if (stage.champion()) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(pending.owner());
            String ownerName = owner != null ? owner.getGameProfile().getName() : "";
            server.getPlayerManager().broadcast(Text.literal("[내 트레이너] ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(ownerName + " 님의 트레이너 " + own.name + " 이(가) " + stage.region() + " 챔피언이 되었습니다!")
                    .formatted(Formatting.YELLOW)), false);
        }

        if (own.stage < Stages.ALL.size()) {
            notify(server, pending, Text.literal(" 다음 상대: " + Stages.label(Stages.ALL.get(own.stage)) + " ").formatted(Formatting.GRAY)
                .append(PokeTrainer.button("[도전]", "/trainer challenge", Formatting.GREEN)));
        }
    }
}
