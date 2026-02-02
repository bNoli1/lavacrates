package hu.bNoli1.lavacrates;

import com.comphenry.protocol.PacketType;
import com.comphenry.protocol.ProtocolLibrary;
import com.comphenry.protocol.ProtocolManager;
import com.comphenry.protocol.events.PacketContainer;
import com.comphenry.protocol.wrappers.WrappedChatComponent;
import com.comphenry.protocol.wrappers.WrappedDataWatcher;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
    private final Map<UUID, Map<String, List<Integer>>> virtualEntities = new HashMap<>();
    private final Map<UUID, List<String>> openingLogs = new HashMap<>();
    private final Map<UUID, String> editingCrate = new HashMap<>();
    private final Map<UUID, ItemStack> pendingChance = new HashMap<>();

    private File cratesFile, dataFile;
    private FileConfiguration cratesConfig, dataConfig;
    private NamespacedKey chanceKey;
    private ProtocolManager protocolManager;
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
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        loadCrates();
        getCommand("lavacrates").setExecutor(this);
        getCommand("lavacrates").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        startHologramTask();
    }

    @Override
    public void onDisable() {
        virtualEntities.keySet().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) removeAllHolograms(p);
        });
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
                double dist = getConfig().getDouble("hologram-view-distance", 50.0);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    for (String name : crateLocations.keySet()) {
                        Location loc = crateLocations.get(name);
                        if (loc != null && loc.getWorld().equals(p.getWorld()) && p.getLocation().distance(loc) <= dist) {
                            sendVirtualHologram(p, name);
                        } else {
                            removeVirtualHologram(p, name);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void sendVirtualHologram(Player p, String name) {
        Location loc = crateLocations.get(name).clone().add(0.5, 1.2, 0.5);
        List<String> lines = cratesConfig.getStringList("crates." + name + ".hologram");
        if (lines.isEmpty()) return;
        int keys = dataConfig.getInt("players." + p.getUniqueId() + "." + name, 0);
        int rem = getConfig().getInt("party." + name + ".threshold", 100) - getConfig().getInt("party." + name + ".current", 0);
        Map<String, List<Integer>> pMap = virtualEntities.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        if (pMap.containsKey(name)) {
            List<Integer> ids = pMap.get(name);
            for (int i = 0; i < lines.size(); i++) {
                String text = lines.get(i).replace("%keys%", (keys > 0 ? "&a" : "&c") + keys).replace("%party%", String.valueOf(rem));
                sendMetadataPacket(p, ids.get(i), text);
            }
        } else {
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                int id = (int) (Math.random() * Integer.MAX_VALUE); ids.add(id);
                String text = lines.get((lines.size() - 1) - i).replace("%keys%", (keys > 0 ? "&a" : "&c") + keys).replace("%party%", String.valueOf(rem));
                sendSpawnPacket(p, id, loc.clone().add(0, i * 0.28, 0), text);
            }
            pMap.put(name, ids);
        }
    }

    private void sendSpawnPacket(Player p, int id, Location l, String text) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().write(0, id);
        packet.getUUIDs().write(0, UUID.randomUUID());
        packet.getEntityTypeModifier().write(0, EntityType.ARMOR_STAND);
        packet.getDoubles().write(0, l.getX()).write(1, l.getY()).write(2, l.getZ());
        protocolManager.sendServerPacket(p, packet);
        sendMetadataPacket(p, id, text);
    }

    private void sendMetadataPacket(Player p, int id, String text) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().write(0, id);
        WrappedDataWatcher watcher = new WrappedDataWatcher();
        watcher.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(0, WrappedDataWatcher.Registry.get(Byte.class)), (byte) 0x20);
        watcher.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(2, WrappedDataWatcher.Registry.getChatComponentSerializer(true)), Optional.of(WrappedChatComponent.fromLegacyText(colorize(text)).getHandle()));
        watcher.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(3, WrappedDataWatcher.Registry.get(Boolean.class)), true);
        watcher.setObject(new WrappedDataWatcher.WrappedDataWatcherObject(15, WrappedDataWatcher.Registry.get(Byte.class)), (byte) 0x10);
        packet.getWatchableCollectionModifier().write(0, watcher.getWatchableObjects());
        protocolManager.sendServerPacket(p, packet);
    }

    private void removeVirtualHologram(Player p, String name) {
        Map<String, List<Integer>> pMap = virtualEntities.get(p.getUniqueId());
        if (pMap != null && pMap.containsKey(name)) {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            packet.getIntLists().write(0, pMap.remove(name));
            protocolManager.sendServerPacket(p, packet);
        }
    }

    private void removeAllHolograms(Player p) {
        if (virtualEntities.containsKey(p.getUniqueId())) {
            virtualEntities.get(p.getUniqueId()).keySet().forEach(name -> {
                PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
                packet.getIntLists().write(0, virtualEntities.get(p.getUniqueId()).get(name));
                protocolManager.sendServerPacket(p, packet);
            });
            virtualEntities.remove(p.getUniqueId());
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { removeAllHolograms(e.getPlayer()); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lavacrates.admin") && (args.length == 0 || !args[0].equalsIgnoreCase("withdraw"))) {
            sender.sendMessage(colorize("&#FF5555Nincs jogod ehhez!")); return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { sendHelp(sender); return true; }
        String act = args[0].toLowerCase();
        switch (act) {
            case "create", "move" -> {
                if (!(sender instanceof Player p) || args.length < 2) return false;
                crateLocations.put(args[1].toLowerCase(), p.getTargetBlockExact(5).getLocation());
                saveCrate(args[1].toLowerCase()); p.sendMessage(colorize("&#00FF7FSikeres művelet!"));
            }
            case "delete" -> {
                if (args.length < 2) return false;
                String n = args[1].toLowerCase(); crateLocations.remove(n); cratesConfig.set("crates." + n, null);
                saveCratesFile(); sender.sendMessage(colorize("&#FB5454Láda törölve!"));
            }
            case "add" -> {
                if (!(sender instanceof Player p) || args.length < 3) return false;
                int c = Integer.parseInt(args[2]); ItemStack h = p.getInventory().getItemInMainHand().clone();
                ItemMeta m = h.getItemMeta(); m.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, c); h.setItemMeta(m);
                crateRewards.computeIfAbsent(args[1].toLowerCase(), k -> new ArrayList<>()).add(new CrateItem(h, c));
                saveCrate(args[1].toLowerCase()); p.sendMessage(colorize("&#00FF7FTárgy hozzáadva!"));
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
                } else { if(crateKeys.containsKey(cn)) { ItemStack k = crateKeys.get(cn).clone(); k.setAmount(am); t.getInventory().addItem(k); } }
                sender.sendMessage(colorize("&#00FF7FKulcs kiosztva!"));
            }
            case "keyall" -> {
                if (args.length < 3) return false;
                String cn = args[1].toLowerCase(); int am = Integer.parseInt(args[2]);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (args.length > 3 && args[3].equalsIgnoreCase("v")) {
                        dataConfig.set("players." + p.getUniqueId() + "." + cn, dataConfig.getInt("players." + p.getUniqueId() + "." + cn, 0) + am);
                    } else { if(crateKeys.containsKey(cn)) { ItemStack k = crateKeys.get(cn).clone(); k.setAmount(am); p.getInventory().addItem(k); } }
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
                saveCratesFile(); sender.sendMessage(colorize("&#00FF7FHologram elmentve!"));
            }
            case "logs" -> { if (sender instanceof Player p && args.length > 1) openLogs(p, Bukkit.getPlayer(args[1])); }
            case "list" -> {
                sender.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FFFFFFLádák:"));
                crateLocations.keySet().forEach(n -> sender.sendMessage(colorize(" &8• &#FFD700" + n)));
            }
            case "clean" -> sender.sendMessage(colorize("&#00FF7FA virtuális hologramok automatikusan tisztulnak!"));
            case "reload" -> { reloadConfig(); loadFiles(); loadCrates(); sender.sendMessage(colorize("&#00FF7FÚjratöltve!")); }
        }
        return true;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(colorize("&#FF8C00&lLavaCrates Admin Segítség (14 Funkció)"));
        s.sendMessage(colorize("&8- &f/lc create <név> &7| Új láda létrehozása."));
        s.sendMessage(colorize("&8- &f/lc delete <név> &7| Láda törlése."));
        s.sendMessage(colorize("&8- &f/lc move <név> &7| Láda áthelyezése."));
        s.sendMessage(colorize("&8- &f/lc add <név> <esély> &7| Tárgy hozzáadása kézből."));
        s.sendMessage(colorize("&8- &f/lc edit <név> &7| GUI alapú szerkesztő."));
        s.sendMessage(colorize("&8- &f/lc setkey <név> &7| Kulcs beállítása kézből."));
        s.sendMessage(colorize("&8- &f/lc givekey <player> <láda> <db> [v] &7| Kulcsadás."));
        s.sendMessage(colorize("&8- &f/lc keyall <láda> <db> [v] &7| Kulcs mindenki."));
        s.sendMessage(colorize("&8- &f/lc withdraw <láda> <db> &7| Virtuális -> Fizikai."));
        s.sendMessage(colorize("&8- &f/lc holo <név> <sor1|sor2> &7| Hologram."));
        s.sendMessage(colorize("&8- &f/lc logs <player> &7| Nyitási napló."));
        s.sendMessage(colorize("&8- &f/lc list &7| Ládák listája."));
        s.sendMessage(colorize("&8- &f/lc clean &7| Virtuális takarítás (Auto)."));
        s.sendMessage(colorize("&8- &f/lc reload &7| Újratöltés."));
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
                break;
            }
        }
    }

    private void startBulkOpening(Player p, String name) {
        int virt = dataConfig.getInt("players." + p.getUniqueId() + "." + name, 0);
        int phys = 0; ItemStack key = crateKeys.get(name);
        if (key != null) for (ItemStack is : p.getInventory().getContents()) if (is != null && is.isSimilar(key)) phys += is.getAmount();
        int total = virt + phys; if (total <= 0) return;
        int toOpen = Math.min(total, 10);
        int rem = toOpen;
        if (key != null) {
            for (ItemStack is : p.getInventory().getContents()) {
                if (is != null && is.isSimilar(key)) {
                    int take = Math.min(is.getAmount(), rem); is.setAmount(is.getAmount() - take); rem -= take;
                    if (rem <= 0) break;
                }
            }
        }
        if (rem > 0) { dataConfig.set("players." + p.getUniqueId() + "." + name, virt - rem); saveData(); }
        p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FFFFFFTömeges nyitás (" + toOpen + "x)"));
        for (int i = 0; i < toOpen; i++) {
            List<CrateItem> pool = crateRewards.get(name);
            int sum = pool.stream().mapToInt(CrateItem::getChance).sum(), r = random.nextInt(sum > 0 ? sum : 1), c = 0;
            for (CrateItem ci : pool) { c += ci.getChance(); if (r < c) { p.getInventory().addItem(ci.getItem().clone()); break; } }
            handleParty(name);
        }
    }

    private void startOpening(Player p, String name) {
        int virt = dataConfig.getInt("players." + p.getUniqueId() + "." + name, 0);
        ItemStack key = crateKeys.get(name);
        boolean hasPhys = key != null && p.getInventory().getItemInMainHand().isSimilar(key);
        if (!hasPhys && virt <= 0) return;
        if (hasPhys) p.getInventory().getItemInMainHand().setAmount(p.getInventory().getItemInMainHand().getAmount() - 1);
        else { dataConfig.set("players." + p.getUniqueId() + "." + name, virt - 1); saveData(); }
        List<CrateItem> pool = crateRewards.get(name);
        Inventory inv = Bukkit.createInventory(null, 27, colorize("Nyitás: " + name)); p.openInventory(inv);
        new BukkitRunnable() {
            int t = 0;
            public void run() {
                if (t >= 20) {
                    int sum = pool.stream().mapToInt(CrateItem::getChance).sum(), r = random.nextInt(sum > 0 ? sum : 1), c = 0;
                    ItemStack win = pool.get(0).getItem();
                    for (CrateItem ci : pool) { c += ci.getChance(); if (r < c) { win = ci.getItem(); break; } }
                    p.getInventory().addItem(win.clone()); p.closeInventory();
                    openingLogs.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(name + " | " + win.getType());
                    handleParty(name); this.cancel(); return;
                }
                inv.setItem(13, pool.get(random.nextInt(pool.size())).getItem());
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 1f, 1f); t++;
            }
        }.runTaskTimer(this, 0, 3);
    }

    private void openEditor(Player p, String name) {
        editingCrate.put(p.getUniqueId(), name);
        Inventory inv = Bukkit.createInventory(null, 54, colorize("Szerkesztés: " + name));
        if (crateRewards.get(name) != null) crateRewards.get(name).forEach(ci -> inv.addItem(ci.getItem()));
        p.openInventory(inv);
    }

    private void openLogs(Player admin, Player target) {
        Inventory inv = Bukkit.createInventory(null, 54, colorize("Napló: " + target.getName()));
        openingLogs.getOrDefault(target.getUniqueId(), new ArrayList<>()).forEach(s -> {
            ItemStack i = new ItemStack(Material.PAPER); ItemMeta m = i.getItemMeta();
            m.setDisplayName(colorize("&e" + s)); i.setItemMeta(m); inv.addItem(i);
        });
        admin.openInventory(inv);
    }

    private void openPreview(Player p, String name) {
        Inventory inv = Bukkit.createInventory(null, 54, colorize("Előnézet: " + name));
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

    private void handleParty(String n) {
        String path = "party." + n;
        if (!getConfig().contains(path)) return;
        int current = getConfig().getInt(path + ".current", 0) + 1;
        if (current >= getConfig().getInt(path + ".threshold", 100)) {
            current = 0;
            getConfig().getStringList(path + ".commands").forEach(cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
        getConfig().set(path + ".current", current); saveConfig();
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        String t = e.getView().getTitle();
        if (t.contains("Nyitás") || t.contains("Napló") || t.contains("Előnézet")) e.setCancelled(true);
        if (t.contains("Szerkesztés")) {
            e.setCancelled(true); Player p = (Player) e.getWhoClicked();
            if (e.isRightClick()) {
                crateRewards.get(editingCrate.get(p.getUniqueId())).removeIf(ci -> ci.getItem().isSimilar(e.getCurrentItem()));
                saveCrate(editingCrate.get(p.getUniqueId())); openEditor(p, editingCrate.get(p.getUniqueId()));
            } else if (e.isShiftClick()) {
                pendingChance.put(p.getUniqueId(), e.getCurrentItem()); p.closeInventory();
                p.sendMessage(colorize("&#FFD700Esély (0-100):"));
            }
        }
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (pendingChance.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            try {
                int c = Integer.parseInt(e.getMessage()); String n = editingCrate.get(p.getUniqueId());
                ItemStack target = pendingChance.remove(p.getUniqueId());
                for (CrateItem ci : crateRewards.get(n)) {
                    if (ci.getItem().isSimilar(target)) {
                        ci.setChance(c); ItemMeta m = ci.getItem().getItemMeta();
                        m.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, c);
                        ci.getItem().setItemMeta(m); break;
                    }
                }
                saveCrate(n); p.sendMessage(colorize("&#00FF7FKész!"));
                new BukkitRunnable() { @Override public void run() { openEditor(p, n); } }.runTask(this);
            } catch (Exception ex) { p.sendMessage(colorize("&#FF5555Hiba!")); }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("create", "delete", "add", "edit", "setkey", "givekey", "keyall", "withdraw", "holo", "logs", "reload", "list", "move", "clean");
        return null;
    }
}
