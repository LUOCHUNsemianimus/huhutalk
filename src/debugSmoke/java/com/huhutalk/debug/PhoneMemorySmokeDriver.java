package com.huhutalk.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.huhutalk.client.ChatHistoryStorage;
import com.huhutalk.client.settings.HuhuTalkClientSettings;
import com.huhutalk.core.archive.ArchiveRole;
import com.huhutalk.core.chat.PhoneMessageQuote;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import com.huhutalk.phone.pages.ChatPage;
import com.huhutalk.phone.pages.SettingsPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原生手机回忆/引用气泡验收驱动（仅 -PphoneMemorySmoke）。
 *
 * <p>客户端只负责隔离 UI、清当前会话缓存、按序请求场景、从真实 cache 重建页面并
 * 截图；服务端由 {@link PhoneMemorySmokeBackend} 建真实归档 turn 并走真实工具与
 * S2C/cache 路径。两边只通过 volatile 索引/状态协作，不阻塞等网络。</p>
 */
@EventBusSubscriber(modid = "huhutalk", value = Dist.CLIENT)
public final class PhoneMemorySmokeDriver {
    private static final String PROP_ENABLED = PhoneMemorySmokeBackend.PROP_ENABLED;
    private static final String PROP_WORLD = PhoneMemorySmokeBackend.PROP_WORLD;
    private static final String PROP_OUTPUT = PhoneMemorySmokeBackend.PROP_OUTPUT;
    private static final long TIMEOUT_NANOS = 240_000_000_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Set<String> REQUIRED_CHECKS = new LinkedHashSet<>();
    static {
        REQUIRED_CHECKS.add("isolatedWorldPath");
        REQUIRED_CHECKS.add("playerAliveAtStart");
        REQUIRED_CHECKS.add("spawnWait80");
        REQUIRED_CHECKS.add("historyMemoryCleared");
        REQUIRED_CHECKS.add("maidPhoneEquipped");
        REQUIRED_CHECKS.add("fixturesSix");
        REQUIRED_CHECKS.add("toolsRegistered");
        REQUIRED_CHECKS.add("toolSearchDefaultPlayerOnly");
        REQUIRED_CHECKS.add("toolSearchAllRoles");
        REQUIRED_CHECKS.add("toolSearchImageAlias");
        REQUIRED_CHECKS.add("toolSearchItemAlias");
        REQUIRED_CHECKS.add("toolSearchItemId");
        REQUIRED_CHECKS.add("toolSearchItemFrameNoMatch");
        REQUIRED_CHECKS.add("getNoQuote");
        REQUIRED_CHECKS.add("getQuoteRealFields");
        REQUIRED_CHECKS.add("quoteClearedOnObservation");
        REQUIRED_CHECKS.add("quoteClearedOnMissing");
        REQUIRED_CHECKS.add("storedReadOnlyRecords");
        REQUIRED_CHECKS.add("storedReadOnlyBytes");
        REQUIRED_CHECKS.add("storedFileWithinWorld");
        REQUIRED_CHECKS.add("missingArchiveReturnsUnavailable");
        REQUIRED_CHECKS.add("corruptReturnsArchiveUnavailable");
        REQUIRED_CHECKS.add("corruptRestored");
        REQUIRED_CHECKS.add("authNonPhoneCallbackRejected");
        REQUIRED_CHECKS.add("authStableMismatchRejected");
        REQUIRED_CHECKS.add("authReplacedPlayerRejected");
        REQUIRED_CHECKS.add("authMaidOwnerChangedRejected");
        REQUIRED_CHECKS.add("authPhoneRemovedRejected");
        REQUIRED_CHECKS.add("authSecretNotVisible");
        REQUIRED_CHECKS.add("ownerPhoneRestored");
        REQUIRED_CHECKS.add("ttsIsolatedOff");
        REQUIRED_CHECKS.add("demoIsolatedFlushKeepsBothQuotes");
        for (int i = 1; i <= 6; i++) {
            REQUIRED_CHECKS.add("scenario_" + i + "_toolQuote");
            REQUIRED_CHECKS.add("scenario_" + i + "_archiveReply");
            REQUIRED_CHECKS.add("scenario_" + i + "_replyQuoteSeparated");
            REQUIRED_CHECKS.add("scenario_" + i + "_networkQuote");
            REQUIRED_CHECKS.add("scenario_" + i + "_pageFirstSegment");
            REQUIRED_CHECKS.add("scenario_" + i + "_reopenQuote");
            REQUIRED_CHECKS.add("screenshot_" + PhoneMemorySmokeBackend.SHOT_NAMES[i - 1]);
        }
    }

    private static final List<String> SCREENSHOTS = new ArrayList<>();
    private static volatile boolean finished = false;
    private static long clientStartedNanos = -1L;
    private static int phase = 0;
    private static int scenario = 0;
    private static int ticks = 0;
    private static boolean shotPending = false;
    private static boolean shotDone = false;
    private static String shotName = "";

    private PhoneMemorySmokeDriver() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled() || finished) {
            return;
        }
        PhoneMemorySmokeBackend.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled() || finished) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (clientStartedNanos < 0) {
            clientStartedNanos = System.nanoTime();
        }
        if (System.nanoTime() - clientStartedNanos > TIMEOUT_NANOS) {
            check("hardTimeout240s", false, "elapsed=" + elapsedMs() + "ms");
            finish(mc);
            return;
        }
        try {
            tickClient(mc);
        } catch (Throwable throwable) {
            error("clientTick", throwable);
            finish(mc);
        }
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Post event) {
        if (!enabled() || finished || !shotPending) {
            return;
        }
        shotPending = false;
        Minecraft mc = Minecraft.getInstance();
        Path out = outputDir().resolve(shotName + ".png");
        try {
            Files.createDirectories(out.getParent());
            var image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            if (image == null) {
                check("screenshot_" + shotName, false, "Screenshot.takeScreenshot returned null");
            } else {
                try {
                    image.writeToFile(out.toFile());
                    SCREENSHOTS.add(out.toAbsolutePath().toString());
                    check("screenshot_" + shotName, Files.isRegularFile(out),
                            out.toAbsolutePath().toString());
                } finally {
                    image.close();
                }
            }
        } catch (Throwable throwable) {
            error("screenshot:" + shotName, throwable);
            check("screenshot_" + shotName, false, String.valueOf(throwable));
        }
        shotDone = true;
    }

    private static void tickClient(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (PhoneMemorySmokeBackend.isFatal()) {
            check("serverPreparation", false, PhoneMemorySmokeBackend.getFatalDetail());
            finish(mc);
            return;
        }
        if (!PhoneMemorySmokeBackend.isReady()) {
            return;
        }
        if (phase == 0) {
            initializeClient(mc);
            phase = 1;
            scenario = 0;
            return;
        }
        if (scenario >= PhoneMemorySmokeBackend.SHOT_NAMES.length) {
            finish(mc);
            return;
        }
        switch (phase) {
            case 1 -> {
                startScenario(mc, scenario);
                phase = 2;
                ticks = 0;
            }
            case 2 -> waitNetwork();
            case 3 -> {
                if (++ticks < 20) {
                    return;
                }
                verifyCurrentPage();
                requestShot(PhoneMemorySmokeBackend.SHOT_NAMES[scenario]);
                phase = 4;
            }
            case 4 -> {
                if (!shotDone) {
                    return;
                }
                verifyReopen();
                scenario++;
                phase = 1;
            }
            default -> {
            }
        }
    }

    private static void initializeClient(Minecraft mc) {
        HuhuTalkClientSettings.setCharTypingMs(50L);
        HuhuTalkClientSettings.setMaxTypingMs(100L);
        check("typingIsolated", HuhuTalkClientSettings.getCharTypingMs() == 50L
                        && HuhuTalkClientSettings.getMaxTypingMs() == 100L,
                "char=50 max=100");
        flushIsolatedCheck();
        mc.setScreen(null);
        if (!PhoneOverlay.isVisible()) {
            PhoneOverlay.toggle();
        }
        PhoneOverlay.openChatApp();
        check("phoneVisible", PhoneOverlay.isVisible(), "phone overlay visible before scenarios");
    }

    /**
     * 隔离演示页的 flush 检查：旧多段回复还在逐条播放时立即收到一条无标点单句，
     * flush 必须把旧回复的剩余段落屏且只让首段携带旧引用，新单句再携带新引用，
     * 不能因为重置断句队列而丢掉或覆盖前一个引用。不读写真实会话、不发包。
     */
    private static void flushIsolatedCheck() {
        try {
            ChatPage page = new ChatPage("smoke-flush-isolated",
                    PhoneMemorySmokeBackend.MAID_NAME, true);
            PhoneMessageQuote quoteA = new PhoneMessageQuote("smoke-flush-quote-a",
                    ArchiveRole.MAID, PhoneMessageQuote.Kind.TEXT, "旧引用A", 1L);
            PhoneMessageQuote quoteB = new PhoneMessageQuote("smoke-flush-quote-b",
                    ArchiveRole.MAID, PhoneMessageQuote.Kind.TEXT, "新引用B", 2L);
            page.onReceiveMessage("旧回复第一段，旧回复第二段", false, null, quoteA);
            page.onReceiveMessage("新回复没有标点", false, null, quoteB);

            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) fieldValue(page, "messages");
            int quoteCount = 0;
            for (Object message : messages) {
                if (invokeDeclared(message, "quote") instanceof PhoneMessageQuote) {
                    quoteCount++;
                }
            }
            Object firstQuote = messages.isEmpty() ? null : invokeDeclared(messages.get(0), "quote");
            Object middleQuote = messages.size() < 2 ? null : invokeDeclared(messages.get(1), "quote");
            Object lastQuote = messages.size() < 3 ? null : invokeDeclared(messages.get(2), "quote");
            boolean ok = messages.size() == 3 && quoteCount == 2
                    && quoteA.equals(firstQuote) && middleQuote == null
                    && quoteB.equals(lastQuote)
                    && "旧回复第一段".equals(String.valueOf(invokeDeclared(messages.get(0), "text")))
                    && "旧回复第二段".equals(String.valueOf(invokeDeclared(messages.get(1), "text")))
                    && "新回复没有标点".equals(String.valueOf(invokeDeclared(messages.get(2), "text")));
            check("demoIsolatedFlushKeepsBothQuotes", ok,
                    "messages=" + messages.size() + " quoteCount=" + quoteCount
                            + " first=" + firstQuote + " middle=" + middleQuote + " last=" + lastQuote);
        } catch (Throwable throwable) {
            error("flushIsolatedCheck", throwable);
            check("demoIsolatedFlushKeepsBothQuotes", false, String.valueOf(throwable));
        }
    }

    private static void startScenario(Minecraft mc, int index) {
        String stable = PhoneMemorySmokeBackend.getStableId();
        ChatHistoryStorage.deleteConversation(stable);
        check("scenario_" + (index + 1) + "_cacheClean",
                ChatHistoryStorage.getMessages(stable).isEmpty(), "deleted current conversation");
        if (index == 1) {
            PhoneOverlay.pushPage(new SettingsPage());
            check("scenario_2_offscreenBeforeReceive",
                    PhoneOverlay.getCurrentPage() instanceof SettingsPage,
                    "current page is SettingsPage when reply arrives");
        } else {
            PhoneOverlay.pushPage(new ChatPage(stable, PhoneMemorySmokeBackend.MAID_NAME));
        }
        PhoneMemorySmokeBackend.requestScenario(index);
    }

    private static void waitNetwork() {
        ticks++;
        String stable = PhoneMemorySmokeBackend.getStableId();
        String expected = PhoneMemorySmokeBackend.getLastQuoteId();
        List<ChatHistoryStorage.ChatMessageEntry> entries = ChatHistoryStorage.getMessages(stable);
        long quoteEntries = entries.stream()
                .filter(entry -> entry.quote() != null)
                .count();
        PhoneMessageQuote quote = entries.stream()
                .map(ChatHistoryStorage.ChatMessageEntry::quote)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        boolean handled = PhoneMemorySmokeBackend.getHandled() >= scenario;
        boolean has = quote != null && !expected.isBlank()
                && expected.equals(quote.messageId()) && quoteEntries == 1;
        if (handled && has) {
            check("scenario_" + (scenario + 1) + "_networkQuote", true,
                    "cache quote=" + quote.messageId() + " kind=" + quote.kind());
            if (scenario == 1) {
                PhoneOverlay.pushPage(new ChatPage(stable, PhoneMemorySmokeBackend.MAID_NAME));
                check("scenario_2_offscreenCacheOpen",
                        ChatHistoryStorage.getMessages(stable).size() == 1,
                        "new ChatPage built from real cache after offscreen receive");
            }
            phase = 3;
            ticks = 0;
            return;
        }
        if (ticks > 240) {
            check("scenario_" + (scenario + 1) + "_networkQuote", false,
                    "handled=" + handled + " expected=" + expected + " entries=" + entries.size()
                            + " quoteEntries=" + quoteEntries);
            finish(Minecraft.getInstance());
        }
    }

    private static void verifyCurrentPage() {
        String stable = PhoneMemorySmokeBackend.getStableId();
        IPhonePage current = PhoneOverlay.getCurrentPage();
        if (!(current instanceof ChatPage page) || !stable.equals(page.getStableId())) {
            check("scenario_" + (scenario + 1) + "_pageFirstSegment", false,
                    "current page is not the expected ChatPage: " + current);
            return;
        }
        inspectPage(page, "pageFirstSegment");
    }

    private static void verifyReopen() {
        String stable = PhoneMemorySmokeBackend.getStableId();
        ChatPage reopened = new ChatPage(stable, PhoneMemorySmokeBackend.MAID_NAME);
        PhoneOverlay.pushPage(reopened);
        inspectPage(reopened, "reopenQuote");
    }

    private static void inspectPage(ChatPage page, String suffix) {
        int expectedIndex = scenario;
        String expected = PhoneMemorySmokeBackend.getLastQuoteId();
        try {
            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) fieldValue(page, "messages");
            int quoteCount = 0;
            int firstQuote = -1;
            String firstId = null;
            String firstText = "";
            for (int i = 0; i < messages.size(); i++) {
                Object message = messages.get(i);
                Object quote = invokeDeclared(message, "quote");
                if (i == 0) {
                    firstText = String.valueOf(invokeDeclared(message, "text"));
                }
                if (quote instanceof PhoneMessageQuote value) {
                    quoteCount++;
                    if (firstQuote < 0) {
                        firstQuote = i;
                        firstId = value.messageId();
                    }
                }
            }
            boolean ok = messages.size() >= 2 && quoteCount == 1 && firstQuote == 0
                    && expected.equals(firstId);
            check("scenario_" + (expectedIndex + 1) + "_" + suffix, ok,
                    "messages=" + messages.size() + " quoteCount=" + quoteCount
                            + " firstQuote=" + firstQuote + " firstId=" + firstId
                            + " firstText=" + firstText);
        } catch (Throwable throwable) {
            error("inspect_" + (expectedIndex + 1) + "_" + suffix, throwable);
            check("scenario_" + (expectedIndex + 1) + "_" + suffix, false, String.valueOf(throwable));
        }
    }

    private static void requestShot(String name) {
        shotName = name;
        shotPending = true;
        shotDone = false;
    }

    private static void finish(Minecraft mc) {
        if (finished) {
            return;
        }
        finished = true;
        try {
            List<String> missing = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (String name : REQUIRED_CHECKS) {
                Object value = PhoneMemorySmokeBackend.CHECKS.get(name);
                if (!(value instanceof Map<?, ?> map)) {
                    missing.add(name);
                } else if (!Boolean.TRUE.equals(map.get("ok"))) {
                    failed.add(name);
                }
            }
            boolean screenshotsOk = SCREENSHOTS.size() == PhoneMemorySmokeBackend.SHOT_NAMES.length;
            for (String path : SCREENSHOTS) {
                screenshotsOk &= Files.isRegularFile(Path.of(path));
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("task", "phone-memory-native-smoke");
            root.put("worldProperty", System.getProperty(PROP_WORLD, ""));
            root.put("missingChecks", missing);
            root.put("failedChecks", failed);
            root.put("completed", missing.isEmpty() && failed.isEmpty()
                    && PhoneMemorySmokeBackend.ERRORS.isEmpty()
                    && PhoneMemorySmokeBackend.allChecksOk()
                    && screenshotsOk
                    && !PhoneMemorySmokeBackend.isFatal());
            root.put("checks", PhoneMemorySmokeBackend.CHECKS);
            root.put("errors", PhoneMemorySmokeBackend.ERRORS);
            root.put("screenshots", SCREENSHOTS);
            root.put("elapsedMs", elapsedMs());
            root.put("nativeFixtureNote",
                    "Native fixtures prove registered tools, archive permission/storage and real S2C/UI quoting; "
                            + "they do not prove a paid model autonomously called these tools.");
            Map<String, Object> display = new LinkedHashMap<>();
            if (mc != null) {
                display.put("windowWidth", mc.getWindow().getWidth());
                display.put("windowHeight", mc.getWindow().getHeight());
                display.put("guiScaledWidth", mc.getWindow().getGuiScaledWidth());
                display.put("guiScaledHeight", mc.getWindow().getGuiScaledHeight());
                display.put("guiScale", mc.options.guiScale().get());
                display.put("fov", mc.options.fov().get());
                display.put("pitch", mc.player == null ? null : mc.player.getXRot());
            }
            root.put("display", display);
            Path out = outputDir().resolve("result.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            error("writeResult", throwable);
        }
        if (mc != null) {
            mc.stop();
        }
    }

    private static void check(String name, boolean ok, String detail) {
        PhoneMemorySmokeBackend.check(name, ok, detail);
    }

    private static void error(String where, Throwable throwable) {
        PhoneMemorySmokeBackend.error(where, throwable);
    }

    private static boolean enabled() {
        return Boolean.getBoolean(PROP_ENABLED);
    }

    private static Path outputDir() {
        return Path.of(System.getProperty(PROP_OUTPUT, "phone-memory-results"));
    }

    private static long elapsedMs() {
        return clientStartedNanos < 0 ? 0L : (System.nanoTime() - clientStartedNanos) / 1_000_000L;
    }

    private static Object invokeDeclared(Object target, String name) throws Exception {
        Method method = findMethod(target.getClass(), name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params)
            throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // continue with superclass
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static Object fieldValue(Object target, String name) throws Exception {
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // continue with superclass
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "#" + name);
    }
}
