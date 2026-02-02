package hu.bNoli1.lavacrates;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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
    private final Map<UUID, Map<String, List<ArmorStand>>> playerHolograms = new HashMap<>();
    private final Map<UUID, List<String>> openingLogs = new HashMap<>();
    private final Map<UUID, String> editingCrate = new HashMap<>();
    private final Map<UUID, ItemStack> pendingChance = new HashMap<>();

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
        public void setChance(int chance) { this.chance = chance; }
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
        playerHolograms.values().forEach(map -> map.values().forEach(list -> list.forEach(ArmorStand::remove)));
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
            Location loc = cratesConfig.getLocation("crates." + key + ".location");
            if (loc != null) crateLocations.put(key, loc);
            ItemStack keyItem = cratesConfig.getItemStack("crates." + key + ".key_item");
            if (keyItem != null) crateKeys.put(key, keyItem);
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lavacrates.admin") && (args.length == 0 || !args[0].equalsIgnoreCase("withdraw"))) {
            sender.sendMessage(colorize("&#FF5555Nincs jogod ehhez!"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { sendHelp(sender); return true; }
        
        String action = args[0].toLowerCase();
        switch (action) {
            case "list" -> {
                sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FFFFFFLádák:"));
                crateLocations.keySet().forEach(name -> sender.sendMessage(colorize(" &8• &#FFD700" + name)));
            }
            case "create" -> {
                if (!(sender instanceof Player p) || args.length < 2) return false;
                crateLocations.put(args[1].toLowerCase(), p.getTargetBlockExact(5).getLocation());
                saveCrate(args[1].toLowerCase());
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FLáda létrehozva a blokkon!"));
            }
            case "delete" -> {
                if (args.length < 2) return false;
                String n = args[1].toLowerCase();
                crateLocations.remove(n); cratesConfig.set("crates." + n, null);
                saveCratesFile(); sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FB5454Láda törölve!"));
            }
            case "move" -> {
                if (!(sender instanceof Player p) || args.length < 2) return false;
                crateLocations.put(args[1].toLowerCase(), p.getTargetBlockExact(5).getLocation());
                saveCrate(args[1].toLowerCase()); p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FLáda áthelyezve!"));
            }
            case "add" -> {
                if (!(sender instanceof Player p) || args.length < 3) return false;
                int c = Integer.parseInt(args[2]); ItemStack h = p.getInventory().getItemInMainHand().clone();
                if (h.getType().isAir()) return true;
                ItemMeta m = h.getItemMeta(); m.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, c); h.setItemMeta(m);
                crateRewards.computeIfAbsent(args[1].toLowerCase(), k -> new ArrayList<>()).add(new CrateItem(h, c));
                saveCrate(args[1].toLowerCase()); p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FTárgy hozzáadva a ládához!"));
            }
            case "setkey" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                ItemStack h = p.getInventory().getItemInMainHand().clone(); h.setAmount(1);
                crateKeys.put(args[1].toLowerCase(), h); saveCrate(args[1].toLowerCase());
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FMainHand tárgy beállítva kulcsként!"));
            }
            case "edit" -> { if (sender instanceof Player p && args.length > 1) openEditor(p, args[1].toLowerCase()); }
            case "clean" -> {
                if (!(sender instanceof Player p)) return true;
                int cnt = 0; for (Entity e : p.getNearbyEntities(5, 5, 5)) if (e instanceof ArmorStand as && as.isMarker()) { e.remove(); cnt++; }
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7F" + cnt + " hologram eltávolítva a közeledből!"));
            }
            case "holo" -> {
                if (args.length < 3) return false;
                cratesConfig.set("crates." + args[1].toLowerCase() + ".hologram", Arrays.asList(String.join(" ", Arrays.copyOfRange(args, 2, args.length)).split("\\|")));
                saveCratesFile(); sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FHologram elmentve!"));
            }
            case "givekey" -> {
                if (args.length < 4) return false;
                Player t = Bukkit.getPlayer(args[1]); int am = Integer.parseInt(args[3]); String cn = args[2].toLowerCase();
                if (t == null || !crateKeys.containsKey(cn)) return true;
                if (args.length > 4 && args[4].equalsIgnoreCase("v")) {
                    dataConfig.set("players." + t.getUniqueId() + "." + cn, dataConfig.getInt("players." + t.getUniqueId() + "." + cn, 0) + am); saveData();
                } else { ItemStack k = crateKeys.get(cn).clone(); k.setAmount(am); t.getInventory().addItem(k); }
                sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FKulcs sikeresen kiosztva!"));
            }
            case "keyall" -> {
                if (args.length < 3) return false;
                String cn = args[1].toLowerCase(); int am = Integer.parseInt(args[2]);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (args.length > 3 && args[3].equalsIgnoreCase("v")) {
                        dataConfig.set("players." + p.getUniqueId() + "." + cn, dataConfig.getInt("players." + p.getUniqueId() + "." + cn, 0) + am);
                    } else { ItemStack k = crateKeys.get(cn).clone(); k.setAmount(am); p.getInventory().addItem(k); }
                }
                saveData(); Bukkit.broadcastMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FMindenki kapott &#FFFFFF" + am + "x &#00FF7Fkulcsot!"));
            }
            case "withdraw" -> {
                if (!(sender instanceof Player p) || args.length < 3) return true;
                String cn = args[1].toLowerCase(); int am = Integer.parseInt(args[2]);
                int cur = dataConfig.getInt("players." + p.getUniqueId() + "." + cn, 0);
                if (cur >= am) {
                    dataConfig.set("players." + p.getUniqueId() + "." + cn, cur - am); saveData();
                    ItemStack k = crateKeys.get(cn).clone(); k.setAmount(am); p.getInventory().addItem(k);
                    p.sendMessage(colorize("&#00FF7FSikeresen kivettél " + am + " kulcsot!"));
                } else { p.sendMessage(colorize("&#FF5555Nincs elég virtuális kulcsod!")); }
            }
            case "logs" -> { if (sender instanceof Player p && args.length > 1) openLogs(p, Bukkit.getPlayer(args[1])); }
            case "reload" -> { reloadConfig(); loadFiles(); loadCrates(); sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FKonfiguráció újratöltve!")); }
        }
        return true;
    }

    private void startHologramTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                double dist = getConfig().getDouble("hologram-view-distance", 50.0);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    for (String name : crateLocations.keySet()) {
                        Location loc = crateLocations.get(name);
                        if (loc != null && loc.getWorld().equals(p.getWorld()) && p.getLocation().distance(loc) <= dist) {
                            updateHologramForPlayer(p, name);
                        } else { removeHologramForPlayer(p, name); }
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void updateHologramForPlayer(Player p, String name) {
        removeHologramForPlayer(p, name);
        Location loc = crateLocations.get(name);
        List<String> lines = cratesConfig.getStringList("crates." + name + ".hologram");
        if (lines.isEmpty()) return;
        
        int keys = dataConfig.getInt("players." + p.getUniqueId() + "." + name, 0);
        int rem = getConfig().getInt("party." + name + ".threshold", 100) - getConfig().getInt("party." + name + ".current", 0);
        
        List<ArmorStand> stands = new ArrayList<>();
        Location spawn = loc.clone().add(0.5, 1.2, 0.5);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String text = lines.get(i).replace("%keys%", (keys > 0 ? "&a" : "&c") + keys).replace("%party%", String.valueOf(rem));
            ArmorStand as = (ArmorStand) spawn.getWorld().spawnEntity(spawn.clone().add(0, (lines.size()-1-i)*0.28, 0), EntityType.ARMOR_STAND);
            as.setVisible(false); as.setGravity(false); as.setMarker(true); as.setCustomName(colorize(text)); as.setCustomNameVisible(true);
            stands.add(as);
        }
        playerHolograms.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(name, stands);
    }

    private void removeHologramForPlayer(Player p, String name) {
        if (playerHolograms.containsKey(p.getUniqueId())) {
            Map<String, List<ArmorStand>> map = playerHolograms.get(p.getUniqueId());
            List<ArmorStand> list = map.remove(name);
            if (list != null) list.forEach(ArmorStand::remove);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        for (Map.Entry<String, Location> entry : crateLocations.entrySet()) {
            if (entry.getValue().equals(e.getClickedBlock().getLocation())) {
                e.setCancelled(true);
                Player p = e.getPlayer();
                String crateName = entry.getKey();
                if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    if (p.isSneaking()) startBulkOpening(p, crateName);
                    else startOpening(p, crateName);
                } else if (e.getAction() == Action.LEFT_CLICK_BLOCK) openPreview(p, crateName);
                break;
            }
        }
    }

    private void startBulkOpening(Player p, String name) {
        int virt = dataConfig.getInt("players." + p.getUniqueId() + "." + name, 0);
        int phys = 0; ItemStack key = crateKeys.get(name);
        if (key != null) {
            for (ItemStack is : p.getInventory().getContents()) if (is != null && is.isSimilar(key)) phys += is.getAmount();
        }
        int total = virt + phys; if (total <= 0) { p.sendMessage(colorize("&#FF5555Nincs kulcsod!")); return; }
        int toOpen = Math.min(total, 10);
        
        int remain = toOpen;
        if (key != null) {
            for (ItemStack is : p.getInventory().getContents()) {
                if (is != null && is.isSimilar(key)) {
                    int take = Math.min(is.getAmount(), remain);
                    is.setAmount(is.getAmount() - take); remain -= take;
                    if (remain <= 0) break;
                }
            }
        }
        if (remain > 0) { dataConfig.set("players." + p.getUniqueId() + "." + name, virt - remain); saveData(); }

        p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FFFFFFTömeges nyitás (&#FFD700" + toOpen + "x&#FFFFFF)"));
        List<CrateItem> pool = crateRewards.get(name);
        if (pool == null || pool.isEmpty()) return;
        int sum = pool.stream().mapToInt(CrateItem::getChance).sum();

        for (int i = 0; i < toOpen; i++) {
            int r = random.nextInt(sum > 0 ? sum : 1), c = 0;
            for (CrateItem ci : pool) { 
                c += ci.getChance(); 
                if (r < c) { 
                    p.getInventory().addItem(ci.getItem().clone()); 
                    openingLogs.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add("Bulk [" + name + "]: " + ci.getItem().getType());
                    break; 
                } 
            }
            handleParty(name);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
    }

    private void startOpening(Player p, String name) {
        int virt = dataConfig.getInt("players." + p.getUniqueId() + "." + name, 0);
        ItemStack key = crateKeys.get(name);
        boolean hasPhys = key != null && p.getInventory().getItemInMainHand().isSimilar(key);

        if (!hasPhys && virt <= 0) { p.sendMessage(colorize("&#FF5555Nincs kulcsod!")); return; }
        if (hasPhys) p.getInventory().getItemInMainHand().setAmount(p.getInventory().getItemInMainHand().getAmount() - 1);
        else { dataConfig.set("players." + p.getUniqueId() + "." + name, virt - 1); saveData(); }

        List<CrateItem> pool = crateRewards.get(name);
        Inventory inv = Bukkit.createInventory(null, 27, colorize("Nyitás: " + name));
        p.openInventory(inv);

        new BukkitRunnable() {
            int t = 0;
            public void run() {
                if (t >= 20) {
                    int sum = pool.stream().mapToInt(CrateItem::getChance).sum(), r = random.nextInt(sum > 0 ? sum : 1), c = 0;
                    ItemStack win = pool.get(0).getItem();
                    for (CrateItem ci : pool) { c += ci.getChance(); if (r < c) { win = ci.getItem(); break; } }
                    p.getInventory().addItem(win.clone()); p.closeInventory();
                    p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FNyertél egy tárgyat!"));
                    openingLogs.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add("Sima [" + name + "]: " + win.getType());
                    handleParty(name); this.cancel(); return;
                }
                inv.setItem(13, pool.get(random.nextInt(pool.size())).getItem());
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 1f, 1f);
                t++;
            }
        }.runTaskTimer(this, 0, 3);
    }

    private void openPreview(Player p, String name) {
        Inventory inv = Bukkit.createInventory(null, 54, colorize("Előnézet: " + name));
        List<CrateItem> items = crateRewards.get(name);
        if (items != null) {
            for (CrateItem ci : items) {
                ItemStack is = ci.getItem().clone();
                ItemMeta m = is.getItemMeta();
                List<String> lore = m.hasLore() ? m.getLore() : new ArrayList<>();
                lore.add(colorize("&8&m----------------"));
                lore.add(colorize("&7Esély: &e" + ci.getChance() + "%"));
                m.setLore(lore); is.setItemMeta(m); inv.addItem(is);
            }
        }
        p.openInventory(inv);
    }

    private void openEditor(Player p, String name) {
        editingCrate.put(p.getUniqueId(), name);
        Inventory inv = Bukkit.createInventory(null, 54, colorize("Szerkesztés: " + name));
        List<CrateItem> items = crateRewards.get(name);
        if (items != null) {
            for (CrateItem ci : items) {
                ItemStack is = ci.getItem().clone();
                ItemMeta m = is.getItemMeta();
                m.setLore(Arrays.asList(colorize("&7Esély: &e" + ci.getChance() + "%"), colorize("&7Shift+Bal: Esély módosítás"), colorize("&7Jobb klikk: Törlés")));
                is.setItemMeta(m); inv.addItem(is);
            }
        }
        p.openInventory(inv);
    }

    private void openLogs(Player admin, Player target) {
        if (target == null) return;
        Inventory inv = Bukkit.createInventory(null, 54, colorize("Napló: " + target.getName()));
        List<String> logs = openingLogs.getOrDefault(target.getUniqueId(), new ArrayList<>());
        for (String log : logs) {
            ItemStack paper = new ItemStack(Material.PAPER);
            ItemMeta m = paper.getItemMeta(); m.setDisplayName(colorize("&e" + log)); paper.setItemMeta(m);
            inv.addItem(paper);
        }
        admin.openInventory(inv);
    }

    private void saveCrate(String n) {
        cratesConfig.set("crates." + n + ".location", crateLocations.get(n));
        List<ItemStack> raw = new ArrayList<>();
        if (crateRewards.get(n) != null) for (CrateItem ci : crateRewards.get(n)) raw.add(ci.getItem());
        cratesConfig.set("crates." + n + ".items", raw);
        cratesConfig.set("crates." + n + ".key_item", crateKeys.get(n));
        saveCratesFile();
    }

    private void handleParty(String n) {
        String path = "party." + n;
        if (!getConfig().contains(path)) return;
        int current = getConfig().getInt(path + ".current", 0) + 1;
        int threshold = getConfig().getInt(path + ".threshold", 100);
        if (current >= threshold) {
            current = 0;
            getConfig().getStringList(path + ".commands").forEach(cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
        getConfig().set(path + ".current", current); saveConfig();
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(colorize("&#FF8C00&lLavaCrates Admin Segítség &8(14 parancs)"));
        s.sendMessage(colorize("&8- &f/lc create <név> &7| Új láda létrehozása a blokkon."));
        s.sendMessage(colorize("&8- &f/lc delete <név> &7| Láda végleges törlése."));
        s.sendMessage(colorize("&8- &f/lc move <név> &7| Láda áthelyezése az aktuális blokkra."));
        s.sendMessage(colorize("&8- &f/lc add <név> <esély> &7| Kézben lévő tárgy hozzáadása."));
        s.sendMessage(colorize("&8- &f/lc setkey <név> &7| Kézben lévő tárgy beállítása kulcsként."));
        s.sendMessage(colorize("&8- &f/lc edit <név> &7| GUI alapú esély és tárgy szerkesztő."));
        s.sendMessage(colorize("&8- &f/lc holo <név> <sor1|sor2> &7| Hologram beállítása."));
        s.sendMessage(colorize("&8- &f/lc givekey <játékos> <név> <db> [v] &7| Kulcs adása (v=virtuális)."));
        s.sendMessage(colorize("&8- &f/lc keyall <név> <db> [v] &7| Kulcs minden online játékosnak."));
        s.sendMessage(colorize("&8- &f/lc withdraw <név> <db> &7| Virtuális kulcs fizikaivá alakítása."));
        s.sendMessage(colorize("&8- &f/lc logs <játékos> &7| Játékos nyitási előzményeinek megtekintése."));
        s.sendMessage(colorize("&8- &f/lc list &7| Összes létező láda listázása."));
        s.sendMessage(colorize("&8- &f/lc clean &7| Beragadt hologramok törlése a közeledben."));
        s.sendMessage(colorize("&8- &f/lc reload &7| Konfigurációk frissítése a fájlokból."));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("create", "delete", "add", "setkey", "edit", "holo", "reload", "givekey", "keyall", "move", "clean", "withdraw", "logs", "list");
        if (args.length == 2 && !args[0].equalsIgnoreCase("create")) return new ArrayList<>(crateLocations.keySet());
        return null;
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();
        if (title.contains("Nyitás") || title.contains("Napló") || title.contains("Előnézet")) e.setCancelled(true);
        if (title.contains("Szerkesztés")) {
            e.setCancelled(true);
            Player p = (Player) e.getWhoClicked();
            String name = editingCrate.get(p.getUniqueId());
            if (e.isRightClick() && e.getCurrentItem() != null) {
                crateRewards.get(name).removeIf(ci -> ci.getItem().isSimilar(e.getCurrentItem()));
                saveCrate(name); openEditor(p, name);
            } else if (e.isShiftClick() && e.isLeftClick() && e.getCurrentItem() != null) {
                pendingChance.put(p.getUniqueId(), e.getCurrentItem()); p.closeInventory();
                p.sendMessage(colorize("&#FFD700Írd be az új esélyt (0-100) a chatre:"));
            }
        }
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (pendingChance.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            try {
                int chance = Integer.parseInt(e.getMessage());
                String name = editingCrate.get(p.getUniqueId());
                ItemStack target = pendingChance.remove(p.getUniqueId());
                for (CrateItem ci : crateRewards.get(name)) {
                    if (ci.getItem().isSimilar(target)) {
                        ci.setChance(chance);
                        ItemMeta m = ci.getItem().getItemMeta();
                        m.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, chance);
                        ci.getItem().setItemMeta(m); break;
                    }
                }
                saveCrate(name); p.sendMessage(colorize("&#00FF7FSikeresen módosítottad az esélyt!"));
                new BukkitRunnable() { @Override public void run() { openEditor(p, name); } }.runTask(this);
            } catch (Exception ex) { p.sendMessage(colorize("&#FF5555Érvénytelen szám formátum!")); }
        }
    }
}
