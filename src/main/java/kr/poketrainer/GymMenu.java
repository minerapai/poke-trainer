package kr.poketrainer;

import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chest screens of player gyms (vanilla screens, clients need no mod): the owner's setup (type, fee, prize, vault,
 * income, reward stock, move / close), the challenger's view of a gym (its team, fee and rewards, challenge), the
 * list of all gyms, and picking the gym type. Items only move between the owner's inventory and the reward stock.
 */
public final class GymMenu extends GenericContainerScreenHandler {
    public enum Mode { OWNER, CHALLENGE, LIST, TYPE }

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int REWARD_FIRST = 36;
    private static final int REWARD_SLOTS = 9;
    private static final int BACK = 45;
    private static final long STEP = 1_000;

    private final ServerPlayerEntity player;
    private final SimpleInventory inventory;
    private final Mode mode;
    /** CHALLENGE: whose gym; LIST: the gyms by slot. */
    private final UUID owner;
    private final List<UUID> shown = new ArrayList<>();
    private final List<String> types = new ArrayList<>(Gyms.TYPES.keySet());

    private GymMenu(int syncId, PlayerInventory playerInventory, SimpleInventory inventory, ServerPlayerEntity player,
                    Mode mode, UUID owner) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, ROWS);
        this.player = player;
        this.inventory = inventory;
        this.mode = mode;
        this.owner = owner;
        fill();
    }

    public static int open(ServerPlayerEntity player, Mode mode, UUID owner) {
        String title = switch (mode) {
            case OWNER -> "내 체육관";
            case CHALLENGE -> "체육관 도전";
            case LIST -> "체육관 목록";
            case TYPE -> "체육관 타입 고르기";
        };
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInventory, p) -> new GymMenu(syncId, playerInventory, new SimpleInventory(SIZE), player, mode, owner),
            Text.literal(title)));
        return 1;
    }

    private TrainerData data() {
        return PokeTrainer.data();
    }

    private TrainerData.Record own() {
        return PokeTrainer.record(player);
    }

    // building the screens

    private void fill() {
        inventory.clear();
        ItemStack filler = named(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), Text.literal(" "), List.of());

        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setStack(slot, filler.copy());
        }

        switch (mode) {
            case OWNER -> fillOwner();
            case CHALLENGE -> fillChallenge();
            case LIST -> fillList();
            case TYPE -> fillType();
        }

        sendContentUpdates();
    }

    private static String money(long amount) {
        return String.format("%,d", amount);
    }

    private void fillOwner() {
        TrainerData.Record record = own();
        TrainerData.Gym gym = record != null ? record.gym : null;
        inventory.setStack(BACK, named(new ItemStack(Items.ARROW), plain("내 트레이너로", Formatting.AQUA), List.of()));

        if (gym == null) {
            inventory.setStack(22, named(new ItemStack(Items.LECTERN), plain("여기에 체육관 세우기", Formatting.GREEN), List.of(
                plain("내가 서 있는 자리에 체육관을 세웁니다 (내 트레이너가 관장).", Formatting.GRAY),
                plain("먼저 체육관 타입을 고릅니다. 트레이너의 팀은 모두 그 타입이어야", Formatting.GRAY),
                plain("도전을 받을 수 있습니다. 도전은 Lv." + Gyms.LEVEL + ", 6:6 배틀입니다.", Formatting.GRAY),
                plain("클릭: 타입 고르기", Formatting.YELLOW))));
            return;
        }

        String team = Gyms.teamProblem(record);
        inventory.setStack(4, named(new ItemStack(Items.LECTERN), plain(record.name + "의 " + Gyms.typeName(gym.type) + " 체육관",
            Formatting.GOLD), List.of(
            plain("방어 " + gym.defenses + "승 / 패배 " + gym.defeats, Formatting.GRAY),
            plain("위치: " + (int) gym.x + " " + (int) gym.y + " " + (int) gym.z, Formatting.GRAY),
            team == null ? plain("도전을 받는 중", Formatting.GREEN) : plain("도전을 받을 수 없음: " + team, Formatting.RED))));

        for (int i = 0; i < record.team.size() && i < 6; i++) {
            Pokemon pokemon = record.team.get(i);
            boolean ok = Gyms.hasType(pokemon, gym.type);
            inventory.setStack(10 + i, named(PokemonItem.Companion.from(pokemon), pokemon.getDisplayName(false).copy()
                .formatted(ok ? Formatting.WHITE : Formatting.RED), List.of(plain(ok ? "체육관 타입 맞음" : "체육관 타입이 아님!",
                ok ? Formatting.GREEN : Formatting.RED), plain("팀은 내 트레이너 화면에서 바꿉니다.", Formatting.GRAY))));
        }

        inventory.setStack(19, named(new ItemStack(Items.NAME_TAG), plain("타입: " + Gyms.typeName(gym.type), Formatting.AQUA),
            List.of(plain("클릭: 타입 바꾸기", Formatting.YELLOW))));
        inventory.setStack(21, named(new ItemStack(Items.GOLD_NUGGET), plain("도전료: " + money(gym.fee), Formatting.YELLOW), List.of(
            plain("도전자가 시작할 때 냅니다. 체육관이 지키면 내 수익이 됩니다.", Formatting.GRAY),
            plain("왼쪽 +" + money(STEP) + " / 오른쪽 -" + money(STEP) + " (쉬프트: 10배)", Formatting.YELLOW))));
        inventory.setStack(23, named(new ItemStack(Items.GOLD_INGOT), plain("승리 상금: " + money(gym.prize), Formatting.YELLOW), List.of(
            plain("도전자가 이기면 금고에서 이만큼 줍니다 (금고가 모자라면 남은 만큼).", Formatting.GRAY),
            plain("왼쪽 +" + money(STEP) + " / 오른쪽 -" + money(STEP) + " (쉬프트: 10배)", Formatting.YELLOW))));
        inventory.setStack(25, named(new ItemStack(Items.CHEST), plain("금고: " + money(gym.vault), Formatting.GOLD), List.of(
            plain("상금은 여기서만 나갑니다. 내 지갑: " + money(Gyms.balance(player)), Formatting.GRAY),
            plain("왼쪽: 10,000 넣기 (쉬프트: 100,000) / 오른쪽: 모두 꺼내기", Formatting.YELLOW))));
        inventory.setStack(31, named(new ItemStack(Items.EMERALD), plain("쌓인 수익: " + money(gym.income), Formatting.GREEN), List.of(
            plain("체육관이 지켜서 받은 도전료입니다.", Formatting.GRAY), plain("클릭: 받기", Formatting.YELLOW))));
        inventory.setStack(27, named(new ItemStack(Items.HOPPER), plain("보상 아이템 재고 (아래 줄)", Formatting.GOLD), List.of(
            plain("도전자가 이기면 맨 앞 칸의 아이템을 1개 받습니다.", Formatting.GRAY),
            plain("내 인벤토리의 아이템을 클릭하면 재고에 넣고,", Formatting.GRAY),
            plain("재고를 클릭하면 돌려받습니다 (최대 " + REWARD_SLOTS + "칸).", Formatting.GRAY))));

        for (int i = 0; i < REWARD_SLOTS; i++) {
            if (i < gym.rewards.size()) {
                inventory.setStack(REWARD_FIRST + i, gym.rewards.get(i).copy());
            } else {
                inventory.setStack(REWARD_FIRST + i, named(new ItemStack(Items.LIGHT_GRAY_STAINED_GLASS_PANE), plain("빈 칸",
                    Formatting.DARK_GRAY), List.of()));
            }
        }

        boolean busy = Gyms.busy(player.getUuid());
        inventory.setStack(49, named(new ItemStack(Items.COMPASS), plain("체육관을 여기로 옮기기", Formatting.AQUA), List.of(
            plain("내가 서 있는 자리로 관장을 옮깁니다.", Formatting.GRAY),
            busy ? plain("도전 중에는 옮길 수 없습니다", Formatting.RED) : plain("클릭: 옮기기", Formatting.YELLOW))));
        inventory.setStack(53, named(new ItemStack(Items.BARRIER), plain("체육관 닫기", Formatting.RED), List.of(
            plain("금고, 수익, 보상 아이템을 모두 돌려받고 체육관을 없앱니다.", Formatting.GRAY),
            busy ? plain("도전 중에는 닫을 수 없습니다", Formatting.RED) : plain("쉬프트 클릭: 닫기", Formatting.YELLOW))));
    }

    private void fillChallenge() {
        TrainerData.Record record = owner != null ? data().get(owner) : null;
        TrainerData.Gym gym = record != null ? record.gym : null;

        if (gym == null) {
            inventory.setStack(22, named(new ItemStack(Items.BARRIER), plain("이 체육관은 문을 닫았습니다", Formatting.RED), List.of()));
            return;
        }

        ServerPlayerEntity ownerPlayer = player.getServer().getPlayerManager().getPlayer(owner);
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);

        if (ownerPlayer != null) {
            head.set(DataComponentTypes.PROFILE, new ProfileComponent(ownerPlayer.getGameProfile()));
        }

        inventory.setStack(4, named(head, plain(record.name + "의 " + Gyms.typeName(gym.type) + " 체육관", Formatting.GOLD), List.of(
            plain("방어 " + gym.defenses + "승 / 패배 " + gym.defeats, Formatting.GRAY),
            plain("배틀: 내 파티 vs 체육관 팀, 모두 Lv." + Gyms.LEVEL + " (원래 포켓몬은 그대로)", Formatting.GRAY))));

        for (int i = 0; i < record.team.size() && i < 6; i++) {
            Pokemon pokemon = record.team.get(i);
            inventory.setStack(19 + i, named(PokemonItem.Companion.from(pokemon), pokemon.getDisplayName(false).copy()
                .formatted(Formatting.WHITE), List.of(plain((i + 1) + "번째 (Lv." + Gyms.LEVEL + " 로 출전)", Formatting.GRAY))));
        }

        long prize = Math.min(gym.prize, gym.vault);
        List<Text> rewardLore = new ArrayList<>();
        rewardLore.add(plain("이기면: 도전료 돌려받기 + 상금 " + money(prize), Formatting.GREEN));
        gym.rewards.removeIf(ItemStack::isEmpty);
        rewardLore.add(gym.rewards.isEmpty() ? plain("보상 아이템: 없음", Formatting.GRAY)
            : plain("보상 아이템: ", Formatting.GREEN).append(gym.rewards.get(0).getName()).append(plain(" 1개", Formatting.GREEN)));
        rewardLore.add(plain("지면: 도전료는 체육관 주인에게", Formatting.RED));
        inventory.setStack(31, named(gym.rewards.isEmpty() ? new ItemStack(Items.GOLD_INGOT) : gym.rewards.get(0).copyWithCount(1),
            plain("보상", Formatting.GOLD), rewardLore));

        String team = Gyms.teamProblem(record);
        boolean mine = owner.equals(player.getUuid());
        boolean ready = team == null && !mine && !Gyms.busy(owner);
        inventory.setStack(40, named(new ItemStack(ready ? Items.LIME_CONCRETE : Items.RED_CONCRETE),
            plain(ready ? "도전! (도전료 " + money(gym.fee) + ")" : "도전할 수 없음", ready ? Formatting.GREEN : Formatting.RED), List.of(
                plain(mine ? "내 체육관입니다" : team != null ? "체육관 준비 중: " + team
                    : Gyms.busy(owner) ? "다른 도전자가 배틀 중입니다" : "내 지갑: " + money(Gyms.balance(player)), Formatting.GRAY))));
    }

    private void fillList() {
        shown.clear();

        for (Map.Entry<UUID, TrainerData.Gym> entry : data().gyms().entrySet()) {
            if (shown.size() >= BACK) {
                break;
            }

            TrainerData.Record record = data().get(entry.getKey());
            TrainerData.Gym gym = entry.getValue();
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            ServerPlayerEntity ownerPlayer = player.getServer().getPlayerManager().getPlayer(entry.getKey());

            if (ownerPlayer != null) {
                head.set(DataComponentTypes.PROFILE, new ProfileComponent(ownerPlayer.getGameProfile()));
            }

            inventory.setStack(shown.size(), named(head, plain(record.name + "의 " + Gyms.typeName(gym.type) + " 체육관", Formatting.GOLD),
                List.of(plain("위치: " + (int) gym.x + " " + (int) gym.y + " " + (int) gym.z, Formatting.GRAY),
                    plain("방어 " + gym.defenses + "승 / 패배 " + gym.defeats, Formatting.GRAY),
                    plain("도전료 " + money(gym.fee) + ", 상금 " + money(Math.min(gym.prize, gym.vault)), Formatting.GRAY),
                    plain("클릭: 위치를 채팅에 표시", Formatting.YELLOW))));
            shown.add(entry.getKey());
        }

        if (shown.isEmpty()) {
            inventory.setStack(22, named(new ItemStack(Items.BARRIER), plain("아직 체육관이 없습니다", Formatting.RED), List.of(
                plain("관동을 클리어한 플레이어는 /트레이너 → 내 체육관 에서 세울 수 있습니다.", Formatting.GRAY))));
        }
    }

    private void fillType() {
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            inventory.setStack(10 + i + (i / 7) * 2, named(new ItemStack(Items.PAPER), plain(Gyms.typeName(type), Formatting.AQUA),
                List.of(plain("클릭: 이 타입으로", Formatting.YELLOW))));
        }

        inventory.setStack(BACK, named(new ItemStack(Items.ARROW), plain("돌아가기", Formatting.AQUA), List.of()));
    }

    // clicks

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clicker) {
        boolean shift = actionType == SlotActionType.QUICK_MOVE;

        if ((actionType == SlotActionType.PICKUP || shift) && slotIndex >= 0) {
            if (slotIndex < SIZE) {
                switch (mode) {
                    case OWNER -> clickOwner(slotIndex, button, shift);
                    case CHALLENGE -> clickChallenge(slotIndex);
                    case LIST -> clickList(slotIndex);
                    case TYPE -> clickType(slotIndex);
                }
            } else if (mode == Mode.OWNER) {
                stockFromInventory(slotIndex);
            }
        }

        syncState(); // undo whatever the client already showed moving
    }

    private void clickOwner(int slot, int button, boolean shift) {
        TrainerData.Record record = own();

        if (slot == BACK) {
            TrainerMenu.open(player, TrainerMenu.Mode.MAIN);
            return;
        }

        if (record == null) {
            return;
        }

        TrainerData.Gym gym = record.gym;

        if (gym == null) {
            if (slot == 22) {
                open(player, Mode.TYPE, null);
            }

            return;
        }

        long step = shift ? STEP * 10 : STEP;

        switch (slot) {
            case 19 -> {
                open(player, Mode.TYPE, null);
                return;
            }
            case 21 -> gym.fee = clamp(gym.fee + (button == 1 ? -step : step));
            case 23 -> gym.prize = clamp(gym.prize + (button == 1 ? -step : step));
            case 25 -> {
                if (button == 1) {
                    Gyms.addMoney(player, gym.vault);
                    gym.vault = 0;
                } else {
                    long amount = shift ? 100_000 : 10_000;

                    if (gym.vault + amount <= Gyms.MAX_AMOUNT && Gyms.takeMoney(player, amount)) {
                        gym.vault += amount;
                    } else {
                        player.sendMessage(Text.literal("지갑의 돈이 모자라거나 금고가 가득 찼습니다.").formatted(Formatting.RED));
                    }
                }
            }
            case 31 -> {
                Gyms.addMoney(player, gym.income);
                gym.income = 0;
            }
            case 49 -> {
                if (!Gyms.busy(player.getUuid())) {
                    Gyms.removeLeader(player.getServer(), player.getUuid(), gym);
                    place(gym);
                    player.sendMessage(Text.literal("체육관을 여기로 옮겼습니다.").formatted(Formatting.GREEN));
                }
            }
            case 53 -> {
                if (shift && !Gyms.busy(player.getUuid())) {
                    close(record);
                    data().save(player.getUuid());
                    fill();
                    return;
                }
            }
            default -> {
                if (slot >= REWARD_FIRST && slot < REWARD_FIRST + REWARD_SLOTS && slot - REWARD_FIRST < gym.rewards.size()) {
                    ItemStack stack = gym.rewards.remove(slot - REWARD_FIRST);

                    if (!player.getInventory().insertStack(stack)) {
                        player.dropItem(stack, false);
                    }
                } else {
                    return;
                }
            }
        }

        data().save(player.getUuid());
        fill();
    }

    /** An item clicked in the owner's own inventory goes into the reward stock. */
    private void stockFromInventory(int slotIndex) {
        TrainerData.Record record = own();

        if (record == null || record.gym == null || slotIndex >= slots.size()) {
            return;
        }

        ItemStack stack = slots.get(slotIndex).getStack();
        record.gym.rewards.removeIf(ItemStack::isEmpty);

        if (stack.isEmpty() || record.gym.rewards.size() >= REWARD_SLOTS) {
            return;
        }

        record.gym.rewards.add(stack.copy());
        slots.get(slotIndex).setStack(ItemStack.EMPTY);
        data().save(player.getUuid());
        fill();
    }

    private static long clamp(long amount) {
        return Math.max(0, Math.min(Gyms.MAX_AMOUNT, amount));
    }

    private void place(TrainerData.Gym gym) {
        gym.world = player.getServerWorld().getRegistryKey().getValue().toString();
        gym.x = player.getX();
        gym.y = player.getY();
        gym.z = player.getZ();
        gym.yaw = player.getYaw();
    }

    private void close(TrainerData.Record record) {
        TrainerData.Gym gym = record.gym;
        Gyms.removeLeader(player.getServer(), player.getUuid(), gym);
        Gyms.addMoney(player, gym.vault + gym.income);

        for (ItemStack stack : gym.rewards) {
            if (!stack.isEmpty() && !player.getInventory().insertStack(stack)) {
                player.dropItem(stack, false);
            }
        }

        record.gym = null;
        player.sendMessage(Text.literal("체육관을 닫았습니다. 금고, 수익, 보상 아이템을 돌려받았습니다.").formatted(Formatting.GRAY));
        PokeTrainer.LOGGER.info("{} closed their gym", player.getGameProfile().getName());
    }

    private void clickChallenge(int slot) {
        if (slot == 40 && owner != null) {
            player.closeHandledScreen();
            String problem = Gyms.challenge(player, owner, data());

            if (problem != null) {
                player.sendMessage(Text.literal(problem).formatted(Formatting.RED));
            }
        }
    }

    private void clickList(int slot) {
        if (slot >= shown.size()) {
            return;
        }

        TrainerData.Record record = data().get(shown.get(slot));

        if (record != null && record.gym != null) {
            TrainerData.Gym gym = record.gym;
            player.sendMessage(Text.literal("[체육관] " + record.name + "의 " + Gyms.typeName(gym.type) + " 체육관: "
                + (int) gym.x + " " + (int) gym.y + " " + (int) gym.z + " (" + gym.world + ")").formatted(Formatting.GOLD));
        }
    }

    private void clickType(int slot) {
        if (slot == BACK) {
            open(player, Mode.OWNER, null);
            return;
        }

        for (int i = 0; i < types.size(); i++) {
            if (slot == 10 + i + (i / 7) * 2) {
                TrainerData.Record record = own();

                if (record == null || !PokeTrainer.unlocked(player)) {
                    return;
                }

                if (record.gym == null) {
                    record.gym = new TrainerData.Gym();
                    place(record.gym);
                    record.gym.fee = 5_000;
                    player.sendMessage(Text.literal(Gyms.typeName(types.get(i)) + " 체육관을 세웠습니다! 도전료, 상금, 보상을 정해 주세요.")
                        .formatted(Formatting.GREEN));
                    PokeTrainer.LOGGER.info("{} opened a {} gym", player.getGameProfile().getName(), types.get(i));
                }

                record.gym.type = types.get(i);
                data().save(player.getUuid());
                open(player, Mode.OWNER, null);
                return;
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
