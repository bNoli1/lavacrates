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

    private static record CrateItem(ItemStack item, int chance) {}

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
                crateLocations.keySet().forEach(LavaCrates.this::updateHologram);
            }
        }.runTaskLater(this, 20L);
    }

    @Override
    public void onDisable() {
        activeHolograms.values().forEach(stands -> stands.forEach(ArmorStand::remove));
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
            crateLocations.put(key, config.getLocation("crates." + key + ".location"));
            crateKeys.put(key, config.getItemStack("crates." + key + ".key_item"));
            
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
            player.sendMessage(colorize("&#FF5555Ehhez nincs jogosultságod!"));
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
            activeHolograms.keySet().forEach(this::removeHologram);
            crateLocations.keySet().forEach(this::updateHologram);
            player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FKonfiguráció újratöltve!"));
            return true;
        }

        if (args.length < 2) return false;
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
                if (!crateLocations.containsKey(name)) {
                    player.sendMessage(colorize("&#FF5555Hiba: Nem létezik '" + name + "' nevű láda!"));
                    return true;
                }
                removeHologram(name);
                crateLocations.remove(name);
                crateRewards.remove(name);
                crateKeys.remove(name);
                getConfig().set("crates." + name, null);
                saveConfig();
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#FB5454Láda véglegesen törölve!"));
            }
            case "setlocation" -> {
                Block b = player.getTargetBlockExact(5);
                if (b != null && b.getType() != Material.AIR) {
                    crateLocations.put(name, b.getLocation());
                    saveCrate(name);
                    updateHologram(name);
                    player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FÚj helyszín elmentve!"));
                }
            }
            case "add" -> {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType().isAir() || args.length < 3) return false;
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
                    player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FTárgy hozzáadva (" + chance + "%)."));
                } catch (Exception e) { player.sendMessage(colorize("&#FF5555Hiba: Érvénytelen szám!")); }
            }
            case "edit" -> {
                if (!crateLocations.containsKey(name)) return true;
                Inventory inv = Bukkit.createInventory(null, 54, "Szerkesztés: " + name);
                List<CrateItem> items = crateRewards.get(name);
                if (items != null) items.forEach(ci -> inv.addItem(updateLore(ci.item.clone(), ci.chance)));
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
                ItemStack hand = player.getInventory().getItemInMainHand().clone();
                if (hand.getType().isAir()) return true;
                hand.setAmount(1);
                crateKeys.put(name, hand);
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
        for (var entry : crateLocations.entrySet()) {
            if (entry.getValue().equals(loc)) {
                event.setCancelled(true);
                if (event.getAction() == Action.LEFT_CLICK_BLOCK) openPreview(event.getPlayer(), entry.getKey());
                else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) startOpening(event.getPlayer(), entry.getKey());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        for (Location cloc : crateLocations.values()) {
            if (cloc.equals(loc)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(colorize("&#FF5555Ez egy védett LavaCrate! Törlés: /lc delete"));
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        event.getPlayer().sendBlockChange(loc, loc.getBlock().getBlockData());
                    }
                }.runTaskLater(this, 1L);
                return;
            }
        }
    }

    private void openPreview(Player player, String name) {
        List<CrateItem> items = crateRewards.get(name);
        if (items == null || items.isEmpty()) {
            player.sendMessage(colorize("&#FF5555Ez a láda még üres!"));
            return;
        }
        Inventory inv = Bukkit.createInventory(null, 27, colorize("&#FF8C00" + name + " - Előnézet"));
        for (CrateItem ci : items) {
            ItemStack is = ci.item.clone();
            ItemMeta m = is.getItemMeta();
            if (m != null) {
                List<String> lore = m.hasLore() ? m.getLore() : new ArrayList<>();
                lore.add("");
                lore.add(colorize("&#B0C4DEEsély: &#FFD700" + ci.chance + "%"));
                m.setLore(lore);
                is.setItemMeta(m);
            }
            inv.addItem(is);
        }
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f);
    }

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

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        if (!pendingChanceEdit.containsKey(p.getUniqueId())) return;
        event.setCancelled(true);
        try {
            int chance = Integer.parseInt(event.getMessage().trim());
            new BukkitRunnable() {
                @Override
                public void run() {
                    ItemStack item = pendingChanceEdit.remove(p.getUniqueId());
                    ItemMeta m = item.getItemMeta();
                    if (m != null) {
                        m.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, Math.max(1, Math.min(100, chance)));
                        item.setItemMeta(m);
                        updateLore(item, chance);
                    }
                    p.performCommand("lc edit " + editingPlayers.get(p.getUniqueId()));
                }
            }.runTask(this);
        } catch (Exception e) { p.sendMessage(colorize("&#FF5555Hiba: Érvénytelen szám!")); }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (editingPlayers.containsKey(uuid) && !pendingChanceEdit.containsKey(uuid)) {
            String name = editingPlayers.remove(uuid);
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

    private void startOpening(Player player, String name) {
        List<CrateItem> pool = crateRewards.get(name);
        if (pool == null || pool.isEmpty()) {
            player.sendMessage(colorize("&#FF5555Ez a láda üres!"));
            return;
        }
        
        ItemStack key = crateKeys.get(name);
        if (key != null) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (!hand.isSimilar(key)) { 
                player.sendMessage(colorize("&#FF5555Szükséged van egy kulcsra a nyitáshoz!")); 
                return; 
            }
            if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
            else player.getInventory().setItemInMainHand(null);
        }

        Inventory inv = Bukkit.createInventory(null, 27, colorize("&#FFA500Sorsolás..."));
        player.openInventory(inv);
        
        new BukkitRunnable() {
            int ticks = 0;
            public void run() {
                if (ticks >= 25) {
                    int total = pool.stream().mapToInt(CrateItem::chance).sum();
                    int r = random.nextInt(total == 0 ? 1 : total), cur = 0;
                    ItemStack win = pool.get(0).item.clone();
                    for (CrateItem ci : pool) { cur += ci.chance; if (r < cur) { win = ci.item.clone(); break; } }
                    
                    ItemMeta m = win.getItemMeta();
                    if (m != null && m.hasLore()) {
                        List<String> l = m.getLore();
                        if (l.size() >= 3) {
                            l.remove(l.size()-1); l.remove(l.size()-1); l.remove(l.size()-1);
                            if(!l.isEmpty() && l.get(l.size()-1).isEmpty()) l.remove(l.size()-1);
                        }
                        m.setLore(l); win.setItemMeta(m);
                    }
                    
                    player.getInventory().addItem(win);
                    player.closeInventory();
                    player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FGratulálunk! Nyertél!"));
                    player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1f, 1f);
                    this.cancel(); return;
                }
                inv.setItem(13, pool.get(random.nextInt(pool.size())).item);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 2f);
                ticks++;
            }
        }.runTaskTimer(this, 0, 2);
    }

    private void updateHologram(String name) {
        removeHologram(name);
        List<String> lines = getConfig().getStringList("crates." + name + ".hologram");
        if (lines.isEmpty() || !crateLocations.containsKey(name)) return;
        Location loc = crateLocations.get(name).clone().add(0.5, 1.2, 0.5);
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
        if (activeHolograms.containsKey(name)) { activeHolograms.get(name).forEach(ArmorStand::remove); activeHolograms.remove(name); }
    }

    private void saveCrate(String name) {
        List<ItemStack> toSave = new ArrayList<>();
        if (crateRewards.get(name) != null) crateRewards.get(name).forEach(ci -> toSave.add(ci.item));
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

    private void sendHelp(Player p) {
        p.sendMessage(colorize(""));
        p.sendMessage(colorize("&#FF8C00&l LavaCrates &7- &#FFD700Adminisztrátori Segítség"));
        p.sendMessage(colorize("&#778899&m------------------------------------------"));
        
        p.sendMessage(colorize("&#FFA500/lc create <név>"));
        p.sendMessage(colorize("  &8» &fLétrehoz egy új ládát azon a blokkon, amire nézel."));
        
        p.sendMessage(colorize("&#FFA500/lc delete <név>"));
        p.sendMessage(colorize("  &8» &fVéglegesen törli a ládát és minden adatát."));
        
        p.sendMessage(colorize("&#FFA500/lc edit <név>"));
        p.sendMessage(colorize("  &8» &fSzerkesztő felület megnyitása (tárgyak + esélyek)."));
        
        p.sendMessage(colorize("&#FFA500/lc add <név> <esély>"));
        p.sendMessage(colorize("  &8» &fA kezedben lévő tárgy hozzáadása eséllyel."));
        
        p.sendMessage(colorize("&#FFA500/lc setlocation <név>"));
        p.sendMessage(colorize("  &8» &fLáda áthelyezése a nézett blokkra."));
        
        p.sendMessage(colorize("&#FFA500/lc setkey <név>"));
        p.sendMessage(colorize("  &8» &fKézben tartott tárgy beállítása kulcsként."));
        
        p.sendMessage(colorize("&#FFA500/lc holo <név> <szöveg>"));
        p.sendMessage(colorize("  &8» &fHologram szerkesztése (sorváltás: &e|&f)."));
        
        p.sendMessage(colorize("&#FFA500/lc reload"));
        p.sendMessage(colorize("  &8» &fKonfiguráció és hologramok újratöltése."));
        
        p.sendMessage(colorize("&#778899&m------------------------------------------"));
        p.sendMessage(colorize("&7Tipp: Használj HEX kódokat (&#RRGGBB) a színekhez!"));
    }
}
