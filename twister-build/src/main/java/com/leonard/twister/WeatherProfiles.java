package com.leonard.twister;

import net.minecraft.util.RandomSource;

import java.util.Locale;

public final class WeatherProfiles {
    private WeatherProfiles() { }

    public record TornadoSpec(
            int intensity,
            double windMph,
            double damageRadius,
            double suctionRadius,
            double topRadius,
            double tangentialForce,
            double inwardForce,
            double liftForce,
            double blockPower,
            int blockSamples,
            int scarSamples,
            int lifeTicks,
            double moveSpeed
    ) { }

    public record StormSpec(
            String id,
            int peakStage,
            boolean rain,
            boolean thunder,
            boolean hail,
            boolean rotating,
            double lightningChancePerSecond,
            double tornadoChance,
            double minRadius,
            double maxRadius,
            int minLifeTicks,
            int maxLifeTicks
    ) { }

    public static TornadoSpec tornado(int f) {
        return switch (Math.max(0, Math.min(5, f))) {
            case 0 -> new TornadoSpec(0, 72.0, 7.0, 17.0, 12.0, 0.095, 0.038, 0.045, 1.10, 8, 4, 2600, 0.095);
            case 1 -> new TornadoSpec(1, 96.0, 10.0, 23.0, 16.0, 0.135, 0.052, 0.064, 2.00, 12, 6, 3200, 0.105);
            case 2 -> new TornadoSpec(2, 123.0, 15.0, 31.0, 21.0, 0.180, 0.070, 0.090, 3.60, 18, 8, 3800, 0.115);
            case 3 -> new TornadoSpec(3, 154.0, 21.0, 42.0, 28.0, 0.240, 0.095, 0.125, 6.40, 26, 11, 4400, 0.125);
            case 4 -> new TornadoSpec(4, 188.0, 29.0, 57.0, 37.0, 0.320, 0.125, 0.170, 10.50, 38, 15, 5000, 0.135);
            default -> new TornadoSpec(5, 225.0, 38.0, 74.0, 49.0, 0.415, 0.160, 0.225, 17.00, 52, 20, 5600, 0.145);
        };
    }

    public static StormSpec storm(String profile) {
        String id = normalize(profile);
        return switch (id) {
            case "RAIN_SHOWER" -> new StormSpec(id, 1, true, false, false, false,
                    0.0, 0.0, 62.0, 100.0, 3200, 6500);
            case "WEAK_THUNDER" -> new StormSpec(id, 2, true, true, false, false,
                    0.012, 0.0, 74.0, 112.0, 4300, 7600);
            case "THUNDERSTORM" -> new StormSpec(id, 2, true, true, false, false,
                    0.028, 0.0, 86.0, 128.0, 5200, 8800);
            case "LIGHTNING_STORM" -> new StormSpec(id, 3, true, true, false, false,
                    0.095, 0.0, 90.0, 136.0, 5000, 8200);
            case "HAIL_STORM" -> new StormSpec(id, 3, true, true, true, false,
                    0.040, 0.0, 92.0, 142.0, 5600, 9000);
            case "SEVERE_STORM" -> new StormSpec(id, 3, true, true, true, false,
                    0.045, 0.0, 102.0, 148.0, 6500, 9800);
            case "MESOCYCLONE" -> new StormSpec(id, 4, true, true, true, true,
                    0.060, 0.28, 112.0, 165.0, 7600, 11200);
            case "SUPERCELL" -> new StormSpec(id, 4, true, true, true, true,
                    0.070, 0.46, 124.0, 184.0, 8500, 13200);
            case "SUPERCELL_CYCLE" -> new StormSpec("SUPERCELL", 4, true, true, true, true,
                    0.070, 0.46, 124.0, 184.0, 8500, 13200);
            default -> new StormSpec("RAIN_SHOWER", 1, true, false, false, false,
                    0.0, 0.0, 62.0, 100.0, 3200, 6500);
        };
    }

    public static String chooseNaturalEvent(float r) {
        if (r < 0.565f) return "FAIR";
        if (r < 0.760f) return "RAIN_SHOWER";
        if (r < 0.865f) return "WEAK_THUNDER";
        if (r < 0.935f) return "THUNDERSTORM";
        if (r < 0.978f) return "SEVERE_STORM";
        if (r < 0.993f) return "MESOCYCLONE";
        return "SUPERCELL";
    }

    public static int chooseIntensity(float r) {
        if (r < 0.31f) return 0;
        if (r < 0.62f) return 1;
        if (r < 0.80f) return 2;
        if (r < 0.91f) return 3;
        if (r < 0.975f) return 4;
        return 5;
    }

    public static String chooseVariant(int n) {
        return switch (Math.floorMod(n, 7)) {
            case 0 -> "A";
            case 1 -> "B";
            case 2 -> "C";
            case 3 -> "D";
            case 4 -> "E";
            case 5 -> "F";
            default -> "G";
        };
    }

    public static double chooseContactFactor(RandomSource random, int intensity) {
        double fullTouchChance = 0.50 + Math.min(5, Math.max(0, intensity)) * 0.065;
        double r = random.nextDouble();
        if (r < fullTouchChance) return 1.0;
        if (r < fullTouchChance + 0.23) return 0.62;
        return 0.0;
    }

    public static double condensationFloorBlocks(double contactFactor, int intensity) {
        if (contactFactor >= 0.95) return 0.5;
        if (contactFactor >= 0.40) return Math.max(8.0, 18.0 - intensity * 1.4);
        return 28.0 + Math.max(0, 3 - intensity) * 4.0;
    }

    public static boolean hasSurfaceCirculation(double contactFactor) {
        return contactFactor >= 0.40;
    }

    private static String normalize(String s) {
        return s == null ? "RAIN_SHOWER" : s.trim().toUpperCase(Locale.ROOT);
    }
}
