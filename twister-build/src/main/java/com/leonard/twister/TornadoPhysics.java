package com.leonard.twister;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TornadoPhysics {
    private TornadoPhysics() { }

    private static final RandomSource RNG = RandomSource.create();
    public static final int FUNNEL_DESCENT_TICKS = 17 * 20;
    private static final int DISSIPATION_TICKS = 180;

    public static void tick(ServerLevel level, ArmorStand marker, WeatherMarker.Data data) {
        WeatherProfiles.TornadoSpec spec = WeatherProfiles.tornado(data.intensity());
        long age = data.age(level.getGameTime());
        if (age >= data.maxAge()) return;

        double contact = data.extra();
        double circulation = surfaceCirculation(age, data.maxAge(), contact);

        if ((level.getGameTime() & 1L) == 0L) affectEntities(level, marker, spec, contact, circulation);

        if (circulation > 0.08) {
            if (level.getGameTime() % 3L == 0L) damageStructures(level, marker, spec, circulation);
            if ((level.getGameTime() & 1L) == 0L) scarGround(level, marker, spec, circulation);
        }
    }

    public static double surfaceCirculation(long age, int maxAge, double contactFactor) {
        if (!WeatherProfiles.hasSurfaceCirculation(contactFactor)) return 0.0;
        double down = smoothstep(clamp01((age - FUNNEL_DESCENT_TICKS * 0.62) / (FUNNEL_DESCENT_TICKS * 0.38)));
        double out = smoothstep(clamp01((maxAge - age) / (double) DISSIPATION_TICKS));
        return Math.min(1.0, down * out * (0.72 + contactFactor * 0.28));
    }

    private static void affectEntities(ServerLevel level, ArmorStand marker, WeatherProfiles.TornadoSpec spec,
                                       double contactFactor, double circulation) {
        double r = spec.suctionRadius();
        double floor = marker.getY() + WeatherProfiles.condensationFloorBlocks(contactFactor, spec.intensity());
        double minY = WeatherProfiles.hasSurfaceCirculation(contactFactor) ? marker.getY() - 5.0 : floor - 6.0;
        AABB box = new AABB(marker.getX() - r, minY, marker.getZ() - r,
                marker.getX() + r, marker.getY() + 170.0, marker.getZ() + r);

        List<Entity> entities = level.getEntities(marker, box, Entity::isAlive);
        for (Entity e : entities) {
            if (e instanceof ArmorStand as && WeatherMarker.isMarker(as)) continue;
            if (e instanceof Player p && p.isCreative() && p.getAbilities().flying) continue;

            double dx = e.getX() - marker.getX();
            double dz = e.getZ() - marker.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.35 || dist > r) continue;
            if (!WeatherProfiles.hasSurfaceCirculation(contactFactor) && e.getY() < floor - 3.0) continue;

            double closeness = 1.0 - dist / r;
            double nx = dx / dist;
            double nz = dz / dist;
            double tx = -nz;
            double tz = nx;

            double surfaceScale = WeatherProfiles.hasSurfaceCirculation(contactFactor)
                    ? Math.max(0.22, circulation)
                    : 0.52;
            double tangential = spec.tangentialForce() * (0.42 + 1.28 * closeness) * surfaceScale;
            double inward = spec.inwardForce() * (0.30 + 1.45 * closeness) * surfaceScale;
            double lift = spec.liftForce() * (0.22 + 1.85 * closeness) * surfaceScale;
            if (e instanceof ItemEntity || e instanceof FallingBlockEntity) lift *= 1.55;

            double relY = Math.max(0.0, e.getY() - marker.getY());
            double heightScale = 0.85 + Math.min(0.35, relY / 240.0);

            Vec3 old = e.getDeltaMovement();
            Vec3 force = new Vec3((tx * tangential - nx * inward) * heightScale,
                    lift * heightScale,
                    (tz * tangential - nz * inward) * heightScale);
            e.setDeltaMovement(old.scale(0.84).add(force));
            e.hurtMarked = true;
            e.fallDistance = 0.0F;

            if (circulation > 0.55 && spec.intensity() >= 3 && dist < spec.damageRadius() * 0.65
                    && RNG.nextFloat() < 0.010f * spec.intensity()) {
                e.hurt(level.damageSources().generic(), 0.8F + 0.65F * spec.intensity());
            }
        }
    }

    private static void damageStructures(ServerLevel level, ArmorStand marker, WeatherProfiles.TornadoSpec spec,
                                         double circulation) {
        int samples = Math.max(1, (int) Math.round(spec.blockSamples() * circulation));
        double radius = spec.damageRadius();

        for (int i = 0; i < samples; i++) {
            double a = RNG.nextDouble() * Math.PI * 2.0;
            double radial01 = 0.18 + Math.sqrt(RNG.nextDouble()) * 0.82;
            double rr = radial01 * radius;
            int x = (int) Math.floor(marker.getX() + Math.cos(a) * rr);
            int z = (int) Math.floor(marker.getZ() + Math.sin(a) * rr);

            int terrainY = BlockWindResistance.terrainBaseY(level, x, z);
            int columnTop = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            if (columnTop <= terrainY) continue;

            BlockPos seed = findExposedSeed(level, marker, spec, x, z, terrainY, columnTop);
            if (seed == null) continue;

            BlockState state = level.getBlockState(seed);
            int required = BlockWindResistance.requiredEf(level, seed, state);
            if (required > spec.intensity()) continue;

            double ringPeak = Math.exp(-Math.pow((radial01 - 0.48) / 0.34, 2.0));
            int exposure = BlockWindResistance.exposedFaces(level, seed);
            int support = BlockWindResistance.supportCount(level, seed);
            double margin = spec.intensity() - required + 1.0;
            double chance = (0.055 + margin * 0.105) * (0.55 + ringPeak * 0.72) * circulation;
            chance *= 0.76 + exposure * 0.11;
            chance /= 1.0 + Math.max(0, support - 2) * 0.18;
            if (RNG.nextDouble() > Math.min(0.88, chance)) continue;

            peelPatch(level, marker, spec, seed, circulation);
        }
    }

    private static BlockPos findExposedSeed(ServerLevel level, ArmorStand marker, WeatherProfiles.TornadoSpec spec,
                                            int x, int z, int terrainY, int columnTop) {
        int depth = 10 + spec.intensity() * 5;
        int bottom = Math.max(terrainY + 1, columnTop - depth);
        double rx = x + 0.5 - marker.getX();
        double rz = z + 0.5 - marker.getZ();

        for (int y = columnTop; y >= bottom; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (BlockWindResistance.isTerrainProtected(level, pos)) continue;
            if (BlockWindResistance.requiredEf(level, pos, state) > spec.intensity()) continue;
            if (BlockWindResistance.isWindExposed(level, pos, rx, rz)) return pos;
        }
        return null;
    }

    private static void peelPatch(ServerLevel level, ArmorStand marker, WeatherProfiles.TornadoSpec spec,
                                  BlockPos seed, double circulation) {
        int maxPatch = Math.max(1, 1 + spec.intensity() * 2 + (int) Math.floor(circulation * (1 + spec.intensity())));
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(seed);

        int removed = 0;
        while (!queue.isEmpty() && removed < maxPatch) {
            BlockPos pos = queue.poll();
            if (!seen.add(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || BlockWindResistance.isTerrainProtected(level, pos)) continue;

            int required = BlockWindResistance.requiredEf(level, pos, state);
            if (required > spec.intensity()) continue;

            double dx = pos.getX() + 0.5 - marker.getX();
            double dz = pos.getZ() + 0.5 - marker.getZ();
            int exposure = BlockWindResistance.exposedFaces(level, pos);
            int supports = BlockWindResistance.supportCount(level, pos);
            if (removed > 0 && exposure == 0 && supports >= 4) continue;
            if (!BlockWindResistance.isWindExposed(level, pos, dx, dz) && supports >= 3) continue;

            double localChance = removed == 0 ? 1.0
                    : Math.min(0.92, 0.30 + 0.105 * spec.intensity() + 0.08 * exposure + 0.10 * circulation);
            if (RNG.nextDouble() > localChance) continue;

            ripBlock(level, marker, spec, pos, state);
            removed++;

            Direction[] order = { Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN };
            for (Direction d : order) {
                BlockPos n = pos.relative(d);
                if (!seen.contains(n)) queue.add(n);
            }

            collapseUnsupportedAbove(level, marker, spec, pos);
        }
    }

    private static void collapseUnsupportedAbove(ServerLevel level, ArmorStand marker, WeatherProfiles.TornadoSpec spec,
                                                 BlockPos base) {
        int checks = 2 + spec.intensity();
        for (int dy = 1; dy <= checks; dy++) {
            BlockPos pos = base.above(dy);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || BlockWindResistance.isTerrainProtected(level, pos)) break;
            int required = BlockWindResistance.requiredEf(level, pos, state);
            if (required > spec.intensity()) break;
            int support = BlockWindResistance.supportCount(level, pos);
            if (support > 1) break;
            if (RNG.nextDouble() < 0.28 + spec.intensity() * 0.08) {
                ripBlock(level, marker, spec, pos, state);
            }
        }
    }

    private static void ripBlock(ServerLevel level, ArmorStand marker, WeatherProfiles.TornadoSpec spec,
                                 BlockPos pos, BlockState state) {
        if (BlockWindResistance.canBecomeFlyingDebris(level, pos, state, spec.intensity())
                && RNG.nextFloat() < 0.20f + spec.intensity() * 0.045f) {
            FallingBlockEntity debris = FallingBlockEntity.fall(level, pos, state);
            double dx = debris.getX() - marker.getX();
            double dz = debris.getZ() - marker.getZ();
            double d = Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
            double tangential = 0.12 + spec.intensity() * 0.025;
            double inward = 0.06 + spec.intensity() * 0.010;
            debris.setDeltaMovement(-dz / d * tangential - dx / d * inward,
                    0.22 + 0.050 * spec.intensity(),
                    dx / d * tangential - dz / d * inward);
            debris.setHurtsEntities(1.0F + spec.intensity(), 16 + spec.intensity() * 8);
        } else {
            level.destroyBlock(pos, RNG.nextFloat() < 0.08f);
        }
    }

    private static void scarGround(ServerLevel level, ArmorStand marker, WeatherProfiles.TornadoSpec spec,
                                   double circulation) {
        double scarRadius = Math.max(3.0, spec.damageRadius() * 0.34);
        int samples = Math.max(1, (int) Math.round(spec.scarSamples() * circulation));

        for (int i = 0; i < samples; i++) {
            double a = RNG.nextDouble() * Math.PI * 2.0;
            double rr = Math.sqrt(RNG.nextDouble()) * scarRadius;
            int x = (int) Math.floor(marker.getX() + Math.cos(a) * rr);
            int z = (int) Math.floor(marker.getZ() + Math.sin(a) * rr);
            int terrainY = BlockWindResistance.terrainBaseY(level, x, z);
            BlockPos ground = new BlockPos(x, terrainY, z);
            BlockPos above = ground.above();
            BlockState gs = level.getBlockState(ground);
            BlockState as = level.getBlockState(above);

            if (BlockWindResistance.isSurfaceVegetation(as)) {
                level.destroyBlock(above, false);
            }

            float scour = (float) Math.min(0.85, 0.12 + spec.intensity() * 0.08 + circulation * 0.22);
            if (RNG.nextFloat() > scour) continue;

            if (gs.is(Blocks.GRASS_BLOCK) || gs.is(Blocks.DIRT_PATH) || gs.is(Blocks.FARMLAND)
                    || gs.is(Blocks.PODZOL) || gs.is(Blocks.MYCELIUM)) {
                level.setBlockAndUpdate(ground, RNG.nextFloat() < 0.58f
                        ? Blocks.DIRT.defaultBlockState()
                        : Blocks.COARSE_DIRT.defaultBlockState());
            } else if (gs.is(Blocks.DIRT) && RNG.nextFloat() < 0.22f) {
                level.setBlockAndUpdate(ground, Blocks.COARSE_DIRT.defaultBlockState());
            }
        }
    }

    private static double clamp01(double x) { return Math.max(0.0, Math.min(1.0, x)); }
    private static double smoothstep(double x) { x = clamp01(x); return x * x * (3.0 - 2.0 * x); }
}
