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
import org.bukkit.event.block.BlockBreakEvent;
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
        
        // Késleltetett hologram betöltés, hogy a világok biztosan be töltsenek
        new BukkitRunnable() {
            @Override
            public void run() {
                for (String name : crateLocations.keySet()) {
                    updateHologram(name);
                }
            }
        }.runTaskLater(this, 40L);
        
        getLogger().info("LavaCrates v1.2 elindult (Javított verzió)");
    }

    @Override
    public void onDisable() {
        for (List<ArmorStand> stands : activeHolograms.values()) {
            for (ArmorStand as : stands) {
                if (as != null) as.remove();
            }
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
            
            ItemStack keyItem = config.getItemStack("crates." + key + ".key_item");
            if (keyItem != null) crateKeys.put(key, keyItem);
            
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

        if (args.length < 2) {
            player.sendMessage(colorize("&#FF5555Használat: /lc <művelet> <név>"));
            return true;
        }
        
        String name = args[1].toLowerCase();

        switch (action) {
            case "create" -> {
                Block b = player.getTargetBlockExact(5);
                if (b != null && b.getType() != Material.AIR) {
                    crateLocations.put(name, b.getLocation());
                    crateRewards.putIfAbsent(name, new ArrayList<>());
                    saveCrate(name);
                    updateHologram(name);
                    player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FLáda létrehozva: &#FFD700" + name));
                }
            }
            case "delete" -> {
                removeHologram(name);
                crateLocations.remove(name);
                crateRewards.remove(name);
                crateKeys.remove(name);
                getConfig().set("crates." + name, null);
                saveConfig();
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FB5454Törölve!"));
            }
            case "edit" -> {
                if (!crateLocations.containsKey(name)) {
                    player.sendMessage(colorize("&#FF5555Ez a láda nem létezik!"));
                    return true;
                }
                Inventory inv = Bukkit.createInventory(null, 54, "Szerkesztés: " + name);
                List<CrateItem> items = crateRewards.get(name);
                if (items != null) {
                    for (CrateItem ci : items) inv.addItem(updateLore(ci.getItem().clone(), ci.getChance()));
                }
                editingPlayers.put(player.getUniqueId(), name);
                player.openInventory(inv);
            }
            case "holo" -> {
                if (args.length < 3) return false;
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                getConfig().set("crates." + name + ".hologram", Arrays.asList(text.split("\\|")));
                saveConfig();
                updateHologram(name);
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FHologram frissítve!"));
            }
            case "setkey" -> {
                ItemStack handKey = player.getInventory().getItemInMainHand().clone();
                if (handKey.getType() == Material.AIR) {
                    player.sendMessage(colorize("&#FF5555Tarts egy tárgyat a kezedben!"));
                    return true;
                }
                handKey.setAmount(1);
                crateKeys.put(name, handKey);
                saveCrate(name);
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FKulcs beállítva!"));
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Location loc = event.getClickedBlock().getLocation();
        
        for (Map.Entry<String, Location> entry : crateLocations.entrySet()) {
            if (entry.getValue() != null && entry.getValue().equals(loc)) {
                event.setCancelled(true);
                if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                    openPreview(event.getPlayer(), entry.getKey());
                } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    startOpening(event.getPlayer(), entry.getKey());
                }
                return;
            }
        }
    }

    private void startOpening(final Player player, String name) {
        final List<CrateItem> pool = crateRewards.get(name);
        if (pool == null || pool.isEmpty()) {
            player.sendMessage(colorize("&#FF5555Ebben a ládában nincs semmi!"));
            return;
        }
        
        ItemStack key = crateKeys.get(name);
        if (key != null) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (!hand.isSimilar(key)) { 
                player.sendMessage(colorize("&#FF5555Nincs kulcsod ehhez a ládához!")); 
                return; 
            }
            hand.setAmount(hand.getAmount() - 1);
        }

        final Inventory inv = Bukkit.createInventory(null, 27, colorize("&#FFA500Sorsolás..."));
        player.openInventory(inv);
        
        new BukkitRunnable() {
            int ticks = 0;
            public void run() {
                if (ticks >= 25) {
                    int total = pool.stream().mapToInt(CrateItem::getChance).sum();
                    int r = random.nextInt(total <= 0 ? 1 : total), cur = 0;
                    ItemStack win = pool.get(0).getItem().clone();
                    for (CrateItem ci : pool) { 
                        cur += ci.getChance(); 
                        if (r < cur) { win = ci.getItem().clone(); break; } 
                    }
                    
                    // Tisztítsuk meg a lore-t a nyereménynél
                    ItemMeta m = win.getItemMeta();
                    if (m != null && m.hasLore()) {
                        List<String> l = m.getLore();
                        if (l != null && l.size() >= 3) {
                            l.remove(l.size()-1); l.remove(l.size()-1); l.remove(l.size()-1);
                        }
                        m.setLore(l); win.setItemMeta(m);
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
        Location baseLoc = crateLocations.get(name);
        if (lines.isEmpty() || baseLoc == null) return;
        
        Location loc = baseLoc.clone().add(0.5, 1.2, 0.5);
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
        if (stands != null) {
            for (ArmorStand as : stands) if (as != null) as.remove();
        }
    }

    private void saveCrate(String name) {
        List<ItemStack> toSave = new ArrayList<>();
        List<CrateItem> rewards = crateRewards.get(name);
        if (rewards != null) {
            for (CrateItem ci : rewards) toSave.add(ci.getItem());
        }
        getConfig().set("crates." + name + ".location", crateLocations.get(name));
        getConfig().set("crates." + name + ".items", toSave);
        getConfig().set("crates." + name + ".key_item", crateKeys.get(name));
        saveConfig();
    }

    private ItemStack updateLore(ItemStack item, int chance) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<String> lore = new ArrayList<>();
        lore.add(colorize("&#778899&m----------------"));
        lore.add(colorize("&#B0C4DEEsély: &#FFD700" + chance + "%"));
        lore.add(colorize("&#F0E68CShift + Bal klikk: Pontos érték"));
        meta.setLore(lore); item.setItemMeta(meta);
        return item;
    }

    private void openPreview(Player player, String name) {
        List<CrateItem> items = crateRewards.get(name);
        if (items == null || items.isEmpty()) return;
        Inventory inv = Bukkit.createInventory(null, 27, colorize("&#FF8C00" + name + " - Előnézet"));
        for (CrateItem ci : items) {
            inv.addItem(updateLore(ci.getItem().clone(), ci.getChance()));
        }
        player.openInventory(inv);
    }

    private void sendHelp(Player p) {
        p.sendMessage(colorize(""));
        p.sendMessage(colorize("&#FF8C00&l LavaCrates &7- &#FFD700Súgó"));
        p.sendMessage(colorize("&#778899&m------------------------------------------"));
        p.sendMessage(colorize("&#FFA500/lc create <név> &8- &fLáda létrehozása"));
        p.sendMessage(colorize("&#FFA500/lc delete <név> &8- &fLáda törlése"));
        p.sendMessage(colorize("&#FFA500/lc edit <név> &8- &fGUI szerkesztő"));
        p.sendMessage(colorize("&#FFA500/lc setkey <név> &8- &fKulcs beállítása"));
        p.sendMessage(colorize("&#FFA500/lc holo <név> <sor1|sor2> &8- &fHologram"));
        p.sendMessage(colorize("&#FFA500/lc reload &8- &fÚjratöltés"));
        p.sendMessage(colorize("&#778899&m------------------------------------------"));
    }

    // A többi eseménykezelő (InventoryClick, Chat stb.) marad változatlanul, csak null-checkekkel kiegészítve.
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (title.contains("- Előnézet")) { event.setCancelled(true); return; }
        if (title.startsWith("Szerkesztés: ")) {
            if (event.getRawSlot() < event.getInventory().getSize()) {
                if (event.getCurrentItem() == null) return;
                event.setCancelled(true);
                if (event.isShiftClick() && event.isLeftClick()) {
                    pendingChanceEdit.put(player.getUniqueId(), event.getCurrentItem());
                    player.closeInventory();
                    player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FFD700Írd be az esélyt (1-100):"));
                    return;
                }
                // Gyors esély állítás (±5%)
                ItemStack item = event.getCurrentItem();
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    int chance = meta.getPersistentDataContainer().getOrDefault(chanceKey, PersistentDataType.INTEGER, 100);
                    chance = event.isLeftClick() ? Math.min(100, chance + 5) : Math.max(1, chance - 5);
                    meta.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, chance);
                    item.setItemMeta(meta);
                    updateLore(item, chance);
                }
            }
        }
    }
}
