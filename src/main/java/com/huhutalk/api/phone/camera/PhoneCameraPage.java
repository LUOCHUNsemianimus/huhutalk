package com.huhutalk.api.phone.camera;

import com.huhutalk.api.phone.ui.PhoneImages;
import com.huhutalk.api.phone.ui.PhoneShapes;
import com.huhutalk.api.phone.ui.PhoneText;
import com.huhutalk.client.PhotoLibrary;
import com.huhutalk.event.ClientModEvents;
import com.huhutalk.phone.PhoneOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * <b>桌面「相机」App 的页面</b>——一个独立于聊天页的、整屏取景的相机。
 *
 * <h2>与聊天内相机的区别（就这两条）</h2>
 * <ol>
 *   <li><b>没有「已拍成页」</b>：按下就是拍照 + 存入相册，然后<b>留在取景界面继续拍</b>——
 *       和真机相机的"按几下拍几下"一致。因为没有"确认/重拍"这个反悔设计，
 *       拍照成功后<b>刻意不弹提示气泡</b>（只有存盘失败才说话）。聊天内相机则有"确认/重拍"两态。</li>
 *   <li><b>左下角缩略图是纯装饰</b>：不参与命中（相机界面全程没有光标，鼠标要留给转视角取景），
 *       玩家想看照片请去聊天页的相册。</li>
 * </ol>
 * 其它版面（顶栏两段文字、四角取景标记、准星、圆形快门、底部黑边）与聊天内相机<b>逐项一致</b> ——
 * 两套相机是同一台手机上的两个入口，不该让玩家以为是两个不同的东西。
 *
 * <h2>它怎么拿到"透明取景框 + 侧键操作"</h2>
 * 全部来自 {@link PhoneCameraHost}：只要 {@link #isCameraMode()} 返回 true，
 * {@code PhoneOverlay} 就会把手机体变透明、3D 机壳换成擦除版纹理、不画光标、不吃鼠标点击，
 * 并把玩家绑定的手机侧键路由到 {@link #cameraPrimaryAction()} / {@link #cameraBackAction()}。
 * <b>本类一行都没有碰 PhoneOverlay 的渲染逻辑</b>。
 *
 * <h2>键位</h2>
 * <table border="1">
 *   <tr><th>操作</th><th>行为</th></tr>
 *   <tr><td>侧键 4（手机左键，默认鼠标侧键 4）</td><td>拍照并存相册，留在取景</td></tr>
 *   <tr><td>侧键 5（手机右键，默认鼠标侧键 5）</td><td>退出相机，回桌面</td></tr>
 *   <tr><td>长按侧键 4</td><td>无第二级语义（已经存了），松开照样拍一张 —— 见 {@link #handlesLongPress()}</td></tr>
 * </table>
 *
 * <h2>⚠ 不要继承 {@code PhonePage}</h2>
 * 它的 {@code render} 是 {@code final} 且必定用 {@code pageBackground()} 铺满整个内容区，
 * 会把取景框整个盖死。相机页只能像本类这样直接 {@code implements PhoneCameraHost} 自绘。
 */
public class PhoneCameraPage implements PhoneCameraHost {

    /**
     * 装饰缩略图用的临时照片 key。
     * <p>
     * <b>刻意不复用聊天相机的 {@code __camshot__}</b>：那个 key 是聊天页"这一张还没确认的照片"，
     * 两个相机共用会让"独立相机拍一张"把聊天页正在等确认的那张覆盖掉。
     */
    public static final String THUMB_KEY = "__appcamshot__";

    /** 快门直径 = 手机宽度 × 此比例（与聊天内相机取同一个值，保持同款控件尺寸） */
    private static final float SHUTTER_W_RATIO = 0.15f;
    /** 快门直径下限（随全局字体倍率缩放） */
    private static final int SHUTTER_MIN = 9;
    /** 控件区高度（逻辑像素） */
    private static final int BAR_H = 44;
    /** 顶栏底色：与聊天内相机同一个粉，保持"同一台手机上的相机"的观感 */
    private static final int TOPBAR_BG = 0xFFDE7E7E;

    /**
     * 本次进入相机后是否已经拍过至少一张。
     * <p>
     * 没拍过时左下角<b>不画</b>缩略图——真机相机也是这样（相册空的时候那儿是空的）。
     * 一进页面就画个白框反而像坏了。
     */
    private boolean hasShot = false;

    // ==================== PhoneCameraHost ====================

    /**
     * 整页都是取景界面，所以恒为 true。
     * <p>
     * 副作用（全部是想要的）：手机体透明 + 擦除机壳 + 不画光标 + 吞鼠标点击 + 侧键路由到本类。
     * 玩家退出相机靠侧键 5，不能靠点屏幕——因为此时鼠标要用来转视角取景。
     */
    @Override
    public boolean isCameraMode() {
        return true;
    }

    /** 侧键 4 轻点：拍照并存相册 */
    @Override
    public void cameraPrimaryAction() {
        takePhoto();
    }

    /** 侧键 5：退出相机，回桌面 */
    @Override
    public void cameraBackAction() {
        PhoneOverlay.popPage();
    }

    /**
     * {@code false}：不启用「长按 = 保存」那套手势。
     * <p>
     * 本相机按下即存，长按没有第二级语义。如果这里返回 true，玩家按住超过 350ms 再松开就会
     * 落到 {@code cameraSaveAction()}（空实现）上、并且<b>不再补一次轻点</b>——
     * 表现为"按了没反应"。返回 false 后松开总是拍一张。
     */
    @Override
    public boolean handlesLongPress() {
        return false;
    }

    /**
     * 右键（独立光标那条通路）也当作"退出相机"。
     * <p>
     * 相机模式下独立光标本来就被关掉了，这条基本走不到；留着是为了万一从别的通路
     * （例如某个界面把右键转发进来）收到右键时行为仍然一致，而不是穿透到上层把手机收起来。
     */
    @Override
    public boolean onBackPressed() {
        cameraBackAction();
        return true;
    }

    // ==================== 拍照 ====================

    /**
     * 拍照：请求在「本帧世界画完、手与 HUD 都还没画」的时机抓一张主渲染目标
     * （见 {@code PhoneOverlay.requestWorldCapture}），然后<b>直接存入相册</b>。
     * <p>
     * 抓到的就是玩家眼里那片干净的世界——没有 HUD / 物品栏 / 手臂 / 手机本体。
     * <p>
     * ⚠ 存盘是<b>同步</b>的（PNG 编码 + 写文件，都在渲染线程）。按一下拍一张没问题；
     * 若实机上连按会掉帧，再考虑加节流或异步化。
     */
    private void takePhoto() {
        PhoneOverlay.requestWorldCapture(png -> {
            if (png == null) {
                PhoneOverlay.showBubble("拍照失败");
                return;
            }
            // 装饰缩略图：用固定 key 覆盖注册，这样左下角永远显示"刚拍的这张"
            PhotoLibrary.registerCapturedBytes(THUMB_KEY, png);
            hasShot = true;
            String saved = PhotoLibrary.saveToGallery(png);
            // 成功时**不弹气泡**：本相机是"按下即存、没有反悔"的设计，
            // 快门本身就是反馈，再每次弹一条"已保存"只是噪声。
            // 失败则必须说 —— 否则玩家会以为拍下来了，回头找不到照片。
            if (saved == null) {
                PhoneOverlay.showBubble("保存失败");
            }
        });
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics graphics, int baseX, int baseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int w = PhoneOverlay.getPhoneWidth();
        int h = PhoneOverlay.getPhoneHeight();
        int navH = navH();

        // ---- 顶栏：与聊天内相机同款，左「< 返回 <返回键名>」、右「拍照」----
        // 两段共用同一个缩放：键名是玩家绑定的、可能很长，宁可一起缩也不让它们撞在一起。
        graphics.fill(baseX, baseY, baseX + w, baseY + navH, TOPBAR_BG);
        String backHint = "< 返回 " + keyHint(false);
        String title = "拍照";
        float barTs = PhoneText.scale(0.85f);
        int barAvail = w - PhoneText.px(10) - PhoneText.px(6);
        int barRaw = font.width(backHint) + font.width(title);
        if (barRaw > 0) {
            barTs = Math.min(barTs, barAvail / (float) barRaw);
        }
        barTs = Math.max(barTs, 0.40f);
        float barTextY = baseY + navH / 2f - font.lineHeight * barTs / 2f;
        PhoneText.draw(graphics, font, backHint, baseX + PhoneText.px(4), barTextY, 0xFFFFFFFF, barTs);
        PhoneText.draw(graphics, font, title, baseX + w - PhoneText.px(6) - font.width(title) * barTs,
                barTextY, 0xFFFFFFFF, barTs);

        // ---- 取景框：不铺任何底色（游戏画面从这里透出来）+ 四角边框 + 十字准星 ----
        int viewTop = baseY + navH + PhoneText.px(8);
        int viewBottom = baseY + viewBottom();
        int cx = baseX + w / 2;
        int cy = (viewTop + viewBottom) / 2;

        int fr = PhoneText.px(2);       // 框线宽
        int corner = PhoneText.px(16);  // 四角边长
        int l = baseX + PhoneText.px(8);
        int r = baseX + w - PhoneText.px(8);
        graphics.fill(l, viewTop, l + corner, viewTop + fr, 0xCCFFFFFF);
        graphics.fill(r - corner, viewTop, r, viewTop + fr, 0xCCFFFFFF);
        graphics.fill(l, viewBottom - fr, l + corner, viewBottom, 0xCCFFFFFF);
        graphics.fill(r - corner, viewBottom - fr, r, viewBottom, 0xCCFFFFFF);
        graphics.fill(l, viewTop, l + fr, viewTop + corner, 0xCCFFFFFF);
        graphics.fill(r - fr, viewTop, r, viewTop + corner, 0xCCFFFFFF);
        graphics.fill(l, viewBottom - corner, l + fr, viewBottom, 0xCCFFFFFF);
        graphics.fill(r - fr, viewBottom - corner, r, viewBottom, 0xCCFFFFFF);
        graphics.fill(cx - 10, cy - 1, cx + 10, cy + 1, 0xAAFFFFFF);
        graphics.fill(cx - 1, cy - 10, cx + 1, cy + 10, 0xAAFFFFFF);

        // ---- 控件区：半透明黑条，衬托控件可读 ----
        // 这条黑边的**上沿正好是取景区四角标记的下沿**（两者都用 viewBottom()），不是巧合：
        // 手机 3D 模型在屏幕下部预留了一条"本该镂空"的横带，但模型那边并没有真的掏空，
        // 于是那条带子里会露出机壳而不是游戏画面。把它用黑边盖住，既遮掉了那截机壳，
        // 又正好和四角取景标记连成完整的取景框。
        // 注意：**只有这一条**允许铺不透明/半不透明填充，取景框范围内一律不许铺东西。
        graphics.fill(baseX, viewBottom, baseX + w, baseY + h, 0x88000000);

        int[] L = barLayout(w);
        int thumbX = L[2], thumbY = L[3], thumbSize = L[4];
        int shutterX = L[5], shutterY = L[6], shutterD = L[7];

        // 左下：装饰缩略图（刚拍的这张）。拍过才画。
        if (hasShot) {
            ResourceLocation thumbTex = PhotoLibrary.getOrLoadTexture(THUMB_KEY);
            // getTextureSize 永不返回 null（取不到时兜底 24×24），所以不判空
            int[] thumbDim = PhotoLibrary.getTextureSize(THUMB_KEY);
            if (thumbTex != null) {
                int tx = baseX + thumbX;
                int ty = baseY + thumbY;
                PhoneShapes.fillRoundedRect(graphics, tx, ty, thumbSize, thumbSize, PhoneText.px(3), 0xCCFFFFFF);
                // 用 cover 而不是拉伸：任意屏幕比例的截图贴进方形框里都不变形
                PhoneImages.drawCover(graphics, thumbTex, tx, ty, thumbSize, thumbSize, thumbDim[0], thumbDim[1]);
            }
        }

        // 中间：圆形快门（白圈 + 粉芯），圆内写玩家绑定的确认键名。
        // 直径很小，所以键名要自适应字号：以圆的内接矩形为可用区（宽 ≈ 0.86d、高 ≈ 0.52d），
        // 两个方向同时收缩取小；倍率随全局字体倍率走，玩家用 2 倍 GUI 时这些逻辑像素会整体放大。
        int sx = baseX + shutterX;
        int sy = baseY + shutterY;
        int ring = Math.max(2, shutterD / 10);
        PhoneShapes.fillRoundedRect(graphics, sx, sy, shutterD, shutterD, shutterD / 2, 0xFFFFFFFF);
        int inner = shutterD - ring * 2;
        PhoneShapes.fillRoundedRect(graphics, sx + ring, sy + ring, inner, inner, inner / 2, 0xFFDE7E7E);

        String key = keyHint(true);
        int keyW = Math.max(1, font.width(key));
        // 以**粉芯**为可用区而不是整个圆：白圈还要占 ring 像素，键名压到白圈上会糊在一起
        float ts = Math.min(inner * 0.86f / keyW, inner * 0.52f / Math.max(1f, font.lineHeight));
        ts = Math.max(0.20f, Math.min(ts, 0.70f));
        PhoneText.draw(graphics, font, key, sx + (shutterD - keyW * ts) / 2f,
                sy + (shutterD - font.lineHeight * ts) / 2f, 0xFFFFFFFF, ts);
    }

    /**
     * 相机界面全程由侧键驱动、没有光标，所以这里没有命中区可言。
     * <p>
     * 但仍然<b>必须消费掉</b>而不是返回 false：相机模式下 {@code PhoneOverlay} 已经把这条通路
     * 关掉了（{@code handlePhoneLocalMouse} 直接 return false），万一哪天有别的通路把点击转进来，
     * 返回 false 会让它继续往上走，可能误触到"收起手机"之类的行为。
     */
    @Override
    public boolean mouseClicked(double relX, double relY, int button, int action) {
        return true;
    }

    // ==================== 布局 ====================

    private int navH() {
        return Math.max(1, (int) (Minecraft.getInstance().font.lineHeight * PhoneText.scale(1.0f))) + PhoneText.px(8);
    }

    /** 取景框底 = 内容区相对坐标（下方留给控件区） */
    private int viewBottom() {
        return PhoneOverlay.getPhoneHeight() - PhoneText.px(BAR_H);
    }

    /**
     * 控件区布局（渲染与任何未来的命中判定共用同一份，避免两处各写一套导致脱节）。
     *
     * @return {barTop, barH, thumbX, thumbY, thumbSize, shutterX, shutterY, shutterD}
     */
    private int[] barLayout(int w) {
        int h = PhoneOverlay.getPhoneHeight();
        int barTop = viewBottom();
        int barH = Math.max(PhoneText.px(24), h - barTop);
        int edge = PhoneText.px(5);

        int thumbSize = Math.max(PhoneText.px(14), barH - 8);
        int thumbX = edge;
        int thumbY = barTop + (barH - thumbSize) / 2;

        int shutterD = Math.max(PhoneText.px(SHUTTER_MIN), Math.round(w * SHUTTER_W_RATIO));
        int shutterX = w / 2 - shutterD / 2;
        int shutterY = barTop + (barH - shutterD) / 2;

        return new int[]{barTop, barH, thumbX, thumbY, thumbSize, shutterX, shutterY, shutterD};
    }

    /**
     * 取当前玩家绑定键位的显示名（例如 {@code 鼠标按键 4} 或 {@code N}）。
     * <p>
     * 必须走 {@code getTranslatedKeyMessage()} 而不是硬编码字符串：玩家改绑之后，
     * 界面上的提示要立刻跟着变，而不是继续写着旧键名。这也正是"界面即文档"的意思。
     */
    private String keyHint(boolean confirmButton) {
        var mapping = confirmButton ? ClientModEvents.PHONE_CLICK : ClientModEvents.PHONE_BACK;
        return mapping.getTranslatedKeyMessage().getString();
    }
}
