package com.huhutalk.core.archive;

/**
 * 档案页固定底栏的纯 Java 几何。
 *
 * <p>底栏三个按钮（较近 / 刷新 / 较早）的本地 X 由内容宽度统一计算；
 * 页面渲染时用 {@link #screenX(int, int)} 平移到屏幕坐标，命中时用
 * {@link #hit(double, double, int, int)} 使用同一份本地矩形。这样绘制与命中
 * 不再各算一遍，也不会在非零内容原点（例如手机居中后左侧有偏移）时画在
 * 屏幕左缘却仍按本地坐标判定。</p>
 *
 * <p>本类不引用 Minecraft，上游可用纯 Java 直接验证不同宽度、不同原点下的
 * 矩形平移；像素缩放由页面传入，几何本身只处理已缩放的整数像素。</p>
 */
public final class ArchiveHistoryGeometry {
    public static final int NEWER_BUTTON = 0;
    public static final int REFRESH_BUTTON = 1;
    public static final int OLDER_BUTTON = 2;
    public static final int BUTTON_COUNT = 3;

    private final int contentWidth;
    private final int buttonHeight;
    private final int footerHeight;
    private final int buttonWidth;
    private final int[] localXs;

    /**
     * @param contentWidth  内容区宽度（已缩放像素）
     * @param buttonMargin  底栏左右外边距（已缩放像素）
     * @param buttonGap     按钮间距（已缩放像素）
     * @param buttonHeight  按钮高度（已缩放像素）
     * @param footerHeight  底栏高度（已缩放像素）
     */
    public ArchiveHistoryGeometry(int contentWidth, int buttonMargin, int buttonGap,
                                  int buttonHeight, int footerHeight) {
        this.contentWidth = contentWidth;
        this.buttonHeight = buttonHeight;
        this.footerHeight = footerHeight;
        this.buttonWidth = Math.max(1, (contentWidth - buttonMargin * 2
                - buttonGap * 2) / 3);
        this.localXs = new int[]{
                buttonMargin,
                buttonMargin + buttonWidth + buttonGap,
                buttonMargin + (buttonWidth + buttonGap) * 2
        };
    }

    public int contentWidth() {
        return contentWidth;
    }

    public int buttonWidth() {
        return buttonWidth;
    }

    public int buttonHeight() {
        return buttonHeight;
    }

    public int footerHeight() {
        return footerHeight;
    }

    /** 底栏上边沿在内容坐标系中的 Y（内容高度从内容区顶部算起）。 */
    public int footerTop(int contentHeight) {
        return contentHeight - footerHeight;
    }

    /** 第 {@code index} 个按钮在内容坐标系中的本地 X。 */
    public int localX(int index) {
        requireIndex(index);
        return localXs[index];
    }

    /** 按钮行在内容坐标系中的本地 Y。 */
    public int localY(int contentHeight) {
        return footerTop(contentHeight) + (footerHeight - buttonHeight) / 2;
    }

    /** 渲染用：本地 X 平移到内容区原点的屏幕坐标。 */
    public int screenX(int originX, int index) {
        return originX + localX(index);
    }

    /** 渲染用：本地 Y 平移到内容区原点的屏幕坐标。 */
    public int screenY(int originY, int contentHeight) {
        return originY + localY(contentHeight);
    }

    /**
     * 命中用：点击点（内容坐标）是否落在第 {@code index} 个按钮上。
     * 与渲染共用 {@link #localX(int)} / {@link #localY(int)}。
     */
    public boolean hit(double localX, double localY, int contentHeight, int index) {
        int x = localX(index);
        int y = localY(contentHeight);
        return localX >= x && localX < x + buttonWidth
                && localY >= y && localY < y + buttonHeight;
    }

    private static void requireIndex(int index) {
        if (index < 0 || index >= BUTTON_COUNT) {
            throw new IllegalArgumentException("button index out of range: " + index);
        }
    }
}
