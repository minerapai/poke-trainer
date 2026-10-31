package kr.poketrainer;

import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Every player's trainer, one file per player in world/poke-trainer/<uuid>.dat (the pokemon are stored there). */
public final class TrainerData {
    public static final int TEAM_SIZE = 6;

    public static final class Record {
        public String name;
        /** The pokemon handed over, in battle order. */
        public final List<Pokemon> team = new ArrayList<>();
        /** Index into Stages.ALL of the next opponent. */
        public int stage;
        public int wins;
        public int losses;
        public int friendlyWins;
        public int friendlyLosses;
        /** RCT ids of the stages beaten. */
        public final Set<String> defeated = new LinkedHashSet<>();
        /** The stat each pokemon (by uuid) trains its EVs in; no entry: none. */
        public final Map<UUID, Stats> focus = new HashMap<>();

        /** The player's gym (their trainer guards it), or null. */
        public Gym gym;
        /** Gyms of other players this player beat in person. */
        public int gymWins;

        public Stats focus(Pokemon pokemon) {
            return focus.get(pokemon.getUuid());
        }
    }

    /** A gym a player set up near their home: their trainer stands there as its leader. */
    public static final class Gym {
        public String world;
        public double x;
        public double y;
        public double z;
        public float yaw;
        /** Elemental type id (fire, water, ...): every pokemon of the team must have it. */
        public String type;
        /** Paid by the challenger at the start; back to them on a win, the owner's income on a loss. */
        public long fee;
        /** Paid to a winning challenger out of the vault (as far as the vault reaches). */
        public long prize;
        public long vault;
        /** Fees won by defending, waiting for the owner to collect them. */
        public long income;
        /** Reward items the owner stocked: a winning challenger gets one of the first stack. */
        public final List<ItemStack> rewards = new ArrayList<>();
        public int defenses;
        public int defeats;
    }

    private final MinecraftServer server;
    private final Path dir;
    private final Map<UUID, Record> loaded = new HashMap<>();

    TrainerData(MinecraftServer server) {
        this.server = server;
        this.dir = server.getSavePath(WorldSavePath.ROOT).resolve("poke-trainer");
    }

    /** The player's trainer, or null when they have not made one. */
    public Record get(UUID player) {
        if (loaded.containsKey(player)) {
            return loaded.get(player);
        }

        Record record = read(player);
        loaded.put(player, record);
        return record;
    }

    /** Loads every trainer file (gyms of offline players have to stand too). */
    void loadAll() {
        if (Files.notExists(dir)) {
            return;
        }

        try (var files = Files.list(dir)) {
            files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".dat")).forEach(n -> {
                try {
                    get(UUID.fromString(n.substring(0, n.length() - 4)));
                } catch (IllegalArgumentException | IllegalStateException e) {
                    // not a trainer file, or unreadable (logged)
                }
            });
        } catch (IOException e) {
            PokeTrainer.LOGGER.error("Could not list the trainer files", e);
        }
    }

    /** Owner -> gym of every trainer loaded. */
    public Map<UUID, Gym> gyms() {
        Map<UUID, Gym> gyms = new HashMap<>();
        loaded.forEach((uuid, record) -> {
            if (record != null && record.gym != null) {
                gyms.put(uuid, record.gym);
            }
        });
        return gyms;
    }

    Record create(UUID player, String name) {
        Record record = new Record();
        record.name = name;
        loaded.put(player, record);
        save(player);
        return record;
    }

    public void save(UUID player) {
        Record record = loaded.get(player);

        if (record == null) {
            return;
        }

        DynamicRegistryManager registries = server.getRegistryManager();
        NbtCompound nbt = new NbtCompound();
        nbt.putString("name", record.name);
        nbt.putInt("stage", record.stage);
        nbt.putInt("wins", record.wins);
        nbt.putInt("losses", record.losses);
        nbt.putInt("friendlyWins", record.friendlyWins);
        nbt.putInt("friendlyLosses", record.friendlyLosses);
        NbtList team = new NbtList();
        record.team.forEach(pokemon -> team.add(pokemon.saveToNBT(registries, new NbtCompound())));
        nbt.put("team", team);
        NbtList defeated = new NbtList();
        record.defeated.forEach(id -> defeated.add(NbtString.of(id)));
        nbt.put("defeated", defeated);
        NbtCompound focus = new NbtCompound();
        record.focus.forEach((uuid, stat) -> focus.putString(uuid.toString(), stat.name()));
        nbt.put("focus", focus);
        nbt.putInt("gymWins", record.gymWins);

        if (record.gym != null) {
            Gym gym = record.gym;
            NbtCompound g = new NbtCompound();
            g.putString("world", gym.world);
            g.putDouble("x", gym.x);
            g.putDouble("y", gym.y);
            g.putDouble("z", gym.z);
            g.putFloat("yaw", gym.yaw);
            g.putString("type", gym.type);
            g.putLong("fee", gym.fee);
            g.putLong("prize", gym.prize);
            g.putLong("vault", gym.vault);
            g.putLong("income", gym.income);
            g.putInt("defenses", gym.defenses);
            g.putInt("defeats", gym.defeats);
            NbtList rewards = new NbtList();
            gym.rewards.stream().filter(stack -> !stack.isEmpty()).forEach(stack -> rewards.add(stack.encode(registries)));
            g.put("rewards", rewards);
            nbt.put("gym", g);
        }

        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(player + ".dat");
            Path temp = dir.resolve(player + ".dat.tmp");
            NbtIo.writeCompressed(nbt, temp);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            PokeTrainer.LOGGER.error("Could not save the trainer of {}", player, e);
        }
    }

    private Record read(UUID player) {
        Path file = dir.resolve(player + ".dat");

        if (Files.notExists(file)) {
            return null;
        }

        try {
            NbtCompound nbt = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            Record record = new Record();
            record.name = nbt.getString("name");
            record.stage = nbt.getInt("stage");
            record.wins = nbt.getInt("wins");
            record.losses = nbt.getInt("losses");
            record.friendlyWins = nbt.getInt("friendlyWins");
            record.friendlyLosses = nbt.getInt("friendlyLosses");
            DynamicRegistryManager registries = server.getRegistryManager();

            for (NbtElement element : nbt.getList("team", NbtElement.COMPOUND_TYPE)) {
                record.team.add(Pokemon.Companion.loadFromNBT(registries, (NbtCompound) element));
            }

            for (NbtElement element : nbt.getList("defeated", NbtElement.STRING_TYPE)) {
                record.defeated.add(element.asString());
            }

            NbtCompound focus = nbt.getCompound("focus");

            for (String key : focus.getKeys()) {
                try {
                    record.focus.put(UUID.fromString(key), Stats.valueOf(focus.getString(key)));
                } catch (IllegalArgumentException ignored) {
                    // an unknown stat or uuid: no focus
                }
            }

            record.gymWins = nbt.getInt("gymWins");

            if (nbt.contains("gym", NbtElement.COMPOUND_TYPE)) {
                NbtCompound g = nbt.getCompound("gym");
                Gym gym = new Gym();
                gym.world = g.getString("world");
                gym.x = g.getDouble("x");
                gym.y = g.getDouble("y");
                gym.z = g.getDouble("z");
                gym.yaw = g.getFloat("yaw");
                gym.type = g.getString("type");
                gym.fee = g.getLong("fee");
                gym.prize = g.getLong("prize");
                gym.vault = g.getLong("vault");
                gym.income = g.getLong("income");
                gym.defenses = g.getInt("defenses");
                gym.defeats = g.getInt("defeats");

                for (NbtElement element : g.getList("rewards", NbtElement.COMPOUND_TYPE)) {
                    ItemStack.fromNbt(registries, element).ifPresent(gym.rewards::add);
                }

                record.gym = gym;
            }

            return record;
        } catch (IOException | RuntimeException e) {
            // never treat a broken file as "no trainer": the pokemon in it would be overwritten
            PokeTrainer.LOGGER.error("Could not read the trainer of {} ({})", player, file, e);
            throw new IllegalStateException("trainer file unreadable: " + file, e);
        }
    }
}
