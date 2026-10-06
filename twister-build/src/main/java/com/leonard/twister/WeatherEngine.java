package com.leonard.twister;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WeatherEngine {
    private WeatherEngine() { }

    private static final RandomSource RNG = RandomSource.create();
    private static final double SCAN_RADIUS = 1050.0;
    private static final Map<ResourceKey<Level>, ClimateState> CLIMATE = new HashMap<>();

    private static final class ClimateState {
        long nextCloudTick;
        long nextEventTick;
        long nextWindTick;
        double windHeadingDeg;
        double windSpeed;
        double humidity;
        double instability;
        double shear;
        double pressure;

        ClimateState(long now) {
            nextCloudTick = now + 40;
            nextEventTick = now + 1800 + RNG.nextInt(2600);
            nextWindTick = now + 100;
            windHeadingDeg = RNG.nextDouble() * 360.0;
            windSpeed = 0.030 + RNG.nextDouble() * 0.025;
            humidity = 0.34 + RNG.nextDouble() * 0.48;
            instability = 0.18 + RNG.nextDouble() * 0.54;
            shear = 0.16 + RNG.nextDouble() * 0.50;
            pressure = 0.40 + RNG.nextDouble() * 0.28;
        }
    }

    public static void tick(ServerLevel level) {
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        long time = level.getGameTime();

        Set<Integer> handled = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            AABB area = player.getBoundingBox().inflate(SCAN_RADIUS, 300.0, SCAN_RADIUS);
            List<ArmorStand> markers = level.getEntitiesOfClass(ArmorStand.class, area, WeatherMarker::isMarker);
            for (ArmorStand marker : markers) {
                if (handled.add(marker.getId())) tickMarker(level, marker);
            }
        }

        tickClimate(level, time);
    }

    private static void tickMarker(ServerLevel level, ArmorStand marker) {
        WeatherMarker.Data d = WeatherMarker.parse(marker);
        if (d == null) {
            marker.discard();
            return;
        }
        long age = d.age(level.getGameTime());
        if (age >= d.maxAge()) {
            marker.discard();
            return;
        }

        double rad = Math.toRadians(d.headingDeg());
        double dx = Math.cos(rad) * d.speed();
        double dz = Math.sin(rad) * d.speed();
        marker.setPos(marker.getX() + dx, marker.getY(), marker.getZ() + dz);

        if (d.kind() == WeatherMarker.Kind.TORNADO && level.getGameTime() % 10L == 0L) {
            int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Mth.floor(marker.getX()), Mth.floor(marker.getZ()));
            marker.setPos(marker.getX(), sy, marker.getZ());
        }

        if (d.kind() == WeatherMarker.Kind.TORNADO) {
            TornadoPhysics.tick(level, marker, d);
        } else if (d.kind() == WeatherMarker.Kind.STORM) {
            tickStorm(level, marker, d);
        }
    }

    private static void tickStorm(ServerLevel level, ArmorStand marker, WeatherMarker.Data d) {
        WeatherProfiles.StormSpec spec = WeatherProfiles.storm(d.subtype());
        double strength = stormStrength(d, level.getGameTime());

        if (spec.thunder() && strength >= 1.35 && level.getGameTime() % 20L == 0L) {
            double normalized = strength / Math.max(1.0, spec.peakStage());
            if (RNG.nextDouble() < spec.lightningChancePerSecond() * (0.45 + normalized * 0.80)) {
                spawnLightning(level, marker, d.radius());
            }
        }

        if (strength >= 2.35 && (level.getGameTime() & 1L) == 0L) {
            applyStormWind(level, marker, d, strength);
        }

        if (spec.hail() && strength >= 2.75 && level.getGameTime() % 20L == 0L) {
            applyHail(level, marker, d, strength);
        }

        if (spec.rotating() && strength >= 3.45 && !marker.getTags().contains("twister_tornado_decided")) {
            marker.addTag("twister_tornado_decided");
            double chance = d.extra() > 0.0 ? d.extra() : spec.tornadoChance();
            if (RNG.nextDouble() < chance) {
                int intensity = WeatherProfiles.chooseIntensity(RNG.nextFloat());
                String variant = WeatherProfiles.chooseVariant(RNG.nextInt());
                double offsetA = RNG.nextDouble() * Math.PI * 2.0;
                double offsetR = d.radius() * (0.06 + RNG.nextDouble() * 0.18);
                double x = marker.getX() + Math.cos(offsetA) * offsetR;
                double z = marker.getZ() + Math.sin(offsetA) * offsetR;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
                double contact = WeatherProfiles.chooseContactFactor(level.random, intensity);
                WeatherMarker.tornado(level, x, y, z, intensity, variant, RNG.nextLong(),
                        d.headingDeg() + RNG.nextGaussian() * 7.0, level.getGameTime(), contact);
            }
        }
    }

    public static double stormStrength(WeatherMarker.Data d, long now) {
        WeatherProfiles.StormSpec spec = WeatherProfiles.storm(d.subtype());
        double life = d.life(now);
        double ramp = smoothstep(clamp01(life / 0.28));
        double decay = smoothstep(clamp01((1.0 - life) / 0.18));
        return spec.peakStage() * Math.min(ramp, decay);
    }

    public static int stormPhase(WeatherMarker.Data d, long now) {
        double s = stormStrength(d, now);
        if (s < 0.45) return 0;
        if (s < 1.45) return 1;
        if (s < 2.45) return 2;
        if (s < 3.45) return 3;
        return 4;
    }

    public static int stormPhase(double life) {
        if (life < 0.14) return 0;
        if (life < 0.31) return 1;
        if (life < 0.58) return 2;
        if (life < 0.78) return 3;
        if (life < 0.91) return 4;
        return 1;
    }

    private static void spawnLightning(ServerLevel level, ArmorStand storm, double radius) {
        double a = RNG.nextDouble() * Math.PI * 2.0;
        double rr = Math.sqrt(RNG.nextDouble()) * radius * 0.82;
        int x = Mth.floor(storm.getX() + Math.cos(a) * rr);
        int z = Mth.floor(storm.getZ() + Math.sin(a) * rr);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(x + 0.5, y, z + 0.5);
            level.addFreshEntity(bolt);
        }
    }

    private static void applyStormWind(ServerLevel level, ArmorStand storm, WeatherMarker.Data d, double strength) {
        double radius = d.radius() * (0.78 + Math.min(0.22, strength * 0.04));
        AABB area = new AABB(storm.getX() - radius, storm.getY() - 8.0, storm.getZ() - radius,
                storm.getX() + radius, storm.getY() + 190.0, storm.getZ() + radius);
        List<Entity> entities = level.getEntities(storm, area, e -> e.isAlive() && !(e instanceof ArmorStand as && WeatherMarker.isMarker(as)));
        double h = Math.toRadians(d.headingDeg());
        double wx = Math.cos(h);
        double wz = Math.sin(h);

        for (Entity e : entities) {
            if (e instanceof Player p && p.isCreative() && p.getAbilities().flying) continue;
            if (!level.canSeeSky(e.blockPosition().above())) continue;
            double dist = Math.sqrt(e.distanceToSqr(storm.getX(), e.getY(), storm.getZ()));
            if (dist > radius) continue;
            double edge = 1.0 - dist / radius;
            double gust = 0.006 + strength * 0.0045 + Math.sin((level.getGameTime() + e.getId() * 13L) * 0.17) * 0.0025;
            if (e instanceof ItemEntity) gust *= 1.65;
            Vec3 old = e.getDeltaMovement();
            e.setDeltaMovement(old.add(wx * gust * (0.55 + edge), Math.max(0.0, gust * 0.16), wz * gust * (0.55 + edge)));
            e.hurtMarked = true;
        }
    }

    private static void applyHail(ServerLevel level, ArmorStand storm, WeatherMarker.Data d, double strength) {
        double radius = d.radius() * 0.86;
        AABB area = new AABB(storm.getX() - radius, storm.getY() - 4.0, storm.getZ() - radius,
                storm.getX() + radius, storm.getY() + 120.0, storm.getZ() + radius);
        List<LivingEntity> living = level.getEntitiesOfClass(LivingEntity.class, area,
                e -> e.isAlive() && level.canSeeSky(e.blockPosition().above()));
        for (LivingEntity e : living) {
            if (e instanceof Player p && p.isCreative()) continue;
            double dx = e.getX() - storm.getX();
            double dz = e.getZ() - storm.getZ();
            if (dx * dx + dz * dz > radius * radius) continue;
            if (RNG.nextFloat() < 0.018f + (float) strength * 0.004f) {
                e.hurt(level.damageSources().generic(), 0.5F);
            }
        }
    }

    private static void tickClimate(ServerLevel level, long now) {
        ClimateState c = CLIMATE.computeIfAbsent(level.dimension(), k -> new ClimateState(now));

        if (now >= c.nextWindTick) {
            c.nextWindTick = now + 100;
            c.windHeadingDeg = wrapDegrees(c.windHeadingDeg + RNG.nextGaussian() * 1.8);
            c.windSpeed = clamp(c.windSpeed + RNG.nextGaussian() * 0.0025, 0.018, 0.082);

            double tod = (level.getDayTime() % 24000L) / 24000.0;
            double solar = Math.max(0.0, Math.sin((tod - 0.18) * Math.PI * 2.0));
            c.humidity = clamp(c.humidity + RNG.nextGaussian() * 0.012 + (0.50 - c.humidity) * 0.008, 0.16, 0.96);
            c.instability = clamp(c.instability + RNG.nextGaussian() * 0.014 + solar * 0.010 - 0.004, 0.05, 0.98);
            c.shear = clamp(c.shear + RNG.nextGaussian() * 0.010 + Math.abs(c.windSpeed - 0.045) * 0.025, 0.05, 0.96);
            c.pressure = clamp(c.pressure + RNG.nextGaussian() * 0.008 + (0.52 - c.pressure) * 0.005, 0.08, 0.94);
        }

        if (now >= c.nextCloudTick) {
            c.nextCloudTick = now + 160 + RNG.nextInt(180);
            maintainFairWeatherClouds(level, c);
        }

        if (now >= c.nextEventTick) {
            String event = chooseEventFromAirMass(c);
            if ("FAIR".equals(event)) {
                spawnFairCloudBank(level, c);
            } else {
                spawnNaturalEvent(level, c, event);
                c.humidity = clamp(c.humidity - 0.035, 0.12, 0.96);
                c.instability = clamp(c.instability - (event.equals("SUPERCELL") ? 0.12 : 0.055), 0.05, 0.98);
                c.pressure = clamp(c.pressure + 0.025, 0.08, 0.94);
            }
            c.nextEventTick = now + 4200 + RNG.nextInt(8400);
        }
    }

    private static void maintainFairWeatherClouds(ServerLevel level, ClimateState c) {
        for (ServerPlayer player : level.players()) {
            AABB box = player.getBoundingBox().inflate(470.0, 240.0, 470.0);
            List<ArmorStand> nearby = level.getEntitiesOfClass(ArmorStand.class, box, WeatherMarker::isMarker);
            long cloudCount = nearby.stream().map(WeatherMarker::parse)
                    .filter(d -> d != null && d.kind() == WeatherMarker.Kind.CLOUD).count();
            if (cloudCount < 3) spawnAmbientCloud(level, player, c);
        }
    }

    private static void spawnFairCloudBank(ServerLevel level, ClimateState c) {
        for (ServerPlayer player : level.players()) {
            int amount = 1 + RNG.nextInt(2);
            for (int i = 0; i < amount; i++) spawnAmbientCloud(level, player, c);
        }
    }

    private static void spawnAmbientCloud(ServerLevel level, ServerPlayer player, ClimateState c) {
        double a = RNG.nextDouble() * Math.PI * 2.0;
        double r = 130.0 + RNG.nextDouble() * 310.0;
        double x = player.getX() + Math.cos(a) * r;
        double z = player.getZ() + Math.sin(a) * r;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        String type;
        if (c.humidity > 0.70 && c.instability < 0.40) {
            type = RNG.nextBoolean() ? "STRATUS" : "STRATOCUMULUS";
        } else if (c.instability > 0.62) {
            type = RNG.nextBoolean() ? "CUMULUS_MEDIOCRIS" : "CUMULUS";
        } else {
            type = RNG.nextBoolean() ? "CUMULUS_HUMILIS" : "CUMULUS";
        }
        double heading = wrapDegrees(c.windHeadingDeg + RNG.nextGaussian() * 5.0);
        WeatherMarker.cloud(level, x, y, z, type, RNG.nextLong(), heading,
                c.windSpeed * (0.85 + RNG.nextDouble() * 0.28),
                42.0 + RNG.nextDouble() * 52.0,
                4800 + RNG.nextInt(5200));
    }

    private static void spawnNaturalEvent(ServerLevel level, ClimateState c, String profile) {
        WeatherProfiles.StormSpec spec = WeatherProfiles.storm(profile);
        for (ServerPlayer player : level.players()) {
            AABB box = player.getBoundingBox().inflate(680.0, 260.0, 680.0);
            List<ArmorStand> nearby = level.getEntitiesOfClass(ArmorStand.class, box, WeatherMarker::isMarker);
            boolean hasStorm = nearby.stream().map(WeatherMarker::parse)
                    .anyMatch(d -> d != null && d.kind() == WeatherMarker.Kind.STORM);
            if (hasStorm) continue;

            double a = RNG.nextDouble() * Math.PI * 2.0;
            double r = 220.0 + RNG.nextDouble() * 360.0;
            double x = player.getX() + Math.cos(a) * r;
            double z = player.getZ() + Math.sin(a) * r;
            spawnStormAt(level, x, z, profile, c.windHeadingDeg + RNG.nextGaussian() * 6.0, c.windSpeed);
        }
    }

    public static ArmorStand spawnNaturalStorm(ServerLevel level, ServerPlayer player) {
        ClimateState c = CLIMATE.computeIfAbsent(level.dimension(), k -> new ClimateState(level.getGameTime()));
        String profile = WeatherProfiles.chooseNaturalEvent(Math.max(0.7601f, RNG.nextFloat()));
        if ("FAIR".equals(profile)) profile = "RAIN_SHOWER";
        double a = RNG.nextDouble() * Math.PI * 2.0;
        double r = 190.0 + RNG.nextDouble() * 310.0;
        return spawnStormAt(level, player.getX() + Math.cos(a) * r, player.getZ() + Math.sin(a) * r,
                profile, c.windHeadingDeg, c.windSpeed);
    }

    public static ArmorStand spawnStormAt(ServerLevel level, double x, double z) {
        ClimateState c = CLIMATE.computeIfAbsent(level.dimension(), k -> new ClimateState(level.getGameTime()));
        return spawnStormAt(level, x, z, "SUPERCELL", c.windHeadingDeg, Math.max(0.045, c.windSpeed));
    }

    public static ArmorStand spawnStormAt(ServerLevel level, double x, double z, String profile) {
        ClimateState c = CLIMATE.computeIfAbsent(level.dimension(), k -> new ClimateState(level.getGameTime()));
        return spawnStormAt(level, x, z, profile, c.windHeadingDeg, c.windSpeed);
    }

    private static ArmorStand spawnStormAt(ServerLevel level, double x, double z, String profile,
                                           double heading, double baseWindSpeed) {
        WeatherProfiles.StormSpec spec = WeatherProfiles.storm(profile);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        double radius = spec.minRadius() + RNG.nextDouble() * (spec.maxRadius() - spec.minRadius());
        int life = spec.minLifeTicks() + RNG.nextInt(Math.max(1, spec.maxLifeTicks() - spec.minLifeTicks() + 1));
        double tornadoChance = spec.tornadoChance() <= 0.0 ? 0.0
                : clamp(spec.tornadoChance() * (0.82 + RNG.nextDouble() * 0.36), 0.0, 0.88);
        double speed = Math.max(0.018, baseWindSpeed * (0.92 + RNG.nextDouble() * 0.28));
        return WeatherMarker.storm(level, x, y, z, spec.id(), RNG.nextLong(), wrapDegrees(heading),
                speed, radius, life, tornadoChance);
    }

    public static ArmorStand spawnMatureStormAt(ServerLevel level, double x, double z, String profile) {
        ClimateState c = CLIMATE.computeIfAbsent(level.dimension(), k -> new ClimateState(level.getGameTime()));
        WeatherProfiles.StormSpec spec = WeatherProfiles.storm(profile);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        double radius = (spec.minRadius() + spec.maxRadius()) * 0.5;
        int life = (spec.minLifeTicks() + spec.maxLifeTicks()) / 2;
        int initialAge = (int) (life * 0.36);
        return WeatherMarker.storm(level, x, y, z, spec.id(), RNG.nextLong(), c.windHeadingDeg,
                Math.max(0.035, c.windSpeed), radius, life, spec.tornadoChance(), initialAge);
    }

    public static void setWind(ServerLevel level, double headingDeg, double speed) {
        ClimateState c = CLIMATE.computeIfAbsent(level.dimension(), k -> new ClimateState(level.getGameTime()));
        c.windHeadingDeg = wrapDegrees(headingDeg);
        c.windSpeed = clamp(speed, 0.0, 0.16);
    }

    public static double getWindHeading(ServerLevel level) {
        return CLIMATE.computeIfAbsent(level.dimension(), k -> new ClimateState(level.getGameTime())).windHeadingDeg;
    }

    public static double getWindSpeed(ServerLevel level) {
        return CLIMATE.computeIfAbsent(level.dimension(), k -> new ClimateState(level.getGameTime())).windSpeed;
    }

    private static String chooseEventFromAirMass(ClimateState c) {
        double moisture = c.humidity;
        double convective = c.humidity * 0.40 + c.instability * 0.44 + (1.0 - c.pressure) * 0.16;
        double organized = c.instability * 0.46 + c.shear * 0.42 + c.humidity * 0.12;
        double r = RNG.nextDouble();

        if (moisture < 0.40 || convective < 0.36) return "FAIR";
        if (c.instability < 0.36) return r < 0.74 ? "RAIN_SHOWER" : "FAIR";
        if (convective < 0.52) return r < 0.66 ? "RAIN_SHOWER" : "WEAK_THUNDER";
        if (organized < 0.58) return r < 0.56 ? "WEAK_THUNDER" : "THUNDERSTORM";
        if (organized < 0.70 || c.shear < 0.50) return r < 0.68 ? "THUNDERSTORM" : "SEVERE_STORM";

        if (c.instability > 0.78 && c.shear > 0.72 && c.humidity > 0.62 && r > 0.79) return "SUPERCELL";
        if (c.instability > 0.68 && c.shear > 0.62 && c.humidity > 0.56 && r > 0.70) return "MESOCYCLONE";
        return r < 0.62 ? "SEVERE_STORM" : "THUNDERSTORM";
    }

    public static String getClimateSummary(ServerLevel level) {
        ClimateState c = CLIMATE.computeIfAbsent(level.dimension(), k -> new ClimateState(level.getGameTime()));
        return String.format(java.util.Locale.ROOT,
                "humidity %.0f%% | instability %.0f%% | shear %.0f%% | pressure %.0f%%",
                c.humidity * 100.0, c.instability * 100.0, c.shear * 100.0, c.pressure * 100.0);
    }

    private static double wrapDegrees(double d) {
        d %= 360.0;
        if (d < 0.0) d += 360.0;
        return d;
    }

    private static double clamp01(double x) { return Math.max(0.0, Math.min(1.0, x)); }
    private static double clamp(double x, double min, double max) { return Math.max(min, Math.min(max, x)); }
    private static double smoothstep(double x) { x = clamp01(x); return x * x * (3.0 - 2.0 * x); }
}
