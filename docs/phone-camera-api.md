# HuhuTalk 相机能力 API

> **面向**：想让自己的附属模组在手机里做一个相机页的开发者。
> **状态**：已实现、已编译通过；**HuhuTalk 自带的桌面「相机」App 就是用它写的**（狗粮验证）。
> **代码位置**：`src/main/java/com/huhutalk/api/phone/camera/`

---

## 0. 一句话

实现公开接口 `PhoneCameraHost` 并让 `isCameraMode()` 返回 `true`，
你的页面就自动获得**透明取景框（游戏画面透出来）+ 手机抬到眼前当取景框 + 侧键操作 + 不画光标**。
抓图用 `PhoneOverlay.requestWorldCapture(...)`，存盘用 `PhotoLibrary.saveToGallery(...)`。
**不需要碰 HuhuTalk 任何源码，不需要 mixin。**

---

## 1. 最小相机页

```java
public final class MyCameraPage implements PhoneCameraHost {

    @Override public boolean isCameraMode() { return true; }   // 整页都是取景界面

    /** 侧键 4 轻点（默认鼠标侧键 4） */
    @Override public void cameraPrimaryAction() {
        PhoneOverlay.requestWorldCapture(png -> {
            if (png == null) return;
            PhotoLibrary.registerCapturedBytes("__myshot__", png);   // 想预览才需要
            PhoneOverlay.showBubble(PhotoLibrary.saveToGallery(png) != null ? "已保存" : "保存失败");
        });
    }

    /** 侧键 5（默认鼠标侧键 5） */
    @Override public void cameraBackAction() { PhoneOverlay.popPage(); }

    /** 按下即存，没有长按语义 —— 必须显式返回 false，理由见 §4 */
    @Override public boolean handlesLongPress() { return false; }

    @Override
    public void render(GuiGraphics g, int baseX, int baseY, float partialTick) {
        // 只画"浮在游戏画面上"的东西：四角边框、准星、快门。
        // **不要**铺任何背景色 —— 铺了就看不见游戏画面了。
    }

    @Override public boolean mouseClicked(double x, double y, int button, int action) { return true; }
}
```

然后按普通 App 注册即可（详见 [phone-app-api.md](phone-app-api.md)）：

```java
event.register(PhoneApp.builder(id, "我的相机")
        .center("📷", 2.0f, 0xFFFFFFFF)
        .slot(3)                       // 桌面只有 9 格，挑个空位
        .page(MyCameraPage::new)
        .build());
```

---

## 2. `PhoneCameraHost` 契约

| 方法 | 必须 | 说明 |
|---|---|---|
| `isCameraMode()` | ✅ | **主开关**。返回 true 时下面三个细分判据默认也全是 true，整套相机行为生效 |
| `cameraScreenTransparent()` | ❌ 默认 = `isCameraMode()` | 手机体是否透明 + 3D 机壳是否换擦除纹理。**"显示全屏照片"的状态必须单独关掉它**，否则照片半透 |
| `cameraSideKeysActive()` | ❌ 默认 = `isCameraMode()` | 侧键 4/5 是否路由到相机动作、长按手势是否生效 |
| `cameraInputExclusive()` | ❌ 默认 = `isCameraMode()` | 是否独占输入（不画光标 + 吞鼠标点击） |
| `cameraPrimaryAction()` | ✅ | 侧键 4 轻点。典型语义：拍照 |
| `cameraBackAction()` | ✅ | 侧键 5。典型语义：退出/取消 |
| `cameraSaveAction()` | ❌ 默认空实现 | 侧键 4 **长按**。不需要就别覆盖 |
| `handlesLongPress()` | ❌ 默认 `true` | 是否启用长按手势。**按下即拍的相机必须返回 false**，见 §4 |
| `isSelfieMode()` | ❌ 默认 `false` | 当前是否前摄/自拍取景。true 时开前摄离屏 pass、换右手单手持机姿态、普通右键当快门。**只在取景态返回 true**，见 §8 |
| `supportsCameraFacingSwitch()` | ❌ 默认 `false` | 是否支持前/后摄切换（V 键与页内切换按钮是否生效） |
| `cameraToggleFacing()` | ❌ 默认空实现 | 切换前摄/后摄；实现者自行判断"现在能不能切" |
| `cameraUseDefaultHoldPose()` | ❌ 默认 `false` | 相机模式下是否改用常规地图式双手持握（前摄确认页返回 true 把手机放下） |

它继承了 `IPhonePage`，所以 `render` / `mouseClicked` / `onBackPressed` 等照常实现。

> **单状态相机（整页都是取景框）只实现 `isCameraMode()` + 三个动作方法就够了** ——
> 桌面「相机」App 就是这么写的。只有页内有多级状态时才需要碰那三个细分判据。

---

## 3. 每个判据各驱动什么

| 行为 | 判据 | 为什么 |
|---|---|---|
| **手机抬到眼前当取景框** | `isCameraMode()` | 平时是"平行地面"的地图式持握，取景要正对视线 |
| **手机体背景透明** | `cameraScreenTransparent()` | 让游戏画面从取景框里透出来 |
| **3D 机壳换成擦除版纹理** | `cameraScreenTransparent()` | 在模型屏下也能直接看穿机身看到真实场景 |
| **不画独立光标** | `cameraInputExclusive()` | 那个界面全程侧键操作，画出来只会让人误以为能挪过去点 |
| **吞掉鼠标点击** | `cameraInputExclusive()` | 鼠标要完整留给"转视角取景" |
| **侧键路由到本接口的动作方法** | `cameraSideKeysActive()` | 键位映射是显式的，与你的排版彻底解耦 |

**这就是相机页和普通页的全部区别。** 上面每一条你都不用自己实现，也不受你的排版影响。

> ⚠ 早先这六件事共用 `isCameraMode()` 一个判据，于是"只想让某个状态不透明"办不到 ——
> 一关就把光标、侧键一起关了。现在拆开之后，外观 / 侧键 / 光标可以各自独立开关。

---

## 4. ⚠ `handlesLongPress()` 为什么必须显式返回 false（如果你不需要长按）

HuhuTalk 的相机侧键状态机是这样的：

```
按下侧键4 → 开始计时
   ├─ 按住 ≥ 350ms → 触发 cameraSaveAction()，并标记「长按已触发」
   └─ 松开 → 若「长按已触发」则什么都不做；否则走 cameraPrimaryAction()
```

所以一个**没有长按语义**的相机（按下即存/即拍）如果沿用默认值 `true`，会出现：
玩家按住 400ms 再松开 → `cameraSaveAction()` 是空实现、什么都没发生，而轻点也被吞了 ——
**表现就是"按了没反应"**，很难排查。

返回 `false` 后，HuhuTalk 根本不启动长按计时，松开总是走 `cameraPrimaryAction()`。

---

## 5. 抓图与存盘

| 你想做的事 | 用这个 |
|---|---|
| 抓一张"干净的世界" | `PhoneOverlay.requestWorldCapture(Consumer<byte[]>)` |
| 存进相册 | `PhotoLibrary.saveToGallery(byte[])` → 返回文件名，失败返回 `null` |
| 注册一张**临时**照片当纹理（做缩略图预览） | `PhotoLibrary.registerCapturedBytes(key, bytes)` |
| 取纹理 / 尺寸 | `PhotoLibrary.getOrLoadTexture(key)` / `getTextureSize(key)` |
| 列相册 / 删照片 | `PhotoLibrary.listGallery()` / `deleteGallery(fileName)` |
| 屏内提示 | `PhoneOverlay.showBubble("已保存")` |

**抓图时机是刻意的**：回调在 `RenderLevelStageEvent.AFTER_LEVEL` 执行 ——
那一刻本帧世界已画完，而**手 / 手机 / HUD 都还没画**，所以抓到的就是"玩家眼睛里那片干净的世界"，
不需要临时隐藏任何东西，也不会把 HUD / 手臂 / 手机本体拍进去。

**几个必须知道的点**：

1. **回调在渲染线程、可能在按下拍照的同一帧内执行**，别在里面做重活。
2. **`saveToGallery` 是同步落盘**（PNG 编码 + 写文件）。按一下拍一张没问题；
   如果你要做"按住连拍"，请自己加节流或异步化，否则会掉帧。
3. **临时照片的 key 请用自己的**（例如 `__myshot__`）。HuhuTalk 聊天页用的是 `__camshot__`，
   共用会把玩家在聊天里"还没确认的那一张"覆盖掉。
4. 相册目录由 `PhotoLibrary` 管理，和聊天页共用同一个相册 —— 你存进去的照片，
   玩家在聊天页的 🖼️ 里能看到。

---

## 6. 别做的事

| ❌ | 为什么 |
|---|---|
| **继承 `PhonePage` 做相机页** | 它的 `render` 是 `final` 且**必定用 `pageBackground()` 铺满整个内容区**，会把你的透明取景框整个盖死。直接 `implements PhoneCameraHost` 自绘 |
| **在 `render` 里铺不透明背景** | 同上：取景框的意义就是"什么都不铺，让游戏画面透出来" |
| **在 `isCameraMode()` 返回 true 时指望鼠标能点** | 那个状态下 `PhoneLocalMouse` 被整个关掉、光标也不画。要可变的状态请让 `isCameraMode()` 只在该透明取景的状态返回 true（见下） |
| **在 `cameraPrimaryAction()` 里写同步的阻塞 IO** | 它在渲染线程上被调用 |

### 多状态页怎么写

聊天页的相机就是这样：**取景中**要透明，**已拍成**要显示全屏照片。做法是
`isCameraMode()` 全程返回 true（两个状态都是相机），再按需关掉细分判据：

```java
@Override public boolean isCameraMode()            { return active; }
// 已拍成要显示全屏照片 → 那个状态绝不能透明，否则照片半透、背后的世界穿过来
@Override public boolean cameraScreenTransparent() { return active && shot == null; }
// 侧键语义两个状态都一样（4=发送 / 5=重拍），所以这两条不用覆盖
```

**如果还想让某个状态能被光标点**（例如"已拍成"页给两颗可点按钮），
必须把这两条**一起**关掉：

```java
@Override public boolean cameraSideKeysActive() { return active && shot == null; }
@Override public boolean cameraInputExclusive() { return active && shot == null; }
```

只关 `cameraInputExclusive()` 没有用：侧键 4 在手机里就是"左键单击"，
只要它还路由给相机，键事件在路由那一步就被消费了，玩家的光标永远点不到东西。

> ⚠ **不要**用"让某个状态的 `isCameraMode()` 返回 false"来实现可点状态 ——
> 那会把侧键一起关掉，玩家的侧键 4/5 就不再是"发送/重拍"了。
>
> HuhuTalk 自己的聊天相机**没有**开启光标点击：侧键 4 = 发送 / 侧键 5 = 重拍是玩家的肌肉记忆，
> 换成"挪光标去点按钮"反而更难用。它的「已拍成」页只覆盖了 `cameraScreenTransparent()`，
> 但仍然把两颗按钮画出来、并让命中区与排版对齐 —— 将来若要放开光标，改判据即可。

---

## 7. HuhuTalk 自带的实现：`PhoneCameraPage`

桌面上的「相机」App（`huhutalk:camera`，占槽位 1，深灰图标）就是
`com.huhutalk.api.phone.camera.PhoneCameraPage`，它是本接口的第一个使用方，也是最好的参考实现。

| 行为 | 说明 |
|---|---|
| **没有「已拍成页」** | 后摄：按下 = 拍照 + 存相册，然后**留在取景界面**继续拍（真机相机的"按几下拍几下"） |
| **拍照成功不弹气泡** | 后摄没有"确认/重拍"这个反悔设计，快门本身就是反馈；只有**存盘失败**才弹一条提示 |
| **前摄（自拍）有确认页** | 切到前摄后按下 = 进确认页（整屏照片 + 「重拍 / 保存」两颗按钮）：保存入相册、失败保留可重试、重拍丢当前照片回自拍取景 |
| **前后摄切换** | 取景态按 V（默认，可重绑定）切换；页内右侧胶囊显示"当前前/后摄 + 切换键名" |
| **版面与聊天内相机逐项一致** | 顶栏两段文字、四角取景标记、准星、圆形快门、底部黑边都相同——两套相机是同一台手机上的两个入口 |
| **左下缩略图是纯装饰** | 不参与命中（没有光标），玩家想看照片去聊天页的相册 |
| **键位** | 侧键 4 = 后摄拍照存盘 / 前摄拍照进确认页 / 确认页保存；侧键 5 = 退出（确认页为"重拍"）；V = 切换前后摄 |
| **注册方式** | **走公开注册通路**（`PhoneAppRegisterEvent`），一行没往内置表里塞 —— 见 `com.huhutalk.client.CameraAppRegistration` |

> **关于底部黑边**：它不是为了好看，而是<b>遮住模型没掏空的那条带子</b>——手机 3D 模型在屏幕下部
> 预留了一条"本该镂空"的横带，但模型那边没真的掏空，于是那里会露出机壳。
> 黑边**上沿 == 四角取景标记的下沿 == `viewBottom()`**，三者同一个值，所以天然对齐、连成完整取景框。

它与**聊天内相机**（`ChatPage` 里的 📷）刻意区分：
聊天内相机是"拍了要发给女仆"，所以有确认/重拍两态 —— 已拍成时
**整块内容区铺满刚拍的照片**（不透明）并给「重拍 / 发送」两颗按钮；
独立相机是"拍了就存"，所以只有取景态。

---

## 8. 前摄（自拍）

两个内置相机入口都支持前摄。契约上只需要少数几个**全部有默认实现**的方法（见 §2 表）：

```java
@Override public boolean isSelfieMode()             { return active && shot == null && front; }
@Override public boolean supportsCameraFacingSwitch(){ return active; }
@Override public void    cameraToggleFacing()       { if (active && shot == null) front = !front; }
@Override public boolean cameraUseDefaultHoldPose() { return shot != null && front; }
```

要点：

1. **`isSelfieMode()` 只在取景态返回 true**（已拍成/确认页不算自拍取景）。
   HuhuTalk 只在"当前页前摄取景 **且** 本地玩家第一人称主手真的举着手机"时才跑前摄离屏 pass，
   所以第三人称、收起手机、手机不在手里都会自动停。
2. **前摄拍照不要再用 `requestWorldCapture`（那是主画面）**，改用
   `SelfieCameraTarget.requestCapture(Consumer<byte[]>)`——拿到的是一张竖屏、
   未镜像、正常方向的 PNG。回调可能延迟一帧左右，务必用"请求序号 + 页面仍活跃"判废旧请求。
3. **预览镜像是 HuhuTalk 负责的**：页面只需把 `SelfieCameraTarget.renderPreviewFit(g, x, y, w, h)`
   画进自己的取景框即可。它内部等比 letterbox（固定 2:3），400×600 绝不会被拉伸变形；
   照片本身与确认页都是**正常方向**（不镜像）。
   取景框应先铺不透明黑再画图：`isReady()==false` 时不要画图，改用 `isFailed()` 区分并给
   「准备中 / 不可用」短提示，**绝不能让主视角或世界透出来冒充自拍**。
4. **前摄取景的第一人称是右手单手持机、屏朝玩家**（由 `ProPhoneHandRenderer` 统一处理，
   页面无需自绘手臂）；确认页返回 `cameraUseDefaultHoldPose() == true` 时手机会"放下"，
   用常规地图式持握看照片。
5. **透明判据只留给后摄**：`cameraScreenTransparent()` 在前摄取景与前摄确认页都应返回 false
   （机身实心 + 正常机壳纹理）；后摄取景才返回 true。
6. **键位**：侧键 4/5 语义与后摄一致（4=主操作、5=返回），**V（可重绑定）= 切换前后摄**。
   相机没有光标，所以切换按钮点不到——页内那颗胶囊只负责显示状态 + 提示键名；
   附属模组做自己的相机时，用 `ClientModEvents.PHONE_CAMERA_FACING.getTranslatedKeyMessage()`
   取真实键名，别硬编码 "V"。
7. **临时照片 key 用自己的**：聊天相机 `__camshot__`、桌面相机前摄 `__appcamselfieshot__`。
   共用会把另一台相机"还没处置的那张"覆盖掉。

> 完整实现细节、验收步骤与已知限制见 [selfie-camera-handoff.md](selfie-camera-handoff.md)。

---

## 9. 相关文件

| 文件 | 说明 |
|---|---|
| `api/phone/camera/PhoneCameraHost.java` | **公开接口**（做相机页要实现它） |
| `api/phone/camera/PhoneCameraPage.java` | **公开页面**（HuhuTalk 桌面相机 App 的实现，可作参考） |
| `client/CameraAppRegistration.java` | 桌面相机 App 的注册（公开通路的示例） |
| `phone/PhoneOverlay.java` | `isCameraMode()` / `isSelfieMode()` / `cameraUseDefaultHoldPose()` / `toggleCameraFacing()` / `cameraSideButton` / `tickCameraHold` / `requestWorldCapture` / `showBubble` |
| `client/PhotoLibrary.java` | 相册落盘与纹理 |
| `client/render/SelfieCameraTarget.java` | **前摄离屏取景核心**（预览 / 抓图 / 节流 / 资源释放） |
| `client/render/ProPhoneHandRenderer.java` | 第一人称持机姿态（后摄取景 / 前摄单手持机 / 确认页地图式） |
| `phone/pages/ChatPage.java` | 聊天内相机（多状态相机的参考：取景中 / 已拍成；含前摄） |
| `event/ClientModEvents.java` | `PHONE_CAMERA_FACING`（V 键）、`RenderFrame.Pre` 里的前摄 pass 开关 |
| [selfie-camera-handoff.md](selfie-camera-handoff.md) | **前摄交接**（键位、镜像、真实渲染、局部模型姿态、性能、验收步骤） |
| [phone-app-api.md](phone-app-api.md) | App 注册（含 `.slot()` 与启动动画） |
| [phone-model-screen-handoff.md](phone-model-screen-handoff.md) | 手机贴到 3D 模型屏上的完整交接（相机模式姿态、擦除纹理的来龙去脉） |
