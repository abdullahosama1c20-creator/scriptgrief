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

import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public class ScriptGrief extends JavaPlugin implements Listener {
    private static final List<String> ITEMS = List.of("pot_horn", "pot_rod", "sonic_horn", "sonic_rod");
    private static final MusicInstrument[] HORNS = {MusicInstrument.PONDER, MusicInstrument.SING, MusicInstrument.SEEK,
            MusicInstrument.FEEL, MusicInstrument.ADMIRE, MusicInstrument.CALL, MusicInstrument.YEARN, MusicInstrument.DREAM};
    private static final double SONIC_DAMAGE = 10.0; // 5 hearts per boom
    private NamespacedKey key;
    private final Set<UUID> busy = new HashSet<>();

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "item");
        getServer().getPluginManager().registerEvents(this, this);
    }

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
        if (a.length == 0) { p.sendMessage("§e/script spawn destroy <type> | undo | stop | list | give <player> <item>"); return true; }
        UUID id = p.getUniqueId();
        switch (a[0].toLowerCase()) {
            case "list" -> p.sendMessage("§eTypes: §f" + String.join(", ", TYPES));
            case "give" -> {
                if (a.length < 3 || !ITEMS.contains(a[2].toLowerCase())) {
                    p.sendMessage("§eUsage: /script give <player> <" + String.join("|", ITEMS) + ">");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { p.sendMessage("§cPlayer not online."); return true; }
                t.getInventory().addItem(make(a[2].toLowerCase())).values().forEach(it -> t.getWorld().dropItem(t.getLocation(), it));
                p.sendMessage("§aGave " + a[2].toLowerCase() + " to " + t.getName());
            }
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
            default -> p.sendMessage("§e/script spawn destroy <type> | undo | stop | list | give <player> <item>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("spawn", "undo", "stop", "list", "give");
        if (a.length == 2 && a[0].equalsIgnoreCase("spawn")) return List.of("destroy");
        if (a.length == 3 && a[0].equalsIgnoreCase("spawn")) return TYPES;
        if (a.length == 2 && a[0].equalsIgnoreCase("give")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (a.length == 3 && a[0].equalsIgnoreCase("give")) return ITEMS;
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
        if (b.getType() == m || b.getType() == Material.BEDROCK || b.getY() < b.getWorld().getMinHeight() || b.getY() >= b.getWorld().getMaxHeight()) return;
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

    // ---------- fight: separate skirmish sites, each with its own block palette ----------
    private static final Material[] PAL = {Material.COBBLESTONE, Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.DIRT,
            Material.NETHERRACK, Material.COBBLED_DEEPSLATE, Material.STONE, Material.WHITE_WOOL, Material.RED_WOOL,
            Material.SANDSTONE, Material.END_STONE, Material.OAK_LOG};

    private void fight(World w, int cx, int cz, List<Saved> log) {
        int[] box = {cx - HALF + 3, cz - HALF + 3, cx + HALF - 3, cz + HALF - 3};
        List<int[]> sc = new ArrayList<>();
        sc.add(new int[]{cx, cz, 14, 9}); // main brawl on you
        for (int i = 0; i < 7; i++)
            sc.add(new int[]{cx - 38 + r.nextInt(77), cz - 38 + r.nextInt(77), 7 + r.nextInt(6), 3 + r.nextInt(3)});
        int[] i = {0};
        start(() -> {
            int[] s = sc.get(i[0]);
            scene(w, s[0], s[1], s[2], s[3], box, log);
            return ++i[0] >= sc.size();
        }, 2);
    }

    private void scene(World w, int cx, int cz, int rad, int parts, int[] box, List<Saved> log) {
        Material m1 = PAL[r.nextInt(PAL.length)];
        Material[] pal = {m1, r.nextInt(3) == 0 ? PAL[r.nextInt(PAL.length)] : m1};
        for (int p = 0; p < parts + 2; p++) {
            int x = Math.max(box[0], Math.min(box[2], cx + (int) (r.nextGaussian() * rad / 2.5)));
            int z = Math.max(box[1], Math.min(box[3], cz + (int) (r.nextGaussian() * rad / 2.5)));
            if (!w.getBlockAt(x, top(w, x, z), z).getType().isSolid()) continue;
            if (p >= parts) { pile(w, x, z, log); continue; } // loot always lands around the scene
            switch (r.nextInt(9)) {
                case 0 -> webs(w, x, z, log);
                case 1 -> tower(w, x, z, pal, log);
                case 2 -> { int d[] = dir(); int y = top(w, x, z) + 1 + r.nextInt(3);
                    set(log, w.getBlockAt(x, y, z), pal[0]); line(w, x, z, d[0], d[1], 6 + r.nextInt(9), pal, 0.1, y, log); }
                case 3 -> bunker(w, x, z, log);
                case 4 -> wall(w, x, z, pal, log);
                case 5 -> crater(w, x, z, log);
                case 6 -> pit(w, x, z, log);
                case 7 -> pile(w, x, z, log);
                default -> { int d[] = dir(); line(w, x, z, d[0], d[1], 6 + r.nextInt(8), pal, 0.5, null, log); }
            }
        }
    }

    private int[] dir() {
        int dx = r.nextInt(3) - 1, dz = r.nextInt(3) - 1;
        if (dx == 0 && dz == 0) dx = 1;
        return new int[]{dx, dz};
    }

    /** wobbly line of blocks with gaps; fy == null means follow the ground */
    private void line(World w, int x, int z, int dx, int dz, int len, Material[] pal, double gap, Integer fy, List<Saved> log) {
        for (int t = 0; t < len; t++) {
            x += dx; z += dz;
            if (r.nextInt(100) < 15) { if (dx != 0) z += r.nextBoolean() ? 1 : -1; else x += r.nextBoolean() ? 1 : -1; }
            if (r.nextDouble() < gap) continue;
            int y = fy != null ? fy : top(w, x, z) + 1;
            set(log, w.getBlockAt(x, y, z), pal[r.nextInt(pal.length)]);
        }
    }

    private void webs(World w, int x, int z, List<Saved> log) {
        int y = top(w, x, z) + 1, rad = 2 + r.nextInt(3);
        for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) for (int dy = 0; dy <= rad; dy++) {
            double d = Math.sqrt(dx * dx + dz * dz + dy * dy);
            Block b = w.getBlockAt(x + dx, y + dy, z + dz);
            if (d <= rad && b.getType().isAir() && r.nextDouble() < 0.5 * (1 - d / (rad + 1)) + 0.1) set(log, b, Material.COBWEB);
        }
    }

    private void tower(World w, int x, int z, Material[] pal, List<Saved> log) {
        int y = top(w, x, z), h = 4 + r.nextInt(10);
        for (int k = 1; k <= h; k++) set(log, w.getBlockAt(x, y + k, z), pal[r.nextInt(pal.length)]);
        if (r.nextInt(3) == 0) { int d[] = dir(); line(w, x, z, d[0], d[1], 5 + r.nextInt(9), pal, 0.12, y + h, log); }
    }

    private void bunker(World w, int x, int z, List<Saved> log) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) continue;
            int y = top(w, x + dx, z + dz);
            for (int k = 1; k <= 1 + r.nextInt(2); k++)
                if (r.nextInt(100) < 80) set(log, w.getBlockAt(x + dx, y + k, z + dz), Material.OBSIDIAN);
        }
        int y = top(w, x, z) + 1;
        if (r.nextBoolean()) set(log, w.getBlockAt(x, y, z), Material.ENDER_CHEST); else chest(w, x, y, z, log);
    }

    private void wall(World w, int x, int z, Material[] pal, List<Saved> log) {
        boolean ew = r.nextBoolean();
        for (int i = 0; i < 3 + r.nextInt(4); i++) {
            int bx = x + (ew ? i : 0), bz = z + (ew ? 0 : i), y = top(w, bx, bz);
            for (int k = 1; k <= 2; k++) if (r.nextInt(100) < 75) set(log, w.getBlockAt(bx, y + k, bz), pal[r.nextInt(pal.length)]);
        }
        if (r.nextBoolean()) set(log, w.getBlockAt(x + (ew ? 1 : 2), top(w, x + (ew ? 1 : 2), z + (ew ? 2 : 1)) + 1, z + (ew ? 2 : 1)), Material.COBWEB);
    }

    private void crater(World w, int x, int z, List<Saved> log) {
        int y = top(w, x, z), rad = 3 + r.nextInt(3);
        for (int dx = -rad - 1; dx <= rad + 1; dx++) for (int dy = -rad - 1; dy <= rad + 1; dy++) for (int dz = -rad - 1; dz <= rad + 1; dz++) {
            double lim = rad + r.nextDouble() * 1.2 - 0.6;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= lim) set(log, w.getBlockAt(x + dx, y + dy, z + dz), Material.AIR);
        }
        for (int dx = -rad - 3; dx <= rad + 3; dx++) for (int dz = -rad - 3; dz <= rad + 3; dz++) {
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d <= rad || d >= rad + 3 || r.nextInt(100) >= 40) continue;
            Block b = w.getBlockAt(x + dx, top(w, x + dx, z + dz), z + dz);
            if (b.getType().isSolid()) set(log, b, r.nextBoolean() ? Material.COARSE_DIRT : r.nextInt(3) == 0 ? Material.BLACKSTONE : Material.NETHERRACK);
        }
        if (r.nextInt(3) == 0) {
            set(log, w.getBlockAt(x, y - rad + 1, z), Material.LAVA);
            set(log, w.getBlockAt(x + 1, y - rad + 1, z), Material.OBSIDIAN);
        }
    }

    private void pit(World w, int x, int z, List<Saved> log) {
        int y = top(w, x, z);
        for (int k = 0; k <= 3; k++) set(log, w.getBlockAt(x, y - k, z), Material.AIR);
        if (r.nextBoolean()) set(log, w.getBlockAt(x, y - 3, z), Material.COBWEB);
    }

    private void pile(World w, int x, int z, List<Saved> log) {
        int y = top(w, x, z) + 1;
        for (int i = 0, n = 2 + r.nextInt(5); i < n; i++)
            w.dropItem(new Location(w, x + 0.5 + r.nextGaussian(), y + 1, z + 0.5 + r.nextGaussian()), randItem());
        if (r.nextInt(3) == 0) chest(w, x + 1, top(w, x + 1, z) + 1, z, log);
    }

    private void chest(World w, int x, int y, int z, List<Saved> log) {
        Block b = w.getBlockAt(x, y, z);
        set(log, b, Material.CHEST);
        if (b.getState() instanceof Container c)
            for (int i = 0, n = 3 + r.nextInt(6); i < n; i++) c.getInventory().setItem(r.nextInt(27), randItem());
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

    // ---------- custom items ----------
    private ItemStack make(String id) {
        boolean horn = id.endsWith("horn");
        ItemStack it = new ItemStack(horn ? Material.GOAT_HORN : Material.FISHING_ROD);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(switch (id) {
            case "pot_horn" -> "§d§lPot Horn";
            case "pot_rod" -> "§d§lPot Rod";
            case "sonic_horn" -> "§3§lSonic Horn";
            default -> "§3§lSonic Rod";
        });
        m.setLore(List.of(id.startsWith("pot") ? "§7Pots rain on the target after 3s" : "§73 sonic booms from above",
                horn ? "§7Silent. No cooldown." : "§7Breaks when the bobber lands."));
        Enchantment glint = Enchantment.getByKey(NamespacedKey.minecraft("lure"));
        if (glint != null) { m.addEnchant(glint, 1, true); m.addItemFlags(ItemFlag.HIDE_ENCHANTS); }
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        if (m instanceof MusicInstrumentMeta mi)
            mi.setInstrument(id.equals("pot_horn") ? MusicInstrument.YEARN : HORNS[r.nextInt(HORNS.length)]);
        it.setItemMeta(m);
        return it;
    }

    private String id(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private String held(Player p, String suffix) {
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : new ItemStack[]{inv.getItemInMainHand(), inv.getItemInOffHand()}) {
            String id = id(it);
            if (id != null && id.endsWith(suffix)) return id;
        }
        return null;
    }

    private void later(int ticks, Runnable run) {
        Bukkit.getScheduler().runTaskLater(this, run, ticks);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String id = id(e.getItem());
        if (id == null || !id.endsWith("horn")) return;
        e.setUseItemInHand(Event.Result.DENY); // vanilla horn never plays, no cooldown is applied
        Player p = e.getPlayer();
        if (e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable() && !p.isSneaking()) return;
        if (id.equals("pot_horn")) {
            p.sendMessage("§dPots in 3s...");
            later(60, () -> { if (p.isOnline() && !p.isDead()) pots(p.getEyeLocation().add(0, 0.4, 0)); });
        } else {
            Location eye = p.getEyeLocation();
            RayTraceResult rt = p.getWorld().rayTraceEntities(eye, eye.getDirection(), 40, 0.4, en -> en instanceof LivingEntity && en != p);
            if (rt != null && rt.getHitEntity() != null) sonic(p, rt.getHitEntity(), rt.getHitEntity().getLocation());
            else {
                Vector d = eye.getDirection().setY(0);
                if (d.lengthSquared() < 1e-4) d = new Vector(0, 0, 1);
                sonic(p, null, p.getLocation().add(d.normalize().multiply(5)));
            }
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        String id = held(p, "rod");
        if (id == null) return;
        if (e.getState() == PlayerFishEvent.State.FISHING) {
            if (busy.contains(p.getUniqueId())) { e.setCancelled(true); return; }
            rod(p, id, e.getHook());
        } else if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) e.setCancelled(true);
    }

    /** follows the bobber; once it lands the rod breaks and the effect fires at that spot */
    private void rod(Player p, String id, FishHook hook) {
        busy.add(p.getUniqueId());
        Location[] last = {hook.getLocation()};
        int[] t = {0};
        new BukkitRunnable() {
            public void run() {
                t[0]++;
                boolean alive = hook.isValid();
                if (alive) last[0] = hook.getLocation();
                boolean landed = !alive || t[0] > 100 || hook.getHookedEntity() != null || hook.isInWater()
                        || hook.isOnGround() || (t[0] > 6 && hook.getVelocity().lengthSquared() < 0.002);
                if (!landed) return;
                cancel();
                Entity hooked = alive ? hook.getHookedEntity() : null;
                consume(p, id);
                hook.remove();
                busy.remove(p.getUniqueId());
                Location at = last[0];
                if (id.equals("pot_rod")) later(60, () -> pots(at.clone().add(0, 1, 0)));
                else sonic(p, hooked, at);
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void consume(Player p, String id) {
        PlayerInventory inv = p.getInventory();
        List<Integer> slots = new ArrayList<>(List.of(inv.getHeldItemSlot(), 40));
        for (int i = 0; i < 36; i++) slots.add(i);
        for (int i : slots) {
            ItemStack it = inv.getItem(i);
            if (id.equals(id(it))) {
                if (it.getAmount() > 1) it.setAmount(it.getAmount() - 1); else inv.setItem(i, null);
                return;
            }
        }
    }

    private PotionEffect pe(String k, int ticks, int amp) {
        return new PotionEffect(PotionEffectType.getByKey(NamespacedKey.minecraft(k)), ticks, amp);
    }

    /** instant health II, regeneration I (1:30), strength II (1:30), speed I (8:00) */
    private void pots(Location at) {
        PotionEffect[] fx = {pe("instant_health", 1, 1), pe("regeneration", 1800, 0), pe("strength", 1800, 1), pe("speed", 9600, 0)};
        for (PotionEffect e : fx)
            at.getWorld().spawn(at, ThrownPotion.class, tp -> {
                ItemStack st = new ItemStack(Material.SPLASH_POTION);
                PotionMeta pm = (PotionMeta) st.getItemMeta();
                pm.addCustomEffect(e, true);
                pm.setColor(e.getType().getColor());
                st.setItemMeta(pm);
                tp.setItem(st);
                tp.setVelocity(new Vector(0, -0.4, 0));
            });
    }

    /** 3 warden sonic booms coming down from above; follows the target if there is one */
    private void sonic(Player p, Entity target, Location base) {
        World w = base.getWorld();
        for (int n = 0; n < 3; n++)
            for (int i = 0; i <= 5; i++) {
                int ii = i;
                later(n * 12 + i, () -> {
                    Location at = target != null && target.isValid() ? target.getLocation() : base;
                    w.spawnParticle(Particle.SONIC_BOOM, at.clone().add(0, 8 - 1.4 * ii, 0), 1);
                    if (ii < 5) return;
                    w.playSound(at, "entity.warden.sonic_boom", SoundCategory.HOSTILE, 2f, 1f);
                    for (Entity en : w.getNearbyEntities(at.clone().add(0, 1, 0), 2, 2, 2))
                        if (en instanceof LivingEntity le && en != p && !(en instanceof ArmorStand)) {
                            le.setNoDamageTicks(0);
                            le.damage(SONIC_DAMAGE, p);
                        }
                });
            }
    }
}
