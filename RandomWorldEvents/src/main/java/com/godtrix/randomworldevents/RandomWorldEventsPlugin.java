package com.godtrix.randomworldevents;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

public final class RandomWorldEventsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Random random = new Random();
    private final Map<UUID, Double> savedMaxHealth = new HashMap<>();
    private final Map<UUID, Integer> savedFood = new HashMap<>();
    private final Set<Location> supplyChests = new HashSet<>();
    private Economy economy;
    private EventType activeEvent;
    private int loopTask = -1;
    private int endTask = -1;
    private int supplyTask = -1;
    private long eventEndsAt;
    private boolean running;
    private Team anonymousTeam;

    private enum EventType {
        EXHAUSTED("Exhausted", "Mining is tiring: mining fatigue is active."),
        STARVATION("Starvation", "Hunger drains faster and food is harder to keep."),
        DOUBLE_DROPS("Double Drops", "Hostile and passive mobs drop twice their usual loot."),
        FORBIDDEN_FRUIT("Forbidden Fruit", "Leaves have a chance to drop golden apples."),
        MINERS_HAVEN("Miner's Haven", "Mining ores may give bonus drops."),
        VULNERABLE("Vulnerable", "Everyone has reduced maximum health."),
        ANONYMOUS("Anonymous", "Player nametags are hidden."),
        ULTRA_HARDCORE("Ultra Hardcore", "Natural health regeneration is disabled."),
        FLIMSY_ARMOR("Flimsy Armor", "Equipped armor loses extra durability when you take damage."),
        GLOW_AND_BEHOLD("Glow and Behold", "Everyone glows, making hiding difficult."),
        SHIELDLESS("Shieldless", "Shields cannot be used."),
        SUPPLY_RUSH("Supply Rush", "Supply chests appear around the world with useful loot.");
        final String title, description;
        EventType(String title, String description) { this.title = title; this.description = description; }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = Objects.requireNonNull(getCommand("randomworldevents"));
        command.setExecutor(this);
        command.setTabCompleter(this);
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
        setupAnonymousTeam();
        if (getConfig().getBoolean("settings.auto-start", true)) startLoop();
        getLogger().info("RandomWorldEvents enabled. Vault economy: " + (economy != null));
    }

    @Override public void onDisable() {
        if (loopTask != -1) Bukkit.getScheduler().cancelTask(loopTask);
        if (endTask != -1) Bukkit.getScheduler().cancelTask(endTask);
        endEvent(false);
    }

    private void setupAnonymousTeam() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        anonymousTeam = board.getTeam("me_anonymous");
        if (anonymousTeam == null) anonymousTeam = board.registerNewTeam("me_anonymous");
        anonymousTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void broadcast(String message) {
        Bukkit.broadcastMessage(color(getConfig().getString("settings.broadcast-prefix", "&6&lRANDOM WORLD EVENTS &8» &r") + message));
    }

    private void startLoop() {
        running = true;
        if (loopTask != -1) Bukkit.getScheduler().cancelTask(loopTask);
        long interval = Math.max(60, getConfig().getLong("settings.interval-seconds", 900)) * 20L;
        loopTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::startRandomEvent, interval, interval);
        Bukkit.getScheduler().runTaskLater(this, this::startRandomEvent, 100L);
    }

    private void startRandomEvent() {
        if (!running || activeEvent != null || Bukkit.getOnlinePlayers().isEmpty()) return;
        EventType[] values = EventType.values();
        startEvent(values[random.nextInt(values.length)]);
    }

    private void startEvent(EventType type) {
        if (activeEvent != null) endEvent(false);
        activeEvent = type;
        long configuredDuration = type == EventType.SUPPLY_RUSH
                ? getConfig().getLong("settings.supply-rush-duration-seconds", 600)
                : getConfig().getLong("settings.event-duration-seconds", 180);
        long durationSeconds = Math.max(30, configuredDuration);
        eventEndsAt = System.currentTimeMillis() + durationSeconds * 1000L;
        broadcast("&e&l" + type.title.toUpperCase(Locale.ROOT) + " &ahas started! &7" + type.description);
        for (Player p : Bukkit.getOnlinePlayers()) applyEvent(p, type);
        if (type == EventType.SUPPLY_RUSH) spawnSupplyChests();
        long durationTicks = durationSeconds * 20L;
        endTask = Bukkit.getScheduler().runTaskLater(this, () -> endEvent(true), durationTicks).getTaskId();
    }

    private void applyEvent(Player p, EventType type) {
        switch (type) {
            case EXHAUSTED -> p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, remainingTicks(), 0, false, true, true));
            case STARVATION -> { p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, remainingTicks(), 1, false, true, true)); savedFood.putIfAbsent(p.getUniqueId(), p.getFoodLevel()); }
            case VULNERABLE -> {
                if (p.getAttribute(Attribute.MAX_HEALTH) != null) {
                    savedMaxHealth.putIfAbsent(p.getUniqueId(), p.getAttribute(Attribute.MAX_HEALTH).getBaseValue());
                    p.getAttribute(Attribute.MAX_HEALTH).setBaseValue(10.0);
                    if (p.getHealth() > 10.0) p.setHealth(10.0);
                }
            }
            case ANONYMOUS -> anonymousTeam.addEntry(p.getName());
            case ULTRA_HARDCORE -> p.setSaturation(0);
            case GLOW_AND_BEHOLD -> p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, remainingTicks(), 0, false, true, true));
            default -> { }
        }
    }

    private int remainingTicks() { return (int)Math.max(600, (eventEndsAt - System.currentTimeMillis()) / 50L); }

    private void endEvent(boolean announce) {
        EventType ended = activeEvent;
        activeEvent = null;
        if (ended == EventType.SUPPLY_RUSH) {
            if (supplyTask != -1) { Bukkit.getScheduler().cancelTask(supplyTask); supplyTask = -1; }
            cleanEmptySupplyChests();
        }
        if (endTask != -1) { Bukkit.getScheduler().cancelTask(endTask); endTask = -1; }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(PotionEffectType.MINING_FATIGUE);
            p.removePotionEffect(PotionEffectType.HUNGER);
            p.removePotionEffect(PotionEffectType.GLOWING);
            Double max = savedMaxHealth.remove(p.getUniqueId());
            if (max != null && p.getAttribute(Attribute.MAX_HEALTH) != null) {
                p.getAttribute(Attribute.MAX_HEALTH).setBaseValue(max);
                if (p.getHealth() > max) p.setHealth(max);
            }
            Integer food = savedFood.remove(p.getUniqueId()); if (food != null) p.setFoodLevel(food);
            if (anonymousTeam != null) anonymousTeam.removeEntry(p.getName());
        }
        if (announce && ended != null) broadcast("&a" + ended.title + " has ended!");
    }

    private void spawnSupplyChests() {
        // Spawn exactly three chests in a 3,000 x 3,000 block square around a randomly chosen online player.
        if (supplyTask != -1) {
            Bukkit.getScheduler().cancelTask(supplyTask);
            supplyTask = -1;
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;
        Player anchor = players.get(random.nextInt(players.size()));
        Location center = anchor.getLocation().clone();
        int chestCount = Math.max(1, getConfig().getInt("settings.supply-rush-chest-count", 3));
        int areaSize = Math.max(100, getConfig().getInt("settings.supply-rush-area-size-blocks", 3000));
        for (int i = 0; i < chestCount; i++) {
            spawnSupplyChest(center, areaSize);
        }
    }

    private void spawnSupplyChest(Location center, int areaSize) {
        if (activeEvent != EventType.SUPPLY_RUSH || center.getWorld() == null) return;
        Location chestLoc = null;
        World world = center.getWorld();
        int radius = areaSize / 2;
        for (int attempt = 0; attempt < 100 && chestLoc == null; attempt++) {
            int x = center.getBlockX() + random.nextInt(areaSize) - radius;
            int z = center.getBlockZ() + random.nextInt(areaSize) - radius;
            Location candidate = world.getHighestBlockAt(x, z).getLocation().add(0, 1, 0);
            if (!candidate.getBlock().isEmpty() || !candidate.clone().add(0, 1, 0).getBlock().isEmpty()) continue;
            Biome biome = candidate.getBlock().getBiome();
            if (biome == Biome.OCEAN || biome == Biome.DEEP_OCEAN) continue;
            boolean tooClose = supplyChests.stream().anyMatch(existing -> existing.getWorld().equals(world)
                    && existing.distanceSquared(candidate) < 25);
            if (tooClose) continue;
            chestLoc = candidate;
        }
        if (chestLoc == null) {
            broadcast("&cSupply Rush couldn't find a safe place for a chest this round.");
            return;
        }
        chestLoc.getBlock().setType(Material.CHEST);
        if (!(chestLoc.getBlock().getState() instanceof org.bukkit.block.Chest chest)) {
            chestLoc.getBlock().setType(Material.AIR);
            return;
        }
        fillSupplyChest(chest.getBlockInventory());
        supplyChests.add(chestLoc.clone());
        chestLoc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, chestLoc.clone().add(0.5, 1, 0.5), 12, 0.35, 0.35, 0.35, 0.02);
        chestLoc.getWorld().playSound(chestLoc, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.2f);
        broadcast("&eSupply Rush chest: &f" + chestLoc.getWorld().getName() + " &7X: &f" + chestLoc.getBlockX()
                + " &7Y: &f" + chestLoc.getBlockY() + " &7Z: &f" + chestLoc.getBlockZ()
                + " &e— go claim it!");
    }

    private void fillSupplyChest(org.bukkit.inventory.Inventory inventory) {
        Material[] loot = {Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND,
                Material.EMERALD, Material.COOKED_BEEF, Material.EXPERIENCE_BOTTLE, Material.ARROW,
                Material.ENDER_PEARL, Material.OBSIDIAN, Material.GOLDEN_CARROT};
        int slots = 4 + random.nextInt(5);
        for (int i = 0; i < slots; i++) {
            Material item = loot[random.nextInt(loot.length)];
            int amount = switch (item) {
                case IRON_INGOT, ARROW, EXPERIENCE_BOTTLE -> 4 + random.nextInt(13);
                case GOLD_INGOT, COOKED_BEEF, GOLDEN_CARROT -> 2 + random.nextInt(7);
                case OBSIDIAN -> 2 + random.nextInt(5);
                default -> 1 + random.nextInt(3);
            };
            inventory.setItem(random.nextInt(inventory.getSize()), new ItemStack(item, amount));
        }

        // Every supply chest contains a full stack of golden apples and can contain up to two maxed armor pieces.
        inventory.setItem(random.nextInt(inventory.getSize()), new ItemStack(Material.GOLDEN_APPLE, 32));
        int gearPieces = random.nextInt(3); // 0, 1, or 2 pieces
        List<Material> armorTypes = new ArrayList<>(List.of(Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS));
        Collections.shuffle(armorTypes, random);
        for (int i = 0; i < gearPieces; i++) {
            ItemStack gear = makeMaxedArmor(armorTypes.get(i));
            int slot;
            do { slot = random.nextInt(inventory.getSize()); } while (inventory.getItem(slot) != null);
            inventory.setItem(slot, gear);
        }
    }

    private ItemStack makeMaxedArmor(Material material) {
        ItemStack item = new ItemStack(material);
        item.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        item.addUnsafeEnchantment(Enchantment.MENDING, 1);
        item.addUnsafeEnchantment(Enchantment.THORNS, 3);
        switch (material) {
            case NETHERITE_HELMET -> {
                item.addUnsafeEnchantment(Enchantment.RESPIRATION, 3);
                item.addUnsafeEnchantment(Enchantment.AQUA_AFFINITY, 1);
            }
            case NETHERITE_LEGGINGS -> item.addUnsafeEnchantment(Enchantment.SWIFT_SNEAK, 3);
            case NETHERITE_BOOTS -> {
                item.addUnsafeEnchantment(Enchantment.FEATHER_FALLING, 4);
                item.addUnsafeEnchantment(Enchantment.DEPTH_STRIDER, 3);
                item.addUnsafeEnchantment(Enchantment.SOUL_SPEED, 3);
            }
            default -> { }
        }
        return item;
    }

    private void cleanEmptySupplyChests() {
        for (Location location : new HashSet<>(supplyChests)) {
            if (location.getBlock().getState() instanceof org.bukkit.block.Chest chest
                    && chest.getBlockInventory().isEmpty()) {
                location.getBlock().setType(Material.AIR);
            }
        }
        supplyChests.clear();
    }

    private void pay(Player p, double amount, String reason) {
        if (economy == null) { p.sendMessage(color("&cRandomWorldEvents: install Vault and an economy plugin to use money rewards.")); return; }
        economy.depositPlayer(p, amount);
        broadcast("&a" + p.getName() + " won $" + String.format(Locale.US, "%.2f", amount) + " &7(" + reason + ")");
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { if (activeEvent != null) applyEvent(e.getPlayer(), activeEvent); }

    @EventHandler public void onBlockBreak(BlockBreakEvent e) {
        if (activeEvent == EventType.FORBIDDEN_FRUIT && e.getBlock().getType().name().endsWith("LEAVES") && random.nextDouble() < 0.08) {
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), new ItemStack(Material.GOLDEN_APPLE));
        }
        if (activeEvent == EventType.MINERS_HAVEN && e.getBlock().getType().name().endsWith("_ORE") && !e.getBlock().getType().name().equals("IRON_ORE") && !e.getBlock().getType().name().equals("GOLD_ORE") && random.nextDouble() < 0.35) {
            Material drop = switch (e.getBlock().getType()) {
                case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> Material.DIAMOND;
                case EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> Material.EMERALD;
                case LAPIS_ORE, DEEPSLATE_LAPIS_ORE -> Material.LAPIS_LAZULI;
                case REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> Material.REDSTONE;
                case COAL_ORE, DEEPSLATE_COAL_ORE -> Material.COAL;
                case COPPER_ORE, DEEPSLATE_COPPER_ORE -> Material.RAW_COPPER;
                case NETHER_QUARTZ_ORE -> Material.QUARTZ;
                case NETHER_GOLD_ORE -> Material.GOLD_NUGGET;
                default -> null;
            };
            if (drop != null) e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), new ItemStack(drop));
        }
    }

    @EventHandler public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (activeEvent == EventType.FLIMSY_ARMOR) {
            for (ItemStack armor : p.getInventory().getArmorContents()) {
                if (armor == null || armor.getType().getMaxDurability() <= 0 || !(armor.getItemMeta() instanceof Damageable meta)) continue;
                // Flimsy Armor: apply 5x the plugin's previous extra wear (3 -> 15 durability per hit).
                meta.setDamage(Math.min(armor.getType().getMaxDurability(), meta.getDamage() + 15));
                armor.setItemMeta(meta);
            }
        }
    }

    @EventHandler public void onEntityDeath(EntityDeathEvent e) {
        if (activeEvent != EventType.DOUBLE_DROPS || e.getEntity() instanceof Player) return;
        List<ItemStack> originalDrops = new ArrayList<>();
        for (ItemStack drop : e.getDrops()) originalDrops.add(drop.clone());
        e.getDrops().addAll(originalDrops);
    }

    @EventHandler public void onInteract(PlayerInteractEvent e) {
        if (activeEvent != EventType.SHIELDLESS || e.getItem() == null) return;
        if (e.getItem().getType() == Material.SHIELD && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            e.setCancelled(true); e.getPlayer().sendMessage(color("&cShields are disabled during Shieldless!"));
        }
    }

    private EventType findEvent(String input) {
        String normalized = input.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "").replace("'", "");
        for (EventType type : EventType.values()) {
            String name = type.name().toLowerCase(Locale.ROOT).replace("_", "").replace("'", "");
            String title = type.title.toLowerCase(Locale.ROOT).replace(" ", "").replace("'", "").replace(".", "");
            if (normalized.equals(name) || normalized.equals(title)) return type;
        }
        return null;
    }

    private void startLoopIfNeeded() {
        running = true;
        if (loopTask != -1) Bukkit.getScheduler().cancelTask(loopTask);
        long interval = Math.max(60, getConfig().getLong("settings.interval-seconds", 900)) * 20L;
        loopTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::startRandomEvent, interval, interval);
    }

    private void forceEvent(CommandSender sender, EventType type) {
        if (type == null) {
            sender.sendMessage(color("&cUnknown event. Use /randomworldevents list to see valid names."));
            return;
        }
        running = true;
        if (loopTask == -1) startLoopIfNeeded();
        if (activeEvent != null) endEvent(false);
        startEvent(type);
        sender.sendMessage(color("&aForce-started &e" + type.title + "&a."));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("randomworldevents.admin")) {
            sender.sendMessage(color("&cYou don't have permission."));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(color("&eRandomWorldEvents admin commands:"));
            sender.sendMessage(color("&6/randomworldevents force <event> &7- force-start a specific event"));
            sender.sendMessage(color("&6/randomworldevents start [event] &7- enable random events, optionally force an event"));
            sender.sendMessage(color("&6/randomworldevents stop [all] &7- stop current event, or stop the whole system"));
            sender.sendMessage(color("&6/randomworldevents reroll &7- replace the current event with a different random event"));
            sender.sendMessage(color("&6/randomworldevents next &7- start a random event now"));
            sender.sendMessage(color("&6/randomworldevents list | status | reload | enable | disable"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "force", "forcestart" -> {
                if (args.length < 2) { sender.sendMessage(color("&cUsage: /randomworldevents force <event>")); return true; }
                forceEvent(sender, findEvent(String.join(" ", Arrays.copyOfRange(args, 1, args.length))));
            }
            case "start" -> {
                if (args.length >= 2) {
                    forceEvent(sender, findEvent(String.join(" ", Arrays.copyOfRange(args, 1, args.length))));
                } else {
                    startLoopIfNeeded();
                    if (activeEvent == null) startRandomEvent();
                    sender.sendMessage(color("&aRandom events enabled."));
                }
            }
            case "enable", "resume" -> {
                startLoopIfNeeded();
                sender.sendMessage(color("&aRandom event system enabled."));
            }
            case "disable" -> {
                running = false;
                if (loopTask != -1) { Bukkit.getScheduler().cancelTask(loopTask); loopTask = -1; }
                endEvent(false);
                sender.sendMessage(color("&cRandom event system disabled and active event stopped."));
            }
            case "stop" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
                    running = false;
                    if (loopTask != -1) { Bukkit.getScheduler().cancelTask(loopTask); loopTask = -1; }
                    endEvent(true);
                    sender.sendMessage(color("&cRandom event system stopped."));
                } else if (activeEvent != null) {
                    String oldName = activeEvent.title;
                    endEvent(false);
                    broadcast("&c" + oldName + " was stopped by an admin.");
                    sender.sendMessage(color("&aStopped the active event. Random scheduling remains enabled."));
                } else {
                    sender.sendMessage(color("&eNo event is currently active. Use /randomworldevents stop all to disable scheduling."));
                }
            }
            case "reroll", "reroll-event" -> {
                EventType previous = activeEvent;
                List<EventType> choices = new ArrayList<>(Arrays.asList(EventType.values()));
                if (previous != null) choices.remove(previous);
                if (choices.isEmpty()) { sender.sendMessage(color("&cNo alternative events are available.")); return true; }
                EventType chosen = choices.get(random.nextInt(choices.size()));
                if (activeEvent != null) endEvent(false);
                running = true;
                if (loopTask == -1) startLoopIfNeeded();
                startEvent(chosen);
                sender.sendMessage(color("&aRerolled event to &e" + chosen.title + "&a."));
            }
            case "next" -> {
                List<EventType> choices = new ArrayList<>(Arrays.asList(EventType.values()));
                if (activeEvent != null) choices.remove(activeEvent);
                if (choices.isEmpty()) { sender.sendMessage(color("&cNo alternative events are available.")); return true; }
                if (activeEvent != null) endEvent(false);
                running = true;
                if (loopTask == -1) startLoopIfNeeded();
                EventType chosen = choices.get(random.nextInt(choices.size()));
                startEvent(chosen);
                sender.sendMessage(color("&aStarted next random event: &e" + chosen.title));
            }
            case "list" -> {
                sender.sendMessage(color("&6&lAvailable RandomWorldEvents:"));
                for (EventType type : EventType.values()) sender.sendMessage(color("&e- " + type.title + " &7(" + type.name().toLowerCase(Locale.ROOT) + ")"));
            }
            case "status" -> {
                sender.sendMessage(color("&6Random system: " + (running ? "&aenabled" : "&cdisabled")));
                sender.sendMessage(color("&6Active event: " + (activeEvent == null ? "&7none" : "&e" + activeEvent.title)));
                if (activeEvent != null) {
                    long seconds = Math.max(0, (eventEndsAt - System.currentTimeMillis()) / 1000L);
                    sender.sendMessage(color("&6Time remaining: &e" + seconds + " seconds"));
                }
            }
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(color("&aConfig reloaded. New settings apply to future events; use /randomworldevents enable to ensure scheduling is enabled."));
            }
            default -> sender.sendMessage(color("&cUnknown command. Use /randomworldevents for help."));
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("randomworldevents.admin")) return Collections.emptyList();
        if (args.length == 1) {
            return List.of("force", "start", "stop", "reroll", "next", "list", "status", "reload", "enable", "disable")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length >= 2 && (args[0].equalsIgnoreCase("force") || args[0].equalsIgnoreCase("forcestart") || args[0].equalsIgnoreCase("start"))) {
            return Arrays.stream(EventType.values()).map(t -> t.name().toLowerCase(Locale.ROOT))
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stop") && "all".startsWith(args[1].toLowerCase(Locale.ROOT))) return List.of("all");
        return Collections.emptyList();
    }
}
