package org.axial.blightBreak;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class BlightBreak extends JavaPlugin implements Listener {

    private static final String PLAYER_WORLD_PREFIX = "BlightBreak_";
    private static final double STARTING_PLOT_SIZE = 16.0;
    private static final double STARTING_PLOT_CENTER = STARTING_PLOT_SIZE / 2.0;

    @Override
    public void onEnable() {
        getCommand("test").setExecutor(this::handleTestCommand);
        getCommand("plot").setExecutor(this::handlePlotCommand);
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
                player.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, particleLocation, 10, 0.8, 1.3, 0.8, 0.025);
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

        configureStartingPlot(world, generator);
        return world;
    }

    private void configureStartingPlot(World world, WastelandGenerator generator) {
        world.getWorldBorder().setCenter(STARTING_PLOT_CENTER, STARTING_PLOT_CENTER);
        world.getWorldBorder().setSize(STARTING_PLOT_SIZE);
        world.setSpawnLocation(
                (int) STARTING_PLOT_CENTER,
                generator.groundHeight((int) STARTING_PLOT_CENTER, (int) STARTING_PLOT_CENTER) + 1,
                (int) STARTING_PLOT_CENTER
        );
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
        sender.sendMessage(ChatColor.GREEN.toString() + ChatColor.BOLD + "Hello :)");
        return true;
    }

    private boolean handlePlotCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can claim a plot.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(ChatColor.RED + "Usage: /plot <claim|home>");
            return true;
        }

        World plotWorld;
        if (args[0].equalsIgnoreCase("claim")) {
            plotWorld = createWasteland(player.getName());
            player.sendMessage(ChatColor.DARK_GREEN + "You step into " + ChatColor.BOLD + "your Blightbreak Wasteland" + ChatColor.DARK_GREEN + ".");
        } else if (args[0].equalsIgnoreCase("home")) {
            plotWorld = getExistingPlotWorld(player.getName());
            if (plotWorld == null) {
                player.sendMessage(ChatColor.RED + "You do not have a plot yet. Use /plot claim first.");
                return true;
            }
            player.sendMessage(ChatColor.DARK_GREEN + "You return to " + ChatColor.BOLD + "your Blightbreak Wasteland" + ChatColor.DARK_GREEN + ".");
        } else {
            player.sendMessage(ChatColor.RED + "Usage: /plot <claim|home>");
            return true;
        }

        Location arrival = plotWorld.getSpawnLocation().add(0.5, 0, 0.5);
        player.teleportAsync(arrival);
        return true;
    }

    /** Generates the barren overworld used for player plots. */
    private static final class WastelandGenerator extends ChunkGenerator {
        private static final int SURFACE_Y = 63;
        private static final int WATER_LEVEL = 62;
        private static final double STARTING_PLOT_SAFE_RADIUS = 20.0;
        private final long terrainSeed;
        private static final Material[] SURFACE_BLOCKS = {
                Material.ROOTED_DIRT,
                Material.PODZOL,
                Material.GRAVEL,
                Material.DIRT,
                Material.DIRT_PATH
        };

        private WastelandGenerator(long terrainSeed) {
            this.terrainSeed = terrainSeed;
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
            if (isStartingPlotArea(x, z)) {
                return 0.0;
            }

            double basin = valueNoise(x / 145.0, z / 145.0);
            double brokenShore = valueNoise((x + 4_183) / 43.0, (z - 7_291) / 43.0);
            return smoothStep(0.76, 0.90, basin) * (0.82 + brokenShore * 0.18);
        }

        /** Creates two independently warped, world-spanning channels that join naturally at crossings. */
        private double riverCarve(int x, int z) {
            if (isStartingPlotArea(x, z)) {
                return 0.0;
            }

            double eastWestCenter = Math.sin(x / 74.0) * 19.0 + fractalNoise(x, 3_817, 110.0, 3) * 16.0;
            double northSouthCenter = Math.cos(z / 91.0) * 23.0 + fractalNoise(6_149, z, 105.0, 3) * 15.0;
            double channelDistance = Math.min(Math.abs(z - eastWestCenter), Math.abs(x - northSouthCenter));
            double width = 2.6 + valueNoise((x - 911) / 36.0, (z + 2_077) / 36.0) * 2.4;
            return smoothStep(width + 2.0, width, channelDistance);
        }

        private static boolean isStartingPlotArea(int x, int z) {
            double centerX = STARTING_PLOT_CENTER;
            double centerZ = STARTING_PLOT_CENTER;
            double offsetX = x - centerX;
            double offsetZ = z - centerZ;
            return offsetX * offsetX + offsetZ * offsetZ < STARTING_PLOT_SAFE_RADIUS * STARTING_PLOT_SAFE_RADIUS;
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
            // The origin chunk is the player's initial 16x16 home plot; leave it clear to build on.
            if (chunkX == 0 && chunkZ == 0) {
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
