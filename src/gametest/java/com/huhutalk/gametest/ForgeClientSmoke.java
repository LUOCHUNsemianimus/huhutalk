package com.huhutalk.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.huhutalk.HuhuTalk;
import com.huhutalk.api.phone.camera.PhoneCameraHost;
import com.huhutalk.api.phone.camera.PhoneCameraPage;
import com.huhutalk.client.CameraAppRegistration;
import com.huhutalk.client.PhotoLibrary;
import com.huhutalk.client.render.ProPhoneHandRenderer;
import com.huhutalk.client.render.SelfieCameraTarget;
import com.huhutalk.init.HuhuTalkItems;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.pages.SettingsPage;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 仅由 {@code -PportSmoke} 启用的原生客户端运行验收驱动。
 *
 * <p>本类位于独立 {@code gametest} 源集，只在 {@code build.gradle} 的
 * {@code minecraft.runs.client} 检测到 {@code portSmoke} 属性时加入客户端；
 * 普通 client/server 与正式 jar 都不包含它。未设置系统属性
 * {@code huhutalk.portSmoke=true} 时，两个事件入口立即返回，零操作。</p>
 *
 * <p>驱动只操作 {@code --quickPlaySingleplayer port-smoke} 指定的隔离运行目录：
 * 不发任何 AI 请求，不读取用户其它存档，不调用 {@code System.exit}。所有检查、
 * 截图与失败原因写入 {@code huhutalk.portSmokeOutput/result.json}，随后用
 * {@link Minecraft#stop()} 正常保存退出。</p>
 *
 * <p>等待与截图分离：状态机在 {@link TickEvent.ClientTickEvent} END 推进，
 * 截图请求在 {@link TickEvent.RenderTickEvent} END 执行，避免抓到一半帧。</p>
 */
@Mod.EventBusSubscriber(modid = HuhuTalk.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeClientSmoke {

    private static final boolean ENABLED = Boolean.getBoolean("huhutalk.portSmoke");
    private static final long TIMEOUT_MS = 180_000L;

    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<JsonObject> CHECKS = new ArrayList<>();
    private static final List<String> SCREENSHOTS = new ArrayList<>();

    private static Phase phase = Phase.WAIT_WORLD;
    private static int phaseTicks;
    private static long startedNanos;
    private static String startedAt;
    private static Path outputDir;
    private static boolean finished;

    private static String pendingScreenshot;
    private static boolean screenshotWritten;
    private static String screenshotError;

    private static volatile String serverError;

    private static int galleryStart = -1;
    private static int galleryRear = -1;
    private static int gallerySelfie = -1;

    private ForgeClientSmoke() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ENABLED || finished) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (startedNanos == 0L) {
            startedNanos = System.nanoTime();
            startedAt = Instant.now().toString();
            outputDir = resolveOutputDir();
        }
        if (outputDir == null) {
            // 没有输出目录就没有可核验的结果；只记录并正常停止，不写任意外部路径。
            HuhuTalk.LOGGER.error("[port-smoke] 缺少 -Dhuhutalk.portSmokeOutput，无法写 result.json");
            finished = true;
            mc.stop();
            return;
        }
        if (System.nanoTime() - startedNanos > TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)) {
            fail("未能在 " + TIMEOUT_MS + "ms 内完成客户端验收，当前阶段 " + phase);
            return;
        }
        if (serverError != null) {
            fail("集成服授予手机失败: " + serverError);
            return;
        }
        try {
            phaseTicks++;
            step(mc);
        } catch (Throwable t) {
            fail("客户端验收驱动异常: " + stackTrace(t));
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!ENABLED || finished || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (pendingScreenshot == null) {
            return;
        }
        String name = pendingScreenshot;
        pendingScreenshot = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            try {
                Files.createDirectories(outputDir);
                image.writeToFile(outputDir.resolve(name));
                SCREENSHOTS.add(name);
            } finally {
                image.close();
            }
        } catch (Throwable t) {
            screenshotError = name + ": " + stackTrace(t);
        } finally {
            screenshotWritten = true;
        }
    }

    private static void step(Minecraft mc) {
        switch (phase) {
            case WAIT_WORLD -> waitWorld(mc);
            case GIVE_WAIT -> {
                if (phaseTicks < 60) {
                    return;
                }
                enter(Phase.ASSERT_MODEL);
            }
            case ASSERT_MODEL -> assertModel();
            case SHOT_MODEL -> {
                if (!screenshotDone()) {
                    return;
                }
                PhoneOverlay.pushPage(new SettingsPage());
                enter(Phase.SETTINGS_WAIT);
            }
            case SETTINGS_WAIT -> {
                if (phaseTicks < 30) {
                    return;
                }
                scheduleScreenshot("02-settings.png");
                enter(Phase.SHOT_SETTINGS);
            }
            case SHOT_SETTINGS -> {
                if (!screenshotDone()) {
                    return;
                }
                PhoneOverlay.popPage();
                PhoneOverlay.toggle();
                enter(Phase.FLOAT_WAIT);
            }
            case FLOAT_WAIT -> {
                if (phaseTicks < 30) {
                    return;
                }
                enter(Phase.ASSERT_FLOAT);
            }
            case ASSERT_FLOAT -> assertFloating();
            case SHOT_FLOATING -> {
                if (!screenshotDone()) {
                    return;
                }
                PhoneOverlay.toggle();
                enter(Phase.WAIT_HIDDEN);
            }
            case WAIT_HIDDEN -> {
                if (!PhoneOverlay.isVisible()) {
                    PhoneOverlay.armHolderAutoOpen();
                    enter(Phase.WAIT_MODEL_REOPEN);
                    return;
                }
                if (phaseTicks > 300) {
                    fail("悬浮窗在 300 tick 内未完成收起动画");
                }
            }
            case WAIT_MODEL_REOPEN -> {
                if (phaseTicks < 30) {
                    return;
                }
                if (PhoneOverlay.isModelScreen() && ProPhoneHandRenderer.isActive()) {
                    enter(Phase.OPEN_CAMERA);
                    return;
                }
                if (phaseTicks > 120) {
                    fail("重新 armHolderAutoOpen 后 120 tick 内未恢复模型屏");
                }
            }
            case OPEN_CAMERA -> {
                PhoneOverlay.launchDesktopApp(new PhoneCameraPage(), CameraAppRegistration.CAMERA_SLOT);
                enter(Phase.WAIT_CAMERA);
            }
            case WAIT_CAMERA -> {
                if (phaseTicks < 30) {
                    return;
                }
                if (!PhoneOverlay.isCameraMode() || !(PhoneOverlay.getCurrentPage() instanceof PhoneCameraHost host)) {
                    fail("打开桌面相机后未进入相机模式，page=" + PhoneOverlay.getCurrentPage());
                    return;
                }
                galleryStart = PhotoLibrary.listGallery().size();
                host.cameraPrimaryAction();
                enter(Phase.WAIT_REAR_PHOTO);
            }
            case WAIT_REAR_PHOTO -> waitRearPhoto();
            case SHOT_REAR -> {
                if (!screenshotDone()) {
                    return;
                }
                if (!PhoneOverlay.isCameraMode()) {
                    fail("后摄保存后已不在相机模式");
                    return;
                }
                PhoneOverlay.toggleCameraFacing();
                mc.player.setXRot(0.0F);
                enter(Phase.WAIT_SELFIE_READY);
            }
            case WAIT_SELFIE_READY -> {
                if (PhoneOverlay.isSelfieMode() && SelfieCameraTarget.isReady()) {
                    if (PhoneOverlay.getCurrentPage() instanceof PhoneCameraHost host) {
                        host.cameraPrimaryAction();
                        enter(Phase.WAIT_SELFIE_PHOTO);
                        return;
                    }
                    fail("前摄就绪但当前页不是 PhoneCameraHost");
                    return;
                }
                if (phaseTicks > 300) {
                    fail("前摄 300 tick 内未就绪: isSelfieMode=" + PhoneOverlay.isSelfieMode()
                            + ", isReady=" + SelfieCameraTarget.isReady()
                            + ", isFailed=" + SelfieCameraTarget.isFailed());
                }
            }
            case WAIT_SELFIE_PHOTO -> waitSelfiePhoto();
            case SHOT_SELFIE -> {
                if (!screenshotDone()) {
                    return;
                }
                int count = PhotoLibrary.listGallery().size();
                check("gallery.total", count >= galleryStart + 2,
                        "最终图库 " + count + "，起始 " + galleryStart + "，至少应为起始+2");
                if (!FAILURES.isEmpty()) {
                    fail("最终检查失败: " + String.join("; ", FAILURES));
                    return;
                }
                finish(true, "模型屏、设置页、悬浮窗、前后摄与本地照片检查完成");
            }
        }
    }

    private static void waitWorld(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        UUID uuid = mc.player.getUUID();
        if (server.getPlayerList().getPlayer(uuid) == null) {
            return;
        }

        mc.options.pauseOnLostFocus = false;
        mc.options.framerateLimit().set(30);
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        server.execute(() -> grantPhone(server, uuid));
        mc.player.setXRot(70.0F);
        PhoneOverlay.armHolderAutoOpen();
        enter(Phase.GIVE_WAIT);
    }

    /** 由集成服线程执行；给当前客户端 UUID 对应的服务端玩家主手放入智能机。 */
    private static void grantPhone(MinecraftServer server, UUID uuid) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                throw new IllegalStateException("服务端玩家不存在: " + uuid);
            }
            player.getInventory().selected = 0;
            player.getInventory().setItem(0, new ItemStack(HuhuTalkItems.PHONE_SMART.get()));
        } catch (Throwable t) {
            serverError = stackTrace(t);
            HuhuTalk.LOGGER.error("[port-smoke] 集成服授予手机失败", t);
        }
    }

    private static void assertModel() {
        boolean visible = PhoneOverlay.isVisible();
        boolean model = PhoneOverlay.isModelScreen();
        boolean renderer = ProPhoneHandRenderer.isActive();
        check("model.visible", visible, "PhoneOverlay.isVisible()=" + visible);
        check("model.screen", model, "PhoneOverlay.isModelScreen()=" + model);
        check("model.rendererActive", renderer, "ProPhoneHandRenderer.isActive()=" + renderer);
        if (!visible || !model || !renderer) {
            fail("未进入模型手机屏: visible=" + visible + ", model=" + model + ", renderer=" + renderer);
            return;
        }
        scheduleScreenshot("01-model-desktop.png");
        enter(Phase.SHOT_MODEL);
    }

    private static void assertFloating() {
        boolean floating = PhoneOverlay.isFloatingScreen();
        check("floating.screen", floating, "PhoneOverlay.isFloatingScreen()=" + floating);
        if (!floating) {
            fail("MODEL 态 toggle 后未切到悬浮窗");
            return;
        }
        scheduleScreenshot("03-floating.png");
        enter(Phase.SHOT_FLOATING);
    }

    private static void waitRearPhoto() {
        int count = PhotoLibrary.listGallery().size();
        if (phaseTicks >= 40 && count > galleryStart) {
            galleryRear = count;
            check("camera.rear.saved", count >= galleryStart + 1,
                    "后摄保存后图库 " + count + "，起始 " + galleryStart);
            scheduleScreenshot("04-rear-camera.png");
            enter(Phase.SHOT_REAR);
            return;
        }
        if (phaseTicks > 200) {
            fail("后摄 200 tick 内未保存照片，图库 " + count + "，起始 " + galleryStart);
        }
    }

    private static void waitSelfiePhoto() {
        int count = PhotoLibrary.listGallery().size();
        if (phaseTicks >= 40 && count > galleryRear) {
            gallerySelfie = count;
            check("camera.selfie.saved", count >= galleryRear + 1,
                    "前摄保存后图库 " + count + "，后摄后 " + galleryRear);
            check("camera.selfie.mode", PhoneOverlay.isSelfieMode(),
                    "PhoneOverlay.isSelfieMode()=" + PhoneOverlay.isSelfieMode());
            scheduleScreenshot("05-selfie-camera.png");
            enter(Phase.SHOT_SELFIE);
            return;
        }
        if (phaseTicks > 200) {
            fail("前摄 200 tick 内未保存照片，图库 " + count + "，后摄后 " + galleryRear);
        }
    }

    private static void scheduleScreenshot(String name) {
        pendingScreenshot = name;
        screenshotWritten = false;
        screenshotError = null;
    }

    /** 上一张截图是否已经落盘；失败时直接终止，避免把缺图当成功。 */
    private static boolean screenshotDone() {
        if (!screenshotWritten) {
            return false;
        }
        if (screenshotError != null) {
            fail("截图失败: " + screenshotError);
            return false;
        }
        return true;
    }

    private static void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private static void check(String name, boolean ok, String detail) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("ok", ok);
        entry.addProperty("detail", detail);
        CHECKS.add(entry);
        if (!ok) {
            FAILURES.add(name + ": " + detail);
        }
    }

    private static void fail(String reason) {
        if (finished) {
            return;
        }
        FAILURES.add(reason);
        HuhuTalk.LOGGER.error("[port-smoke] 失败: {}", reason);
        finish(false, reason);
    }

    private static void finish(boolean success, String reason) {
        if (finished) {
            return;
        }
        finished = true;
        if (success && !FAILURES.isEmpty()) {
            success = false;
            reason = "仍有失败项: " + String.join("; ", FAILURES);
        }
        HuhuTalk.LOGGER.info("[port-smoke] 结束 success={}, reason={}", success, reason);
        try {
            writeResult(success, reason);
        } catch (Throwable t) {
            HuhuTalk.LOGGER.error("[port-smoke] 写 result.json 失败", t);
        }
        Minecraft.getInstance().stop();
    }

    private static void writeResult(boolean success, String reason) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("success", success);
        root.addProperty("reason", reason);
        root.addProperty("startedAt", startedAt);
        root.addProperty("durationMs", startedNanos == 0L
                ? 0L : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        root.addProperty("outputDir", outputDir.toString());
        root.addProperty("galleryStart", galleryStart);
        root.addProperty("galleryRear", galleryRear);
        root.addProperty("gallerySelfie", gallerySelfie);

        JsonArray checks = new JsonArray();
        for (JsonObject entry : CHECKS) {
            checks.add(entry);
        }
        root.add("checks", checks);

        JsonArray failures = new JsonArray();
        for (String failure : FAILURES) {
            failures.add(failure);
        }
        root.add("failures", failures);

        JsonArray screenshots = new JsonArray();
        for (String screenshot : SCREENSHOTS) {
            screenshots.add(screenshot);
        }
        root.add("screenshots", screenshots);

        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("result.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(root),
                StandardCharsets.UTF_8);
    }

    private static Path resolveOutputDir() {
        String value = System.getProperty("huhutalk.portSmokeOutput");
        if (value == null || value.isBlank()) {
            return null;
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }

    private static String stackTrace(Throwable t) {
        StringWriter buffer = new StringWriter();
        t.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }

    private enum Phase {
        WAIT_WORLD,
        GIVE_WAIT,
        ASSERT_MODEL,
        SHOT_MODEL,
        SETTINGS_WAIT,
        SHOT_SETTINGS,
        FLOAT_WAIT,
        ASSERT_FLOAT,
        SHOT_FLOATING,
        WAIT_HIDDEN,
        WAIT_MODEL_REOPEN,
        OPEN_CAMERA,
        WAIT_CAMERA,
        WAIT_REAR_PHOTO,
        SHOT_REAR,
        WAIT_SELFIE_READY,
        WAIT_SELFIE_PHOTO,
        SHOT_SELFIE
    }
}
