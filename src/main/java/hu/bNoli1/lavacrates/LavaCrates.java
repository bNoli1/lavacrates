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
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
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
        
        getLogger().info("LavaCrates (HEX support) sikeresen elindult!");
    }

    @Override
    public void onDisable() {
        activeHolograms.values().forEach(stands -> stands.forEach(ArmorStand::remove));
    }

    private String colorize(String message) {
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
                    ItemStack is = (ItemStack) obj;
                    ItemMeta meta = is.getItemMeta();
                    int chance = (meta != null) ? meta.getPersistentDataContainer().getOrDefault(chanceKey, PersistentDataType.INTEGER, 100) : 100;
                    items.add(new CrateItem(is, chance));
                }
            }
            crateRewards.put(key, items);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (!player.hasPermission("lavacrates.admin")) {
            player.sendMessage(colorize("&#FF5555LavaCrates &8» &#FB5454Nincs jogosultságod!"));
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
            player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FConfig és hologramok újratöltve!"));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            return true;
        }

        if (args.length < 2) return false;
        String name = args[1].toLowerCase();

        switch (action) {
            case "create" -> {
                Block b = player.getTargetBlockExact(5);
                if (b != null && b.getType() == Material.CHEST) {
                    crateLocations.put(name, b.getLocation());
                    crateRewards.putIfAbsent(name, new ArrayList<>());
                    saveCrate(name);
                    updateHologram(name);
                    player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FLáda létrehozva: &#FFD700" + name));
                }
            }
            case "edit" -> {
                if (!crateLocations.containsKey(name)) return true;
                Inventory inv = Bukkit.createInventory(null, 54, "Szerkesztés: " + name);
                crateRewards.get(name).forEach(ci -> inv.addItem(updateLore(ci.item.clone(), ci.chance)));
                editingPlayers.put(player.getUniqueId(), name);
                player.openInventory(inv);
            }
            case "setkey" -> {
                ItemStack hand = player.getInventory().getItemInMainHand().clone();
                if (hand.getType().isAir()) return true;
                hand.setAmount(1);
                crateKeys.put(name, hand);
                saveCrate(name);
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FKulcs beállítva!"));
            }
            case "getkey" -> {
                if (!crateKeys.containsKey(name)) return true;
                player.getInventory().addItem(crateKeys.get(name).clone());
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FKulcs megkapva!"));
            }
            case "keyall" -> {
                if (!crateKeys.containsKey(name)) return true;
                int amount = (args.length > 2) ? Integer.parseInt(args[2]) : 1;
                ItemStack k = crateKeys.get(name).clone(); k.setAmount(amount);
                Bukkit.getOnlinePlayers().forEach(p -> p.getInventory().addItem(k.clone()));
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FKulcsosztás kész!"));
            }
            case "add" -> {
                if (args.length < 3) return true;
                int chance = Integer.parseInt(args[2]);
                ItemStack item = player.getInventory().getItemInMainHand().clone();
                if (item.getType() == Material.AIR) return true;
                ItemMeta m = item.getItemMeta();
                m.getPersistentDataContainer().set(chanceKey, PersistentDataType.INTEGER, chance);
                item.setItemMeta(m);
                crateRewards.get(name).add(new CrateItem(item, chance));
                saveCrate(name);
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FTárgy hozzáadva (" + chance + "%)"));
            }
            case "holo" -> {
                if (args.length < 3) return true;
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                getConfig().set("crates." + name + ".hologram", Arrays.asList(text.split("\\|")));
                saveConfig();
                updateHologram(name);
                player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FHologram frissítve!"));
            }
        }
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage(colorize("&#FF8C00&l&m   &r &#FFD700&l LavaCrates Súgó &#FF8C00&l&m   "));
        p.sendMessage(colorize("&#FFA500/lc create <név> &8- &7Láda létrehozása"));
        p.sendMessage(colorize("&#FFA500/lc edit <név> &8- &7GUI szerkesztő"));
        p.sendMessage(colorize("&#FFA500/lc add <név> <esély> &8- &7Tárgy hozzáadása"));
        p.sendMessage(colorize("&#FFA500/lc holo <név> <sor1|sor2> &8- &7Hologram"));
        p.sendMessage(colorize("&#FFA500/lc keyall <név> [db] &8- &7Kulcsosztás mindenkinek"));
        p.sendMessage(colorize("&#FFA500/lc reload &8- &7Újratöltés"));
        p.sendMessage(colorize("&#FF8C00&l&m                        "));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (editingPlayers.containsKey(event.getPlayer().getUniqueId())) {
            String name = editingPlayers.remove(event.getPlayer().getUniqueId());
            List<CrateItem> newRewards = new ArrayList<>();
            for (ItemStack is : event.getInventory().getContents()) {
                if (is != null && is.getType() != Material.AIR) {
                    ItemMeta m = is.getItemMeta();
                    int chance = (m != null) ? m.getPersistentDataContainer().getOrDefault(chanceKey, PersistentDataType.INTEGER, 100) : 100;
                    newRewards.add(new CrateItem(is, chance));
                }
            }
            crateRewards.put(name, newRewards);
            saveCrate(name);
            event.getPlayer().sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FTartalom mentve!"));
        }
    }

    // --- További metódusok (Hologram, Sorsolás, Mentés, HEX Lore) ---
    private void updateHologram(String name) {
        removeHologram(name);
        List<String> lines = getConfig().getStringList("crates." + name + ".hologram");
        if (lines.isEmpty() || !crateLocations.containsKey(name)) return;
        Location loc = crateLocations.get(name).clone().add(0.5, 1.2, 0.5);
        List<ArmorStand> stands = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0; i--) {
            ArmorStand as = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(0, (lines.size()-1-i)*0.28, 0), EntityType.ARMOR_STAND);
            as.setVisible(false); as.setGravity(false); as.setMarker(true);
            as.setCustomName(colorize(lines.get(i)));
            as.setCustomNameVisible(true);
            stands.add(as);
        }
        activeHolograms.put(name, stands);
    }

    private void removeHologram(String name) {
        if (activeHolograms.containsKey(name)) {
            activeHolograms.get(name).forEach(ArmorStand::remove);
            activeHolograms.remove(name);
        }
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
        lore.add(colorize("&#F0E68CBal klikk: +5% | Jobb klikk: -5%"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void startOpening(Player player, String name) {
        List<CrateItem> pool = crateRewards.get(name);
        if (pool == null || pool.isEmpty()) return;
        Inventory inv = Bukkit.createInventory(null, 27, colorize("&#FFA500Sorsolás..."));
        player.openInventory(inv);
        new BukkitRunnable() {
            int ticks = 0;
            public void run() {
                if (ticks >= 25) {
                    ItemStack win = getWeightedItem(pool);
                    player.getInventory().addItem(win.clone());
                    player.closeInventory();
                    player.sendMessage(colorize("&#FF8C00LavaCrates &8» &#00FF7FGratulálunk!"));
                    this.cancel(); return;
                }
                inv.setItem(13, pool.get(random.nextInt(pool.size())).item);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 2f);
                ticks++;
            }
        }.runTaskTimer(this, 0, 2);
    }

    private ItemStack getWeightedItem(List<CrateItem> items) {
        int total = items.stream().mapToInt(CrateItem::chance).sum();
        int r = random.nextInt(total);
        int cur = 0;
        for (CrateItem ci : items) {
            cur += ci.chance();
            if (r < cur) return ci.item;
        }
        return items.get(0).item;
    }
}
