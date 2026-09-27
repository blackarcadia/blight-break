package org.axial.blightBreak;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Random;

public final class BlightBreak extends JavaPlugin {

    private static final String WASTELAND_WORLD = "blightbreak_wasteland";
    private World wasteland;

    @Override
    public void onEnable() {
        getCommand("test").setExecutor(this::handleTestCommand);
        getCommand("plot").setExecutor(this::handlePlotCommand);

        wasteland = createWasteland();
    }

    private World createWasteland() {
        World existingWorld = getServer().getWorld(WASTELAND_WORLD);
        if (existingWorld != null) {
            return existingWorld;
        }

        WorldCreator creator = new WorldCreator(WASTELAND_WORLD)
                .generator(new WastelandGenerator())
                .seed(847_239_105L)
                .generateStructures(false);
        World world = creator.createWorld();
        if (world == null) {
            throw new IllegalStateException("Could not create the BlightBreak wasteland world.");
        }

        world.setSpawnLocation(0, WastelandGenerator.SURFACE_Y + 1, 0);
        return world;
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

        if (args.length != 1 || !args[0].equalsIgnoreCase("claim")) {
            player.sendMessage(ChatColor.RED + "Usage: /plot claim");
            return true;
        }

        Location arrival = wasteland.getSpawnLocation().add(0.5, 0, 0.5);
        player.teleportAsync(arrival);
        player.sendMessage(ChatColor.DARK_GREEN + "You step into the " + ChatColor.BOLD + "Blightbreak Wasteland" + ChatColor.DARK_GREEN + ".");
        return true;
    }

    /** Generates the barren overworld used for player plots. */
    private static final class WastelandGenerator extends ChunkGenerator {
        private static final int SURFACE_Y = 63;
        private static final Material[] SURFACE_BLOCKS = {
                Material.ROOTED_DIRT,
                Material.PODZOL,
                Material.GRAVEL,
                Material.DIRT,
                Material.DIRT_PATH
        };

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

                    chunk.setBlock(localX, 0, localZ, Material.BEDROCK);
                    chunk.setRegion(localX, 1, localZ, localX + 1, surfaceY - 3, localZ + 1, Material.STONE);
                    chunk.setRegion(localX, surfaceY - 3, localZ, localX + 1, surfaceY, localZ + 1, Material.DIRT);
                    chunk.setBlock(localX, surfaceY, localZ, surfaceMaterial(worldX, worldZ));
                }
            }

            generateDeadTrees(chunk, chunkX, chunkZ);
            return chunk;
        }

        private static int groundHeight(int x, int z) {
            // Broad, low rises keep the terrain bleak without looking perfectly flat.
            return SURFACE_Y + (int) Math.round(Math.sin(x * 0.035) * 1.4 + Math.cos(z * 0.028) * 1.2);
        }

        private static Material surfaceMaterial(int x, int z) {
            long value = hash(x, z);
            return SURFACE_BLOCKS[(int) Math.floorMod(value, (long) SURFACE_BLOCKS.length)];
        }

        private static void generateDeadTrees(ChunkData chunk, int chunkX, int chunkZ) {
            Random treeRandom = new Random(hash(chunkX, chunkZ));
            int treeCount = 1 + treeRandom.nextInt(3);
            for (int tree = 0; tree < treeCount; tree++) {
                // The border leaves enough room for every bare branch to remain in this chunk.
                int x = 4 + treeRandom.nextInt(8);
                int z = 4 + treeRandom.nextInt(8);
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                int y = groundHeight(worldX, worldZ) + 1;
                growDeadTree(chunk, treeRandom, x, y, z);
            }
        }

        private static void growDeadTree(ChunkData chunk, Random random, int x, int y, int z) {
            int height = 5 + random.nextInt(5);
            int currentX = x;
            int currentZ = z;
            Material trunk = random.nextBoolean() ? Material.DARK_OAK_LOG : Material.SPRUCE_LOG;

            for (int level = 0; level < height; level++) {
                chunk.setBlock(currentX, y + level, currentZ, trunk);
                if (level > 1 && random.nextInt(3) == 0) {
                    currentX = clamp(currentX + (random.nextBoolean() ? 1 : -1), 3, 12);
                }
                if (level > 1 && random.nextInt(3) == 0) {
                    currentZ = clamp(currentZ + (random.nextBoolean() ? 1 : -1), 3, 12);
                }
            }

            int branchY = y + height - 2;
            for (int branch = 0; branch < 2 + random.nextInt(2); branch++) {
                int directionX = random.nextBoolean() ? 1 : -1;
                int directionZ = random.nextBoolean() ? 1 : -1;
                int branchLength = 2 + random.nextInt(2);
                for (int step = 1; step <= branchLength; step++) {
                    chunk.setBlock(currentX + directionX * step, branchY + (step / 2), currentZ + directionZ * step, trunk);
                }
            }
        }

        private static long hash(int x, int z) {
            long value = 847_239_105L;
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
