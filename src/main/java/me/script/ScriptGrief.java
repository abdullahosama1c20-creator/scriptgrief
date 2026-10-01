package me.script;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public class ScriptGrief extends JavaPlugin {
    private static final int HALF = 50; // 100x100 area
    private static final List<String> TYPES = List.of("tnt", "used", "base", "fire", "lava", "flood", "flat", "nether", "fight");
    private final Random r = new Random();
    private final Map<UUID, List<Saved>> hist = new HashMap<>();
    private final Set<BukkitTask> tasks = new HashSet<>();

    record Saved(Block b, BlockData d) {}

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        if (!p.hasPermission("script.use")) { p.sendMessage("§cNo permission."); return true; }
        if (a.length == 0) { p.sendMessage("§e/script spawn destroy <type> | undo | stop | list"); return true; }
        UUID id = p.getUniqueId();
        switch (a[0].toLowerCase()) {
            case "list" -> p.sendMessage("§eTypes: §f" + String.join(", ", TYPES));
            case "stop" -> {
                tasks.forEach(BukkitTask::cancel);
                int n = tasks.size(); tasks.clear();
                p.sendMessage("§aStopped " + n + " running job(s). Use /script undo to revert.");
            }
            case "undo" -> {
                if (!tasks.isEmpty()) { p.sendMessage("§cJob running. /script stop first."); return true; }
                List<Saved> log = hist.remove(id);
                if (log == null || log.isEmpty()) { p.sendMessage("§cNothing to undo."); return true; }
                int[] i = {log.size() - 1};
                start(() -> {
                    for (int k = 0; k < 20000 && i[0] >= 0; k++, i[0]--)
                        log.get(i[0]).b().setBlockData(log.get(i[0]).d(), false);
                    return i[0] < 0;
                }, 1);
                p.sendMessage("§aUndoing " + log.size() + " blocks...");
            }
            case "spawn" -> {
                if (a.length < 3 || !a[1].equalsIgnoreCase("destroy") || !TYPES.contains(a[2].toLowerCase())) {
                    p.sendMessage("§eUsage: /script spawn destroy <" + String.join("|", TYPES) + ">");
                    return true;
                }
                if (!tasks.isEmpty()) { p.sendMessage("§cJob running. /script stop first."); return true; }
                World w = p.getWorld();
                Location loc = p.getLocation();
                int cx = loc.getBlockX(), cz = loc.getBlockZ(), cy = loc.getBlockY();
                List<Saved> log = new ArrayList<>();
                hist.put(id, log);
                switch (a[2].toLowerCase()) {
                    case "tnt" -> tnt(w, cx, cz, log);
                    case "used" -> used(w, cx, cz, cy, log);
                    case "base" -> base(w, cx, cz, log);
                    case "fire" -> rows(cx, cz, (x, z) -> {
                        int y = top(w, x, z);
                        if (r.nextInt(100) < 8 && w.getBlockAt(x, y, z).getType().isSolid()) set(log, w.getBlockAt(x, y + 1, z), Material.FIRE);
                    });
                    case "fight" -> fight(w, cx, cz, log);
                    case "lava" -> lava(w, cx, cz, log);
                    case "flood" -> rows(cx, cz, (x, z) -> {
                        for (int y = top(w, x, z) + 1; y <= cy; y++) {
                            Block b = w.getBlockAt(x, y, z);
                            if (b.getType().isAir()) set(log, b, Material.WATER);
                        }
                    });
                    case "flat" -> rows(cx, cz, (x, z) -> {
                        for (int y = cy; y < Math.min(w.getMaxHeight(), cy + 80); y++) {
                            Block b = w.getBlockAt(x, y, z);
                            if (!b.getType().isAir()) set(log, b, Material.AIR);
                        }
                    });
                    case "nether" -> rows(cx, cz, (x, z) -> {
                        int y = top(w, x, z);
                        if (!w.getBlockAt(x, y, z).getType().isSolid()) return;
                        int k = r.nextInt(100);
                        set(log, w.getBlockAt(x, y, z), k < 55 ? Material.NETHERRACK : k < 70 ? Material.MAGMA_BLOCK : k < 90 ? Material.SOUL_SAND : Material.CRIMSON_NYLIUM);
                        set(log, w.getBlockAt(x, y - 1, z), Material.NETHERRACK);
                        set(log, w.getBlockAt(x, y - 2, z), Material.NETHERRACK);
                        if (r.nextInt(100) < 6) set(log, w.getBlockAt(x, y + 1, z), Material.FIRE);
                    });
                }
                p.sendMessage("§aDestroying area (" + a[2].toLowerCase() + ")... /script undo reverts it.");
            }
            default -> p.sendMessage("§e/script spawn destroy <type> | undo | stop | list");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("spawn", "undo", "stop", "list");
        if (a.length == 2 && a[0].equalsIgnoreCase("spawn")) return List.of("destroy");
        if (a.length == 3 && a[0].equalsIgnoreCase("spawn")) return TYPES;
        return List.of();
    }

    // ---------- helpers ----------
    private void start(BooleanSupplier step, int period) {
        BukkitTask[] t = new BukkitTask[1];
        t[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (step.getAsBoolean()) { t[0].cancel(); tasks.remove(t[0]); }
        }, 0L, period);
        tasks.add(t[0]);
    }

    private void set(List<Saved> log, Block b, Material m) {
        if (b.getType() == m || b.getType() == Material.BEDROCK) return;
        log.add(new Saved(b, b.getBlockData()));
        b.setType(m, false);
    }

    private int top(World w, int x, int z) {
        return w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    /** runs f on every column, one x-row per tick */
    private void rows(int cx, int cz, BiConsumer<Integer, Integer> f) {
        int[] x = {cx - HALF};
        start(() -> {
            for (int z = cz - HALF; z < cz + HALF; z++) f.accept(x[0], z);
            return ++x[0] >= cx + HALF;
        }, 1);
    }

    // ---------- types ----------
    private void tnt(World w, int cx, int cz, List<Saved> log) {
        int[] n = {0};
        start(() -> {
            for (int i = 0; i < 2; i++, n[0]++) {
                int x = cx - HALF + r.nextInt(100), z = cz - HALF + r.nextInt(100);
                int y = top(w, x, z) - r.nextInt(3), rad = 3 + r.nextInt(5);
                for (int dx = -rad; dx <= rad; dx++)
                    for (int dy = -rad; dy <= rad; dy++)
                        for (int dz = -rad; dz <= rad; dz++) {
                            if (dx * dx + dy * dy + dz * dz > rad * rad) continue;
                            int by = y + dy;
                            if (by < w.getMinHeight() || by >= w.getMaxHeight()) continue;
                            set(log, w.getBlockAt(x + dx, by, z + dz), Material.AIR);
                        }
                w.createExplosion(new Location(w, x, y, z), 0f, false);
            }
            return n[0] >= 45;
        }, 1);
    }

    private void used(World w, int cx, int cz, int cy, List<Saved> log) {
        int y0 = Math.max(w.getMinHeight(), cy - 40), y1 = Math.min(w.getMaxHeight() - 1, cy + 60);
        rows(cx, cz, (x, z) -> {
            for (int y = y0; y <= y1; y++) {
                Block b = w.getBlockAt(x, y, z);
                if (valuable(b.getType())) set(log, b, Material.AIR);
            }
        });
    }

    private boolean valuable(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM") || n.endsWith("_HYPHAE")
                || n.endsWith("_ORE") || n.endsWith("_LEAVES") || n.equals("ANCIENT_DEBRIS")
                || n.startsWith("RAW_") || n.equals("OBSIDIAN");
    }

    private void lava(World w, int cx, int cz, List<Saved> log) {
        int[] n = {0};
        start(() -> {
            int x = cx - HALF + 6 + r.nextInt(88), z = cz - HALF + 6 + r.nextInt(88);
            int y = top(w, x, z), rad = 2 + r.nextInt(4);
            for (int dx = -rad; dx <= rad; dx++)
                for (int dz = -rad; dz <= rad; dz++) {
                    if (dx * dx + dz * dz > rad * rad) continue;
                    for (int h = 1; h <= 3; h++) set(log, w.getBlockAt(x + dx, y + h, z + dz), Material.AIR);
                    set(log, w.getBlockAt(x + dx, y, z + dz), Material.LAVA);
                    set(log, w.getBlockAt(x + dx, y - 1, z + dz), Material.LAVA);
                }
            return ++n[0] >= 25;
        }, 1);
    }

    // Aftermath of a big PvP fight (denser near you)
    private void fight(World w, int cx, int cz, List<Saved> log) {
        Material[] blocks = {Material.COBBLESTONE, Material.OAK_PLANKS, Material.STONE_BRICKS, Material.DIRT, Material.NETHERRACK, Material.COBBLED_DEEPSLATE};
        Material[] broken = {Material.OAK_FENCE, Material.COBBLESTONE_WALL, Material.OAK_SLAB, Material.STONE_BRICK_STAIRS,
                Material.IRON_BARS, Material.COBBLESTONE_SLAB, Material.CRACKED_STONE_BRICKS, Material.OAK_TRAPDOOR, Material.TNT};
        rows(cx, cz, (x, z) -> {
            int y = top(w, x, z);
            if (!w.getBlockAt(x, y, z).getType().isSolid()) return;
            Block a = w.getBlockAt(x, y + 1, z);
            if (!a.getType().isAir()) return;
            int dx = x - cx, dz = z - cz, m = dx * dx + dz * dz < 400 ? 3 : 1;
            int k = r.nextInt(1000);
            if (k < 40 * m) {                       // cobwebs
                set(log, a, Material.COBWEB);
                if (r.nextBoolean()) set(log, w.getBlockAt(x, y + 2, z), Material.COBWEB);
            } else if (k < 60 * m) {                // panic-placed pillars
                for (int h = 1; h <= 1 + r.nextInt(3); h++) set(log, w.getBlockAt(x, y + h, z), blocks[r.nextInt(blocks.length)]);
            } else if (k < 75 * m) {                // broken things
                set(log, a, broken[r.nextInt(broken.length)]);
            } else if (k < 85 * m) {                // small craters
                int rad = 1 + r.nextInt(2);
                for (int i = -rad; i <= rad; i++) for (int j = -rad; j <= rad; j++) for (int h = -rad; h <= rad; h++)
                    if (i * i + j * j + h * h <= rad * rad) set(log, w.getBlockAt(x + i, y + h, z + j), Material.AIR);
            } else if (k < 100 * m) {               // obsidian
                for (int h = 1; h <= 1 + r.nextInt(2); h++) set(log, w.getBlockAt(x, y + h, z), Material.OBSIDIAN);
            } else if (k < 130 * m) {               // loot on ground
                w.dropItem(new Location(w, x + 0.5, y + 1, z + 0.5), randItem());
            } else if (k < 134 * m) {               // chests
                set(log, a, Material.CHEST);
                if (a.getState() instanceof Container c)
                    for (int i = 0, n = 3 + r.nextInt(6); i < n; i++) c.getInventory().setItem(r.nextInt(27), randItem());
            } else if (k < 137 * m) {               // ender chests
                set(log, a, Material.ENDER_CHEST);
            }
        });
    }

    private ItemStack randItem() {
        Material[] items = {Material.ARROW, Material.COOKED_BEEF, Material.GOLDEN_APPLE, Material.ENDER_PEARL, Material.IRON_SWORD,
                Material.DIAMOND_PICKAXE, Material.BOW, Material.SHIELD, Material.DIAMOND_CHESTPLATE, Material.TNT, Material.OBSIDIAN,
                Material.COBWEB, Material.WATER_BUCKET, Material.IRON_INGOT, Material.GOLDEN_CARROT, Material.TOTEM_OF_UNDYING,
                Material.NETHERITE_SWORD, Material.DIAMOND_BOOTS};
        Material m = items[r.nextInt(items.length)];
        ItemStack it = new ItemStack(m, m.getMaxStackSize() > 1 ? 1 + r.nextInt(Math.min(8, m.getMaxStackSize())) : 1);
        if (it.getItemMeta() instanceof Damageable d && m.getMaxDurability() > 0) {
            d.setDamage(r.nextInt(m.getMaxDurability() * 3 / 4));
            it.setItemMeta(d);
        }
        return it;
    }

    private void base(World w, int cx, int cz, List<Saved> log) {
        List<int[]> spots = new ArrayList<>();
        spots.add(new int[]{cx - 3, cz - 3});
        for (int i = 0; i < 5; i++) spots.add(new int[]{cx - HALF + r.nextInt(93), cz - HALF + r.nextInt(93)});
        int[] i = {0};
        start(() -> {
            ruin(w, spots.get(i[0])[0], spots.get(i[0])[1], log);
            return ++i[0] >= spots.size();
        }, 2);
    }

    private void ruin(World w, int x0, int z0, List<Saved> log) {
        Material[] floor = {Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.COBBLESTONE};
        Material[] wall = {Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS};
        int y = top(w, x0 + 3, z0 + 3);
        for (int dx = 0; dx < 7; dx++)
            for (int dz = 0; dz < 7; dz++) {
                if (r.nextInt(5) != 0) set(log, w.getBlockAt(x0 + dx, y, z0 + dz), floor[r.nextInt(floor.length)]);
                boolean edge = dx == 0 || dz == 0 || dx == 6 || dz == 6;
                for (int h = 1; h <= 5; h++) set(log, w.getBlockAt(x0 + dx, y + h, z0 + dz), Material.AIR);
                if (edge && !(dx == 3 && dz == 0)) {
                    int height = r.nextInt(5);
                    for (int h = 1; h <= height; h++)
                        if (r.nextInt(4) != 0) set(log, w.getBlockAt(x0 + dx, y + h, z0 + dz), wall[r.nextInt(wall.length)]);
                } else if (!edge && r.nextInt(6) == 0) {
                    set(log, w.getBlockAt(x0 + dx, y + 1, z0 + dz), r.nextBoolean() ? Material.COBWEB : Material.COBBLESTONE);
                }
            }
        set(log, w.getBlockAt(x0 + 3, y + 1, z0 + 3), Material.CHEST);
    }
}
