package org.axial.blightBreak;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class BlightBreak extends JavaPlugin implements Listener {

    private final Map<UUID, List<ItemStack>> deathBoundTools = new HashMap<>();

    private static final String PLAYER_WORLD_PREFIX = "BlightBreak_";
    private static final double STARTING_PLOT_SIZE = 16.0;
    private static final int STARTING_RESIDUE_REQUIREMENT = 5;
    private static final double RESIDUE_REQUIREMENT_GROWTH = 1.10;
    private static final int FIRST_PURIFICATION_FLOWER_COUNT = 12;
    private static final Material[] PURIFICATION_FLOWERS = {
            Material.DANDELION,
            Material.POPPY,
            Material.AZURE_BLUET,
            Material.OXEYE_DAISY,
            Material.CORNFLOWER
    };
    private static final String NEXUS_CORE_MESSAGE = "&a&l(!) Nexus Core\n\n"
            + "&fThe Nexus Core allows you to purify your land, making it safe to enhabit.\n\n"
            + "&fApply blighted residue by interacting with the nexus while holding residue. "
            + "Apply enough residue in order to purify the chunks around the nexus border.";

    @Override
    public void onEnable() {
        getCommand("test").setExecutor(this::handleTestCommand);
        getCommand("plot").setExecutor(this::handlePlotCommand);
        getCommand("residue").setExecutor(this::handleResidueCommand);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::spawnBorderParticles, 20L, 5L);
    }

    /** Keeps the edge of each wasteland visibly hostile without placing particles outside its border. */
    private void spawnBorderParticles() {
        for (Player player : getServer().getOnlinePlayers()) {
            World world = player.getWorld();
            if (!world.getName().startsWith(PLAYER_WORLD_PREFIX)) {
                continue;
            }

            Location particleLocation = randomBorderLocation(world);
            if (particleLocation == null) {
                continue;
            }

            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (random.nextInt(100) < 45) {
                player.spawnParticle(Particle.ASH, particleLocation, 16, 0.9, 1.5, 0.9, 0.015);
            } else if (random.nextInt(100) < 70) {
                player.spawnParticle(Particle.SMOKE, particleLocation, 10, 0.8, 1.3, 0.8, 0.025);
            } else if (random.nextBoolean()) {
                player.spawnParticle(Particle.SOUL_FIRE_FLAME, particleLocation, 5, 0.55, 1.0, 0.55, 0.008);
            } else {
                player.spawnParticle(Particle.CRIMSON_SPORE, particleLocation, 9, 0.85, 1.3, 0.85, 0.015);
            }
        }
    }

    private Location randomBorderLocation(World world) {
        double halfSize = world.getWorldBorder().getSize() / 2.0;
        if (halfSize <= 2.0) {
            return null;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double centerX = world.getWorldBorder().getCenter().getX();
        double centerZ = world.getWorldBorder().getCenter().getZ();
        double inset = 1.25;
        double minX = centerX - halfSize + inset;
        double maxX = centerX + halfSize - inset;
        double minZ = centerZ - halfSize + inset;
        double maxZ = centerZ + halfSize - inset;
        double x;
        double z;

        switch (random.nextInt(4)) {
            case 0 -> {
                x = minX;
                z = random.nextDouble(minZ, maxZ);
            }
            case 1 -> {
                x = maxX;
                z = random.nextDouble(minZ, maxZ);
            }
            case 2 -> {
                x = random.nextDouble(minX, maxX);
                z = minZ;
            }
            default -> {
                x = random.nextDouble(minX, maxX);
                z = maxZ;
            }
        }

        int groundY = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
        return new Location(world, x, groundY + random.nextDouble(1.5, 10.0), z);
    }

    private World createWasteland(String playerName) {
        String worldName = playerWorldName(playerName);
        long worldSeed = worldSeed(playerName);
        WastelandGenerator generator = new WastelandGenerator(worldSeed);
        World world = getServer().getWorld(worldName);
        boolean newlyCreated = world == null && !new File(getServer().getWorldContainer(), worldName).isDirectory();
        if (world == null) {
            WorldCreator creator = new WorldCreator(worldName)
                    .generator(generator)
                    .seed(worldSeed)
                    .generateStructures(false);
            world = creator.createWorld();
        }
        if (world == null) {
            throw new IllegalStateException("Could not create the BlightBreak wasteland world.");
        }

        configureStartingPlot(world, generator, newlyCreated);
        if (newlyCreated) {
            createNexus(world);
        }
        return world;
    }

    private void configureStartingPlot(World world, WastelandGenerator generator, boolean newlyCreated) {
        double centerX = generator.startingPlotCenterX();
        double centerZ = generator.startingPlotCenterZ();
        world.getWorldBorder().setCenter(centerX, centerZ);
        if (newlyCreated) {
            world.getWorldBorder().setSize(STARTING_PLOT_SIZE);
            world.setSpawnLocation(
                    (int) centerX,
                    generator.groundHeight((int) centerX, (int) centerZ) + 1,
                    (int) centerZ
            );
        } else {
            // The Nexus beacon occupies ground height + 1; returning players stand on top of it.
            world.setSpawnLocation(
                    (int) centerX,
                    generator.groundHeight((int) centerX, (int) centerZ) + 2,
                    (int) centerZ
            );
        }
    }

    /** Creates the landmark at the centre of a newly claimed plot. */
    private void createNexus(World world) {
        Location spawn = world.getSpawnLocation();
        int baseY = spawn.getBlockY() - 1;
        int centerX = spawn.getBlockX();
        int centerZ = spawn.getBlockZ();

        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                world.getBlockAt(x, baseY, z).setType(Material.CRYING_OBSIDIAN, false);
            }
        }
        world.getBlockAt(centerX, baseY + 1, centerZ).setType(Material.BEACON, false);
        world.setSpawnLocation(centerX, baseY + 2, centerZ);

        Location hologramLocation = new Location(world, centerX + 0.5, baseY + 3.0, centerZ + 0.5);
        world.spawn(hologramLocation, TextDisplay.class, hologram -> {
            hologram.text(LegacyComponentSerializer.legacyAmpersand().deserialize("&a&lNexus"));
            hologram.setBillboard(TextDisplay.Billboard.CENTER);
            hologram.setSeeThrough(true);
            hologram.setShadowed(true);
        });

        Location instructionLocation = new Location(world, centerX + 0.5, baseY + 2.7, centerZ + 0.5);
        world.spawn(instructionLocation, TextDisplay.class, hologram -> {
            hologram.text(LegacyComponentSerializer.legacyAmpersand().deserialize("&fApply Blighted Residue"));
            hologram.setBillboard(TextDisplay.Billboard.CENTER);
            hologram.setSeeThrough(true);
            hologram.setShadowed(true);
        });

        Location residueCounterLocation = new Location(world, centerX + 0.5, baseY + 2.4, centerZ + 0.5);
        world.spawn(residueCounterLocation, TextDisplay.class, hologram -> {
            hologram.text(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    residueCounterText(world)
            ));
            hologram.setBillboard(TextDisplay.Billboard.CENTER);
            hologram.setSeeThrough(true);
            hologram.setShadowed(true);
        });
    }

    private int storedResidue(World world) {
        Integer amount = world.getPersistentDataContainer().get(residueAmountKey(), PersistentDataType.INTEGER);
        return amount == null ? 0 : amount;
    }

    private int residueRequirement(World world) {
        Integer amount = world.getPersistentDataContainer().get(residueRequirementKey(), PersistentDataType.INTEGER);
        return amount == null ? STARTING_RESIDUE_REQUIREMENT : amount;
    }

    private String residueCounterText(World world) {
        return "&e" + storedResidue(world) + " / " + residueRequirement(world) + " Residue";
    }

    private NamespacedKey residueAmountKey() {
        return new NamespacedKey(this, "nexus_residue");
    }

    private NamespacedKey residueRequirementKey() {
        return new NamespacedKey(this, "nexus_residue_requirement");
    }

    private int purificationLevel(World world) {
        Integer level = world.getPersistentDataContainer().get(purificationLevelKey(), PersistentDataType.INTEGER);
        return level == null ? 0 : level;
    }

    private NamespacedKey purificationLevelKey() {
        return new NamespacedKey(this, "nexus_purification_level");
    }

    private ItemStack createBlightedResidue() {
        ItemStack residue = new ItemStack(Material.NETHERRACK);
        ItemMeta meta = residue.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&c&lBlighted Residue"));
        meta.setLore(List.of(
                ChatColor.translateAlternateColorCodes('&', "&7Residue with the power"),
                ChatColor.translateAlternateColorCodes('&', "&7to purify wastelands"),
                "",
                ChatColor.translateAlternateColorCodes('&', "&7Deposit this into a nexus"),
                ChatColor.translateAlternateColorCodes('&', "&7core to purify")
        ));
        meta.getPersistentDataContainer().set(residueItemKey(), PersistentDataType.BYTE, (byte) 1);
        residue.setItemMeta(meta);
        return residue;
    }

    private NamespacedKey residueItemKey() {
        return new NamespacedKey(this, "blighted_residue");
    }

    private NamespacedKey boundToolOwnerKey() {
        return new NamespacedKey(this, "bound_tool_owner");
    }

    private ItemStack createBoundTool(Material material, Player owner, Integer customModelData) {
        ItemStack tool = new ItemStack(material);
        ItemMeta meta = tool.getItemMeta();
        meta.setUnbreakable(true);
        if (customModelData != null) {
            meta.setCustomModelData(customModelData);
        }
        meta.getPersistentDataContainer().set(
                boundToolOwnerKey(), PersistentDataType.STRING, owner.getUniqueId().toString()
        );
        tool.setItemMeta(meta);
        return tool;
    }

    private void giveStartingTools(Player player) {
        List<ItemStack> tools = List.of(
                createBoundTool(Material.STONE_PICKAXE, player, 280),
                createBoundTool(Material.STONE_AXE, player, null),
                createBoundTool(Material.STONE_SWORD, player, 245)
        );
        player.getInventory().addItem(tools.toArray(ItemStack[]::new))
                .values()
                .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private boolean isBoundToolFor(ItemStack item, UUID playerId) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        String ownerId = item.getItemMeta().getPersistentDataContainer()
                .get(boundToolOwnerKey(), PersistentDataType.STRING);
        return playerId.toString().equals(ownerId);
    }

    private static long worldSeed(String playerName) {
        long seed = 847_239_105L;
        for (int index = 0; index < playerName.length(); index++) {
            seed = 31 * seed + playerName.charAt(index);
        }
        return seed;
    }

    private World getExistingPlotWorld(String playerName) {
        String worldName = playerWorldName(playerName);
        World loadedWorld = getServer().getWorld(worldName);
        if (loadedWorld != null) {
            return loadedWorld;
        }

        File worldFolder = new File(getServer().getWorldContainer(), worldName);
        return worldFolder.isDirectory() ? createWasteland(playerName) : null;
    }

    private static String playerWorldName(String playerName) {
        return PLAYER_WORLD_PREFIX + playerName;
    }

    @EventHandler
    private void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        scheduleUnloadIfEmpty(event.getFrom());
    }

    @EventHandler
    private void onPlayerQuit(PlayerQuitEvent event) {
        scheduleUnloadIfEmpty(event.getPlayer().getWorld());
    }

    /** Prevents a player's plot tools from being included in their death drops. */
    @EventHandler
    private void onPlayerDeath(PlayerDeathEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        List<ItemStack> savedTools = new ArrayList<>();
        event.getDrops().removeIf(item -> {
            if (!isBoundToolFor(item, playerId)) {
                return false;
            }
            savedTools.add(item.clone());
            return true;
        });
        if (!savedTools.isEmpty()) {
            deathBoundTools.put(playerId, savedTools);
        }
    }

    @EventHandler
    private void onPlayerRespawn(PlayerRespawnEvent event) {
        List<ItemStack> savedTools = deathBoundTools.remove(event.getPlayer().getUniqueId());
        if (savedTools == null) {
            return;
        }

        getServer().getScheduler().runTask(this, () -> event.getPlayer().getInventory()
                .addItem(savedTools.toArray(ItemStack[]::new))
                .values()
                .forEach(item -> event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), item)));
    }

    /** Handles residue deposits and starts a purification once the Nexus is charged. */
    @EventHandler
    private void onNexusInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.BEACON
                || !isNexus(event.getClickedBlock().getLocation())
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        World world = event.getClickedBlock().getWorld();
        int stored = storedResidue(world);
        int required = residueRequirement(world);

        if (stored >= required) {
            purifyClaim(world);
            player.sendMessage(ChatColor.GREEN + "The Nexus purifies your claim.");
            return;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (!isBlightedResidue(heldItem)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', NEXUS_CORE_MESSAGE));
            return;
        }

        int deposited = player.isSneaking() ? heldItem.getAmount() : 1;
        heldItem.setAmount(heldItem.getAmount() - deposited);
        player.getInventory().setItemInMainHand(heldItem.getAmount() == 0 ? null : heldItem);
        world.getPersistentDataContainer().set(residueAmountKey(), PersistentDataType.INTEGER, stored + deposited);
        updateResidueCounter(world);
        player.sendMessage(ChatColor.YELLOW + "Added " + deposited + " residue to the Nexus ("
                + (stored + deposited) + "/" + required + ").");
    }

    private boolean isBlightedResidue(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(residueItemKey(), PersistentDataType.BYTE);
    }

    private void purifyClaim(World world) {
        int completedPurifications = purificationLevel(world);
        double oldBorderSize = world.getWorldBorder().getSize();
        double halfSize = oldBorderSize / 2.0;
        double centerX = world.getWorldBorder().getCenter().getX();
        double centerZ = world.getWorldBorder().getCenter().getZ();
        int minX = (int) Math.ceil(centerX - halfSize - 0.5);
        int maxX = (int) Math.floor(centerX + halfSize - 0.5);
        int minZ = (int) Math.ceil(centerZ - halfSize - 0.5);
        int maxZ = (int) Math.floor(centerZ + halfSize - 0.5);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    Material material = block.getType();
                    if (material == Material.DIRT || material == Material.ROOTED_DIRT || material == Material.GRAVEL
                            || material == Material.PODZOL || material == Material.DIRT_PATH) {
                        block.setType(Material.GRASS_BLOCK, false);
                    }
                }
            }
        }

        if (completedPurifications == 0) {
            placeFirstPurificationFlowers(world, minX, maxX, minZ, maxZ);
        }
        world.getPersistentDataContainer().set(residueAmountKey(), PersistentDataType.INTEGER, 0);
        int nextRequirement = (int) Math.ceil(residueRequirement(world) * RESIDUE_REQUIREMENT_GROWTH);
        world.getPersistentDataContainer().set(residueRequirementKey(), PersistentDataType.INTEGER, nextRequirement);
        world.getPersistentDataContainer().set(purificationLevelKey(), PersistentDataType.INTEGER, completedPurifications + 1);
        updateResidueCounter(world);
    }

    private void placeFirstPurificationFlowers(World world, int minX, int maxX, int minZ, int maxZ) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location nexus = nexusLocation(world);
        int flowersPlaced = 0;
        int attempts = FIRST_PURIFICATION_FLOWER_COUNT * 12;

        while (flowersPlaced < FIRST_PURIFICATION_FLOWER_COUNT && attempts-- > 0) {
            int x = random.nextInt(minX, maxX + 1);
            int z = random.nextInt(minZ, maxZ + 1);
            if (nexus != null && Math.abs(x - nexus.getBlockX()) <= 2 && Math.abs(z - nexus.getBlockZ()) <= 2) {
                continue;
            }

            int groundY = world.getHighestBlockYAt(x, z);
            org.bukkit.block.Block ground = world.getBlockAt(x, groundY, z);
            org.bukkit.block.Block flowerBlock = ground.getRelative(0, 1, 0);
            if (ground.getType() != Material.GRASS_BLOCK || !flowerBlock.getType().isAir()) {
                continue;
            }

            flowerBlock.setType(PURIFICATION_FLOWERS[random.nextInt(PURIFICATION_FLOWERS.length)], false);
            flowersPlaced++;
        }
    }

    private void updateResidueCounter(World world) {
        Location nexus = nexusLocation(world);
        if (nexus == null) {
            return;
        }
        Location counterLocation = nexus.clone().add(0.5, 1.4, 0.5);
        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(counterLocation, 0.1, 0.1, 0.1)) {
            if (entity instanceof TextDisplay display) {
                display.text(LegacyComponentSerializer.legacyAmpersand().deserialize(residueCounterText(world)));
            }
        }
    }

    private Location nexusLocation(World world) {
        if (!world.getName().startsWith(PLAYER_WORLD_PREFIX)) {
            return null;
        }
        String playerName = world.getName().substring(PLAYER_WORLD_PREFIX.length());
        WastelandGenerator generator = new WastelandGenerator(worldSeed(playerName));
        int centerX = (int) generator.startingPlotCenterX();
        int centerZ = (int) generator.startingPlotCenterZ();
        return new Location(world, centerX, generator.groundHeight(centerX, centerZ) + 1, centerZ);
    }

    private boolean isNexus(Location location) {
        World world = location.getWorld();
        if (world == null || !world.getName().startsWith(PLAYER_WORLD_PREFIX)) {
            return false;
        }

        Location nexus = nexusLocation(world);
        return nexus != null && location.getBlockX() == nexus.getBlockX()
                && location.getBlockY() == nexus.getBlockY()
                && location.getBlockZ() == nexus.getBlockZ();
    }

    private void scheduleUnloadIfEmpty(World world) {
        if (!world.getName().startsWith(PLAYER_WORLD_PREFIX)) {
            return;
        }

        // Run after the teleport/disconnect completes, so Bukkit no longer counts that player in the world.
        getServer().getScheduler().runTask(this, () -> {
            if (world.getPlayers().isEmpty()) {
                getServer().unloadWorld(world, true);
            }
        });
    }

    private boolean handleTestCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can receive Blighted Residue.");
            return true;
        }

        player.getInventory().addItem(createBlightedResidue())
                .values()
                .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.sendMessage(ChatColor.GREEN + "You received Blighted Residue.");
        return true;
    }

    private boolean handleResidueCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.RED + "Usage: /residue give <player> <amount>");
            return true;
        }

        Player target = getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "That player is not online.");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Amount must be a whole number.");
            return true;
        }
        if (amount < 1) {
            sender.sendMessage(ChatColor.RED + "Amount must be at least 1.");
            return true;
        }

        giveBlightedResidue(target, amount);
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " Blighted Residue to " + target.getName() + ".");
        return true;
    }

    private void giveBlightedResidue(Player player, int amount) {
        while (amount > 0) {
            ItemStack residue = createBlightedResidue();
            int stackAmount = Math.min(amount, residue.getMaxStackSize());
            residue.setAmount(stackAmount);
            player.getInventory().addItem(residue)
                    .values()
                    .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            amount -= stackAmount;
        }
    }

    private boolean handlePlotCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can claim a plot.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(ChatColor.RED + "Usage: /plot <claim|home|delete>");
            return true;
        }

        if (args[0].equalsIgnoreCase("delete")) {
            deletePlot(player);
            return true;
        }

        World plotWorld;
        boolean newlyClaimed;
        if (args[0].equalsIgnoreCase("claim")) {
            newlyClaimed = getExistingPlotWorld(player.getName()) == null;
            plotWorld = createWasteland(player.getName());
            player.sendMessage(ChatColor.DARK_GREEN + "You step into " + ChatColor.BOLD + "your Blightbreak Wasteland" + ChatColor.DARK_GREEN + ".");
        } else if (args[0].equalsIgnoreCase("home")) {
            newlyClaimed = false;
            plotWorld = getExistingPlotWorld(player.getName());
            if (plotWorld == null) {
                player.sendMessage(ChatColor.RED + "You do not have a plot yet. Use /plot claim first.");
                return true;
            }
            player.sendMessage(ChatColor.DARK_GREEN + "You return to " + ChatColor.BOLD + "your Blightbreak Wasteland" + ChatColor.DARK_GREEN + ".");
        } else {
            player.sendMessage(ChatColor.RED + "Usage: /plot <claim|home|delete>");
            return true;
        }

        Location arrival = plotWorld.getSpawnLocation().add(0.5, 0, 0.5);
        if (newlyClaimed) {
            player.teleportAsync(arrival).thenAccept(teleported -> {
                if (teleported) {
                    getServer().getScheduler().runTask(this, () -> giveStartingTools(player));
                }
            });
        } else {
            player.teleportAsync(arrival);
        }
        return true;
    }

    private void deletePlot(Player owner) {
        String worldName = playerWorldName(owner.getName());
        File worldFolder = new File(getServer().getWorldContainer(), worldName);
        World plotWorld = getServer().getWorld(worldName);
        if (plotWorld == null && !worldFolder.isDirectory()) {
            owner.sendMessage(ChatColor.RED + "You do not have a plot to delete.");
            return;
        }

        UUID ownerId = owner.getUniqueId();
        if (plotWorld == null) {
            deleteWorldDirectory(worldFolder.toPath(), ownerId);
            owner.sendMessage(ChatColor.YELLOW + "Your plot is being deleted.");
            return;
        }

        World fallbackWorld = getServer().getWorlds().stream()
                .filter(world -> !world.getName().equals(worldName))
                .findFirst()
                .orElse(null);
        if (fallbackWorld == null) {
            owner.sendMessage(ChatColor.RED + "Could not find a safe world to leave your plot.");
            return;
        }

        List<CompletableFuture<Boolean>> teleports = new ArrayList<>();
        Location fallbackLocation = fallbackWorld.getSpawnLocation().add(0.5, 0, 0.5);
        for (Player player : List.copyOf(plotWorld.getPlayers())) {
            teleports.add(player.teleportAsync(fallbackLocation));
        }

        CompletableFuture.allOf(teleports.toArray(CompletableFuture[]::new)).whenComplete((ignored, throwable) ->
                getServer().getScheduler().runTask(this, () -> {
                    World loadedPlotWorld = getServer().getWorld(worldName);
                    if (loadedPlotWorld != null && !loadedPlotWorld.getPlayers().isEmpty()) {
                        sendPlotDeletionMessage(ownerId, ChatColor.RED + "Could not move everyone out of your plot.");
                        return;
                    }
                    if (loadedPlotWorld != null && !getServer().unloadWorld(loadedPlotWorld, true)) {
                        sendPlotDeletionMessage(ownerId, ChatColor.RED + "Could not unload your plot for deletion.");
                        return;
                    }
                    deleteWorldDirectory(worldFolder.toPath(), ownerId);
                })
        );
        owner.sendMessage(ChatColor.YELLOW + "Your plot is being deleted.");
    }

    private void deleteWorldDirectory(Path worldPath, UUID ownerId) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try (var paths = Files.walk(worldPath)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException exception) {
                        throw new IllegalStateException("Could not delete " + path, exception);
                    }
                });
                sendPlotDeletionMessage(ownerId, ChatColor.GREEN + "Your plot has been deleted.");
            } catch (IOException | IllegalStateException exception) {
                getLogger().warning("Could not delete plot world " + worldPath + ": " + exception.getMessage());
                sendPlotDeletionMessage(ownerId, ChatColor.RED + "Could not completely delete your plot. Check the server log.");
            }
        });
    }

    private void sendPlotDeletionMessage(UUID playerId, String message) {
        getServer().getScheduler().runTask(this, () -> {
            Player player = getServer().getPlayer(playerId);
            if (player != null) {
                player.sendMessage(message);
            }
        });
    }

    /** Generates the barren overworld used for player plots. */
    private static final class WastelandGenerator extends ChunkGenerator {
        private static final int SURFACE_Y = 63;
        private static final int WATER_LEVEL = 62;
        private final long terrainSeed;
        private final int claimChunkX;
        private final int claimChunkZ;
        private static final Material[] SURFACE_BLOCKS = {
                Material.ROOTED_DIRT,
                Material.PODZOL,
                Material.GRAVEL,
                Material.DIRT,
                Material.DIRT_PATH
        };

        private WastelandGenerator(long terrainSeed) {
            this.terrainSeed = terrainSeed;
            int[] claimChunk = findDryClaimChunk();
            this.claimChunkX = claimChunk[0];
            this.claimChunkZ = claimChunk[1];
        }

        @Override
        public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
            ChunkData chunk = createChunkData(world);
            int originX = chunkX << 4;
            int originZ = chunkZ << 4;

            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int worldX = originX + localX;
                    int worldZ = originZ + localZ;
                    int surfaceY = groundHeight(worldX, worldZ);
                    boolean water = surfaceY < WATER_LEVEL;

                    chunk.setBlock(localX, 0, localZ, Material.BEDROCK);
                    chunk.setRegion(localX, 1, localZ, localX + 1, surfaceY - 3, localZ + 1, Material.STONE);
                    chunk.setRegion(localX, surfaceY - 3, localZ, localX + 1, surfaceY, localZ + 1, Material.DIRT);
                    chunk.setBlock(localX, surfaceY, localZ, water ? waterbedMaterial(worldX, worldZ) : surfaceMaterial(worldX, worldZ));
                    if (water) {
                        chunk.setRegion(localX, surfaceY + 1, localZ, localX + 1, WATER_LEVEL + 1, localZ + 1, Material.WATER);
                    }
                }
            }

            generateDeadTrees(chunk, chunkX, chunkZ);
            return chunk;
        }

        private int groundHeight(int x, int z) {
            // Large-scale noise chooses regions, then finer layers shape their hills and ravines.
            double mountainRegion = smoothStep(0.52, 0.76, valueNoise(x / 310.0, z / 310.0));
            double valleyRegion = smoothStep(0.58, 0.86, 1.0 - valueNoise(x / 240.0, z / 240.0));
            double rollingHills = fractalNoise(x, z, 95.0, 4) * 7.0;
            double mountainRidges = (0.35 + fractalNoise(x, z, 52.0, 3) * 0.65) * mountainRegion * 34.0;
            double valleyDepth = valleyRegion * 11.0;
            double naturalHeight = SURFACE_Y + rollingHills + mountainRidges - valleyDepth;
            double waterCarve = Math.max(oceanCarve(x, z), riverCarve(x, z));
            double waterbedHeight = WATER_LEVEL - 4.0 + fractalNoise(x, z, 18.0, 2) * 1.5;

            return (int) Math.round(lerp(naturalHeight, waterbedHeight, waterCarve));
        }

        /**
         * Forms occasional broad basins. The coarse field keeps them compact while the finer
         * field breaks up their shorelines, so they read as small inland oceans rather than circles.
         */
        private double oceanCarve(int x, int z) {
            double basin = valueNoise(x / 145.0, z / 145.0);
            double brokenShore = valueNoise((x + 4_183) / 43.0, (z - 7_291) / 43.0);
            return smoothStep(0.76, 0.90, basin) * (0.82 + brokenShore * 0.18);
        }

        /** Creates two independently warped, world-spanning channels that join naturally at crossings. */
        private double riverCarve(int x, int z) {
            double eastWestCenter = Math.sin(x / 74.0) * 19.0 + fractalNoise(x, 3_817, 110.0, 3) * 16.0;
            double northSouthCenter = Math.cos(z / 91.0) * 23.0 + fractalNoise(6_149, z, 105.0, 3) * 15.0;
            double channelDistance = Math.min(Math.abs(z - eastWestCenter), Math.abs(x - northSouthCenter));
            double width = 2.6 + valueNoise((x - 911) / 36.0, (z + 2_077) / 36.0) * 2.4;
            return smoothStep(width + 2.0, width, channelDistance);
        }

        private int[] findDryClaimChunk() {
            Random locationRandom = new Random(terrainSeed ^ 0x6A09E667F3BCC909L);
            int baseChunkX = locationRandom.nextInt(33) - 16;
            int baseChunkZ = locationRandom.nextInt(33) - 16;

            for (int radius = 0; radius <= 32; radius++) {
                for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                    for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                        if (Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != radius) {
                            continue;
                        }
                        int chunkX = baseChunkX + offsetX;
                        int chunkZ = baseChunkZ + offsetZ;
                        if (isDryClaimChunk(chunkX, chunkZ)) {
                            return new int[]{chunkX, chunkZ};
                        }
                    }
                }
            }
            throw new IllegalStateException("Could not find dry terrain for the starting claim.");
        }

        private boolean isDryClaimChunk(int chunkX, int chunkZ) {
            int originX = chunkX << 4;
            int originZ = chunkZ << 4;
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    if (groundHeight(originX + localX, originZ + localZ) < WATER_LEVEL) {
                        return false;
                    }
                }
            }
            return true;
        }

        private double startingPlotCenterX() {
            return (claimChunkX << 4) + STARTING_PLOT_SIZE / 2.0;
        }

        private double startingPlotCenterZ() {
            return (claimChunkZ << 4) + STARTING_PLOT_SIZE / 2.0;
        }

        private double fractalNoise(int x, int z, double scale, int octaves) {
            double total = 0;
            double amplitude = 1;
            double amplitudeTotal = 0;
            for (int octave = 0; octave < octaves; octave++) {
                total += (valueNoise(x / scale, z / scale) * 2.0 - 1.0) * amplitude;
                amplitudeTotal += amplitude;
                amplitude *= 0.5;
                scale *= 0.5;
            }
            return total / amplitudeTotal;
        }

        private double valueNoise(double x, double z) {
            int x0 = (int) Math.floor(x);
            int z0 = (int) Math.floor(z);
            double xBlend = fade(x - x0);
            double zBlend = fade(z - z0);
            double bottom = lerp(noiseAt(x0, z0), noiseAt(x0 + 1, z0), xBlend);
            double top = lerp(noiseAt(x0, z0 + 1), noiseAt(x0 + 1, z0 + 1), xBlend);
            return lerp(bottom, top, zBlend);
        }

        private double noiseAt(int x, int z) {
            return (hash(x, z) & 0x1FFFFFFFFFFFFFL) / (double) 0x1FFFFFFFFFFFFFL;
        }

        private static double fade(double value) {
            return value * value * (3.0 - 2.0 * value);
        }

        private static double smoothStep(double edge0, double edge1, double value) {
            return fade(Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0))));
        }

        private static double lerp(double from, double to, double amount) {
            return from + (to - from) * amount;
        }

        private Material surfaceMaterial(int x, int z) {
            long value = hash(x, z);
            return SURFACE_BLOCKS[(int) Math.floorMod(value, (long) SURFACE_BLOCKS.length)];
        }

        private Material waterbedMaterial(int x, int z) {
            return Math.floorMod(hash(x - 1_337, z + 4_219), 4) == 0 ? Material.MUD : Material.GRAVEL;
        }

        private void generateDeadTrees(ChunkData chunk, int chunkX, int chunkZ) {
            // The selected chunk is the player's initial 16x16 home plot; leave it clear to build on.
            if (chunkX == claimChunkX && chunkZ == claimChunkZ) {
                return;
            }

            Random treeRandom = new Random(hash(chunkX, chunkZ));
            // Most chunks are empty; occasional clusters keep the wasteland from feeling patterned.
            int treeCount = treeRandom.nextInt(100) < 65 ? 1 : 0;
            if (treeCount == 1 && treeRandom.nextInt(100) < 15) {
                treeCount = 2;
            }
            for (int tree = 0; tree < treeCount; tree++) {
                // The border leaves enough room for every bare branch to remain in this chunk.
                int x = 4 + treeRandom.nextInt(8);
                int z = 4 + treeRandom.nextInt(8);
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                if (groundHeight(worldX, worldZ) < WATER_LEVEL) {
                    continue;
                }
                int y = groundHeight(worldX, worldZ) + 1;
                growDeadTree(chunk, treeRandom, x, y, z, treeRandom.nextInt(3));
            }
        }

        private static void growDeadTree(ChunkData chunk, Random random, int x, int y, int z, int variant) {
            Material trunk = random.nextBoolean() ? Material.DARK_OAK_LOG : Material.SPRUCE_LOG;
            Material scarredWood = trunk == Material.DARK_OAK_LOG ? Material.STRIPPED_DARK_OAK_LOG : Material.STRIPPED_SPRUCE_LOG;

            growRoots(chunk, random, x, y, z, trunk);
            if (variant == 0) {
                growBrokenStump(chunk, random, x, y, z, trunk, scarredWood);
            } else if (variant == 1) {
                growForkedTree(chunk, random, x, y, z, trunk, scarredWood);
            } else {
                growGnarledTree(chunk, random, x, y, z, trunk, scarredWood);
            }
        }

        private static void growBrokenStump(ChunkData chunk, Random random, int x, int y, int z, Material trunk, Material scarredWood) {
            int height = 4 + random.nextInt(3);
            for (int level = 0; level < height; level++) {
                placeBlock(chunk, x, y + level, z, level == height - 1 ? scarredWood : trunk);
                if (level > 1 && random.nextBoolean()) {
                    placeBlock(chunk, x + (random.nextBoolean() ? 1 : -1), y + level, z, scarredWood);
                }
            }

            int directionX = random.nextBoolean() ? 1 : -1;
            int directionZ = random.nextBoolean() ? 1 : -1;
            growBranch(chunk, x, y + height - 2, z, directionX, directionZ, 3 + random.nextInt(2), trunk, false);
        }

        private static void growForkedTree(ChunkData chunk, Random random, int x, int y, int z, Material trunk, Material scarredWood) {
            int height = 7 + random.nextInt(3);
            int[] crown = growTwistingTrunk(chunk, random, x, y, z, height, trunk, scarredWood);
            int firstX = random.nextBoolean() ? 1 : -1;
            int firstZ = random.nextBoolean() ? 1 : -1;
            growBranch(chunk, crown[0], crown[1] - 2, crown[2], firstX, firstZ, 3 + random.nextInt(2), trunk, true);
            growBranch(chunk, crown[0], crown[1] - 1, crown[2], -firstZ, firstX, 3 + random.nextInt(2), scarredWood, true);
            growBranch(chunk, crown[0], crown[1], crown[2], -firstX, -firstZ, 2 + random.nextInt(2), trunk, true);
        }

        private static void growGnarledTree(ChunkData chunk, Random random, int x, int y, int z, Material trunk, Material scarredWood) {
            int height = 9 + random.nextInt(3);
            int[] crown = growTwistingTrunk(chunk, random, x, y, z, height, trunk, scarredWood);
            for (int branch = 0; branch < 3 + random.nextInt(2); branch++) {
                int directionX = random.nextBoolean() ? 1 : -1;
                int directionZ = random.nextBoolean() ? 1 : -1;
                int branchY = crown[1] - 2 - random.nextInt(3);
                growBranch(chunk, crown[0], branchY, crown[2], directionX, directionZ, 2 + random.nextInt(3), trunk, true);
            }
        }

        private static int[] growTwistingTrunk(ChunkData chunk, Random random, int x, int y, int z, int height, Material trunk, Material scarredWood) {
            int currentX = x;
            int currentZ = z;

            for (int level = 0; level < height; level++) {
                placeBlock(chunk, currentX, y + level, currentZ, level % 4 == 0 ? scarredWood : trunk);
                if (level > 2 && random.nextInt(3) == 0) {
                    currentX = clamp(currentX + (random.nextBoolean() ? 1 : -1), 3, 12);
                }
                if (level > 2 && random.nextInt(3) == 0) {
                    currentZ = clamp(currentZ + (random.nextBoolean() ? 1 : -1), 3, 12);
                }
                if (level > 3 && random.nextInt(4) == 0) {
                    placeBlock(chunk, currentX + (random.nextBoolean() ? 1 : -1), y + level, currentZ, trunk);
                }
            }
            return new int[]{currentX, y + height - 1, currentZ};
        }

        private static void growRoots(ChunkData chunk, Random random, int x, int y, int z, Material trunk) {
            for (int root = 0; root < 3 + random.nextInt(2); root++) {
                int directionX = random.nextBoolean() ? 1 : -1;
                int directionZ = random.nextBoolean() ? 1 : -1;
                int length = 1 + random.nextInt(3);
                for (int step = 1; step <= length; step++) {
                    placeBlock(chunk, x + directionX * step, y - (step > 1 ? 1 : 0), z + directionZ * step, trunk);
                }
            }
        }

        private static void growBranch(ChunkData chunk, int x, int y, int z, int directionX, int directionZ, int length, Material trunk, boolean forkTip) {
            for (int step = 1; step <= length; step++) {
                placeBlock(chunk, x + directionX * step, y + ((step + 1) / 2), z + directionZ * step, trunk);
            }
            if (forkTip) {
                placeBlock(chunk, x + directionX * length, y + ((length + 1) / 2) + 1, z + directionZ * length, trunk);
                placeBlock(chunk, x + directionX * length + directionZ, y + ((length + 1) / 2), z + directionZ * length - directionX, trunk);
            }
        }

        private static void placeBlock(ChunkData chunk, int x, int y, int z, Material material) {
            if (x >= 0 && x < 16 && z >= 0 && z < 16) {
                chunk.setBlock(x, y, z, material);
            }
        }

        private long hash(int x, int z) {
            long value = terrainSeed;
            value ^= (long) x * 0x9E3779B97F4A7C15L;
            value ^= (long) z * 0xC2B2AE3D27D4EB4FL;
            value ^= value >>> 33;
            value *= 0xFF51AFD7ED558CCDL;
            value ^= value >>> 33;
            return value;
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
