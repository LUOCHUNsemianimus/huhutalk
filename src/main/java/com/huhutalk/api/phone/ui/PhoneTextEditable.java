package com.huhutalk.api.phone.ui;

/**
 * 「能被 {@link com.huhutalk.phone.PhoneTextCaptureScreen} 驱动编辑」的手机文本控件契约。
 *
 * <h2>为什么需要这个接口</h2>
 * 手机页面（{@link PhonePage}）<b>收不到任何键盘事件</b>，所有内联文字输入都要靠
 * 一个<b>完全透明</b>的 {@code Screen} 抢键盘，再把按键转发回控件。那个捕获屏原本
 * 只认 {@link PhoneTextInput} 这一个具体类型 —— 于是每加一种文本控件，就要么复制一份
 * 捕获屏，要么改它的字段类型。
 * <p>
 * 本接口把捕获屏真正需要的那几件事抽出来，捕获屏只依赖它：
 * <ul>
 *   <li>{@link #value()} —— 打开时的初始文本；</li>
 *   <li>{@link #maxLengthValue()} —— {@code EditBox} 的长度上限；</li>
 *   <li>{@link #beginEdit()} / {@link #updateEditingText(String, int)} / {@link #endEdit(boolean)}
 *       —— 编辑期的三个阶段（开始 / 每次按键 / 提交或回滚）；</li>
 *   <li>{@link #focused()} —— 捕获屏被旁路关闭时判断该不该补一次提交。</li>
 * </ul>
 *
 * <h2>为什么 {@code updateEditingText} 要带光标位置</h2>
 * 单行的 {@link PhoneTextInput} 用不上它（它只显示尾巴），但多行的 {@link PhoneTextArea}
 * 必须靠它决定"现在该显示哪几行"——不然玩家按 Home 回到开头改字时，
 * 看到的仍是末尾那几行，等于盲改。
 *
 * @see PhoneTextInput
 * @see PhoneTextArea
 * @see com.huhutalk.phone.PhoneTextCaptureScreen
 */
public interface PhoneTextEditable {

    /** 当前文本（可能为空串，永不为 {@code null}）。 */
    String value();

    /** {@code EditBox} 的长度上限。 */
    int maxLengthValue();

    /** 捕获屏打开时调用：进入编辑态，并记下原值供「取消」回滚。 */
    void beginEdit();

    /**
     * 编辑中同步文本，每次按键调一次。
     *
     * @param text      当前全文
     * @param cursorPos {@code EditBox} 的光标位置（字符索引，0 = 开头）
     */
    void updateEditingText(String text, int cursorPos);

    /**
     * 捕获屏关闭：{@code commit=true} 则裁剪到上限后提交并回调，否则回滚到编辑前的值。
     * <p>实现必须<b>幂等</b>：捕获屏的 {@code finish()} 之后还会走一次 {@code onClose()}。
     */
    void endEdit(boolean commit);

    /** 是否处于编辑态。 */
    boolean focused();
}
