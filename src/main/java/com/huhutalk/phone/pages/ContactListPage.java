package com.huhutalk.phone.pages;

import com.huhutalk.client.MaidGroupHelper;
import com.huhutalk.client.MaidListCache;
import com.huhutalk.client.SosBindCache;
import com.huhutalk.client.theme.ColorScheme;
import com.huhutalk.client.theme.ThemeManager;
import com.huhutalk.common.MaidStatus;
import com.huhutalk.network.C2SRequestMaidListPacket;
import com.huhutalk.network.S2CMaidListPacket;
import com.huhutalk.phone.IPhonePage;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联系人列表页（底部导航「联系人」tab 顶层页）。
 * <p>
 * 复用 {@link MaidListCache} 中的全量女仆列表，按分组聚合展示：
 * 分组来源为 {@link MaidGroupHelper#resolveGroup}（优先玩家手动分组，未设置则按状态默认分组）。
 * 系统默认分组固定顺序：在线 / 离线 / 已关机 / 死亡（按 {@link MaidGroupHelper#groupSortIndex}），
 * 玩家自定义分组排在最后。分组标题行随内容一起滚动。
 * 点击女仆条目进入 {@link MaidInfoPage}（统一信息设置页）。
 */
public class ContactListPage implements IPhonePage {
    /** 顶部标题栏高度：随字体缩放（跟随 GUI 字体大小） */
    private static int navH() {
        return Minecraft.getInstance().font.lineHeight + 8;
    }

    /** 分组标题行高度 */
    private static final int GROUP_HEADER_H = 18;
    /** 女仆条目行高度 */
    private static final int ITEM_HEIGHT = 34;
    /** 头像占位色块边长 */
    private static final int AVATAR_SIZE = 22;
    /** SOS 角标边长（占头像面积约 1/5，约 6×6 像素） */
    private static final int SOS_BADGE_SIZE = 6;
    /** 常规文字深色 */
    private static final int TEXT_COLOR = 0xFF333333;
    /** 次要文字灰色 */
    private static final int TEXT_SUB = 0xFF888888;
    /** 头像占位色块颜色（蓝灰） */
    private static final int AVATAR_COLOR = 0xFFB0BEC5;

    /** 行类型：分组标题行 */
    private static final int ROW_GROUP = 0;
    /** 行类型：女仆条目行 */
    private static final int ROW_MAID = 1;

    /** 单行数据：分组标题行取 group，女仆条目行取 maid */
    private record Row(int kind, String group, S2CMaidListPacket.MaidDisplayInfo maid) {}

    /** 列表滚动偏移（像素），分组标题随内容一起滚动 */
    private int scrollOffset = 0;

    public ContactListPage() {
        // 进入联系人页即向服务端请求最新全量女仆列表（检测新增/变化的女仆）
        PacketDistributor.sendToServer(new C2SRequestMaidListPacket());
    }

    @Override
    public void onOpen() {
        // 每次从内部页返回时也刷新一次，确保新增女仆能及时出现
        PacketDistributor.sendToServer(new C2SRequestMaidListPacket());
    }

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int bottomNavH = PhoneOverlay.getBottomNavHeight();
        ColorScheme scheme = ThemeManager.getActive();
        Font font = Minecraft.getInstance().font;
        // 标题栏高度随字体缩放（跟随 GUI 字体大小），而非手机高度百分比
        int navH = navH();

        // 页面底色
        graphics.fill(baseX, baseY, baseX + w, baseY + h, scheme.pageBg());

        // 顶部标题栏：主题粉底 + 白字标题
        graphics.fill(baseX, baseY, baseX + w, baseY + navH, scheme.topBarBg());
        String title = "联系人";
        graphics.drawString(font, title, baseX + (w - font.width(title)) / 2,
                baseY + (navH - font.lineHeight) / 2 + 1, scheme.topBarText());

        // 内容区：标题栏下方起，底部预留底部导航高度
        int contentTop = baseY + navH;
        int contentBottom = baseY + h - bottomNavH;

        List<Row> rows = buildRows();
        int contentH = 0;
        for (Row row : rows) {
            contentH += rowHeight(row);
        }
        int viewH = Math.max(0, contentBottom - contentTop);
        // 列表变短时把滚动钳制回合法区间，避免出现空白
        int maxScroll = Math.max(0, contentH - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        if (rows.isEmpty()) {
            // 空列表：内容区居中提示
            String tip = "暂无联系人";
            graphics.drawString(font, tip, baseX + (w - font.width(tip)) / 2,
                    (contentTop + contentBottom) / 2 - font.lineHeight / 2, TEXT_SUB, false);
            return;
        }

        // 裁剪到内容区，防止滚动时溢出到标题栏/底部导航
        graphics.enableScissor(baseX, contentTop, baseX + w, contentBottom);
        int y = contentTop - scrollOffset;
        for (Row row : rows) {
            int rowH = rowHeight(row);
            if (y + rowH >= contentTop && y <= contentBottom) {
                if (row.kind() == ROW_GROUP) {
                    renderGroupHeader(graphics, font, scheme, baseX, w, y, row.group());
                } else {
                    renderMaidItem(graphics, font, scheme, baseX, w, y, row.maid());
                }
            }
            y += rowH;
        }
        graphics.disableScissor();
    }

    /** 渲染分组标题行（柔粉底 + 深色分组名） */
    private void renderGroupHeader(GuiGraphics g, Font font, ColorScheme scheme, int baseX, int w, int y, String group) {
        g.fill(baseX, y, baseX + w, y + GROUP_HEADER_H, scheme.accentSoft());
        g.drawString(font, group, baseX + 10, y + (GROUP_HEADER_H - font.lineHeight) / 2 + 1, TEXT_COLOR, false);
    }

    /** 渲染女仆条目行：头像占位色块（+SOS 角标）、名字、状态 */
    private void renderMaidItem(GuiGraphics g, Font font, ColorScheme scheme, int baseX, int w, int y,
                                S2CMaidListPacket.MaidDisplayInfo info) {
        // 头像占位色块（条目左侧）
        int avatarX = baseX + 6;
        int avatarY = y + (ITEM_HEIGHT - AVATAR_SIZE) / 2;
        g.fill(avatarX, avatarY, avatarX + AVATAR_SIZE, avatarY + AVATAR_SIZE, AVATAR_COLOR);

        // SOS 角标：头像右上角 6×6 珊瑚红小方块，仅作"已开启 SOS 呼叫"提醒，不可点击
        if (SosBindCache.isBound(info.stableId())) {
            g.fill(avatarX + AVATAR_SIZE - SOS_BADGE_SIZE, avatarY,
                    avatarX + AVATAR_SIZE, avatarY + SOS_BADGE_SIZE, scheme.sosBg());
        }

        // 名字（深色）
        int nameX = avatarX + AVATAR_SIZE + 6;
        g.drawString(font, info.name(), nameX, y + (ITEM_HEIGHT - font.lineHeight) / 2 + 1, TEXT_COLOR, false);

        // 状态（右对齐）
        String statusText = statusText(info.status());
        g.drawString(font, statusText, baseX + w - font.width(statusText) - 8,
                y + (ITEM_HEIGHT - font.lineHeight) / 2 + 1, statusColor(info.status()), false);

        // 底部分隔线
        g.fill(baseX + 6, y + ITEM_HEIGHT - 1, baseX + w - 6, y + ITEM_HEIGHT, 0x22000000);
    }

    /** 将全量女仆列表按分组聚合成行序列（分组标题行 + 女仆条目行） */
    private List<Row> buildRows() {
        // 分组名 -> 女仆列表（LinkedHashMap 保持首次出现顺序，自定义分组稳定排列）
        Map<String, List<S2CMaidListPacket.MaidDisplayInfo>> groups = new LinkedHashMap<>();
        // 先预置玩家持久化的自定义分组（即使当前没有女仆在其中也展示空分组）
        for (String g : com.huhutalk.client.settings.HuhuTalkClientSettings.getCustomGroupNames()) {
            groups.computeIfAbsent(g, k -> new ArrayList<>());
        }
        for (S2CMaidListPacket.MaidDisplayInfo info : MaidListCache.getList()) {
            String group = MaidGroupHelper.resolveGroup(info.stableId(), info.status());
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(info);
        }
        // 固定顺序：在线/离线/已关机/死亡/自定义（List.sort 稳定，同权重保持插入顺序）
        List<String> names = new ArrayList<>(groups.keySet());
        names.sort(Comparator.comparingInt(MaidGroupHelper::groupSortIndex));

        List<Row> rows = new ArrayList<>();
        for (String group : names) {
            rows.add(new Row(ROW_GROUP, group, null));
            for (S2CMaidListPacket.MaidDisplayInfo info : groups.get(group)) {
                rows.add(new Row(ROW_MAID, null, info));
            }
        }
        return rows;
    }

    /** 行高度：分组标题行 / 女仆条目行 */
    private static int rowHeight(Row row) {
        return row.kind() == ROW_GROUP ? GROUP_HEADER_H : ITEM_HEIGHT;
    }

    /** 状态中文名 */
    private static String statusText(MaidStatus status) {
        if (status == null) {
            return "离线";
        }
        return switch (status) {
            case ONLINE -> "在线";
            case OFFLINE -> "离线";
            case DEAD -> "死亡";
            case SHUTDOWN -> "已关机";
        };
    }

    /** 状态文字颜色（深色系，保证浅粉底色可读） */
    private static int statusColor(MaidStatus status) {
        if (status == null) {
            return 0xFF888888;
        }
        return switch (status) {
            case ONLINE -> 0xFF2E7D32;   // 深绿
            case OFFLINE -> 0xFF888888;  // 灰
            case DEAD -> 0xFFC62828;     // 深红
            case SHUTDOWN -> 0xFF9E9E9E; // 浅灰
        };
    }

    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        if (action != 1) return false;
        int h = PhoneOverlay.getPhoneHeight();
        int navH = navH();
        int bottomNavH = PhoneOverlay.getBottomNavHeight();
        // 标题栏 / 底部导航区域不响应条目点击
        if (relY < navH || relY >= h - bottomNavH) {
            return false;
        }

        List<Row> rows = buildRows();
        // 与渲染同步钳制滚动偏移，保证命中判定与显示一致
        int contentH = 0;
        for (Row row : rows) {
            contentH += rowHeight(row);
        }
        int viewH = Math.max(0, h - navH - bottomNavH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, contentH - viewH)));

        int y = navH - scrollOffset;
        for (Row row : rows) {
            int rowH = rowHeight(row);
            if (relY >= y && relY < y + rowH) {
                if (row.kind() == ROW_MAID) {
                    S2CMaidListPacket.MaidDisplayInfo info = row.maid();
                    PhoneOverlay.pushPage(new MaidInfoPage(info.stableId(), info.name()));
                    return true;
                }
                return false; // 分组标题行不响应点击
            }
            y += rowH;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double relX, double relY, double scrollDelta) {
        int h = PhoneOverlay.getPhoneHeight();
        int navH = navH();
        int bottomNavH = PhoneOverlay.getBottomNavHeight();

        List<Row> rows = buildRows();
        int contentH = 0;
        for (Row row : rows) {
            contentH += rowHeight(row);
        }
        int viewH = Math.max(0, h - navH - bottomNavH);
        int maxScroll = Math.max(0, contentH - viewH);
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int)(scrollDelta * 10), maxScroll));
        return true;
    }
}
