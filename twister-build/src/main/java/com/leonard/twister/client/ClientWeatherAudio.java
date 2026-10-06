package com.leonard.twister.client;

import com.leonard.twister.TwisterMod;
import com.leonard.twister.WeatherEngine;
import com.leonard.twister.WeatherMarker;
import com.leonard.twister.WeatherProfiles;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;

@Mod.EventBusSubscriber(modid = TwisterMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientWeatherAudio {
    private ClientWeatherAudio() { }

    private static long nextTornadoSound = 0;
    private static long nextRainSound = 0;
    private static long nextStormWindSound = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        long now = mc.level.getGameTime();
        if (now % 20L != 0L) return;

        AABB area = mc.player.getBoundingBox().inflate(430.0, 220.0, 430.0);
        List<ArmorStand> markers = mc.level.getEntitiesOfClass(ArmorStand.class, area, WeatherMarker::isMarker);
        if (markers.isEmpty()) return;
        markers.sort(Comparator.comparingDouble(m -> m.distanceToSqr(mc.player)));

        for (ArmorStand m : markers) {
            WeatherMarker.Data d = WeatherMarker.parse(m);
            if (d == null) continue;
            if (d.kind() == WeatherMarker.Kind.TORNADO && now >= nextTornadoSound) {
                double dist = Math.sqrt(m.distanceToSqr(mc.player));
                float vol = (float) Math.max(0.06, Math.min(1.85, 1.55 - dist / 285.0));
                play(mc, m, "f" + d.intensity(), vol, 0.97f + d.intensity() * 0.006f);
                nextTornadoSound = now + 500L;
                break;
            }
        }

        if (now >= nextRainSound || now >= nextStormWindSound) {
            for (ArmorStand m : markers) {
                WeatherMarker.Data d = WeatherMarker.parse(m);
                if (d == null || d.kind() != WeatherMarker.Kind.STORM) continue;
                WeatherProfiles.StormSpec spec = WeatherProfiles.storm(d.subtype());
                double strength = WeatherEngine.stormStrength(d, now);
                double dx = mc.player.getX() - m.getX();
                double dz = mc.player.getZ() - m.getZ();
                double dist2 = dx * dx + dz * dz;
                if (dist2 >= d.radius() * d.radius()) continue;

                if (spec.rain() && strength >= 0.72 && now >= nextRainSound) {
                    play(mc, m, "rain_loop", (float) Math.min(0.82, 0.24 + strength * 0.11),
                            (float) (0.97 + Math.min(0.05, strength * 0.008)));
                    nextRainSound = now + 720L;
                }

                if (strength >= 2.25 && now >= nextStormWindSound) {
                    play(mc, m, "eas_storm", (float) Math.min(1.10, 0.18 + strength * 0.17),
                            (float) (0.93 + strength * 0.012));
                    nextStormWindSound = now + 820L;
                }
                break;
            }
        }
    }

    private static void play(Minecraft mc, ArmorStand marker, String id, float volume, float pitch) {
        SoundEvent event = SoundEvent.createVariableRangeEvent(new ResourceLocation(TwisterMod.MODID, id));
        mc.level.playLocalSound(marker.getX(), marker.getY(), marker.getZ(), event,
                SoundSource.WEATHER, volume, pitch, false);
    }
}
