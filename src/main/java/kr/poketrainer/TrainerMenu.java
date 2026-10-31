package kr.poketrainer;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC;
import com.gitlab.srcmc.rctmod.ModCommon;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Chest screens of the player's trainer (vanilla screens, clients need no mod): the trainer with its team and the
 * player's party (click to hand over / take back / move to the front), the road of opponents, and picking a player
 * for a friendly battle. Nothing can be taken out; every click is handled here.
 */
public final class TrainerMenu extends GenericContainerScreenHandler {
    public enum Mode { MAIN, ROAD, FRIENDLY, ORDER }

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int TEAM_START = 10;
    private static final int PARTY_START = 28;
    private static final int CHALLENGE = 47;
    private static final int ROAD = 49;
    private static final int FRIENDLY = 51;
    private static final int REQUEST = 8;
    private static final int BACK = 45;
    private static final int ORDER = 53;
    private static final int GYM = 45;
    private static final int ORDER_FIRST = 19;
    private static final int SAVE = 40;

    private final ServerPlayerEntity player;
    private final SimpleInventory inventory;
    private final Mode mode;
    /** FRIENDLY: the players shown, by slot. */
    private final List<UUID> shown = new ArrayList<>();
    /** ORDER: the team's pokemon (uuids) in the new order picked so far. */
    private final List<UUID> picked = new ArrayList<>();

    private TrainerMenu(int syncId, PlayerInventory playerInventory, SimpleInventory inventory, ServerPlayerEntity player,
                        Mode mode) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, ROWS);
        this.player = player;
        this.inventory = inventory;
        this.mode = mode;
        fill();
    }

    public static int open(ServerPlayerEntity player, Mode mode) {
        String title = switch (mode) {
            case MAIN -> "내 트레이너";
            case ROAD -> "내 트레이너 - 도전의 길";
            case FRIENDLY -> "내 트레이너 - 친선전 상대 고르기";
            case ORDER -> "내 트레이너 - 순서 바꾸기";
        };
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInventory, p) -> new TrainerMenu(syncId, playerInventory, new SimpleInventory(SIZE), player, mode),
            Text.literal(title)));
        return 1;
    }

    // building the screens

    private void fill() {
        inventory.clear();
        ItemStack filler = named(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), Text.literal(" "), List.of());

        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setStack(slot, filler.copy());
        }

        TrainerData.Record record = PokeTrainer.record(player);

        if (!PokeTrainer.unlocked(player)) {
            fillLocked(record);
        } else if (record == null) {
            fillNoTrainer();
        } else {
            switch (mode) {
                case MAIN -> fillMain(record);
                case ROAD -> fillRoad(record);
                case FRIENDLY -> fillFriendly();
                case ORDER -> fillOrder(record);
            }
        }

        sendContentUpdates();
    }

    private void fillLocked(TrainerData.Record record) {
        inventory.setStack(22, named(new ItemStack(Items.BARRIER), plain("아직 쓸 수 없습니다", Formatting.RED), List.of(
            plain("내 트레이너는 관동 지방(관장 8명, 사천왕, 챔피언)을", Formatting.GRAY),
            plain("직접 클리어한 뒤에 쓸 수 있습니다.", Formatting.GRAY))));

        if (record != null && !record.team.isEmpty()) {
            inventory.setStack(31, named(new ItemStack(Items.HOPPER), plain("맡긴 포켓몬 돌려받기", Formatting.YELLOW), List.of(
                plain("클릭: 첫 번째 포켓몬을 돌려받습니다 (" + record.team.size() + "마리 맡김)", Formatting.GRAY))));
        }
    }

    private void fillNoTrainer() {
        inventory.setStack(22, named(new ItemStack(Items.ARMOR_STAND), plain("트레이너 만들기", Formatting.GREEN), List.of(
            plain("클릭하면 내 트레이너가 생깁니다.", Formatting.GRAY),
            plain("이름은 처음에 내 이름으로 정해지고,", Formatting.GRAY),
            plain("/트레이너 이름 <이름> 으로 바꿀 수 있습니다.", Formatting.GRAY),
            plain("포켓몬을 맡기면 트레이너가 관장, 사천왕, 챔피언에게", Formatting.GRAY),
            plain("차례로 도전합니다 (관동 → 성도 → 호연 → 신오).", Formatting.GRAY))));
    }

    private void fillMain(TrainerData.Record record) {
        Stages.Stage next = record.stage < Stages.ALL.size() ? Stages.ALL.get(record.stage) : null;
        int cap = next != null ? LevelCap.of(player, next.id()) : -1;
        boolean busy = TrainerBattles.busy(player.getUuid());

        // the trainer
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponentTypes.PROFILE, new ProfileComponent(player.getGameProfile()));
        inventory.setStack(4, named(head, plain("트레이너 " + record.name, Formatting.AQUA), List.of(
            plain("관장전 " + record.wins + "승 " + record.losses + "패", Formatting.GRAY),
            plain("친선전 " + record.friendlyWins + "승 " + record.friendlyLosses + "패", Formatting.GRAY),
            plain("격파 " + record.defeated.size() + " / " + Stages.ALL.size(), Formatting.GRAY),
            plain(next != null ? "다음 상대: " + Stages.label(next) : "모든 지방 제패!", Formatting.YELLOW),
            plain("이름 바꾸기: /트레이너 이름 <이름>", Formatting.DARK_GRAY))));

        // the team (row 2) and the party (row 4)
        inventory.setStack(TEAM_START - 1, named(new ItemStack(Items.ARMOR_STAND), plain("트레이너의 팀", Formatting.GOLD), List.of(
            plain("이 순서대로 출전합니다 (첫 번째가 선봉).", Formatting.GRAY),
            plain("왼쪽 클릭: 돌려받기 / 오른쪽 클릭: 선봉으로", Formatting.GRAY),
            plain("쉬프트 클릭: 키울 노력치 바꾸기", Formatting.GRAY),
            plain("관장전을 할 때마다 쓰러뜨린 상대만큼 경험치와", Formatting.GRAY),
            plain("정한 노력치(1마리당 +" + Growth.EVS_PER_KNOCKOUT + ")가 오릅니다. 레벨은 상대의 레벨 제한까지.", Formatting.GRAY),
            plain("배틀에는 복사본이 나가서 다치지 않습니다.", Formatting.GRAY))));

        for (int i = 0; i < TrainerData.TEAM_SIZE; i++) {
            if (i < record.team.size()) {
                Pokemon pokemon = record.team.get(i);
                int battleLevel = LevelCap.target(pokemon.getLevel(), cap);
                List<Text> lore = new ArrayList<>();
                lore.add(plain("Lv." + pokemon.getLevel() + (battleLevel != pokemon.getLevel()
                    ? " → 다음 배틀에서 Lv." + battleLevel + " (레벨 보정)" : ""), Formatting.GRAY));
                lore.add(plain((i + 1) + "번째" + (i == 0 ? " (선봉)" : ""), Formatting.GRAY));
                Stats focus = record.focus(pokemon);
                lore.add(plain("키울 노력치: " + (focus != null ? Growth.statName(focus) : "없음"), Formatting.AQUA));
                StringBuilder evs = new StringBuilder("노력치");
                int total = 0;

                for (Stats stat : Growth.FOCUS) {
                    int value = pokemon.getEvs().getOrDefault(stat);
                    total += value;
                    evs.append(" ").append(Growth.statName(stat)).append(" ").append(value);
                }

                lore.add(plain(evs + " (합계 " + total + "/510)", Formatting.DARK_AQUA));
                lore.add(plain(busy ? "배틀 중에는 바꿀 수 없습니다" : "왼쪽 클릭: 돌려받기" + (i > 0 ? " / 오른쪽 클릭: 선봉으로" : ""),
                    busy ? Formatting.RED : Formatting.YELLOW));
                lore.add(plain("쉬프트 클릭: 키울 노력치 바꾸기", Formatting.YELLOW));
                inventory.setStack(TEAM_START + i, named(PokemonItem.Companion.from(pokemon),
                    pokemon.getDisplayName(false).copy().formatted(pokemon.getShiny() ? Formatting.GOLD : Formatting.WHITE), lore));
            } else {
                inventory.setStack(TEAM_START + i, named(new ItemStack(Items.LIGHT_GRAY_STAINED_GLASS_PANE),
                    plain("빈 자리", Formatting.DARK_GRAY), List.of(plain("아래 파티의 포켓몬을 클릭해 맡기세요.", Formatting.GRAY))));
            }
        }

        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        inventory.setStack(PARTY_START - 1, named(new ItemStack(Items.CHEST), plain("내 파티", Formatting.GOLD), List.of(
            plain("클릭: 트레이너에게 맡기기", Formatting.GRAY),
            plain("파티에는 최소 1마리가 남아야 합니다.", Formatting.GRAY))));

        for (int slot = 0; slot < 6; slot++) {
            Pokemon pokemon = party.get(slot);

            if (pokemon == null) {
                continue;
            }

            boolean full = record.team.size() >= TrainerData.TEAM_SIZE;
            inventory.setStack(PARTY_START + slot, named(PokemonItem.Companion.from(pokemon),
                pokemon.getDisplayName(false).copy().formatted(pokemon.getShiny() ? Formatting.GOLD : Formatting.WHITE), List.of(
                    plain("Lv." + pokemon.getLevel(), Formatting.GRAY),
                    busy ? plain("배틀 중에는 바꿀 수 없습니다", Formatting.RED)
                        : full ? plain("트레이너 팀이 가득 찼습니다", Formatting.RED) : plain("클릭: 트레이너에게 맡기기", Formatting.GREEN))));
        }

        // buttons
        List<Text> challenge = new ArrayList<>();

        if (next != null) {
            challenge.add(plain("다음 상대: " + Stages.label(next), Formatting.YELLOW));
            int ace = aceLevel(next);

            if (ace > 0) {
                challenge.add(plain("상대 에이스 Lv." + ace + (cap > 0 ? ", 레벨 제한 " + cap : ""), Formatting.GRAY));
            }

            challenge.add(plain("내 앞에서 두 트레이너가 자동으로 싸웁니다.", Formatting.GRAY));
            challenge.add(busy ? plain("이미 배틀 중입니다", Formatting.RED)
                : record.team.isEmpty() ? plain("포켓몬을 먼저 맡겨 주세요", Formatting.RED) : plain("클릭: 도전!", Formatting.GREEN));
        } else {
            challenge.add(plain("모든 지방의 챔피언을 이겼습니다!", Formatting.GOLD));
        }

        inventory.setStack(CHALLENGE, named(new ItemStack(next != null ? Items.DIAMOND_SWORD : Items.NETHER_STAR),
            plain(next != null ? "도전" : "제패 완료", Formatting.RED), challenge));
        inventory.setStack(ROAD, named(new ItemStack(Items.FILLED_MAP), plain("도전의 길", Formatting.AQUA), List.of(
            plain("관동부터 신오까지 " + Stages.ALL.size() + "명의 상대와 진행 상황", Formatting.GRAY),
            plain("클릭: 보기", Formatting.GREEN))));
        TrainerData.Gym gym = record.gym;
        inventory.setStack(GYM, named(new ItemStack(Items.LECTERN), plain("내 체육관", Formatting.GOLD), List.of(
            plain(gym == null ? "내 집 근처에 체육관을 세우고 트레이너를 관장으로 앉힙니다." : Gyms.typeName(gym.type)
                + " 체육관, 방어 " + gym.defenses + "승 / 패배 " + gym.defeats, Formatting.GRAY),
            plain("다른 플레이어가 직접 도전합니다 (Lv." + Gyms.LEVEL + ", 6:6). 체육관 목록: /체육관", Formatting.GRAY),
            plain("클릭: 열기", Formatting.GREEN))));
        inventory.setStack(ORDER, named(new ItemStack(Items.HOPPER), plain("순서 바꾸기", Formatting.AQUA), List.of(
            plain("팀이 나가는 순서를 한 번에 정합니다.", Formatting.GRAY),
            plain(record.team.size() < 2 ? "포켓몬이 2마리 이상일 때 쓸 수 있습니다" : "클릭: 순서 정하기",
                record.team.size() < 2 ? Formatting.RED : Formatting.GREEN))));
        inventory.setStack(FRIENDLY, named(new ItemStack(Items.TOTEM_OF_UNDYING), plain("친선전", Formatting.LIGHT_PURPLE), List.of(
            plain("다른 플레이어의 트레이너와 Lv.50, 3:3 배틀", Formatting.GRAY),
            plain("팀의 앞 3마리가 나갑니다. 진행과 업적 기록에는 친선전 승수만 남습니다.", Formatting.GRAY),
            plain("클릭: 상대 고르기", Formatting.GREEN))));

        PokeTrainer.Request request = PokeTrainer.pendingRequest(player.getUuid());

        if (request != null) {
            ServerPlayerEntity challenger = player.getServer().getPlayerManager().getPlayer(request.challenger());
            String name = challenger != null ? challenger.getGameProfile().getName() : "?";
            inventory.setStack(REQUEST, named(new ItemStack(Items.WRITABLE_BOOK), plain("받은 친선전 신청", Formatting.LIGHT_PURPLE), List.of(
                plain(name + " 님의 트레이너가 친선전을 신청했습니다.", Formatting.GRAY),
                plain("왼쪽 클릭: 수락 / 오른쪽 클릭: 거절", Formatting.YELLOW))));
        }
    }

    private void fillRoad(TrainerData.Record record) {
        if (roadPage < 0) {
            roadPage = Math.min(record.stage, Stages.ALL.size() - 1) / ROAD_PAGE; // open where the trainer is
        }

        int pages = (Stages.ALL.size() + ROAD_PAGE - 1) / ROAD_PAGE;
        roadPage = Math.max(0, Math.min(roadPage, pages - 1));

        for (int i = roadPage * ROAD_PAGE; i < Stages.ALL.size() && i < (roadPage + 1) * ROAD_PAGE; i++) {
            Stages.Stage stage = Stages.ALL.get(i);
            boolean beaten = record.defeated.contains(stage.id());
            boolean current = i == record.stage;
            ItemStack icon = new ItemStack(beaten ? (stage.champion() ? Items.GOLDEN_HELMET : Items.LIME_DYE)
                : current ? Items.DIAMOND_SWORD : Items.GRAY_DYE);
            List<Text> lore = new ArrayList<>();
            lore.add(plain(stage.region() + " " + stage.role(), Formatting.GRAY));
            int ace = aceLevel(stage);

            if (ace > 0) {
                lore.add(plain("에이스 Lv." + ace, Formatting.GRAY));
            }

            lore.add(beaten ? plain("격파!", Formatting.GREEN) : current ? plain("다음 상대 (클릭: 도전)", Formatting.YELLOW)
                : plain("아직 도전할 수 없습니다", Formatting.DARK_GRAY));
            ItemStack stack = named(icon, plain((i + 1) + ". " + stage.name(), beaten ? Formatting.GREEN
                : current ? Formatting.YELLOW : Formatting.GRAY), lore);
            stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, current);
            inventory.setStack(slotOfStage(i), stack);
        }

        inventory.setStack(BACK, named(new ItemStack(Items.ARROW), plain(roadPage > 0 ? "이전 쪽" : "돌아가기", Formatting.AQUA),
            List.of(plain((roadPage + 1) + " / " + pages + " 쪽", Formatting.GRAY))));

        if (roadPage < pages - 1) {
            inventory.setStack(ROAD_NEXT, named(new ItemStack(Items.ARROW), plain("다음 쪽", Formatting.AQUA),
                List.of(plain((roadPage + 1) + " / " + pages + " 쪽", Formatting.GRAY))));
        }
    }

    /** Three regions (13 stages each) a page, one after another from the top left. */
    private static final int ROAD_PAGE = 39;
    private static final int ROAD_NEXT = 53;
    /** ROAD: the page shown, -1 until it opens on the trainer's own region. */
    private int roadPage = -1;

    private int slotOfStage(int index) {
        return index / ROAD_PAGE == roadPage ? index % ROAD_PAGE : -1;
    }

    private void fillFriendly() {
        shown.clear();
        List<ServerPlayerEntity> others = player.getServer().getPlayerManager().getPlayerList().stream()
            .filter(p -> p != player).toList();

        for (ServerPlayerEntity other : others) {
            if (shown.size() >= BACK) {
                break;
            }

            TrainerData.Record theirs = PokeTrainer.record(other);
            boolean ready = theirs != null && !theirs.team.isEmpty();
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponentTypes.PROFILE, new ProfileComponent(other.getGameProfile()));
            inventory.setStack(shown.size(), named(head, plain(other.getGameProfile().getName(), ready ? Formatting.AQUA : Formatting.GRAY),
                ready ? List.of(plain("트레이너 " + theirs.name + " (팀 " + theirs.team.size() + "마리)", Formatting.GRAY),
                    plain("친선전 " + theirs.friendlyWins + "승 " + theirs.friendlyLosses + "패", Formatting.GRAY),
                    plain("클릭: 친선전 신청", Formatting.GREEN))
                    : List.of(plain("포켓몬을 맡긴 트레이너가 없습니다", Formatting.RED))));
            shown.add(other.getUuid());
        }

        if (shown.isEmpty()) {
            inventory.setStack(22, named(new ItemStack(Items.BARRIER), plain("접속 중인 다른 플레이어가 없습니다", Formatting.RED), List.of()));
        }

        inventory.setStack(BACK, named(new ItemStack(Items.ARROW), plain("돌아가기", Formatting.AQUA), List.of()));
    }

    private void fillOrder(TrainerData.Record record) {
        picked.removeIf(uuid -> record.team.stream().noneMatch(p -> p.getUuid().equals(uuid)));

        for (int i = 0; i < record.team.size(); i++) {
            Pokemon pokemon = record.team.get(i);
            int order = picked.indexOf(pokemon.getUuid());
            ItemStack stack = named(PokemonItem.Companion.from(pokemon),
                pokemon.getDisplayName(false).copy().formatted(order >= 0 ? Formatting.GREEN : Formatting.WHITE), List.of(
                    plain("Lv." + pokemon.getLevel() + ", 지금 " + (i + 1) + "번째" + (i == 0 ? " (선봉)" : ""), Formatting.GRAY),
                    order >= 0 ? plain("새 순서: " + (order + 1) + "번째 (클릭하면 취소)", Formatting.GREEN)
                        : plain("클릭해서 다음 순서로 지정", Formatting.YELLOW)));
            stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, order >= 0);
            inventory.setStack(ORDER_FIRST + i, stack);
        }

        boolean ready = !record.team.isEmpty() && picked.size() == record.team.size();
        inventory.setStack(4, named(new ItemStack(Items.BOOK), plain("나갈 순서대로 클릭하세요", Formatting.GOLD), List.of(
            plain("처음 클릭한 포켓몬이 선봉입니다.", Formatting.GRAY),
            plain("친선전에는 앞의 3마리가 나갑니다.", Formatting.GRAY))));
        inventory.setStack(SAVE, named(new ItemStack(ready ? Items.LIME_CONCRETE : Items.RED_CONCRETE),
            plain(ready ? "이 순서로 저장" : picked.size() + " / " + record.team.size() + " 지정", ready ? Formatting.GREEN : Formatting.RED),
            List.of(plain(ready ? "클릭하면 저장합니다." : "모두 순서를 정해야 저장할 수 있습니다.", Formatting.GRAY))));
        inventory.setStack(BACK, named(new ItemStack(Items.ARROW), plain("돌아가기", Formatting.AQUA), List.of()));
    }

    private static int aceLevel(Stages.Stage stage) {
        return Stages.aceLevel(stage);
    }

    // clicks

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clicker) {
        // never move items: only react to plain clicks on the menu's own slots
        if (actionType == SlotActionType.QUICK_MOVE && mode == Mode.MAIN && slotIndex >= TEAM_START
            && slotIndex < TEAM_START + TrainerData.TEAM_SIZE) {
            PokeTrainer.cycleFocus(player, slotIndex - TEAM_START + 1);
            fill();
        } else if (actionType == SlotActionType.PICKUP && slotIndex >= 0 && slotIndex < SIZE) {
            if (!PokeTrainer.unlocked(player)) {
                if (slotIndex == 31) {
                    PokeTrainer.take(player, 1);
                    fill();
                }
            } else if (PokeTrainer.record(player) == null) {
                if (slotIndex == 22) {
                    PokeTrainer.create(player, player.getGameProfile().getName());
                    fill();
                }
            } else {
                switch (mode) {
                    case MAIN -> clickMain(slotIndex, button);
                    case ROAD -> clickRoad(slotIndex);
                    case FRIENDLY -> clickFriendly(slotIndex);
                    case ORDER -> clickOrder(slotIndex);
                }
            }
        }

        syncState(); // undo whatever the client already showed moving
    }

    private void clickMain(int slot, int button) {
        if (slot >= TEAM_START && slot < TEAM_START + TrainerData.TEAM_SIZE) {
            int number = slot - TEAM_START + 1;

            if (button == 1) {
                PokeTrainer.lead(player, number);
            } else {
                PokeTrainer.take(player, number);
            }

            fill();
        } else if (slot >= PARTY_START && slot < PARTY_START + 6) {
            PokeTrainer.give(player, slot - PARTY_START + 1);
            fill();
        } else if (slot == CHALLENGE) {
            TrainerData.Record record = PokeTrainer.record(player);

            if (record != null && record.stage < Stages.ALL.size() && !record.team.isEmpty() && !TrainerBattles.busy(player.getUuid())) {
                player.closeHandledScreen();
                PokeTrainer.challenge(player);
            }
        } else if (slot == ROAD) {
            open(player, Mode.ROAD);
        } else if (slot == FRIENDLY) {
            open(player, Mode.FRIENDLY);
        } else if (slot == GYM) {
            GymMenu.open(player, GymMenu.Mode.OWNER, null);
        } else if (slot == ORDER) {
            TrainerData.Record record = PokeTrainer.record(player);

            if (record != null && record.team.size() >= 2) {
                open(player, Mode.ORDER);
            }
        } else if (slot == REQUEST && PokeTrainer.pendingRequest(player.getUuid()) != null) {
            player.closeHandledScreen();

            if (button == 1) {
                PokeTrainer.decline(player);
            } else {
                PokeTrainer.accept(player);
            }
        }
    }

    private void clickRoad(int slot) {
        TrainerData.Record record = PokeTrainer.record(player);

        if (slot == BACK) {
            if (roadPage > 0) {
                roadPage--;
                fill();
            } else {
                open(player, Mode.MAIN);
            }
        } else if (slot == ROAD_NEXT) {
            roadPage++;
            fill();
        } else if (record != null && slot == slotOfStage(record.stage) && !record.team.isEmpty()
            && !TrainerBattles.busy(player.getUuid())) {
            player.closeHandledScreen();
            PokeTrainer.challenge(player);
        }
    }

    private void clickOrder(int slot) {
        TrainerData.Record record = PokeTrainer.record(player);

        if (slot == BACK) {
            open(player, Mode.MAIN);
        } else if (record != null && slot >= ORDER_FIRST && slot < ORDER_FIRST + record.team.size()) {
            UUID uuid = record.team.get(slot - ORDER_FIRST).getUuid();

            if (!picked.remove(uuid)) {
                picked.add(uuid);
            }

            fill();
        } else if (record != null && slot == SAVE && picked.size() == record.team.size()) {
            if (PokeTrainer.reorder(player, List.copyOf(picked)) > 0) {
                open(player, Mode.MAIN);
            } else {
                picked.clear();
                fill();
            }
        }
    }

    private void clickFriendly(int slot) {
        if (slot == BACK) {
            open(player, Mode.MAIN);
        } else if (slot < shown.size()) {
            ServerPlayerEntity target = player.getServer().getPlayerManager().getPlayer(shown.get(slot));

            if (target != null) {
                player.closeHandledScreen();
                PokeTrainer.request(player, target);
            } else {
                fill();
            }
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity clicker, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity clicker) {
        return true;
    }

    // text helpers

    private static MutableText plain(String text, Formatting color) {
        return Text.literal(text).setStyle(Style.EMPTY.withItalic(false).withColor(color));
    }

    private static ItemStack named(ItemStack stack, Text name, List<Text> lore) {
        stack.set(DataComponentTypes.CUSTOM_NAME, name.copy().setStyle(name.getStyle().withItalic(false)));
        List<Text> lines = new ArrayList<>();

        for (Text line : lore) {
            lines.add(line.copy().setStyle(line.getStyle().withItalic(false)));
        }

        stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
        stack.set(DataComponentTypes.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
        return stack;
    }
}
