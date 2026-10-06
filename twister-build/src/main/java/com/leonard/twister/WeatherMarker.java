package com.leonard.twister;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;

public final class WeatherMarker {
    private WeatherMarker() { }
    public static final String PREFIX = "TWR|";
    public static final String TAG = "twister_marker";

    public enum Kind { TORNADO, STORM, CLOUD }

    public record Data(
            Kind kind,
            String subtype,
            int intensity,
            long seed,
            long startTick,
            int maxAge,
            double headingDeg,
            double speed,
            double radius,
            double extra
    ) {
        public long age(long now) { return Math.max(0L, now - startTick); }
        public double life(long now) { return maxAge <= 0 ? 1.0 : Math.min(1.0, age(now) / (double) maxAge); }
    }

    public static boolean isMarker(ArmorStand stand) {
        if (stand == null || stand.getCustomName() == null) return false;
        return stand.getCustomName().getString().startsWith(PREFIX);
    }

    public static Data parse(ArmorStand stand) {
        if (!isMarker(stand)) return null;
        String[] p = stand.getCustomName().getString().split("\\|");
        try {
            if (p.length < 10) return null;
            Kind kind = switch (p[1]) {
                case "T" -> Kind.TORNADO;
                case "S" -> Kind.STORM;
                default -> Kind.CLOUD;
            };
            String subtype = p[2];
            int intensity = Integer.parseInt(p[3]);
            long seed = Long.parseLong(p[4]);
            long start = Long.parseLong(p[5]);
            int maxAge = Integer.parseInt(p[6]);
            double heading = Double.parseDouble(p[7]);
            double speed = Double.parseDouble(p[8]);
            double radius = Double.parseDouble(p[9]);
            double extra = p.length > 10 ? Double.parseDouble(p[10]) : 0.0;
            return new Data(kind, subtype, intensity, seed, start, maxAge, heading, speed, radius, extra);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static ArmorStand create(ServerLevel level, double x, double y, double z, String encoded) {
        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) return null;
        marker.setPos(x, y, z);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.setMarker(true);
        marker.setCustomNameVisible(false);
        marker.setCustomName(Component.literal(encoded));
        marker.addTag(TAG);
        level.addFreshEntity(marker);
        return marker;
    }

    public static ArmorStand tornado(ServerLevel level, double x, double y, double z, int intensity, String variant,
                                      long seed, double heading, long startTick) {
        double contact = WeatherProfiles.chooseContactFactor(level.random, intensity);
        return tornado(level, x, y, z, intensity, variant, seed, heading, startTick, contact);
    }

    public static ArmorStand tornado(ServerLevel level, double x, double y, double z, int intensity, String variant,
                                      long seed, double heading, long startTick, double contactFactor) {
        WeatherProfiles.TornadoSpec spec = WeatherProfiles.tornado(intensity);
        String n = String.format(java.util.Locale.ROOT,
                "TWR|T|%s|%d|%d|%d|%d|%.3f|%.5f|%.3f|%.3f",
                variant, intensity, seed, startTick, spec.lifeTicks(), heading, spec.moveSpeed(),
                spec.suctionRadius(), Math.max(0.0, Math.min(1.0, contactFactor)));
        return create(level, x, y, z, n);
    }

    public static ArmorStand storm(ServerLevel level, double x, double y, double z, String profile, long seed,
                                    double heading, double speed, double radius, int lifeTicks, double tornadoChance) {
        return storm(level, x, y, z, profile, seed, heading, speed, radius, lifeTicks, tornadoChance, 0);
    }

    public static ArmorStand storm(ServerLevel level, double x, double y, double z, String profile, long seed,
                                    double heading, double speed, double radius, int lifeTicks, double tornadoChance,
                                    int initialAgeTicks) {
        long start = level.getGameTime() - Math.max(0, Math.min(lifeTicks - 1, initialAgeTicks));
        String n = String.format(java.util.Locale.ROOT,
                "TWR|S|%s|0|%d|%d|%d|%.3f|%.5f|%.3f|%.3f",
                profile, seed, start, lifeTicks, heading, speed, radius, tornadoChance);
        return create(level, x, y, z, n);
    }

    public static ArmorStand cloud(ServerLevel level, double x, double y, double z, String profile, long seed,
                                    double heading, double speed, double radius, int lifeTicks) {
        String n = String.format(java.util.Locale.ROOT,
                "TWR|C|%s|0|%d|%d|%d|%.3f|%.5f|%.3f|0",
                profile, seed, level.getGameTime(), lifeTicks, heading, speed, radius);
        return create(level, x, y, z, n);
    }
}
