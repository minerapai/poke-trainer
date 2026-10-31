package kr.poketrainer;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import kotlin.Unit;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every player raises an NPC trainer: hands it pokemon (/trainer give), and sends it against the gym leaders, elite
 * four and champions of each region in order (/trainer challenge); the battle is fought by two AI trainers in front of
 * the player. Trainers of two players can have friendly battles (level 50, 3 on 3). Korean commands: /트레이너.
 */
public class PokeTrainer implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("poketrainer");
    private static final int NAME_LENGTH = 16;
    private static final long REQUEST_MILLIS = 60_000L;

    private static MinecraftServer server;
    private static TrainerData data;

    /** Friendly battle requests: target player -> request. */
    record Request(UUID challenger, long expiresAt) {
    }

    private static final Map<UUID, Request> REQUESTS = new HashMap<>();

    static final String LOCKED = "내 트레이너는 관동 지방(관장 8명, 사천왕, 챔피언)을 직접 클리어한 뒤에 쓸 수 있습니다.";

    /** Players can raise a trainer once they have beaten the Kanto series themselves (no raising pokemon for it). */
    static boolean unlocked(ServerPlayerEntity player) {
        try {
            return com.gitlab.srcmc.rctmod.api.RCTMod.getInstance().getTrainerManager().getData(player)
                .getCompletedSeries().getOrDefault("kanto", 0) >= 1;
        } catch (RuntimeException e) {
            LOGGER.warn("could not read the RCT progress of {}", player.getGameProfile().getName(), e);
            return false;
        }
    }

    /** The friendly battle request a player got and can still answer, or null. */
    static Request pendingRequest(UUID player) {
        Request request = REQUESTS.get(player);
        return request != null && request.expiresAt() >= System.currentTimeMillis() ? request : null;
    }

    /** All trainers (for the achievements mod), null while no server runs. */
    public static TrainerData data() {
        return data;
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            server = started;
            data = new TrainerData(started);
            data.loadAll(); // gyms of offline players stand too
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            server = null;
            data = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(TrainerBattles::tick);
        ServerTickEvents.END_SERVER_TICK.register(ticking -> {
            if (data != null && ticking.getTicks() % 100 == 0) {
                Gyms.tick(ticking, data);
            }
        });

        // the trainer NPCs only stand there: no talking, battling or hitting them, none left behind after a crash
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (Gyms.isLeader(entity)) {
                // a gym leader: its owner sets the gym up, everyone else may challenge it
                UUID owner = Gyms.ownerOf(entity);

                if (hand == net.minecraft.util.Hand.MAIN_HAND && player instanceof ServerPlayerEntity serverPlayer && owner != null) {
                    GymMenu.open(serverPlayer, owner.equals(serverPlayer.getUuid()) ? GymMenu.Mode.OWNER : GymMenu.Mode.CHALLENGE, owner);
                }

                return ActionResult.FAIL;
            }

            if (!entity.getCommandTags().contains(TrainerBattles.TAG)) {
                return ActionResult.PASS;
            }

            // right click (with or without sneaking): watch the battle
            if (hand == net.minecraft.util.Hand.MAIN_HAND && player instanceof ServerPlayerEntity serverPlayer) {
                TrainerBattles.spectate(serverPlayer, entity);
            }

            return ActionResult.FAIL;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
            entity.getCommandTags().contains(TrainerBattles.TAG) || Gyms.isLeader(entity) ? ActionResult.FAIL : ActionResult.PASS);
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (TrainerBattles.isLeftover(entity) || Gyms.isLeftover(entity)) {
                entity.discard();
            }
        });
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, event -> {
            if (server != null && data != null) {
                TrainerBattles.onVictory(server, data, event.getBattle(), event.getWinners());
                Gyms.onVictory(server, data, event.getBattle(), event.getWinners());
            }

            return Unit.INSTANCE;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            register(dispatcher, "trainer", "create", "rename", "give", "take", "lead", "challenge", "friendly", "accept", "decline");
            register(dispatcher, "트레이너", "생성", "이름", "맡기기", "돌려받기", "선두", "도전", "친선", "수락", "거절");

            for (String root : new String[] {"gym", "체육관"}) {
                dispatcher.register(CommandManager.literal(root)
                    .executes(ctx -> GymMenu.open(ctx.getSource().getPlayerOrThrow(), GymMenu.Mode.LIST, null))
                    .then(CommandManager.literal(root.equals("gym") ? "mine" : "내체육관")
                        .executes(ctx -> GymMenu.open(ctx.getSource().getPlayerOrThrow(), GymMenu.Mode.OWNER, null))));
            }
        });
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher, String root, String create,
                                 String rename, String give, String take, String lead, String challenge,
                                 String friendly, String accept, String decline) {
        LiteralArgumentBuilder<ServerCommandSource> tree = CommandManager.literal(root)
            .executes(ctx -> TrainerMenu.open(ctx.getSource().getPlayerOrThrow(), TrainerMenu.Mode.MAIN))
            .then(CommandManager.literal(create).then(CommandManager.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> create(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")) > 0
                    ? TrainerMenu.open(ctx.getSource().getPlayerOrThrow(), TrainerMenu.Mode.MAIN) : 0)))
            .then(CommandManager.literal(rename).then(CommandManager.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> rename(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "name")))))
            .then(CommandManager.literal(give).then(CommandManager.argument("slot", IntegerArgumentType.integer(1, 6))
                .executes(ctx -> give(ctx.getSource().getPlayerOrThrow(), IntegerArgumentType.getInteger(ctx, "slot")))))
            .then(CommandManager.literal(take).then(CommandManager.argument("number", IntegerArgumentType.integer(1, TrainerData.TEAM_SIZE))
                .executes(ctx -> take(ctx.getSource().getPlayerOrThrow(), IntegerArgumentType.getInteger(ctx, "number")))))
            .then(CommandManager.literal(lead).then(CommandManager.argument("number", IntegerArgumentType.integer(1, TrainerData.TEAM_SIZE))
                .executes(ctx -> lead(ctx.getSource().getPlayerOrThrow(), IntegerArgumentType.getInteger(ctx, "number")))))
            .then(CommandManager.literal(challenge).executes(ctx -> challenge(ctx.getSource().getPlayerOrThrow())))
            .then(CommandManager.literal(friendly).then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> request(ctx.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(ctx, "player")))))
            .then(CommandManager.literal(accept).executes(ctx -> accept(ctx.getSource().getPlayerOrThrow())))
            .then(CommandManager.literal(decline).executes(ctx -> decline(ctx.getSource().getPlayerOrThrow())));
        if (root.equals("trainer")) {
            // OP test: a made up player's gym (type, species list) where the command runs
            tree.then(CommandManager.literal("debuggym").requires(s -> s.hasPermissionLevel(4))
                .then(CommandManager.argument("type", StringArgumentType.word())
                    .then(CommandManager.argument("species", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            UUID owner = UUID.randomUUID();
                            TrainerData.Record record = data.create(owner, "테스트관장");

                            for (String species : StringArgumentType.getString(ctx, "species").split(",")) {
                                record.team.add(com.cobblemon.mod.common.api.pokemon.PokemonProperties.Companion
                                    .parse("species=" + species.trim() + " level=60").create());
                            }

                            TrainerData.Gym gym = new TrainerData.Gym();
                            ServerCommandSource source = ctx.getSource();
                            gym.world = source.getWorld().getRegistryKey().getValue().toString();
                            gym.x = source.getPosition().x;
                            gym.y = source.getPosition().y;
                            gym.z = source.getPosition().z;
                            gym.type = StringArgumentType.getString(ctx, "type");
                            gym.fee = 5_000;
                            record.gym = gym;
                            data.save(owner);
                            String problem = Gyms.teamProblem(record);
                            source.sendFeedback(() -> Text.literal("debug gym " + owner + " team: "
                                + (problem == null ? "ok" : problem)), false);
                            return 1;
                        }))));
            // OP test: a made up trainer (species list, one level) against a stage, where the command runs
            tree.then(CommandManager.literal("debug").requires(s -> s.hasPermissionLevel(4))
                .then(CommandManager.argument("stage", IntegerArgumentType.integer(0, Stages.ALL.size() - 1))
                    .then(CommandManager.argument("level", IntegerArgumentType.integer(1, 100))
                        .then(CommandManager.argument("species", StringArgumentType.greedyString())
                            .executes(PokeTrainer::debug)))));
        }

        dispatcher.register(tree);
    }

    private static int debug(CommandContext<ServerCommandSource> ctx) {
        UUID owner = UUID.randomUUID();
        TrainerData.Record record = data.create(owner, "테스트");
        record.stage = IntegerArgumentType.getInteger(ctx, "stage");
        int level = IntegerArgumentType.getInteger(ctx, "level");

        for (int i = 0; i < record.stage; i++) {
            record.defeated.add(Stages.ALL.get(i).id()); // as if the road so far was walked
        }

        // comma separated, each "species [more properties]", e.g. "charizard held_item=mega_showdown:charizardite_x, pikachu"
        for (String species : StringArgumentType.getString(ctx, "species").split(",")) {
            // "learn=move/move/..." sets the moves (tests)
            String properties = species.trim().replaceAll("learn=[^ ]+", "");
            Pokemon pokemon = com.cobblemon.mod.common.api.pokemon.PokemonProperties.Companion
                .parse("species=" + properties + " level=" + level).create();
            java.util.regex.Matcher learn = java.util.regex.Pattern.compile("learn=([^ ]+)").matcher(species);

            if (learn.find()) {
                pokemon.getMoveSet().clear();

                for (String move : learn.group(1).split("/")) {
                    var template = com.cobblemon.mod.common.api.moves.Moves.INSTANCE.getByName(move);

                    if (template != null) {
                        pokemon.getMoveSet().add(template.create());
                    }
                }
            }

            record.team.add(pokemon);
        }

        record.focus.put(record.team.get(0).getUuid(), com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK);
        data.save(owner);
        ServerCommandSource source = ctx.getSource();
        String problem = TrainerBattles.startGym(source.getWorld(), source.getPosition(), source.getRotation().y,
            owner, source.getPlayer(), record);
        source.sendFeedback(() -> Text.literal(problem == null ? "debug battle started" : problem), false);
        return problem == null ? 1 : 0;
    }

    static MutableText button(String label, String command, Formatting color) {
        return Text.literal(label).styled(style -> style.withColor(color).withUnderline(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(command))));
    }

    private static int fail(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.RED));
        return 0;
    }

    static TrainerData.Record record(ServerPlayerEntity player) {
        try {
            return data != null ? data.get(player.getUuid()) : null;
        } catch (IllegalStateException e) {
            return null; // logged by TrainerData; never overwrite a file that could not be read
        }
    }

    private static Text describe(Pokemon pokemon) {
        return pokemon.getDisplayName(false).copy().formatted(pokemon.getShiny() ? Formatting.GOLD : Formatting.WHITE)
            .append(Text.literal(" Lv." + pokemon.getLevel()).formatted(Formatting.GRAY));
    }

    // commands

    static int create(ServerPlayerEntity player, String name) {
        if (!unlocked(player)) {
            return fail(player, LOCKED);
        }

        if (data == null) {
            return 0;
        }

        name = name.trim();

        if (name.isEmpty() || name.length() > NAME_LENGTH) {
            return fail(player, "이름은 1~" + NAME_LENGTH + "자로 지어 주세요.");
        }

        try {
            if (data.get(player.getUuid()) != null) {
                return fail(player, "이미 트레이너가 있습니다. 이름을 바꾸려면 /trainer rename <이름>");
            }
        } catch (IllegalStateException e) {
            return fail(player, "트레이너 파일을 읽지 못했습니다. 관리자에게 알려 주세요.");
        }

        data.create(player.getUuid(), name);
        player.sendMessage(Text.literal("트레이너 " + name + " 이(가) 생겼습니다! 파티의 포켓몬을 맡겨 팀을 꾸려 보세요.").formatted(Formatting.GREEN));
        LOGGER.info("{} created trainer {}", player.getGameProfile().getName(), name);
        return 1;
    }

    private static int rename(ServerPlayerEntity player, String name) {
        TrainerData.Record record = record(player);
        name = name.trim();

        if (record == null) {
            return fail(player, "트레이너가 없습니다. /trainer create <이름>");
        }

        if (name.isEmpty() || name.length() > NAME_LENGTH) {
            return fail(player, "이름은 1~" + NAME_LENGTH + "자로 지어 주세요.");
        }

        record.name = name;
        data.save(player.getUuid());
        player.sendMessage(Text.literal("트레이너 이름을 " + name + "(으)로 바꿨습니다.").formatted(Formatting.GREEN));
        return 1;
    }

    /** Problem when the team can not be changed now, else null. */
    private static String locked(ServerPlayerEntity player) {
        if (TrainerBattles.busy(player.getUuid())) {
            return "트레이너가 배틀 중에는 팀을 바꿀 수 없습니다.";
        }

        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return "배틀 중에는 포켓몬을 옮길 수 없습니다.";
        }

        return null;
    }

    static int give(ServerPlayerEntity player, int slot) {
        if (!unlocked(player)) {
            return fail(player, LOCKED);
        }

        TrainerData.Record record = record(player);

        if (record == null) {
            return fail(player, "트레이너가 없습니다. /trainer create <이름>");
        }

        String problem = locked(player);

        if (problem != null) {
            return fail(player, problem);
        }

        if (record.team.size() >= TrainerData.TEAM_SIZE) {
            return fail(player, "트레이너 팀이 가득 찼습니다. (" + TrainerData.TEAM_SIZE + "마리)");
        }

        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        Pokemon pokemon = party.get(slot - 1);

        if (pokemon == null) {
            return fail(player, slot + "번 자리에 포켓몬이 없습니다.");
        }

        if (party.toGappyList().stream().filter(p -> p != null).count() <= 1) {
            return fail(player, "파티에 포켓몬이 최소 1마리는 남아 있어야 합니다.");
        }

        if (!party.remove(pokemon)) {
            return fail(player, "포켓몬을 옮기지 못했습니다.");
        }

        record.team.add(pokemon);
        data.save(player.getUuid());
        player.sendMessage(Text.literal("").append(describe(pokemon)).append(Text.literal(" 을(를) 트레이너에게 맡겼습니다.").formatted(Formatting.GREEN)));
        LOGGER.info("{} gave {} to their trainer", player.getGameProfile().getName(), pokemon.getSpecies().getName());
        return 1;
    }

    static int take(ServerPlayerEntity player, int number) {
        TrainerData.Record record = record(player);

        if (record == null || number > record.team.size()) {
            return fail(player, number + "번 포켓몬이 없습니다.");
        }

        String problem = locked(player);

        if (problem != null) {
            return fail(player, problem);
        }

        Pokemon pokemon = record.team.get(number - 1);
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        boolean added = party.add(pokemon);
        String where = "파티";

        if (!added) {
            PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
            added = pc.add(pokemon);
            where = "PC";
        }

        if (!added) {
            return fail(player, "파티와 PC 에 자리가 없어 돌려받지 못했습니다.");
        }

        record.team.remove(number - 1);
        record.focus.remove(pokemon.getUuid());
        data.save(player.getUuid());
        player.sendMessage(Text.literal("").append(describe(pokemon)).append(Text.literal(" 을(를) " + where + "(으)로 돌려받았습니다.").formatted(Formatting.GREEN)));
        LOGGER.info("{} took {} back from their trainer", player.getGameProfile().getName(), pokemon.getSpecies().getName());
        return 1;
    }

    static int lead(ServerPlayerEntity player, int number) {
        TrainerData.Record record = record(player);

        if (record == null || number > record.team.size()) {
            return fail(player, number + "번 포켓몬이 없습니다.");
        }

        String problem = locked(player);

        if (problem != null) {
            return fail(player, problem);
        }

        record.team.add(0, record.team.remove(number - 1));
        data.save(player.getUuid());
        return 1;
    }

    /** Puts the team in a new order (all its pokemon, by uuid). */
    static int reorder(ServerPlayerEntity player, List<UUID> order) {
        TrainerData.Record record = record(player);

        if (record == null) {
            return 0;
        }

        String problem = locked(player);

        if (problem != null) {
            return fail(player, problem);
        }

        List<Pokemon> sorted = new java.util.ArrayList<>();

        for (UUID uuid : order) {
            record.team.stream().filter(p -> p.getUuid().equals(uuid)).findFirst().ifPresent(sorted::add);
        }

        if (sorted.size() != record.team.size()) {
            return fail(player, "팀이 바뀌어 순서를 저장하지 못했습니다. 다시 정해 주세요.");
        }

        record.team.clear();
        record.team.addAll(sorted);
        data.save(player.getUuid());
        player.sendMessage(Text.literal("트레이너 팀의 순서를 바꿨습니다.").formatted(Formatting.GREEN));
        return 1;
    }

    /** The next EV stat the trainer trains a pokemon in (none -> HP -> ... -> speed -> none). */
    static int cycleFocus(ServerPlayerEntity player, int number) {
        TrainerData.Record record = record(player);

        if (record == null || number > record.team.size()) {
            return 0;
        }

        Pokemon pokemon = record.team.get(number - 1);
        com.cobblemon.mod.common.api.pokemon.stats.Stats next = Growth.next(record.focus(pokemon));

        if (next == null) {
            record.focus.remove(pokemon.getUuid());
        } else {
            record.focus.put(pokemon.getUuid(), next);
        }

        data.save(player.getUuid());
        return 1;
    }

    static int challenge(ServerPlayerEntity player) {
        if (!unlocked(player)) {
            return fail(player, LOCKED);
        }

        TrainerData.Record record = record(player);

        if (record == null) {
            return fail(player, "트레이너가 없습니다. /trainer create <이름>");
        }

        if (record.team.isEmpty()) {
            return fail(player, "트레이너에게 포켓몬을 먼저 맡겨 주세요. (/trainer)");
        }

        if (TrainerBattles.busy(player.getUuid())) {
            return fail(player, "트레이너가 이미 배틀 중입니다.");
        }

        String problem = TrainerBattles.startGym(player, record);
        return problem == null ? 1 : fail(player, problem);
    }

    static int request(ServerPlayerEntity player, ServerPlayerEntity target) {
        if (!unlocked(player)) {
            return fail(player, LOCKED);
        }

        TrainerData.Record record = record(player);

        if (target == player) {
            return fail(player, "자기 자신과는 친선전을 할 수 없습니다.");
        }

        if (record == null || record.team.isEmpty()) {
            return fail(player, "포켓몬을 맡긴 트레이너가 있어야 합니다. (/trainer)");
        }

        TrainerData.Record theirs = record(target);

        if (theirs == null || theirs.team.isEmpty()) {
            return fail(player, target.getGameProfile().getName() + " 님은 포켓몬을 맡긴 트레이너가 없습니다.");
        }

        REQUESTS.put(target.getUuid(), new Request(player.getUuid(), System.currentTimeMillis() + REQUEST_MILLIS));
        player.sendMessage(Text.literal(target.getGameProfile().getName() + " 님에게 친선전을 신청했습니다. (60초)").formatted(Formatting.LIGHT_PURPLE));
        target.sendMessage(Text.literal(player.getGameProfile().getName() + " 님의 트레이너 " + record.name + " 이(가) 당신의 트레이너 "
                + theirs.name + " 에게 친선전을 신청했습니다! (Lv.50, 3:3) ").formatted(Formatting.LIGHT_PURPLE)
            .append(button("[수락]", "/trainer accept", Formatting.GREEN)).append(" ")
            .append(button("[거절]", "/trainer decline", Formatting.RED)));
        return 1;
    }

    static int accept(ServerPlayerEntity player) {
        if (!unlocked(player)) {
            return fail(player, LOCKED);
        }

        Request request = REQUESTS.remove(player.getUuid());

        if (request == null || request.expiresAt() < System.currentTimeMillis()) {
            return fail(player, "받은 친선전 신청이 없습니다.");
        }

        ServerPlayerEntity challenger = player.getServer().getPlayerManager().getPlayer(request.challenger());

        if (challenger == null) {
            return fail(player, "신청한 플레이어가 접속해 있지 않습니다.");
        }

        if (TrainerBattles.busy(player.getUuid()) || TrainerBattles.busy(challenger.getUuid())) {
            return fail(player, "어느 한쪽 트레이너가 이미 배틀 중입니다.");
        }

        TrainerData.Record mine = record(challenger);
        TrainerData.Record theirs = record(player);

        if (mine == null || theirs == null || mine.team.isEmpty() || theirs.team.isEmpty()) {
            return fail(player, "양쪽 트레이너 모두 포켓몬이 있어야 합니다.");
        }

        String problem = TrainerBattles.startFriendly(challenger, mine, player, theirs);

        if (problem != null) {
            challenger.sendMessage(Text.literal(problem).formatted(Formatting.RED));
            return fail(player, problem);
        }

        player.sendMessage(Text.literal("배틀은 " + challenger.getGameProfile().getName() + " 님 앞에서 열립니다.").formatted(Formatting.GRAY));
        return 1;
    }

    static int decline(ServerPlayerEntity player) {
        Request request = REQUESTS.remove(player.getUuid());

        if (request == null) {
            return fail(player, "받은 친선전 신청이 없습니다.");
        }

        ServerPlayerEntity challenger = player.getServer().getPlayerManager().getPlayer(request.challenger());

        if (challenger != null) {
            challenger.sendMessage(Text.literal(player.getGameProfile().getName() + " 님이 친선전을 거절했습니다.").formatted(Formatting.GRAY));
        }

        player.sendMessage(Text.literal("친선전을 거절했습니다.").formatted(Formatting.GRAY));
        return 1;
    }
}
