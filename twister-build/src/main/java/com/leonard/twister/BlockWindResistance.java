package com.leonard.twister;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

public final class BlockWindResistance {
    private BlockWindResistance() { }

    public static final int NEVER = 99;
    private static final Map<Long, TerrainCache> TERRAIN_CACHE = new HashMap<>();
    private record TerrainCache(long tickBucket, int y) { }

    public static int requiredEf(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || isPermanent(state)) return NEVER;

        String p = path(state);
        float hardness = state.getDestroySpeed(level, pos);
        float blast = state.getBlock().getExplosionResistance();
        if (hardness < 0.0F) return NEVER;

        if (state.is(BlockTags.LEAVES)
                || containsAny(p, "grass", "fern", "flower", "sapling", "vine", "bush", "mushroom",
                "torch", "candle", "carpet", "banner", "sign", "rail", "button", "pressure_plate",
                "scaffolding", "ladder", "lantern", "chain", "tripwire", "coral", "azalea")) {
            return 0;
        }

        if (containsAny(p, "glass", "pane", "wool", "hay_block", "leaves", "bamboo_mosaic")) return 0;

        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.WOOL) || state.is(BlockTags.FENCES)
                || containsAny(p, "door", "trapdoor", "fence", "slab", "stairs", "bookshelf", "barrel", "beehive")) {
            if (isWoodName(p)) return 1;
        }

        if (state.is(BlockTags.LOGS) || containsAny(p, "log", "wood", "stem", "hyphae")) return 2;
        if (isWoodName(p)) return 1;
        if (containsAny(p, "cobblestone", "brick", "terracotta", "mud_brick", "calcite", "tuff", "quartz")) return 3;
        if (containsAny(p, "stone", "deepslate", "andesite", "diorite", "granite", "concrete", "basalt", "blackstone")) return 4;
        if (containsAny(p, "iron", "copper", "gold", "diamond", "emerald", "lapis", "redstone_block", "amethyst")) return 5;
        if (containsAny(p, "obsidian", "netherite", "ancient_debris")) return NEVER;

        double score = Math.max(0.0, hardness) * 0.80 + Math.max(0.0, blast) * 0.10;
        if (score < 1.0) return 0;
        if (score < 2.4) return 1;
        if (score < 4.8) return 2;
        if (score < 8.5) return 3;
        if (score < 15.0) return 4;
        if (score < 30.0) return 5;
        return NEVER;
    }

    public static int terrainBaseY(ServerLevel level, int x, int z) {
        long bucket = level.getGameTime() / 20L;
        long dimMix = (long) level.dimension().location().hashCode() * 0x9E3779B97F4A7C15L;
        long key = ((((long) x) << 32) ^ (z & 0xffffffffL)) ^ dimMix;
        TerrainCache cached = TERRAIN_CACHE.get(key);
        if (cached != null && cached.tickBucket() == bucket) return cached.y();
        if (TERRAIN_CACHE.size() > 16384) TERRAIN_CACHE.clear();

        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int min = Math.max(level.getMinBuildHeight(), top - 96);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);

        for (int y = top; y >= min; y--) {
            p.set(x, y, z);
            BlockState s0 = level.getBlockState(p);
            if (!isTerrainLike(s0)) continue;

            int mass = 0;
            for (int d = 0; d < 4; d++) {
                p.set(x, y - d, z);
                if (isTerrainLike(level.getBlockState(p))) mass++;
            }
            if (mass >= 3) {
                TERRAIN_CACHE.put(key, new TerrainCache(bucket, y));
                return y;
            }
        }
        int fallback = Math.max(level.getMinBuildHeight(), top);
        TERRAIN_CACHE.put(key, new TerrainCache(bucket, fallback));
        return fallback;
    }

    public static boolean isTerrainProtected(ServerLevel level, BlockPos pos) {
        return pos.getY() <= terrainBaseY(level, pos.getX(), pos.getZ());
    }

    public static int exposedFaces(ServerLevel level, BlockPos pos) {
        int exposed = 0;
        for (Direction d : Direction.values()) {
            BlockState n = level.getBlockState(pos.relative(d));
            if (n.isAir() || !n.getFluidState().isEmpty() || n.getCollisionShape(level, pos.relative(d)).isEmpty()) exposed++;
        }
        return exposed;
    }

    public static boolean isWindExposed(ServerLevel level, BlockPos pos, double radialX, double radialZ) {
        if (exposedFaces(level, pos) > 0) return true;

        Direction radial = Math.abs(radialX) > Math.abs(radialZ)
                ? (radialX > 0 ? Direction.EAST : Direction.WEST)
                : (radialZ > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockState n = level.getBlockState(pos.relative(radial));
        return n.isAir() || n.getCollisionShape(level, pos.relative(radial)).isEmpty();
    }

    public static int supportCount(ServerLevel level, BlockPos pos) {
        int support = 0;
        for (Direction d : Direction.values()) {
            if (d == Direction.UP) continue;
            BlockPos q = pos.relative(d);
            BlockState n = level.getBlockState(q);
            if (!n.isAir() && n.isFaceSturdy(level, q, d.getOpposite())) support++;
        }
        return support;
    }

    public static boolean canBecomeFlyingDebris(ServerLevel level, BlockPos pos, BlockState state, int ef) {
        if (state.hasBlockEntity() || !state.getFluidState().isEmpty()) return false;
        if (isPermanent(state) || isTerrainProtected(level, pos)) return false;
        float hardness = state.getDestroySpeed(level, pos);
        float blast = state.getBlock().getExplosionResistance();
        return hardness >= 0.0F && hardness <= (2.4F + ef * 1.15F) && blast <= (8.0F + ef * 5.0F);
    }

    public static boolean isSurfaceVegetation(BlockState state) {
        String p = path(state);
        return state.is(BlockTags.LEAVES) || containsAny(p,
                "grass", "fern", "flower", "sapling", "vine", "bush", "mushroom", "snow", "dead_bush");
    }

    private static boolean isTerrainLike(BlockState state) {
        if (state.isAir()) return false;
        String p = path(state);
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL)
                || containsAny(p, "stone", "deepslate", "andesite", "diorite", "granite", "tuff", "calcite",
                "sandstone", "clay", "mud", "netherrack", "end_stone");
    }

    private static boolean isPermanent(BlockState state) {
        return state.is(Blocks.BEDROCK) || state.is(Blocks.END_PORTAL_FRAME) || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.REINFORCED_DEEPSLATE)
                || containsAny(path(state), "command_block", "structure_block", "jigsaw", "barrier", "moving_piston");
    }

    private static boolean isWoodName(String p) {
        return containsAny(p, "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped");
    }

    private static String path(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }
}
