package hu.bNoli1.lavacrates;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LavaCrates extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<String, Location> crateLocations = new HashMap<>();
    private final Map<String, List<CrateItem>> crateRewards = new HashMap<>();
    private final Map<String, ItemStack> crateKeys = new HashMap<>();
    private final Map<UUID, Map<String, List<ArmorStand>>> personalHolograms = new HashMap<>();
    private final Map<UUID, List<String>> openingLogs = new HashMap<>();
    private final Map<UUID, String> editingCrate = new HashMap<>();

    private File cratesFile, dataFile;
    private FileConfiguration cratesConfig, dataConfig;
    private NamespacedKey chanceKey;
    private final Random random = new Random();
    private final Pattern hexPattern = Pattern.compile("&#[a-fA-F0-9]{6}");

    private static class CrateItem {
        private final ItemStack item;
        private int chance;
        public CrateItem(ItemStack item, int chance) { this.item = item; this.chance = chance; }
        public ItemStack getItem() { return item; }
        public int getChance() { return chance; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadFiles();
        this.chanceKey = new NamespacedKey(this, "lavachance");
        loadCrates();
        getCommand("lavacrates").setExecutor(this);
        getCommand("lavacrates").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        startHologramTask();
    }

    @Override
    public void onDisable() {
        personalHolograms.values().forEach(m -> m.values().forEach(l -> l.forEach(Entity::remove)));
    }

    private void loadFiles() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        cratesFile = new File(getDataFolder(), "crates.yml");
        if (!cratesFile.exists()) try { cratesFile.createNewFile(); } catch (IOException ignored) {}
        cratesConfig = YamlConfiguration.loadConfiguration(cratesFile);
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) try { dataFile.createNewFile(); } catch (IOException ignored) {}
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void saveData() { try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); } }
    public void saveCratesFile() { try { cratesConfig.save(cratesFile); } catch (IOException e) { e.printStackTrace(); } }

    public String colorize(String msg) {
        if (msg == null) return "";
        Matcher m = hexPattern.matcher(msg);
        while (m.find()) {
            String color = msg.substring(m.start(), m.end());
            msg = msg.replace(color, ChatColor.of(color.substring(1)).toString());
            m = hexPattern.matcher(msg);
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    private void loadCrates() {
        crateLocations.clear(); crateRewards.clear(); crateKeys.clear();
        if (cratesConfig.getConfigurationSection("crates") == null) return;
        for (String key : cratesConfig.getConfigurationSection("crates").getKeys(false)) {
            crateLocations.put(key, cratesConfig.getLocation("crates." + key + ".location"));
            crateKeys.put(key, cratesConfig.getItemStack("crates." + key + ".key_item"));
            List<CrateItem> items = new ArrayList<>();
            List<?> list = cratesConfig.getList("crates." + key + ".items");
            if (list != null) {
                for (Object obj : list) {
                    if (obj instanceof ItemStack is) {
                        int chance = is.getItemMeta().getPersistentDataContainer().getOrDefault(chanceKey, PersistentDataType.INTEGER, 100);
                        items.add(new CrateItem(is, chance));
                    }
                }
            }
            crateRewards.put(key, items);
        }
    }

    private void startHologramTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                double dist = getConfig().getDouble("hologram-view-distance", 15.0);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    for (String name : crateLocations.keySet()) {
                        Location loc = crateLocations.get(name);
                        if (loc != null && loc.getWorld().equals(p.getWorld()) && p.getLocation().distance(loc) <= dist) {
                            updatePersonalHologram(p, name);
                        } else {
                            removePersonalHologram(p, name);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void updatePersonalHologram(Player p, String name) {
        Location baseLoc = crateLocations.get(name);
        if (baseLoc == null) return;
        Location loc = baseLoc.clone().add(0.5, 1.2, 0.5);
        List<String> lines = cratesConfig.getStringList("crates." + name + ".hologram");
        if (lines.isEmpty()) return;

        int keys = dataConfig.getInt("players." + p.getUniqueId() + "." + name, 0);
        int rem = getConfig().getInt("party." + name + ".threshold", 100) - getConfig().getInt("party." + name + ".current", 0);

        Map<String, List<ArmorStand>> pMap = personalHolograms.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());

        if (pMap.containsKey(name)) {
            List<ArmorStand> stands = pMap.get(name);
            for (int i = 0; i < lines.size(); i++) {
                if (i < stands.size()) {
                    String text = lines.get((lines.size() - 1) - i)
                            .replace("%keys%", (keys > 0 ? "&a" : "&c") + keys)
                            .replace("%party%", String.valueOf(rem));
                    stands.get(i).setCustomName(colorize(text));
                }
            }
        } else {
            List<ArmorStand> stands = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                ArmorStand as = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(0, i * 0.28, 0), EntityType.ARMOR_STAND);
                as.setVisible(false); as.setGravity(false); as.setMarker(true); as.setCustomNameVisible(true); as.setPersistent(false);
                stands.add(as);
            }
            pMap.put(name, stands);
            for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(p)) stands.forEach(as -> other.hideEntity(this, as));
        }
    }

    private void removePersonalHologram(Player p, String name) {
        if (personalHolograms.containsKey(p.getUniqueId())) {
            List<ArmorStand> stands = personalHolograms.get(p.getUniqueId()).remove(name);
            if (stands != null) stands.forEach(Entity::remove);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lavacrates.admin") && (args.length == 0 || !args[0].equalsIgnoreCase("withdraw"))) {
            sender.sendMessage(colorize("&#FF5555Nincs jogod!")); return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { sendHelp(sender); return true; }
        
        String act = args[0].toLowerCase();
        switch (act) {
            case "create", "move" -> {
                if (!(sender instanceof Player p) || args.length < 2) return false;
                crateLocations.put(args[1].toLowerCase(), p.getTargetBlockExact(5).getLocation());
                saveCrate(args[1].toLowerCase()); p.sendMessage(colorize("&#00FF7FSikeres!"));
            }
            case "delete" -> {
                if (args.length < 2) return false;
                String n = args[1].toLowerCase(); crateLocations.remove(n); cratesConfig.set("crates." + n, null);
                saveCratesFile(); sender.sendMessage(colorize("&#FB5454Törölve!"));
            }
            case "add" -> {
                if (!(sender instanceof Player p) || args.length < 3) return false;
                int c = Integer.parseInt(args[2]); ItemStack h = p.getInventory().getItemInMainHand().clone();
                ItemMeta m = h.getItemMeta(); m.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, c); h.setItemMeta(m);
                crateRewards.computeIfAbsent(args[1].toLowerCase(), k -> new ArrayList<>()).add(new CrateItem(h, c));
                saveCrate(args[1].toLowerCase()); p.sendMessage(colorize("&#00FF7FAddolva!"));
            }
            case "edit" -> { if (sender instanceof Player p && args.length > 1) openEditor(p, args[1].toLowerCase()); }
            case "setkey" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                ItemStack h = p.getInventory().getItemInMainHand().clone(); h.setAmount(1);
                crateKeys.put(args[1].toLowerCase(), h); saveCrate(args[1].toLowerCase());
                p.sendMessage(colorize("&#00FF7FKulcs beállítva!"));
            }
            case "givekey" -> {
                if (args.length < 4) return false;
                Player t = Bukkit.getPlayer(args[1]); int am = Integer.parseInt(args[3]); String cn = args[2].toLowerCase();
                if (t == null) return true;
                if (args.length > 4 && args[4].equalsIgnoreCase("v")) {
                    dataConfig.set("players." + t.getUniqueId() + "." + cn, dataConfig.getInt("players." + t.getUniqueId() + "." + cn, 0) + am); saveData();
                } else if(crateKeys.containsKey(cn)) { ItemStack k = crateKeys.get(cn).clone(); k.setAmount(am); t.getInventory().addItem(k); }
            }
            case "keyall" -> {
                if (args.length < 3) return false;
                String cn = args[1].toLowerCase(); int am = Integer.parseInt(args[2]);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (args.length > 3 && args[3].equalsIgnoreCase("v")) {
                        dataConfig.set("players." + p.getUniqueId() + "." + cn, dataConfig.getInt("players." + p.getUniqueId() + "." + cn, 0) + am);
                    } else if(crateKeys.containsKey(cn)) p.getInventory().addItem(crateKeys.get(cn).clone());
                }
                saveData(); Bukkit.broadcastMessage(colorize("&#00FF7FMindenki kapott kulcsot!"));
            }
            case "withdraw" -> {
                if (!(sender instanceof Player p) || args.length < 3) return true;
                String cn = args[1].toLowerCase(); int am = Integer.parseInt(args[2]);
                int cur = dataConfig.getInt("players." + p.getUniqueId() + "." + cn, 0);
                if (cur >= am && crateKeys.containsKey(cn)) {
                    dataConfig.set("players." + p.getUniqueId() + "." + cn, cur - am); saveData();
                    ItemStack k = crateKeys.get(cn).clone(); k.setAmount(am); p.getInventory().addItem(k);
                }
            }
            case "holo" -> {
                if (args.length < 3) return false;
                cratesConfig.set("crates." + args[1].toLowerCase() + ".hologram", Arrays.asList(String.join(" ", Arrays.copyOfRange(args, 2, args.length)).split("\\|")));
                saveCratesFile(); sender.sendMessage(colorize("&#00FF7FHologram kész!"));
            }
            case "logs" -> { if (sender instanceof Player p && args.length > 1) openLogs(p, Bukkit.getPlayer(args[1])); }
            case "list" -> {
                sender.sendMessage(colorize("&#FF8C00Ládák:"));
                crateLocations.keySet().forEach(n -> sender.sendMessage(colorize(" &8• &f" + n)));
            }
            case "clean" -> { personalHolograms.values().forEach(m -> m.values().forEach(l -> l.forEach(Entity::remove))); personalHolograms.clear(); }
            case "reload" -> { reloadConfig(); loadFiles(); loadCrates(); sender.sendMessage(colorize("&#00FF7FÚjratöltve!")); }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(colorize(" "));
        s.sendMessage(colorize("&#FF8C00&l[ LavaCrates Segítség ]"));
        s.sendMessage(colorize("&8• &f/lc create/move/delete <név> &7- Láda kezelés"));
        s.sendMessage(colorize("&8• &f/lc add <név> <esély> &7- Tárgy hozzáadása"));
        s.sendMessage(colorize("&8• &f/lc edit <név> &7- GUI szerkesztő (törlés)"));
        s.sendMessage(colorize("&8• &f/lc setkey <név> &7- Kulcs tárgy beállítása"));
        s.sendMessage(colorize("&8• &f/lc givekey/keyall <player> <láda> <db> [v] &7- Kulcsadás"));
        s.sendMessage(colorize("&8• &f/lc withdraw <láda> <db> &7- Virtuális -> Fizikai"));
        s.sendMessage(colorize("&8• &f/lc holo <név> <szöveg> &7- Hologram (Sorok: | )"));
        s.sendMessage(colorize("&8• &f/lc logs <player> &7- Nyitási előzmények"));
        s.sendMessage(colorize("&8• &f/lc list/clean/reload &7- Rendszer parancsok"));
        s.sendMessage(colorize(" "));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        for (Map.Entry<String, Location> entry : crateLocations.entrySet()) {
            if (entry.getValue().equals(e.getClickedBlock().getLocation())) {
                e.setCancelled(true);
                if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    if (e.getPlayer().isSneaking()) startBulkOpening(e.getPlayer(), entry.getKey());
                    else startOpening(e.getPlayer(), entry.getKey());
                } else if (e.getAction() == Action.LEFT_CLICK_BLOCK) openPreview(e.getPlayer(), entry.getKey());
            }
        }
    }

    private void startBulkOpening(Player p, String name) {
        int virt = dataConfig.getInt("players." + p.getUniqueId() + "." + name, 0);
        if (virt <= 0) { p.sendMessage(colorize("&#FF5555Nincs virtuális kulcsod!")); return; }
        int toOpen = Math.min(virt, 10);
        dataConfig.set("players." + p.getUniqueId() + "." + name, virt - toOpen); saveData();
        for (int i = 0; i < toOpen; i++) {
            giveRandomReward(p, name);
            handleParty(name);
        }
        p.sendMessage(colorize("&#FF8C00LavaCrates &8» &f" + toOpen + "x nyitás sikeres!"));
    }

    private void startOpening(Player p, String name) {
        int virt = dataConfig.getInt("players." + p.getUniqueId() + "." + name, 0);
        if (virt <= 0) { p.sendMessage(colorize("&#FF5555Nincs virtuális kulcsod!")); return; }
        dataConfig.set("players." + p.getUniqueId() + "." + name, virt - 1); saveData();
        Inventory inv = Bukkit.createInventory(null, 27, colorize("&6Nyitás...")); p.openInventory(inv);
        new BukkitRunnable() {
            int t = 0;
            public void run() {
                if (t >= 20) {
                    giveRandomReward(p, name); handleParty(name); p.closeInventory();
                    this.cancel(); return;
                }
                List<CrateItem> rewards = crateRewards.get(name);
                if (rewards != null && !rewards.isEmpty()) {
                    inv.setItem(13, rewards.get(random.nextInt(rewards.size())).getItem());
                }
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 1, 1); t++;
            }
        }.runTaskTimer(this, 0, 2);
    }

    private void giveRandomReward(Player p, String name) {
        List<CrateItem> pool = crateRewards.get(name);
        if (pool == null || pool.isEmpty()) return;
        int sum = pool.stream().mapToInt(CrateItem::getChance).sum(), r = random.nextInt(sum > 0 ? sum : 1), c = 0;
        for (CrateItem ci : pool) {
            c += ci.getChance();
            if (r < c) {
                p.getInventory().addItem(ci.getItem().clone());
                openingLogs.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(name + " | " + ci.getItem().getType());
                break;
            }
        }
    }

    private void handleParty(String n) {
        String path = "party." + n;
        int current = getConfig().getInt(path + ".current", 0) + 1;
        int threshold = getConfig().getInt(path + ".threshold", 100);
        if (current >= threshold) {
            current = 0;
            getConfig().getStringList(path + ".commands").forEach(cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
        getConfig().set(path + ".current", current); saveConfig();
    }

    private void openEditor(Player p, String name) {
        editingCrate.put(p.getUniqueId(), name);
        Inventory inv = Bukkit.createInventory(null, 54, colorize("&cEditor: " + name));
        if (crateRewards.get(name) != null) crateRewards.get(name).forEach(ci -> inv.addItem(ci.getItem()));
        p.openInventory(inv);
    }

    private void openLogs(Player admin, Player target) {
        if (target == null) return;
        Inventory inv = Bukkit.createInventory(null, 54, "Napló: " + target.getName());
        openingLogs.getOrDefault(target.getUniqueId(), new ArrayList<>()).forEach(s -> {
            ItemStack i = new ItemStack(Material.PAPER); ItemMeta m = i.getItemMeta();
            m.setDisplayName(colorize("&e" + s)); i.setItemMeta(m); inv.addItem(i);
        });
        admin.openInventory(inv);
    }

    private void openPreview(Player p, String name) {
        Inventory inv = Bukkit.createInventory(null, 54, colorize("&9Előnézet: " + name));
        if (crateRewards.get(name) != null) crateRewards.get(name).forEach(ci -> inv.addItem(ci.getItem()));
        p.openInventory(inv);
    }

    private void saveCrate(String n) {
        cratesConfig.set("crates." + n + ".location", crateLocations.get(n));
        List<ItemStack> raw = new ArrayList<>();
        if (crateRewards.get(n) != null) crateRewards.get(n).forEach(ci -> raw.add(ci.getItem()));
        cratesConfig.set("crates." + n + ".items", raw);
        cratesConfig.set("crates." + n + ".key_item", crateKeys.get(n));
        saveCratesFile();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();
        if (title.contains("Nyitás") || title.contains("Előnézet") || title.contains("Napló")) e.setCancelled(true);
        if (title.contains("Editor")) {
            e.setCancelled(true);
            Player p = (Player) e.getWhoClicked();
            if (e.isRightClick() && e.getCurrentItem() != null) {
                String crate = editingCrate.get(p.getUniqueId());
                crateRewards.get(crate).removeIf(ci -> ci.getItem().isSimilar(e.getCurrentItem()));
                saveCrate(crate); openEditor(p, crate);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("create", "delete", "add", "edit", "setkey", "givekey", "keyall", "withdraw", "holo", "logs", "reload", "list", "move", "clean");
        return null;
    }
}
