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
 * 聊天页的相机就是这样：取景中是一个状态，"已拍成"是另一个状态。做法是
 * <b>{@link #isCameraMode()} 全程返回 true</b>（两个状态都是相机），再按需关掉细分判据：
 * <ul>
 *   <li>已拍成要显示<b>全屏照片</b> → {@link #cameraScreenTransparent()} 返回 false，
 *       否则照片是半透的、背后的世界会直接穿过来；</li>
 *   <li>已拍成还想让玩家用<b>光标点按钮</b> → 把 {@link #cameraSideKeysActive()} 与
 *       {@link #cameraInputExclusive()} <b>一起</b>返回 false
 *       （只关一个没有用：侧键不关的话，键事件在路由那一步就被消费了，光标永远点不到东西）。</li>
 * </ul>
 * HuhuTalk 自己的聊天相机<b>只做了第一条</b>：侧键 4 = 发送 / 侧键 5 = 重拍是玩家的肌肉记忆，
 * 换成"挪光标去点按钮"反而更难用，所以它在已拍成状态仍然不画光标。
 * <p>
 * ⚠ <b>不要</b>用"让某个状态的 {@code isCameraMode()} 返回 false"来实现第二条 ——
 * 那会把侧键一起关掉，玩家的侧键 4/5 就不再是"发送/重拍"了。
 *
 * @see com.huhutalk.phone.PhoneOverlay#isCameraMode()
 */
public interface PhoneCameraHost extends IPhonePage {

    /**
     * 主开关：当前<b>是否处于相机界面</b>。
     * <p>
     * 返回 true 时，下面三个细分判据（{@link #cameraScreenTransparent()} /
     * {@link #cameraSideKeysActive()} / {@link #cameraInputExclusive()}）默认也全部返回 true，
     * 于是这台相机会拿到"手机体透明 + 擦除机壳 + 侧键接管 + 不画光标"的整套行为。
     * <p>
     * <b>单状态相机（如桌面「相机」App）只需要实现这一个方法。</b>
     * 页内有多级状态、需要给不同状态不同待遇时，再按需覆盖下面三个。
     */
    boolean isCameraMode();

    /**
     * 手机体是否<b>透明</b>（同时 3D 机壳换用擦除版纹理），让游戏画面从取景框里透出来。
     * 默认跟随 {@link #isCameraMode()}。
     * <p>
     * <b>什么时候需要单独关掉它</b>：相机有一个"显示全屏照片"的状态时。
     * 那个状态要是还透明，照片就会半透、背后的世界直接穿过来。
     * 聊天内相机（{@code ChatPage}）就是这么用的：
     * {@code cameraActive && cameraShot == null}。
     * <p>
     * ⚠ 它<b>只管外观</b>：关掉它不会让光标回来、也不会改变侧键含义。
     */
    default boolean cameraScreenTransparent() {
        return isCameraMode();
    }

    /**
     * 侧键 4 / 5 是否路由到 {@link #cameraPrimaryAction()} / {@link #cameraBackAction()}
     * （以及「长按 = 保存」手势是否生效）。默认跟随 {@link #isCameraMode()}。
     * <p>
     * <b>什么时候需要单独关掉它</b>：某个状态想让玩家用<b>光标点按钮</b>而不是按侧键。
     * 侧键 4 在手机里就是"左键单击"，只要它还路由给相机，玩家的光标就永远点不到东西
     * （键事件在路由那一步就被消费掉了）。所以"能点按钮的状态"必须
     * {@code cameraSideKeysActive() == false} 且 {@code cameraInputExclusive() == false} 一起成立。
     */
    default boolean cameraSideKeysActive() {
        return isCameraMode();
    }

    /**
     * 是否<b>独占输入</b>：不画独立光标 + 吞掉鼠标点击。默认跟随 {@link #isCameraMode()}。
     * <p>
     * 取景界面通常要保持独占 —— 玩家的鼠标要完整留给"转视角取景"，
     * 画个光标出来只会让人误以为能挪过去点。
     * <p>
     * 想让某个状态可点，就把它返回 false（并同时关掉 {@link #cameraSideKeysActive()}，
     * 理由见那个方法的说明），然后正常实现 {@link IPhonePage#mouseClicked} 做命中判定。
     */
    default boolean cameraInputExclusive() {
        return isCameraMode();
    }

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

    // ==================== 前后摄（自拍）扩展：全部有默认实现，既有相机零影响 ====================

    /**
     * 当前是否处于<b>前摄 / 自拍取景</b>。
     * <p>
     * 为 {@code true} 时，HuhuTalk 会：
     * <ul>
     *   <li>把 {@code SelfieCameraTarget} 的前摄离屏取景 pass 打开（仅本地玩家第一人称主视角拿着手机时）；</li>
     *   <li>把第一人称手持从「抬到眼前的取景姿态」换成<b>右手单手持机、屏朝玩家</b>的自拍姿态
     *       （见 {@link #cameraUseDefaultHoldPose()}）；</li>
     *   <li>把本机鼠标<b>普通右键</b>（button=1）在自拍取景时当作快门 —— 相机本来没有光标，
     *       这条是给"顺手一按"的补充通路；玩家绑定的确认/返回侧键语义完全不变。</li>
     * </ul>
     * <p>
     * <b>默认 {@code false}</b>：所有既有相机页（含附属模组）都是后摄，行为一字不变。
     * 想加前摄的页面覆盖它即可，同时请覆盖 {@link #supportsCameraFacingSwitch()} /
     * {@link #cameraToggleFacing()}，并保证<b>只在取景态</b>返回 true（已拍成/确认页不算自拍取景）。
     */
    default boolean isSelfieMode() {
        return false;
    }

    /**
     * 本相机是否支持「前摄 / 后摄」切换（决定 V 键与页内切换按钮是否生效）。
     * <p>
     * <b>默认 {@code false}</b>：既有相机页没有切换概念，按 V 键不做任何事。
     */
    default boolean supportsCameraFacingSwitch() {
        return false;
    }

    /**
     * 切换前摄 / 后摄。仅在 {@link #supportsCameraFacingSwitch()} 为 true 时被路由。
     * <p>
     * 实现者应自行判断"现在能不能切"（例如只有取景态可切、已拍成/确认页不切）。
     * <b>默认空实现。</b>
     */
    default void cameraToggleFacing() {
    }

    /**
     * 相机模式下，第一人称手持是否改用<b>常规地图式双手持握</b>（也就是平时不拍照时的那套姿态），
     * 而不是「抬到眼前的取景姿态」。
     * <p>
     * 前摄拍完照片后的<b>确认页</b>应当返回 {@code true} —— 那时手机该"放下"，玩家用平常的姿势看照片；
     * 后摄流程与既有相机保持 {@code false}（始终抬到眼前）。
     * <p>
     * <b>默认 {@code false}</b>：既有相机行为完全不变。
     */
    default boolean cameraUseDefaultHoldPose() {
        return false;
    }
}
