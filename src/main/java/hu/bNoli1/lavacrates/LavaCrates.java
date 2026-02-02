package hu.bNoli1.lavacrates;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
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
import org.bukkit.event.EventPriority;
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
        if (!cratesFile.exists()) saveResource("crates.yml", false);
        cratesConfig = YamlConfiguration.loadConfiguration(cratesFile);
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) { try { dataFile.createNewFile(); } catch (IOException ignored) {} }
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

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String action = args[0].toLowerCase();
        switch (action) {
            case "list" -> {
                sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FFFFFFLétező ládák:"));
                crateLocations.keySet().forEach(name -> {
                    Location l = crateLocations.get(name);
                    TextComponent msg = new TextComponent(colorize(" &8• &#FFD700" + name + " &7(TP)"));
                    msg.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
                    sender.spigot().sendMessage(msg);
                });
            }
            case "create" -> {
                if (!(sender instanceof Player p) || args.length < 2) return false;
                String name = args[1].toLowerCase();
                crateLocations.put(name, p.getTargetBlockExact(5).getLocation());
                saveCrate(name);
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FLáda létrehozva: &#FFD700" + name));
            }
            case "delete" -> {
                if (args.length < 2) return false;
                String name = args[1].toLowerCase();
                crateLocations.remove(name);
                cratesConfig.set("crates." + name, null);
                saveCratesFile();
                sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FB5454Láda törölve!"));
            }
            case "move" -> {
                if (!(sender instanceof Player p) || args.length < 2) return false;
                String name = args[1].toLowerCase();
                if (!crateLocations.containsKey(name)) return true;
                crateLocations.put(name, p.getTargetBlockExact(5).getLocation());
                saveCrate(name);
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FLáda áthelyezve!"));
            }
            case "add" -> {
                if (!(sender instanceof Player p) || args.length < 3) return false;
                String name = args[1].toLowerCase();
                int chance = Integer.parseInt(args[2]);
                ItemStack hand = p.getInventory().getItemInMainHand().clone();
                if (hand.getType().isAir()) return true;
                ItemMeta m = hand.getItemMeta();
                m.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, chance);
                hand.setItemMeta(m);
                crateRewards.computeIfAbsent(name, k -> new ArrayList<>()).add(new CrateItem(hand, chance));
                saveCrate(name);
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FTárgy hozzáadva!"));
            }
            case "setkey" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                ItemStack hand = p.getInventory().getItemInMainHand().clone();
                if (hand.getType().isAir()) return true;
                hand.setAmount(1);
                crateKeys.put(args[1].toLowerCase(), hand);
                saveCrate(args[1].toLowerCase());
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FKulcs beállítva!"));
            }
            case "edit" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                openEditor(p, args[1].toLowerCase());
            }
            case "clean" -> {
                if (!(sender instanceof Player p)) return true;
                int count = 0;
                for (Entity e : p.getNearbyEntities(5, 5, 5)) {
                    if (e instanceof ArmorStand as && as.isMarker()) { e.remove(); count++; }
                }
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FSikeresen törölve " + count + " hologram!"));
            }
            case "holo" -> {
                if (args.length < 3) return false;
                String name = args[1].toLowerCase();
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                cratesConfig.set("crates." + name + ".hologram", Arrays.asList(text.split("\\|")));
                saveCratesFile();
                sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FHologram frissítve!"));
            }
            case "givekey" -> {
                if (args.length < 4) return false;
                Player target = Bukkit.getPlayer(args[1]);
                String cName = args[2].toLowerCase();
                int amount = Integer.parseInt(args[3]);
                boolean virtual = args.length >= 5 && args[4].equalsIgnoreCase("v");
                if (target == null || !crateKeys.containsKey(cName)) return true;
                if (virtual) {
                    int cur = dataConfig.getInt("players." + target.getUniqueId() + "." + cName, 0);
                    dataConfig.set("players." + target.getUniqueId() + "." + cName, cur + amount);
                    saveData();
                } else {
                    ItemStack k = crateKeys.get(cName).clone(); k.setAmount(amount);
                    target.getInventory().addItem(k);
                }
                sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FKulcs odaadva!"));
            }
            case "keyall" -> {
                if (args.length < 3) return false;
                String cName = args[1].toLowerCase();
                int amount = Integer.parseInt(args[2]);
                boolean virtual = args.length >= 4 && args[3].equalsIgnoreCase("v");
                if (!crateKeys.containsKey(cName)) return true;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (virtual) {
                        int cur = dataConfig.getInt("players." + p.getUniqueId() + "." + cName, 0);
                        dataConfig.set("players." + p.getUniqueId() + "." + cName, cur + amount);
                    } else {
                        ItemStack k = crateKeys.get(cName).clone(); k.setAmount(amount);
                        p.getInventory().addItem(k);
                    }
                }
                saveData();
                Bukkit.broadcastMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FMindenki kapott kulcsot!"));
            }
            case "withdraw" -> {
                if (!(sender instanceof Player p) || args.length < 3) return true;
                String cName = args[1].toLowerCase();
                int amount = Integer.parseInt(args[2]);
                int current = dataConfig.getInt("players." + p.getUniqueId() + "." + cName, 0);
                if (current < amount) { p.sendMessage(colorize("&#FF5555Nincs elég virtuális kulcsod!")); return true; }
                dataConfig.set("players." + p.getUniqueId() + "." + cName, current - amount);
                saveData();
                ItemStack k = crateKeys.get(cName).clone(); k.setAmount(amount);
                p.getInventory().addItem(k);
            }
            case "logs" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                Player target = Bukkit.getPlayer(args[1]);
                if (target != null) openLogs(p, target);
            }
            case "reload" -> {
                reloadConfig(); loadFiles(); loadCrates();
                sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FPlugin újratöltve!"));
            }
        }
        return true;
    }

    private void openPreview(Player player, String name) {
        List<CrateItem> items = crateRewards.get(name);
        if (items == null) return;
        Inventory inv = Bukkit.createInventory(null, 54, colorize("Előnézet: " + name));
        for (CrateItem ci : items) {
            ItemStack is = ci.getItem().clone();
            ItemMeta m = is.getItemMeta();
            List<String> lore = m.hasLore() ? m.getLore() : new ArrayList<>();
            lore.add(colorize("&8&m----------------"));
            lore.add(colorize("&7Esély: &e" + ci.getChance() + "%"));
            m.setLore(lore);
            is.setItemMeta(m);
            inv.addItem(is);
        }
        player.openInventory(inv);
    }

    private void openLogs(Player admin, Player target) {
        Inventory inv = Bukkit.createInventory(null, 54, colorize("Napló: " + target.getName()));
        List<String> logs = openingLogs.getOrDefault(target.getUniqueId(), new ArrayList<>());
        for (String log : logs) {
            ItemStack paper = new ItemStack(Material.PAPER);
            ItemMeta m = paper.getItemMeta();
            m.setDisplayName(colorize("&#FFD700Nyitás"));
            m.setLore(Collections.singletonList(colorize("&7" + log)));
            paper.setItemMeta(m); inv.addItem(paper);
        }
        admin.openInventory(inv);
    }

    private void startHologramTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    for (String name : crateLocations.keySet()) updateHologramForPlayer(p, name);
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void updateHologramForPlayer(Player p, String name) {
        Location loc = crateLocations.get(name);
        if (loc == null || !loc.getWorld().equals(p.getWorld()) || p.getLocation().distance(loc) > 8) {
            removeHologramForPlayer(p, name); return;
        }
        removeHologramForPlayer(p, name);
        List<String> lines = cratesConfig.getStringList("crates." + name + ".hologram");
        if (lines.isEmpty()) return;

        int keys = dataConfig.getInt("players." + p.getUniqueId() + "." + name, 0);
        String keyStr = (keys > 0 ? "&a" : "&c") + keys;
        int remaining = getConfig().getInt("party." + name + ".threshold", 100) - getConfig().getInt("party." + name + ".current", 0);

        List<ArmorStand> stands = new ArrayList<>();
        Location spawn = loc.clone().add(0.5, 1.2, 0.5);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String text = lines.get(i).replace("%keys%", keyStr).replace("%party%", String.valueOf(remaining));
            ArmorStand as = (ArmorStand) spawn.getWorld().spawnEntity(spawn.clone().add(0, (lines.size()-1-i)*0.28, 0), EntityType.ARMOR_STAND);
            as.setVisible(false); as.setGravity(false); as.setMarker(true);
            as.setCustomName(colorize(text)); as.setCustomNameVisible(true);
            stands.add(as);
        }
        if (crateKeys.containsKey(name)) {
            ArmorStand itemAs = (ArmorStand) spawn.clone().add(0, 0.7, 0).getWorld().spawnEntity(spawn.clone().add(0, 0.7, 0), EntityType.ARMOR_STAND);
            itemAs.setVisible(false); itemAs.setGravity(false); itemAs.setHelmet(crateKeys.get(name));
            stands.add(itemAs);
        }
        playerHolograms.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(name, stands);
    }

    private void removeHologramForPlayer(Player p, String name) {
        if (playerHolograms.containsKey(p.getUniqueId())) {
            List<ArmorStand> list = playerHolograms.get(p.getUniqueId()).remove(name);
            if (list != null) list.forEach(ArmorStand::remove);
        }
    }

    private void openEditor(Player p, String name) {
        editingCrate.put(p.getUniqueId(), name);
        Inventory inv = Bukkit.createInventory(null, 54, colorize("Szerkesztés: " + name));
        List<CrateItem> items = crateRewards.get(name);
        if (items != null) {
            for (CrateItem ci : items) {
                ItemStack is = ci.getItem().clone();
                ItemMeta m = is.getItemMeta();
                m.setLore(Arrays.asList(colorize("&7Esély: &e" + ci.getChance() + "%"), colorize("&7Shift+Bal: Módosítás"), colorize("&7Jobb: Törlés")));
                is.setItemMeta(m); inv.addItem(is);
            }
        }
        p.openInventory(inv);
    }

    private void startOpening(Player player, String name) {
        int virtual = dataConfig.getInt("players." + player.getUniqueId() + "." + name, 0);
        ItemStack req = crateKeys.get(name);
        if (req != null && player.getInventory().getItemInMainHand().isSimilar(req)) {
            player.getInventory().getItemInMainHand().setAmount(player.getInventory().getItemInMainHand().getAmount() - 1);
        } else if (virtual > 0) {
            dataConfig.set("players." + player.getUniqueId() + "." + name, virtual - 1);
            saveData();
        } else {
            player.sendMessage(colorize("&#FF5555Nincs kulcsod!")); return;
        }

        List<CrateItem> pool = crateRewards.get(name);
        if (pool == null || pool.isEmpty()) return;
        Inventory inv = Bukkit.createInventory(null, 27, colorize("Nyitás: " + name));
        player.openInventory(inv);

        new BukkitRunnable() {
            int ticks = 0;
            public void run() {
                if (ticks >= 30) {
                    int total = pool.stream().mapToInt(CrateItem::getChance).sum();
                    int r = random.nextInt(total > 0 ? total : 1), cur = 0;
                    ItemStack win = pool.get(0).getItem().clone();
                    for (CrateItem ci : pool) { cur += ci.getChance(); if (r < cur) { win = ci.getItem().clone(); break; } }
                    player.getInventory().addItem(win);
                    player.closeInventory();
                    player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FGratulálunk! Nyertél!"));
                    openingLogs.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add("Láda: " + name + " | Tárgy: " + win.getType());
                    handleParty(name); this.cancel(); return;
                }
                inv.setItem(13, pool.get(random.nextInt(pool.size())).getItem());
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 1f, 1.5f);
                ticks++;
            }
        }.runTaskTimer(this, 0, 3);
    }

    private void handleParty(String crateName) {
        String path = "party." + crateName;
        if (!getConfig().contains(path)) return;
        int current = getConfig().getInt(path + ".current", 0) + 1;
        int threshold = getConfig().getInt(path + ".threshold", 100);
        if (current >= threshold) {
            current = 0;
            getConfig().getStringList(path + ".commands").forEach(cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            Bukkit.broadcastMessage(colorize("&#FF8C00LavaCrates &8» &#FFD700" + crateName.toUpperCase() + " PARTI ELKEZDŐDÖTT!"));
        }
        getConfig().set(path + ".current", current); saveConfig();
    }

    private void saveCrate(String name) {
        List<ItemStack> items = new ArrayList<>();
        if (crateRewards.get(name) != null) for (CrateItem ci : crateRewards.get(name)) items.add(ci.getItem());
        cratesConfig.set("crates." + name + ".location", crateLocations.get(name));
        cratesConfig.set("crates." + name + ".items", items);
        cratesConfig.set("crates." + name + ".key_item", crateKeys.get(name));
        saveCratesFile();
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(colorize("&#FF8C00&lLavaCrates Súgó"));
        s.sendMessage(colorize("&#FFA500/lc create <név> &7- Láda létrehozása"));
        s.sendMessage(colorize("&#FFA500/lc delete <név> &7- Láda törlése"));
        s.sendMessage(colorize("&#FFA500/lc move <név> &7- Láda áthelyezése"));
        s.sendMessage(colorize("&#FFA500/lc list &7- Ládák listája"));
        s.sendMessage(colorize("&#FFA500/lc clean &7- Hologram törlés"));
        s.sendMessage(colorize("&#FFA500/lc add <név> <esély> &7- Tárgy hozzáadása"));
        s.sendMessage(colorize("&#FFA500/lc edit <név> &7- GUI szerkesztő"));
        s.sendMessage(colorize("&#FFA500/lc setkey <név> &7- Kulcs beállítása"));
        s.sendMessage(colorize("&#FFA500/lc holo <név> <szöveg> &7- Hologram (%keys%, %party%)"));
        s.sendMessage(colorize("&#FFA500/lc givekey <p> <láda> <db> [v/p] &7- Kulcs adása"));
        s.sendMessage(colorize("&#FFA500/lc keyall <láda> <db> [v/p] &7- Mindenkinek kulcs"));
        s.sendMessage(colorize("&#FFA500/lc reload &7- Újratöltés"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("create", "delete", "add", "setkey", "edit", "holo", "reload", "givekey", "keyall", "move", "clean", "withdraw", "logs", "list", "help");
        if (args.length == 2 && !Arrays.asList("create", "help", "list", "clean").contains(args[0])) return new ArrayList<>(crateLocations.keySet());
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        for (Map.Entry<String, Location> entry : crateLocations.entrySet()) {
            if (entry.getValue().equals(e.getClickedBlock().getLocation())) {
                e.setCancelled(true);
                if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    startOpening(e.getPlayer(), entry.getKey());
                } else if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
                    openPreview(e.getPlayer(), entry.getKey());
                }
                break;
            }
        }
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (e.getView().getTitle().contains("Nyitás") || e.getView().getTitle().contains("Napló") || e.getView().getTitle().contains("Előnézet")) { e.setCancelled(true); return; }
        if (e.getView().getTitle().startsWith("Szerkesztés: ")) {
            e.setCancelled(true); Player p = (Player) e.getWhoClicked(); String name = editingCrate.get(p.getUniqueId());
            if (e.getCurrentItem() == null) return;
            if (e.isShiftClick() && e.isLeftClick()) { pendingChance.put(p.getUniqueId(), e.getCurrentItem()); p.closeInventory(); p.sendMessage(colorize("&#FFD700Új esély (0-100):")); }
            else if (e.isRightClick()) { crateRewards.get(name).removeIf(ci -> ci.getItem().isSimilar(e.getCurrentItem())); saveCrate(name); openEditor(p, name); }
        }
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (pendingChance.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            try {
                int chance = Integer.parseInt(e.getMessage()); String name = editingCrate.get(p.getUniqueId()); ItemStack target = pendingChance.remove(p.getUniqueId());
                for (CrateItem ci : crateRewards.get(name)) {
                    if (ci.getItem().isSimilar(target)) { ci.setChance(chance); ItemMeta m = ci.getItem().getItemMeta(); m.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, chance); ci.getItem().setItemMeta(m); break; }
                }
                saveCrate(name); p.sendMessage(colorize("&#00FF7FSikeres módosítás!"));
                new BukkitRunnable() { @Override public void run() { openEditor(p, name); } }.runTask(this);
            } catch (Exception ex) { p.sendMessage(colorize("&#FF5555Hiba!")); }
        }
    }
}
