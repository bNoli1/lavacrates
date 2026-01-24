package hu.bNoli1.lavacrates;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LavaCrates extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<String, Location> crateLocations = new HashMap<>();
    private final Map<String, List<CrateItem>> crateRewards = new HashMap<>();
    private final Map<String, ItemStack> crateKeys = new HashMap<>();
    private final Map<UUID, String> editingPlayers = new HashMap<>();
    private final Map<UUID, ItemStack> pendingChanceEdit = new HashMap<>();
    private final Map<String, List<ArmorStand>> activeHolograms = new HashMap<>();
    
    private NamespacedKey chanceKey;
    private final Random random = new Random();
    private final Pattern hexPattern = Pattern.compile("&#[a-fA-F0-9]{6}");

    private static class CrateItem {
        private final ItemStack item;
        private final int chance;
        public CrateItem(ItemStack item, int chance) {
            this.item = item;
            this.chance = chance;
        }
        public ItemStack getItem() { return item; }
        public int getChance() { return chance; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.chanceKey = new NamespacedKey(this, "lavachance");
        loadCrates();
        getCommand("lavacrates").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        
        new BukkitRunnable() {
            @Override
            public void run() {
                for (String name : crateLocations.keySet()) updateHologram(name);
            }
        }.runTaskLater(this, 40L);
    }

    @Override
    public void onDisable() {
        for (List<ArmorStand> stands : activeHolograms.values()) {
            for (ArmorStand as : stands) if (as != null) as.remove();
        }
    }

    public String colorize(String message) {
        if (message == null) return "";
        Matcher matcher = hexPattern.matcher(message);
        while (matcher.find()) {
            String color = message.substring(matcher.start(), matcher.end());
            message = message.replace(color, ChatColor.of(color.substring(1)).toString());
            matcher = hexPattern.matcher(message);
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void loadCrates() {
        crateLocations.clear();
        crateRewards.clear();
        crateKeys.clear();
        FileConfiguration config = getConfig();
        if (config.getConfigurationSection("crates") == null) return;
        
        for (String key : config.getConfigurationSection("crates").getKeys(false)) {
            Location loc = config.getLocation("crates." + key + ".location");
            if (loc != null) crateLocations.put(key, loc);
            
            if (config.contains("crates." + key + ".key_item")) {
                ItemStack keyItem = config.getItemStack("crates." + key + ".key_item");
                if (keyItem != null) crateKeys.put(key, keyItem);
            }
            
            List<CrateItem> items = new ArrayList<>();
            List<?> list = config.getList("crates." + key + ".items");
            if (list != null) {
                for (Object obj : list) {
                    if (obj instanceof ItemStack is) {
                        ItemMeta meta = is.getItemMeta();
                        int chance = (meta != null) ? meta.getPersistentDataContainer().getOrDefault(chanceKey, PersistentDataType.INTEGER, 100) : 100;
                        items.add(new CrateItem(is, chance));
                    }
                }
            }
            crateRewards.put(key, items);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (!player.hasPermission("lavacrates.admin")) {
            player.sendMessage(colorize("&#FF5555Nincs jogod ehhez!"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(player);
            return true;
        }

        String action = args[0].toLowerCase();
        if (action.equals("reload")) {
            reloadConfig();
            loadCrates();
            for (String name : new HashSet<>(activeHolograms.keySet())) removeHologram(name);
            for (String name : crateLocations.keySet()) updateHologram(name);
            player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FÚjratöltve!"));
            return true;
        }

        if (args.length < 2) return false;
        String name = args[1].toLowerCase();

        switch (action) {
            case "create" -> {
                Block b = player.getTargetBlockExact(5);
                if (b != null && b.getType() != Material.AIR) {
                    crateLocations.put(name, b.getLocation());
                    saveCrate(name);
                    updateHologram(name);
                    player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FLáda létrehozva: &#FFD700" + name));
                }
            }
            case "delete" -> {
                removeHologram(name);
                crateLocations.remove(name);
                getConfig().set("crates." + name, null);
                saveConfig();
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FB5454Törölve!"));
            }
            case "add" -> {
                if (args.length < 3) return false;
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) return true;
                try {
                    int chance = Integer.parseInt(args[2]);
                    ItemStack toAdd = hand.clone();
                    ItemMeta m = toAdd.getItemMeta();
                    if (m != null) {
                        m.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, chance);
                        toAdd.setItemMeta(m);
                    }
                    crateRewards.computeIfAbsent(name, k -> new ArrayList<>()).add(new CrateItem(toAdd, chance));
                    saveCrate(name);
                    player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FTárgy hozzáadva!"));
                } catch (Exception e) { player.sendMessage("Hiba: Érvénytelen szám!"); }
            }
            case "setkey" -> {
                ItemStack hand = player.getInventory().getItemInMainHand().clone();
                if (hand.getType().isAir()) return true;
                hand.setAmount(1);
                crateKeys.put(name, hand);
                saveCrate(name);
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FKulcs beállítva!"));
            }
            case "edit" -> {
                if (!crateLocations.containsKey(name)) return true;
                Inventory inv = Bukkit.createInventory(null, 54, "Szerkesztés: " + name);
                List<CrateItem> items = crateRewards.get(name);
                if (items != null) for (CrateItem ci : items) inv.addItem(getEditorItem(ci.getItem().clone(), ci.getChance()));
                editingPlayers.put(player.getUniqueId(), name);
                player.openInventory(inv);
            }
            case "holo" -> {
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                getConfig().set("crates." + name + ".hologram", Arrays.asList(text.split("\\|")));
                saveConfig();
                updateHologram(name);
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FHologram frissítve!"));
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Location loc = event.getClickedBlock().getLocation();
        String crateName = null;
        for (Map.Entry<String, Location> entry : crateLocations.entrySet()) {
            if (entry.getValue().equals(loc)) { crateName = entry.getKey(); break; }
        }
        if (crateName != null) {
            event.setCancelled(true);
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) openPreview(event.getPlayer(), crateName);
            else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) startOpening(event.getPlayer(), crateName);
        }
    }

    private void startOpening(Player player, String name) {
        ItemStack reqKey = crateKeys.get(name);
        if (reqKey != null) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType() == Material.AIR || !hand.isSimilar(reqKey)) {
                player.sendMessage(colorize("&#FF5555Ehhez a ládához kulcs kell!"));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return;
            }
            if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
            else player.getInventory().setItemInMainHand(null);
        }
        List<CrateItem> pool = crateRewards.get(name);
        if (pool == null || pool.isEmpty()) return;
        Inventory inv = Bukkit.createInventory(null, 27, colorize("&#FFA500Sorsolás..."));
        player.openInventory(inv);
        new BukkitRunnable() {
            int ticks = 0;
            public void run() {
                if (ticks >= 20) {
                    int total = pool.stream().mapToInt(CrateItem::getChance).sum();
                    int r = random.nextInt(total > 0 ? total : 1), cur = 0;
                    ItemStack win = pool.get(0).getItem().clone();
                    for (CrateItem ci : pool) {
                        cur += ci.getChance();
                        if (r < cur) { win = ci.getItem().clone(); break; }
                    }
                    player.getInventory().addItem(win);
                    player.closeInventory();
                    player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1f, 1f);
                    this.cancel(); return;
                }
                inv.setItem(13, pool.get(random.nextInt(pool.size())).getItem());
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 2f);
                ticks++;
            }
        }.runTaskTimer(this, 0, 2);
    }

    private void updateHologram(String name) {
        removeHologram(name);
        List<String> lines = getConfig().getStringList("crates." + name + ".hologram");
        Location base = crateLocations.get(name);
        if (lines.isEmpty() || base == null) return;
        Location loc = base.clone().add(0.5, 1.2, 0.5);
        List<ArmorStand> stands = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0; i--) {
            ArmorStand as = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(0, (lines.size()-1-i)*0.28, 0), EntityType.ARMOR_STAND);
            as.setVisible(false); as.setGravity(false); as.setMarker(true);
            as.setCustomName(colorize(lines.get(i))); as.setCustomNameVisible(true);
            stands.add(as);
        }
        activeHolograms.put(name, stands);
    }

    private void removeHologram(String name) {
        List<ArmorStand> stands = activeHolograms.remove(name);
        if (stands != null) for (ArmorStand as : stands) as.remove();
    }

    private void saveCrate(String name) {
        List<ItemStack> items = new ArrayList<>();
        if (crateRewards.get(name) != null) for (CrateItem ci : crateRewards.get(name)) items.add(ci.getItem());
        getConfig().set("crates." + name + ".location", crateLocations.get(name));
        getConfig().set("crates." + name + ".items", items);
        getConfig().set("crates." + name + ".key_item", crateKeys.get(name));
        saveConfig();
    }

    // --- KÉT KÜLÖN LORE GENERÁTOR ---
    
    // Csak az esély (Játékosoknak)
    private ItemStack getPreviewItem(ItemStack item, int chance) {
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;
        m.setLore(Arrays.asList(colorize("&#778899&m----------------"), colorize("&#B0C4DEEsély: &#FFD700" + chance + "%")));
        item.setItemMeta(m);
        return item;
    }

    // Esély + Szerkesztési tipp (Adminoknak)
    private ItemStack getEditorItem(ItemStack item, int chance) {
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;
        m.setLore(Arrays.asList(colorize("&#778899&m----------------"), colorize("&#B0C4DEEsély: &#FFD700" + chance + "%"), colorize("&#F0E68CShift+L: Pontos érték")));
        item.setItemMeta(m);
        return item;
    }

    private void openPreview(Player p, String name) {
        List<CrateItem> items = crateRewards.get(name);
        if (items == null) return;
        Inventory inv = Bukkit.createInventory(null, 27, colorize("&#FF8C00" + name + " - Előnézet"));
        for (CrateItem ci : items) inv.addItem(getPreviewItem(ci.getItem().clone(), ci.getChance()));
        p.openInventory(inv);
    }

    private void sendHelp(Player p) {
        p.sendMessage(colorize("&#FF8C00LavaCrates Súgó"));
        p.sendMessage(colorize("&#FFA500/lc create <név> &7- Létrehozás"));
        p.sendMessage(colorize("&#FFA500/lc setkey <név> &7- Kulcs beállítása"));
        p.sendMessage(colorize("&#FFA500/lc add <név> <esély> &7- Tárgy hozzáadása"));
        p.sendMessage(colorize("&#FFA500/lc edit <név> &7- GUI Szerkesztő"));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (title.contains("- Előnézet")) event.setCancelled(true);
        if (title.startsWith("Szerkesztés: ")) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null) return;
            Player p = (Player) event.getWhoClicked();
            if (event.isShiftClick() && event.isLeftClick()) {
                pendingChanceEdit.put(p.getUniqueId(), event.getCurrentItem());
                p.closeInventory();
                p.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FFD700Írd be az új esélyt a chatre:"));
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (editingPlayers.containsKey(id) && !pendingChanceEdit.containsKey(id)) {
            String name = editingPlayers.remove(id);
            List<CrateItem> rewards = new ArrayList<>();
            for (ItemStack is : event.getInventory().getContents()) {
                if (is != null && is.getType() != Material.AIR) {
                    ItemMeta m = is.getItemMeta();
                    int c = (m != null) ? m.getPersistentDataContainer().getOrDefault(chanceKey, PersistentDataType.INTEGER, 100) : 100;
                    rewards.add(new CrateItem(is, c));
                }
            }
            crateRewards.put(name, rewards);
            saveCrate(name);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        if (pendingChanceEdit.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
            try {
                int chance = Integer.parseInt(event.getMessage());
                new BukkitRunnable() {
                    public void run() {
                        ItemStack item = pendingChanceEdit.remove(p.getUniqueId());
                        ItemMeta m = item.getItemMeta();
                        m.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, chance);
                        item.setItemMeta(m);
                        p.performCommand("lc edit " + editingPlayers.get(p.getUniqueId()));
                    }
                }.runTask(this);
            } catch (Exception e) { p.sendMessage("Hiba: Csak számot írj be!"); }
        }
    }
}
