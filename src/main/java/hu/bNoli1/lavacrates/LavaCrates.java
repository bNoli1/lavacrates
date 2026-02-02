package hu.bNoli1.lavacrates;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
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
    private final Map<String, List<ArmorStand>> activeHolograms = new HashMap<>();
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

        new BukkitRunnable() {
            @Override
            public void run() { crateLocations.keySet().forEach(LavaCrates.this::updateHologram); }
        }.runTaskLater(this, 40L);
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

    public String getMsg(String path) { return colorize(getConfig().getString("messages." + path, "Missing: " + path)); }

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
            sender.sendMessage(getMsg("no-permission"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String action = args[0].toLowerCase();
        switch (action) {
            case "list" -> {
                sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FFFFFFLétező ládák &7(Kattints):"));
                for (String name : crateLocations.keySet()) {
                    Location l = crateLocations.get(name);
                    TextComponent msg = new TextComponent(colorize(" &8• &#FFD700" + name));
                    msg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(colorize("&#00FF7FKattints a teleportáláshoz!")).create()));
                    msg.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp " + l.getBlockX() + " " + (l.getBlockY()+1) + " " + l.getBlockZ()));
                    sender.spigot().sendMessage(msg);
                }
            }
            case "create" -> {
                if (!(sender instanceof Player p)) return true;
                if (args.length < 2) return false;
                String name = args[1].toLowerCase();
                Block b = p.getTargetBlockExact(5);
                if (b != null) {
                    crateLocations.put(name, b.getLocation());
                    saveCrate(name);
                    updateHologram(name);
                    p.sendMessage(getMsg("crate-created").replace("%crate%", name));
                }
            }
            case "delete" -> {
                if (args.length < 2) return false;
                String name = args[1].toLowerCase();
                removeHologram(name);
                crateLocations.remove(name);
                crateRewards.remove(name);
                crateKeys.remove(name);
                cratesConfig.set("crates." + name, null);
                saveCratesFile();
                sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FB5454Láda törölve!"));
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
                p.sendMessage(getMsg("item-added"));
            }
            case "setkey" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                String name = args[1].toLowerCase();
                ItemStack hand = p.getInventory().getItemInMainHand().clone();
                if (hand.getType().isAir()) return true;
                hand.setAmount(1);
                crateKeys.put(name, hand);
                saveCrate(name);
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FKulcs beállítva!"));
                updateHologram(name);
            }
            case "edit" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                String name = args[1].toLowerCase();
                if (!crateLocations.containsKey(name)) return true;
                openEditor(p, name);
            }
            case "holo" -> {
                if (args.length < 3) return false;
                String name = args[1].toLowerCase();
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                cratesConfig.set("crates." + name + ".hologram", Arrays.asList(text.split("\\|")));
                saveCratesFile();
                updateHologram(name);
                sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FHologram frissítve!"));
            }
            case "move" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                String name = args[1].toLowerCase();
                Block b = p.getTargetBlockExact(5);
                if (b != null && crateLocations.containsKey(name)) {
                    crateLocations.put(name, b.getLocation());
                    saveCrate(name);
                    updateHologram(name);
                    p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FLáda áthelyezve!"));
                }
            }
            case "givekey" -> {
                if (args.length < 4) return false;
                Player target = Bukkit.getPlayer(args[1]);
                String cName = args[2].toLowerCase();
                int amount = Integer.parseInt(args[3]);
                boolean virtual = args.length >= 5 && args[4].equalsIgnoreCase("v");
                if (target == null || !crateKeys.containsKey(cName)) return true;
                if (virtual) {
                    int current = dataConfig.getInt("players." + target.getUniqueId() + "." + cName, 0);
                    dataConfig.set("players." + target.getUniqueId() + "." + cName, current + amount);
                    saveData();
                } else {
                    ItemStack k = crateKeys.get(cName).clone(); k.setAmount(amount);
                    target.getInventory().addItem(k);
                }
                sender.sendMessage(getMsg("givekey-success").replace("%player%", target.getName()).replace("%amount%", String.valueOf(amount)));
            }
            case "keyall" -> {
                if (args.length < 3) return false;
                String cName = args[1].toLowerCase();
                int amount = Integer.parseInt(args[2]);
                boolean virtual = args.length >= 4 && args[3].equalsIgnoreCase("v");
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
                Bukkit.broadcastMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FMindenki kapott &#FFD700" + amount + "x &#00FF7Fkulcsot a(z) &#FFD700" + cName + " &#00FF7Fládához!"));
            }
            case "withdraw" -> {
                if (!(sender instanceof Player p) || args.length < 3) return true;
                String cName = args[1].toLowerCase();
                int amount = Integer.parseInt(args[2]);
                int current = dataConfig.getInt("players." + p.getUniqueId() + "." + cName, 0);
                if (current < amount) { p.sendMessage(getMsg("key-needed")); return true; }
                dataConfig.set("players." + p.getUniqueId() + "." + cName, current - amount);
                saveData();
                ItemStack k = crateKeys.get(cName).clone(); k.setAmount(amount);
                p.getInventory().addItem(k);
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FSikeres kivétel!"));
            }
            case "logs" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                Player target = Bukkit.getPlayer(args[1]);
                if (target != null) openLogGUI(p, target);
            }
            case "reload" -> {
                reloadConfig(); loadFiles(); loadCrates();
                activeHolograms.keySet().forEach(this::removeHologram);
                crateLocations.keySet().forEach(this::updateHologram);
                sender.sendMessage(getMsg("reload"));
            }
        }
        return true;
    }

    private void saveCrate(String name) {
        List<ItemStack> items = new ArrayList<>();
        if (crateRewards.get(name) != null) for (CrateItem ci : crateRewards.get(name)) items.add(ci.getItem());
        cratesConfig.set("crates." + name + ".location", crateLocations.get(name));
        cratesConfig.set("crates." + name + ".items", items);
        cratesConfig.set("crates." + name + ".key_item", crateKeys.get(name));
        saveCratesFile();
    }

    private void updateHologram(String name) {
        removeHologram(name);
        Location base = crateLocations.get(name);
        List<String> lines = cratesConfig.getStringList("crates." + name + ".hologram");
        if (base == null || lines.isEmpty()) return;
        List<ArmorStand> stands = new ArrayList<>();
        Location loc = base.clone().add(0.5, 1.2, 0.5);
        for (int i = lines.size() - 1; i >= 0; i--) {
            ArmorStand as = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(0, (lines.size()-1-i)*0.28, 0), EntityType.ARMOR_STAND);
            as.setVisible(false); as.setGravity(false); as.setMarker(true);
            as.setCustomName(colorize(lines.get(i))); as.setCustomNameVisible(true);
            stands.add(as);
        }
        if (crateKeys.containsKey(name)) {
            ArmorStand itemAs = (ArmorStand) loc.clone().add(0, 0.7, 0).getWorld().spawnEntity(loc.clone().add(0, 0.7, 0), EntityType.ARMOR_STAND);
            itemAs.setVisible(false); itemAs.setGravity(false); itemAs.setHelmet(crateKeys.get(name));
            stands.add(itemAs);
            new BukkitRunnable() {
                float y = 0;
                public void run() { 
                    if (!itemAs.isValid()) { this.cancel(); return; }
                    Location l = itemAs.getLocation(); l.setYaw(y); itemAs.teleport(l); y = (y + 10) % 360;
                }
            }.runTaskTimer(this, 0, 1);
        }
        activeHolograms.put(name, stands);
    }

    private void removeHologram(String name) {
        List<ArmorStand> stands = activeHolograms.remove(name);
        if (stands != null) stands.forEach(ArmorStand::remove);
    }

    private void openEditor(Player p, String name) {
        editingCrate.put(p.getUniqueId(), name);
        Inventory inv = Bukkit.createInventory(null, 54, colorize("Szerkesztés: " + name));
        List<CrateItem> items = crateRewards.get(name);
        if (items != null) {
            for (CrateItem ci : items) {
                ItemStack is = ci.getItem().clone();
                ItemMeta m = is.getItemMeta();
                m.setLore(Arrays.asList(colorize("&#778899&m----------------"), colorize("&#B0C4DEEsély: &#FFD700" + ci.getChance() + "%"), colorize("&#F0E68CShift+L: Pontos érték")));
                is.setItemMeta(m); inv.addItem(is);
            }
        }
        p.openInventory(inv);
    }

    private void openLogGUI(Player admin, Player target) {
        Inventory inv = Bukkit.createInventory(null, 54, colorize("&#FF8C00Napló: " + target.getName()));
        List<String> logs = openingLogs.getOrDefault(target.getUniqueId(), new ArrayList<>());
        for (String s : logs) {
            ItemStack i = new ItemStack(Material.PAPER);
            ItemMeta m = i.getItemMeta(); m.setDisplayName(colorize("&#FFD700Nyitás"));
            m.setLore(Collections.singletonList(colorize("&7" + s))); i.setItemMeta(m);
            inv.addItem(i);
        }
        admin.openInventory(inv);
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(colorize("&#FF8C00&lLavaCrates Admin Súgó"));
        s.sendMessage(colorize("&#FFA500/lc create <név> &7- Létrehozás"));
        s.sendMessage(colorize("&#FFA500/lc delete <név> &7- Törlés"));
        s.sendMessage(colorize("&#FFA500/lc list &7- Ládák listája"));
        s.sendMessage(colorize("&#FFA500/lc move <név> &7- Áthelyezés"));
        s.sendMessage(colorize("&#FFA500/lc add <név> <esély> &7- Tárgy hozzáadása"));
        s.sendMessage(colorize("&#FFA500/lc edit <név> &7- GUI Szerkesztő"));
        s.sendMessage(colorize("&#FFA500/lc setkey <név> &7- Kulcs beállítás"));
        s.sendMessage(colorize("&#FFA500/lc holo <név> <sor1|sor2> &7- Hologram"));
        s.sendMessage(colorize("&#FFA500/lc givekey <player> <láda> <db> [v/p] &7- Kulcs adás"));
        s.sendMessage(colorize("&#FFA500/lc keyall <láda> <db> [v/p] &7- Kulcs mindenkinek"));
        s.sendMessage(colorize("&#FFA500/lc withdraw <láda> <db> &7- Virtuális -> Fizikai"));
        s.sendMessage(colorize("&#FFA500/lc logs <játékos> &7- Napló"));
        s.sendMessage(colorize("&#FFA500/lc reload &7- Újratöltés"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("create", "delete", "add", "setkey", "edit", "holo", "reload", "givekey", "keyall", "move", "withdraw", "logs", "list", "help");
        if (args.length == 2 && !Arrays.asList("create", "help", "list").contains(args[0].toLowerCase())) return new ArrayList<>(crateLocations.keySet());
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null || e.getAction() == Action.PHYSICAL) return;
        for (Map.Entry<String, Location> entry : crateLocations.entrySet()) {
            if (entry.getValue().equals(e.getClickedBlock().getLocation())) {
                e.setCancelled(true);
                if (e.getAction() == Action.LEFT_CLICK_BLOCK) openPreview(e.getPlayer(), entry.getKey());
                else startOpening(e.getPlayer(), entry.getKey());
                break;
            }
        }
    }

    private void openPreview(Player p, String name) {
        Inventory inv = Bukkit.createInventory(null, 27, colorize(getConfig().getString("gui-titles.preview").replace("%crate%", name)));
        if (crateRewards.get(name) != null) {
            for (CrateItem ci : crateRewards.get(name)) {
                ItemStack is = ci.getItem().clone();
                ItemMeta m = is.getItemMeta();
                m.setLore(Arrays.asList(colorize(getConfig().getString("lore.separator")), colorize(getConfig().getString("lore.chance").replace("%chance%", String.valueOf(ci.getChance())))));
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
            player.sendMessage(getMsg("key-needed"));
            return;
        }

        List<CrateItem> pool = crateRewards.get(name);
        if (pool == null || pool.isEmpty()) return;
        Inventory inv = Bukkit.createInventory(null, 27, colorize(getConfig().getString("gui-titles.opening").replace("%crate%", name)));
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
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    this.cancel();
                    
                    int c = getConfig().getInt("party.current", 0) + 1;
                    if (c >= getConfig().getInt("party.threshold")) {
                        c = 0; getConfig().getStringList("party.commands").forEach(cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
                    }
                    getConfig().set("party.current", c); saveConfig();
                    openingLogs.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add("Láda: " + name + " | Tárgy: " + win.getType());
                    return;
                }
                inv.setItem(13, pool.get(random.nextInt(pool.size())).getItem());
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 1f, 1.5f);
                ticks++;
            }
        }.runTaskTimer(this, 0, 3);
    }

    @EventHandler public void onClick(InventoryClickEvent e) { 
        String title = e.getView().getTitle();
        if (title.contains("Előnézet") || title.contains("Nyitás") || title.contains("Napló")) { e.setCancelled(true); return; }
        
        if (title.startsWith("Szerkesztés: ")) {
            e.setCancelled(true);
            Player p = (Player) e.getWhoClicked();
            String name = editingCrate.get(p.getUniqueId());
            if (e.getCurrentItem() == null) return;
            
            if (e.isShiftClick() && e.isLeftClick()) {
                pendingChance.put(p.getUniqueId(), e.getCurrentItem());
                p.closeInventory();
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FFD700Írd be az új esélyt a chatre (0-100):"));
            } else if (e.isRightClick()) {
                crateRewards.get(name).removeIf(ci -> ci.getItem().isSimilar(e.getCurrentItem()));
                saveCrate(name);
                openEditor(p, name);
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
                        ci.getItem().setItemMeta(m);
                        break;
                    }
                }
                saveCrate(name);
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FEsély frissítve: " + chance + "%"));
                new BukkitRunnable() { @Override public void run() { openEditor(p, name); } }.runTask(this);
            } catch (NumberFormatException ex) { p.sendMessage(colorize("&#FF5555Érvénytelen szám!")); }
        }
    }
}
