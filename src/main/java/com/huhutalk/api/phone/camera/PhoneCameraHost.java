package com.huhutalk.api.phone.camera;

import com.huhutalk.phone.IPhonePage;

/**
 * 「这一页此刻是一台相机」的<b>公开契约</b>。
 *
 * <h2>它是干什么的</h2>
 * 相机的取景界面不是普通页面：它要
 * <ol>
 *   <li><b>透出真实世界</b>——手机体变透明、3D 机壳换成擦除版纹理，游戏画面从取景框里透出来；</li>
 *   <li><b>接管输入</b>——鼠标只管转视角去取景，动作全交给玩家的手机侧键（默认鼠标侧键 4/5），
 *       所以此时不画独立光标、也不吃鼠标点击；</li>
 *   <li><b>换个姿态</b>——把手机抬到眼前当取景框，而不是平时的"平行地面"持握。</li>
 * </ol>
 * 这三件事分散在 {@code PhoneOverlay} 与 {@code ProPhoneHandRenderer} 里，都靠"当前页是不是相机"
 * 这一个判据驱动。本接口就是这个判据：<b>当前页实现了它并且 {@link #isCameraMode()} 返回 true</b>，
 * 上面三条就自动生效，你不需要碰 HuhuTalk 的任何源码。
 *
 * <h2>最小实现</h2>
 * <pre>{@code
 * public final class MyCameraPage implements PhoneCameraHost {
 *     private byte[] shot;
 *
 *     @Override public boolean isCameraMode() { return true; }   // 整页都是取景界面
 *
 *     @Override public void cameraPrimaryAction() {              // 侧键 4 轻点
 *         PhoneOverlay.requestWorldCapture(png -> shot = png);
 *     }
 *     @Override public void cameraBackAction() {                 // 侧键 5
 *         PhoneOverlay.popPage();                                // 回上一页
 *     }
 *     // cameraSaveAction / handlesLongPress 有默认实现，按需覆盖
 *     ...
 * }
 * }</pre>
 *
 * <h2>⚠ 别继承 {@code PhonePage}</h2>
 * {@code PhonePage.render} 是 {@code final}，且必定用 {@code pageBackground()} 把整个内容区铺满——
 * 那会把你辛苦留出来的透明取景框盖死。<b>相机页请直接 {@code implements PhoneCameraHost}</b>
 * （它已经继承了 {@link IPhonePage}），自己画取景框。
 *
 * <h2>两个状态的页怎么办</h2>
 * 聊天页的相机就是这样：取景中是一个状态，"已拍成"是另一个状态。
 * 那种页让 {@link #isCameraMode()} 只在<b>需要透明取景</b>的状态返回 true 即可；
 * 已拍成页若想显示全屏照片、还想让玩家用光标点按钮，
 * 就应该让 {@code isCameraMode()} 返回 false（否则光标不画、点击也被吞掉）。
 *
 * @see com.huhutalk.phone.PhoneOverlay#isCameraMode()
 */
public interface PhoneCameraHost extends IPhonePage {

    /**
     * 当前<b>是否处于取景界面</b>。
     * <p>
     * 返回 true 时：手机体透明 + 3D 机壳用擦除纹理 + 不画光标 + 不吃鼠标点击 +
     * 侧键路由到本接口的三个动作方法 + 手机摆到取景姿态。
     * <p>
     * 页面内有多级状态时（如聊天页的「取景中 / 已拍成」），只在该透明取景的状态返回 true。
     */
    boolean isCameraMode();

    /**
     * 「主操作」——对应玩家绑定的<b>屏幕确认键</b>（默认鼠标侧键 4）轻点。
     * <p>
     * 典型语义：取景中 → 拍照。取景后还有确认页的页，也在这里分派（见 {@code ChatPage}）。
     */
    void cameraPrimaryAction();

    /**
     * 「返回 / 取消」——对应玩家绑定的<b>屏幕返回键</b>（默认鼠标侧键 5）。
     */
    void cameraBackAction();

    /**
     * 「保存到相册」——对应<b>长按</b>屏幕确认键。
     * <p>
     * 默认空实现：不是所有相机都需要第二级动作（独立相机就是"按下即存"，没有长按语义）。
     * 不覆盖它时记得把 {@link #handlesLongPress()} 也返回 false，
     * 否则玩家按住不放会既没有长按动作、又因为"长按已触发"而丢掉这次轻点。
     */
    default void cameraSaveAction() {
    }

    /**
     * 是否使用「长按 = 保存」这套手势。
     * <p>
     * <ul>
     *   <li>{@code true}（默认）：按住确认键到阈值触发 {@link #cameraSaveAction()}，
     *       松开时<b>不再</b>补一次 {@link #cameraPrimaryAction()}；</li>
     *   <li>{@code false}：不检测长按，松开总是走 {@link #cameraPrimaryAction()}——
     *       适合「按下即拍/即存」的相机，避免"按住超过阈值反而什么都没发生"。</li>
     * </ul>
     */
    default boolean handlesLongPress() {
        return true;
    }
}
