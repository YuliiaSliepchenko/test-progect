package com.leonard.twister;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = TwisterMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonEvents {
    private CommonEvents() { }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.level.isClientSide()) return;
        if (event.level instanceof ServerLevel level) WeatherEngine.tick(level);
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("twister")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("intensity", IntegerArgumentType.integer(0, 5))
                                        .executes(ctx -> spawnTornado(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "intensity"), -1.0))))
                        .then(Commands.literal("tornado")
                                .then(Commands.literal("random").executes(ctx -> {
                                    int f = WeatherProfiles.chooseIntensity(ctx.getSource().getLevel().random.nextFloat());
                                    return spawnTornado(ctx.getSource(), f, -1.0);
                                }))
                                .then(Commands.literal("f0").executes(ctx -> spawnTornado(ctx.getSource(), 0, -1.0)))
                                .then(Commands.literal("f1").executes(ctx -> spawnTornado(ctx.getSource(), 1, -1.0)))
                                .then(Commands.literal("f2").executes(ctx -> spawnTornado(ctx.getSource(), 2, -1.0)))
                                .then(Commands.literal("f3").executes(ctx -> spawnTornado(ctx.getSource(), 3, -1.0)))
                                .then(Commands.literal("f4").executes(ctx -> spawnTornado(ctx.getSource(), 4, -1.0)))
                                .then(Commands.literal("f5").executes(ctx -> spawnTornado(ctx.getSource(), 5, -1.0)))
                                .then(Commands.literal("aloft")
                                        .then(Commands.argument("intensity", IntegerArgumentType.integer(0, 5))
                                                .executes(ctx -> spawnTornado(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "intensity"), 0.0))))
                                .then(Commands.literal("partial")
                                        .then(Commands.argument("intensity", IntegerArgumentType.integer(0, 5))
                                                .executes(ctx -> spawnTornado(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "intensity"), 0.62))))
                                .then(Commands.literal("touchdown")
                                        .then(Commands.argument("intensity", IntegerArgumentType.integer(0, 5))
                                                .executes(ctx -> spawnTornado(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "intensity"), 1.0)))))
                        .then(Commands.literal("weather")
                                .then(Commands.literal("cloud").executes(ctx -> spawnCloud(ctx.getSource())))
                                .then(Commands.literal("rain").executes(ctx -> spawnWeather(ctx.getSource(), "RAIN_SHOWER", false)))
                                .then(Commands.literal("weak_thunder").executes(ctx -> spawnWeather(ctx.getSource(), "WEAK_THUNDER", false)))
                                .then(Commands.literal("thunder").executes(ctx -> spawnWeather(ctx.getSource(), "THUNDERSTORM", false)))
                                .then(Commands.literal("lightning").executes(ctx -> spawnWeather(ctx.getSource(), "LIGHTNING_STORM", true)))
                                .then(Commands.literal("hail").executes(ctx -> spawnWeather(ctx.getSource(), "HAIL_STORM", true)))
                                .then(Commands.literal("severe").executes(ctx -> spawnWeather(ctx.getSource(), "SEVERE_STORM", false)))
                                .then(Commands.literal("supercell").executes(ctx -> spawnWeather(ctx.getSource(), "SUPERCELL", false)))
                                .then(Commands.literal("mesocyclone").executes(ctx -> spawnWeather(ctx.getSource(), "MESOCYCLONE", true))))
                        .then(Commands.literal("storm").executes(ctx -> spawnWeather(ctx.getSource(), "SUPERCELL", false)))
                        .then(Commands.literal("wind")
                                .then(Commands.argument("heading", DoubleArgumentType.doubleArg(0.0, 360.0))
                                        .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.0, 0.16))
                                                .executes(ctx -> {
                                                    ServerLevel level = ctx.getSource().getLevel();
                                                    double heading = DoubleArgumentType.getDouble(ctx, "heading");
                                                    double speed = DoubleArgumentType.getDouble(ctx, "speed");
                                                    WeatherEngine.setWind(level, heading, speed);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                                                            "Twister wind: %.1f deg, %.3f blocks/tick", heading, speed)), true);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())))
        );
    }

    private static int spawnTornado(CommandSourceStack source, int f, double forcedContact) {
        ServerLevel level = source.getLevel();
        Vec3 p = source.getPosition();
        double heading = WeatherEngine.getWindHeading(level);
        double a = Math.toRadians(heading);
        double x = p.x + Math.cos(a) * 62.0;
        double z = p.z + Math.sin(a) * 62.0;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
        String v = WeatherProfiles.chooseVariant(level.random.nextInt());
        double contact = forcedContact < 0.0 ? WeatherProfiles.chooseContactFactor(level.random, f) : forcedContact;
        WeatherMarker.tornado(level, x, y, z, f, v, level.random.nextLong(), heading,
                level.getGameTime(), contact);
        String contactName = contact >= 0.95 ? "touchdown" : contact >= 0.4 ? "partial-condensation" : "aloft";
        source.sendSuccess(() -> Component.literal("Spawned F" + f + " tornado, variant " + v + " (" + contactName + ")"), true);
        return 1;
    }

    private static int spawnWeather(CommandSourceStack source, String profile, boolean mature) {
        ServerLevel level = source.getLevel();
        Vec3 p = source.getPosition();
        double heading = WeatherEngine.getWindHeading(level);
        double a = Math.toRadians(heading);
        double x = p.x + Math.cos(a) * 115.0;
        double z = p.z + Math.sin(a) * 115.0;
        if (mature) WeatherEngine.spawnMatureStormAt(level, x, z, profile);
        else WeatherEngine.spawnStormAt(level, x, z, profile);
        source.sendSuccess(() -> Component.literal("Spawned localized weather: " + profile), true);
        return 1;
    }

    private static int spawnCloud(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 p = source.getPosition();
        double heading = WeatherEngine.getWindHeading(level);
        double a = Math.toRadians(heading);
        double x = p.x + Math.cos(a) * 95.0;
        double z = p.z + Math.sin(a) * 95.0;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
        WeatherMarker.cloud(level, x, y, z, "CUMULUS", level.random.nextLong(), heading,
                Math.max(0.02, WeatherEngine.getWindSpeed(level)), 58.0, 6800);
        source.sendSuccess(() -> Component.literal("Spawned fair-weather cumulus cloud"), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 p = source.getPosition();
        AABB area = new AABB(p.x - 1200, level.getMinBuildHeight(), p.z - 1200,
                p.x + 1200, level.getMaxBuildHeight(), p.z + 1200);
        List<ArmorStand> markers = level.getEntitiesOfClass(ArmorStand.class, area, WeatherMarker::isMarker);
        long clouds = 0, storms = 0, tornadoes = 0;
        for (ArmorStand marker : markers) {
            WeatherMarker.Data d = WeatherMarker.parse(marker);
            if (d == null) continue;
            switch (d.kind()) {
                case CLOUD -> clouds++;
                case STORM -> storms++;
                case TORNADO -> tornadoes++;
            }
        }
        long fClouds = clouds, fStorms = storms, fTornadoes = tornadoes;
        source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Twister: clouds=%d storms=%d tornadoes=%d | wind %.1f deg @ %.3f blocks/tick | %s",
                fClouds, fStorms, fTornadoes, WeatherEngine.getWindHeading(level), WeatherEngine.getWindSpeed(level),
                WeatherEngine.getClimateSummary(level))), false);
        return 1;
    }

    private static int clear(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 p = source.getPosition();
        AABB area = new AABB(p.x - 3000, level.getMinBuildHeight(), p.z - 3000,
                p.x + 3000, level.getMaxBuildHeight(), p.z + 3000);
        List<ArmorStand> markers = level.getEntitiesOfClass(ArmorStand.class, area, WeatherMarker::isMarker);
        markers.forEach(ArmorStand::discard);
        source.sendSuccess(() -> Component.literal("Cleared " + markers.size() + " Twister weather markers"), true);
        return markers.size();
    }
}
