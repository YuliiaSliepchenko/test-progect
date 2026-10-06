package com.leonard.twister.client;

import com.leonard.twister.TornadoPhysics;
import com.leonard.twister.TwisterMod;
import com.leonard.twister.WeatherEngine;
import com.leonard.twister.WeatherMarker;
import com.leonard.twister.WeatherProfiles;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.List;

@Mod.EventBusSubscriber(modid = TwisterMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientStormRenderer {
    private ClientStormRenderer() { }

    private static final ResourceLocation SMOKE = new ResourceLocation(TwisterMod.MODID, "textures/weather/smoke1.png");
    private static final ResourceLocation DUST = new ResourceLocation(TwisterMod.MODID, "textures/weather/fas_dust_a.png");
    private static final ResourceLocation TORNADO = new ResourceLocation(TwisterMod.MODID, "textures/weather/animated_tornado.png");
    private static final double MAX_RENDER_DISTANCE = 760.0;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        AABB scan = new AABB(cam.x - MAX_RENDER_DISTANCE, level.getMinBuildHeight(), cam.z - MAX_RENDER_DISTANCE,
                cam.x + MAX_RENDER_DISTANCE, level.getMaxBuildHeight(), cam.z + MAX_RENDER_DISTANCE);
        List<ArmorStand> markers = level.getEntitiesOfClass(ArmorStand.class, scan, WeatherMarker::isMarker);
        if (markers.isEmpty()) return;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = pose.last().pose();
        float yaw = camera.getYRot();
        double yawRad = Math.toRadians(yaw);
        double screenRightX = Math.cos(yawRad);
        double screenRightZ = Math.sin(yawRad);
        long gameTime = level.getGameTime();

        setupBlend();
        for (ArmorStand marker : markers) {
            WeatherMarker.Data d = WeatherMarker.parse(marker);
            if (d == null) continue;
            double dist2 = marker.distanceToSqr(cam.x, marker.getY(), cam.z);
            if (dist2 > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) continue;

            if (d.kind() == WeatherMarker.Kind.TORNADO) {
                renderTornado(matrix, marker, d, gameTime);
            } else if (d.kind() == WeatherMarker.Kind.STORM) {
                renderStorm(matrix, marker, d, gameTime, cam, screenRightX, screenRightZ);
            } else {
                renderAmbientCloud(matrix, marker, d, gameTime);
            }
        }
        restoreBlend();
        pose.popPose();
    }

    private static void setupBlend() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
    }

    private static void restoreBlend() {
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void useTexturedShader(ResourceLocation texture) {
        ShaderInstance custom = ClientShaders.STORM_PARTICLE;
        if (custom != null) {
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            if (custom.getUniform("CameraPos") != null) {
                custom.getUniform("CameraPos").set((float) cam.x, (float) cam.y, (float) cam.z);
            }
            RenderSystem.setShader(() -> custom);
        } else {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        }
        RenderSystem.setShaderTexture(0, texture);
    }

    private static void renderAmbientCloud(Matrix4f matrix, ArmorStand marker, WeatherMarker.Data d, long time) {
        useTexturedShader(SMOKE);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        boolean stratus = d.subtype().contains("STRATUS");
        boolean humilis = d.subtype().contains("HUMILIS");
        int n = stratus ? 62 : humilis ? 36 : 48;
        double base = marker.getY() + (stratus ? 70.0 : 88.0);
        double thickness = stratus ? 11.0 : humilis ? 14.0 : 24.0;

        for (int i = 0; i < n; i++) {
            double a = unit(d.seed() + i * 912931L) * Math.PI * 2.0;
            double rr = Math.sqrt(unit(d.seed() ^ (i * 198491317L))) * d.radius();
            double x = marker.getX() + Math.cos(a) * rr;
            double z = marker.getZ() + Math.sin(a) * rr;
            double y = base + (unit(d.seed() + i * 7717L) - 0.5) * thickness;
            double sx = 16.0 + unit(d.seed() + i * 349L) * 23.0;
            double sy = sx * (stratus ? 0.30 : 0.58);
            double sz = sx * (0.74 + unit(d.seed() + i * 997L) * 0.45);
            float shade = (float) (0.78 + unit(d.seed() + i * 83L) * 0.15);
            int frame = Math.floorMod((int) (d.seed() + i + time / 22L), 4);
            double rot = unit(d.seed() ^ (i * 73471L)) * Math.PI;
            cloudCell(b, matrix, x, y, z, sx, sy, sz, rot, 4, 1, frame,
                    shade, shade, shade + 0.008f, 0.30f);
        }
        BufferUploader.drawWithShader(b.end());
    }

    private static void renderStorm(Matrix4f matrix, ArmorStand marker, WeatherMarker.Data d, long time,
                                    Vec3 camera, double screenRightX, double screenRightZ) {
        WeatherProfiles.StormSpec spec = WeatherProfiles.storm(d.subtype());
        double strength = WeatherEngine.stormStrength(d, time);

        useTexturedShader(SMOKE);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        double base = marker.getY() + 92.0 - strength * 5.0;
        double thickness = 18.0 + strength * 10.0;
        int count = 64 + (int) (strength * 39.0);
        double radius = d.radius() * (0.58 + strength * 0.085);
        float light = (float) clamp(0.72 - strength * 0.105, 0.26, 0.72);

        for (int i = 0; i < count; i++) {
            double u = unit(d.seed() + i * 104729L);
            double a = unit(d.seed() ^ (i * 130363L)) * Math.PI * 2.0;
            double rr = Math.sqrt(u) * radius;
            double shear = strength >= 2.5 ? Math.sin(a * 2.0 + time * 0.008) * 0.075 * rr : 0.0;
            double x = marker.getX() + Math.cos(a) * rr + shear;
            double z = marker.getZ() + Math.sin(a) * rr - shear;
            double y = base + (unit(d.seed() + i * 16127L) - 0.5) * thickness;
            double sx = 22.0 + unit(d.seed() + i * 701L) * (25.0 + strength * 5.0);
            double sy = sx * (0.44 + unit(d.seed() + i * 991L) * 0.18);
            double sz = sx * (0.72 + unit(d.seed() + i * 181L) * 0.44);
            float variance = (float) ((unit(d.seed() + i * 271L) - 0.5) * 0.12);
            int frame = Math.floorMod((int) (d.seed() + i + time / 18L), 4);
            double rot = unit(d.seed() + i * 48907L) * Math.PI;
            cloudCell(b, matrix, x, y, z, sx, sy, sz, rot, 4, 1, frame,
                    clamp01(light + variance), clamp01(light + variance), clamp01(light + variance + 0.014f),
                    (float) clamp(0.40 + strength * 0.055, 0.40, 0.67));
        }

        if (spec.rotating() && strength >= 2.6) {
            double rotStrength = smoothstep((strength - 2.6) / 1.4);
            int rings = 3 + (strength > 3.7 ? 2 : 0);
            for (int ring = 0; ring < rings; ring++) {
                double rr = 20.0 + ring * 10.0 + strength * 4.2;
                int pieces = 24 + ring * 7;
                for (int j = 0; j < pieces; j++) {
                    double a = (j / (double) pieces) * Math.PI * 2.0 + time * (0.0075 + ring * 0.0015) * rotStrength;
                    double wobble = Math.sin(a * 3.0 + d.seed() * 0.001) * 4.5;
                    double x = marker.getX() + Math.cos(a) * (rr + wobble);
                    double z = marker.getZ() + Math.sin(a) * (rr + wobble);
                    double y = marker.getY() + 68.0 + ring * 2.8 + Math.sin(a * 2.0 + time * 0.014) * 3.0;
                    double size = 16.0 + ring * 2.8;
                    int frame = Math.floorMod(j + ring + (int) (time / 14L), 4);
                    cloudCell(b, matrix, x, y, z, size, size * 0.72, size * 0.82, a,
                            4, 1, frame, 0.27f, 0.28f, 0.30f, (float) (0.46 + rotStrength * 0.22));
                }
            }
        }
        BufferUploader.drawWithShader(b.end());

        double dx = camera.x - marker.getX();
        double dz = camera.z - marker.getZ();
        if (spec.rain() && strength >= 0.72 && dx * dx + dz * dz < d.radius() * d.radius()) {
            renderPrecipitation(matrix, d, time, camera, strength, spec.hail(), screenRightX, screenRightZ);
        }
    }

    private static void renderTornado(Matrix4f matrix, ArmorStand marker, WeatherMarker.Data d, long time) {
        WeatherProfiles.TornadoSpec spec = WeatherProfiles.tornado(d.intensity());
        long age = d.age(time);
        double fadeIn = smoothstep(age / (double) TornadoPhysics.FUNNEL_DESCENT_TICKS);
        double fadeOut = smoothstep((d.maxAge() - age) / 180.0);
        double visibility = Math.min(fadeIn, fadeOut);

        double ground = marker.getY() + 1.2;
        double top = marker.getY() + 118.0 + d.intensity() * 5.0;
        double targetBottom = ground + WeatherProfiles.condensationFloorBlocks(d.extra(), d.intensity());
        double descend = smoothstep(Math.min(1.0, age / (double) TornadoPhysics.FUNNEL_DESCENT_TICKS));
        double bottom = top - (top - targetBottom) * descend;
        if (fadeOut < 1.0) bottom += (1.0 - fadeOut) * 24.0;

        useTexturedShader(TORNADO);
        BufferBuilder ribbon = Tesselator.getInstance().getBuilder();
        ribbon.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int layers = 46;
        int strips = 10 + d.intensity() * 2;
        for (int i = 0; i < layers; i++) {
            double t = i / (double) (layers - 1);
            double y = bottom + (top - bottom) * t;
            double radius = funnelRadius(spec, d.subtype(), t);
            double bendX = Math.sin(t * 5.2 + time * 0.012 + d.seed() * 0.0001) * (1.0 + 5.4 * t);
            double bendZ = Math.cos(t * 4.7 + time * 0.011 + d.seed() * 0.00013) * (0.9 + 4.7 * t);
            for (int j = 0; j < strips; j++) {
                double a = j * Math.PI * 2.0 / strips + time * (0.015 + 0.0018 * d.intensity()) * (0.36 + t);
                double x = marker.getX() + bendX + Math.cos(a) * radius;
                double z = marker.getZ() + bendZ + Math.sin(a) * radius;
                double w = 3.6 + radius * 0.30;
                double h = 8.0 + radius * 0.22;
                int frame = Math.floorMod(j + i + (int) (time / 3L), 16);
                float shade = (float) (0.61 - t * 0.18 + (j % 3) * 0.020);
                tangentAtlas(ribbon, matrix, x, y, z, w, h, a,
                        16, 1, frame, shade, shade, shade,
                        (float) ((0.22 + 0.26 * (1.0 - t)) * visibility));
            }
        }
        BufferUploader.drawWithShader(ribbon.end());

        useTexturedShader(DUST);
        BufferBuilder puffs = Tesselator.getInstance().getBuilder();
        puffs.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int puffCount = 150 + d.intensity() * 48;
        for (int i = 0; i < puffCount; i++) {
            double t = unit(d.seed() + i * 32452843L);
            double y = bottom + (top - bottom) * t;
            double radius = funnelRadius(spec, d.subtype(), t);
            double a = unit(d.seed() ^ (i * 49979687L)) * Math.PI * 2.0
                    + time * (0.014 + 0.0018 * d.intensity()) * (1.1 - 0.40 * t);
            double shell = 0.30 + unit(d.seed() + i * 67867967L) * 0.92;
            double x = marker.getX() + Math.cos(a) * radius * shell + Math.sin(t * 5 + time * 0.009) * t * 4.0;
            double z = marker.getZ() + Math.sin(a) * radius * shell + Math.cos(t * 5 + time * 0.008) * t * 4.0;
            double size = 5.2 + (1.0 - t) * (5.0 + d.intensity() * 1.5) + unit(d.seed() + i * 97L) * 5.8;
            int frame = Math.floorMod((int) (d.seed() + i * 3L + time / 5L), 38);
            float gray = (float) (0.49 + t * 0.18);
            cloudCell(puffs, matrix, x, y, z, size, size * 0.80, size * 0.88, a,
                    8, 8, frame, gray, gray, gray,
                    (float) ((0.13 + 0.22 * (1.0 - t)) * visibility));
        }

        double circulation = TornadoPhysics.surfaceCirculation(age, d.maxAge(), d.extra());
        if (circulation > 0.02) {
            int dustCount = 48 + d.intensity() * 22;
            double collar = spec.damageRadius() * (0.78 + 0.08 * d.intensity());
            for (int i = 0; i < dustCount; i++) {
                double a = unit(d.seed() + i * 15485863L) * Math.PI * 2.0 + time * 0.027;
                double rr = Math.sqrt(unit(d.seed() ^ i * 86028121L)) * collar;
                double x = marker.getX() + Math.cos(a) * rr;
                double z = marker.getZ() + Math.sin(a) * rr;
                double y = ground + unit(d.seed() + i * 31337L) * (8.0 + d.intensity() * 2.4);
                double size = 6.8 + unit(d.seed() + i * 211L) * (8.0 + d.intensity() * 2.1);
                int frame = Math.floorMod(i + (int) (time / 4L), 38);
                cloudCell(puffs, matrix, x, y, z, size, size * 0.56, size * 0.82, a,
                        8, 8, frame, 0.34f, 0.30f, 0.25f, (float) (0.24 * circulation * fadeOut));
            }
        }

        boolean multi = d.intensity() >= 4 || d.subtype().equals("E") || (d.intensity() >= 3 && d.subtype().equals("G"));
        if (multi) {
            double multiBlend = smoothstep((age - TornadoPhysics.FUNNEL_DESCENT_TICKS * 0.82) / 220.0) * fadeOut;
            int vortices = d.intensity() >= 5 ? 4 : 3;
            for (int v = 0; v < vortices; v++) {
                double va = time * (0.020 + v * 0.003) + v * Math.PI * 2.0 / vortices;
                double vcx = marker.getX() + Math.cos(va) * spec.damageRadius() * 0.42 * multiBlend;
                double vcz = marker.getZ() + Math.sin(va) * spec.damageRadius() * 0.42 * multiBlend;
                for (int k = 0; k < 25; k++) {
                    double t = k / 24.0;
                    double vr = 1.0 + t * 3.2;
                    double aa = va * 2.0 + k * 0.72;
                    double x = vcx + Math.cos(aa) * vr;
                    double z = vcz + Math.sin(aa) * vr;
                    double y = targetBottom + t * 40.0;
                    int frame = Math.floorMod(k + v * 7 + (int) (time / 4L), 38);
                    double size = 4.2 + t * 2.1;
                    cloudCell(puffs, matrix, x, y, z, size, size * 1.20, size * 0.74, aa,
                            8, 8, frame, 0.46f, 0.45f, 0.44f, (float) (0.20 * multiBlend));
                }
            }
        }
        BufferUploader.drawWithShader(puffs.end());
    }

    private static double funnelRadius(WeatherProfiles.TornadoSpec spec, String variant, double t) {
        double base = 1.5 + spec.intensity() * 0.43;
        double top = spec.topRadius();
        double r = base + (top - base) * Math.pow(t, 0.78);
        switch (variant) {
            case "B" -> r = base * 1.4 + (top * 0.72 - base) * Math.pow(t, 0.55);
            case "C" -> r *= 0.46 + 0.50 * t;
            case "D" -> r = base + (top * 1.15 - base) * Math.pow(t, 0.46);
            case "E" -> r *= 0.80 + 0.22 * Math.sin(t * Math.PI);
            case "F" -> r *= 0.68 + 0.48 * t * t;
            case "G" -> r = base + (top * 1.28 - base) * Math.pow(t, 0.62);
            default -> { }
        }
        return Math.max(0.7, r);
    }

    private static void renderPrecipitation(Matrix4f matrix, WeatherMarker.Data d, long time, Vec3 camera,
                                            double strength, boolean hailEnabled, double rx, double rz) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int drops = 95 + (int) (strength * 58.0);
        double heading = Math.toRadians(d.headingDeg());
        double slantX = Math.cos(heading) * (0.34 + strength * 0.14);
        double slantZ = Math.sin(heading) * (0.34 + strength * 0.14);
        for (int i = 0; i < drops; i++) {
            double px = camera.x + (unit(d.seed() + i * 32491L) - 0.5) * 58.0;
            double pz = camera.z + (unit(d.seed() + i * 99839L) - 0.5) * 58.0;
            double phaseY = (time * (0.95 + strength * 0.18) + unit(d.seed() ^ i * 1237L) * 46.0) % 46.0;
            double py = camera.y + 26.0 - phaseY;
            double w = 0.034;
            double len = 1.2 + strength * 0.45;
            double ax = rx * w;
            double az = rz * w;
            float alpha = (float) clamp(0.14 + strength * 0.042, 0.14, 0.36);
            vertexColor(b, matrix, px - ax, py, pz - az, 0.63f, 0.72f, 0.82f, alpha);
            vertexColor(b, matrix, px + ax, py, pz + az, 0.63f, 0.72f, 0.82f, alpha);
            vertexColor(b, matrix, px + ax + slantX, py - len, pz + az + slantZ, 0.63f, 0.72f, 0.82f, 0.0f);
            vertexColor(b, matrix, px - ax + slantX, py - len, pz - az + slantZ, 0.63f, 0.72f, 0.82f, 0.0f);
        }

        if (hailEnabled && strength >= 2.75) {
            int hail = 42 + (int) ((strength - 2.5) * 36.0);
            for (int i = 0; i < hail; i++) {
                double px = camera.x + (unit(d.seed() + i * 71317L) - 0.5) * 50.0;
                double pz = camera.z + (unit(d.seed() + i * 27109L) - 0.5) * 50.0;
                double py = camera.y + 23.0 - ((time * 1.55 + unit(d.seed() ^ i * 5011L) * 38.0) % 38.0);
                double s = 0.075 + unit(d.seed() + i * 3331L) * 0.055;
                vertexColor(b, matrix, px - rx * s, py - s, pz - rz * s, 0.92f, 0.95f, 1.0f, 0.72f);
                vertexColor(b, matrix, px + rx * s, py - s, pz + rz * s, 0.92f, 0.95f, 1.0f, 0.72f);
                vertexColor(b, matrix, px + rx * s, py + s, pz + rz * s, 0.92f, 0.95f, 1.0f, 0.72f);
                vertexColor(b, matrix, px - rx * s, py + s, pz - rz * s, 0.92f, 0.95f, 1.0f, 0.72f);
            }
        }
        BufferUploader.drawWithShader(b.end());
    }

    private static void cloudCell(BufferBuilder b, Matrix4f matrix, double x, double y, double z,
                                  double sx, double sy, double sz, double rotation,
                                  int cols, int rows, int frame,
                                  float r, float g, float bl, float a) {
        double c = Math.cos(rotation);
        double s = Math.sin(rotation);
        atlasPlane(b, matrix, x, y, z, c, 0.0, s, 0.0, 1.0, 0.0,
                sx, sy, cols, rows, frame, r, g, bl, a);
        atlasPlane(b, matrix, x, y, z, -s, 0.0, c, 0.0, 1.0, 0.0,
                sz, sy, cols, rows, frame, r, g, bl, a * 0.88f);
        atlasPlane(b, matrix, x, y, z, c, 0.0, s, -s, 0.0, c,
                sx, sz, cols, rows, frame, r, g, bl, a * 0.72f);
    }

    private static void tangentAtlas(BufferBuilder b, Matrix4f matrix, double x, double y, double z,
                                     double width, double height, double angle,
                                     int cols, int rows, int frame,
                                     float r, float g, float bl, float a) {
        double ux = -Math.sin(angle);
        double uz = Math.cos(angle);
        atlasPlane(b, matrix, x, y, z, ux, 0.0, uz, 0.0, 1.0, 0.0,
                width, height, cols, rows, frame, r, g, bl, a);
    }

    private static void atlasPlane(BufferBuilder b, Matrix4f matrix, double x, double y, double z,
                                   double ux, double uy, double uz,
                                   double vx, double vy, double vz,
                                   double width, double height,
                                   int cols, int rows, int frame,
                                   float r, float g, float bl, float a) {
        int total = Math.max(1, cols * rows);
        int f = Math.floorMod(frame, total);
        int col = f % cols;
        int row = f / cols;
        float u0 = col / (float) cols;
        float v0 = row / (float) rows;
        float u1 = (col + 1) / (float) cols;
        float v1 = (row + 1) / (float) rows;

        double hw = width * 0.5;
        double hh = height * 0.5;
        double uxw = ux * hw, uyw = uy * hw, uzw = uz * hw;
        double vxh = vx * hh, vyh = vy * hh, vzh = vz * hh;

        vertexTex(b, matrix, x - uxw - vxh, y - uyw - vyh, z - uzw - vzh, u0, v1, r, g, bl, a);
        vertexTex(b, matrix, x + uxw - vxh, y + uyw - vyh, z + uzw - vzh, u1, v1, r, g, bl, a);
        vertexTex(b, matrix, x + uxw + vxh, y + uyw + vyh, z + uzw + vzh, u1, v0, r, g, bl, a);
        vertexTex(b, matrix, x - uxw + vxh, y - uyw + vyh, z - uzw + vzh, u0, v0, r, g, bl, a);
    }

    private static void vertexTex(BufferBuilder b, Matrix4f matrix, double x, double y, double z,
                                  float u, float v, float r, float g, float bl, float a) {
        b.vertex(matrix, (float) x, (float) y, (float) z).uv(u, v).color(r, g, bl, a).endVertex();
    }

    private static void vertexColor(BufferBuilder b, Matrix4f matrix, double x, double y, double z,
                                    float r, float g, float bl, float a) {
        b.vertex(matrix, (float) x, (float) y, (float) z).color(r, g, bl, a).endVertex();
    }

    private static double unit(long x) {
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return (x >>> 11) * 0x1.0p-53;
    }

    private static double smoothstep(double x) {
        x = clamp(x, 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    private static float clamp01(float f) { return Math.max(0.06f, Math.min(1.0f, f)); }
    private static double clamp(double x, double min, double max) { return Math.max(min, Math.min(max, x)); }
}
