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
| `isCameraMode()` | ✅ | **唯一的开关**。返回 true 时下面 §3 那一整批行为全部生效 |
| `cameraPrimaryAction()` | ✅ | 侧键 4 轻点。典型语义：拍照 |
| `cameraBackAction()` | ✅ | 侧键 5。典型语义：退出/取消 |
| `cameraSaveAction()` | ❌ 默认空实现 | 侧键 4 **长按**。不需要就别覆盖 |
| `handlesLongPress()` | ❌ 默认 `true` | 是否启用长按手势。**按下即拍的相机必须返回 false**，见 §4 |

它继承了 `IPhonePage`，所以 `render` / `mouseClicked` / `onBackPressed` 等照常实现。

---

## 3. `isCameraMode() == true` 时，HuhuTalk 会替你做什么

| 行为 | 由谁驱动 | 为什么 |
|---|---|---|
| **手机体背景透明** | `PhoneOverlay.render` | 让游戏画面从取景框里透出来 |
| **3D 机壳换成擦除版纹理** | `ProPhoneHandRenderer` | 在模型屏下也能直接看穿机身看到真实场景 |
| **手机抬到眼前当取景框** | `ProPhoneHandRenderer` | 平时是"平行地面"的地图式持握，取景要正对视线 |
| **不画独立光标** | `PhoneOverlay.renderCursor` | 那个界面全程侧键操作，画出来只会让人误以为能挪过去点 |
| **吞掉鼠标点击** | `PhoneOverlay.handlePhoneLocalMouse` | 鼠标要完整留给"转视角取景" |
| **侧键路由到本接口的三个动作** | `PhoneOverlay.onPhoneSideButton` | 键位映射是显式的，与你的排版彻底解耦 |

**这就是相机页和普通页的全部区别。** 上面每一条你都不用自己实现，也不受你的排版影响。

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

聊天页就是这种情况：**取景中**要透明，**已拍成**要显示全屏照片。

⚠ **当前限制（重要）**：`PhoneOverlay` 目前用**同一个判据** `isCameraMode()` 决定三件事 ——

1. 手机体透明 + 擦除机壳 + 取景姿态；
2. 不画光标 + 吞掉鼠标点击；
3. 侧键是否路由到你的 `cameraPrimaryAction()` / `cameraBackAction()`。

所以如果你在"已拍成"状态返回 `false` 想换回光标，**侧键也会一起不再走你的动作方法**。
在 HuhuTalk 把这三件事拆成独立判据并对外开放之前，多状态相机只有两条路：

- **全程返回 true**：两个状态都由侧键驱动、都不画光标（聊天内相机目前就是这样）；
- 自己处理鼠标输入 —— 但那个状态下的点击在 Overlay 层就被吞了，做不到。

HuhuTalk 自己的聊天内相机正在往"已拍成页显示全屏照片"改，届时会把判据拆开并同步更新本文档。

---

## 7. HuhuTalk 自带的实现：`PhoneCameraPage`

桌面上的「相机」App（`huhutalk:camera`，占槽位 1，深灰图标）就是
`com.huhutalk.api.phone.camera.PhoneCameraPage`，它是本接口的第一个使用方，也是最好的参考实现。

| 行为 | 说明 |
|---|---|
| **没有「已拍成页」** | 按下 = 拍照 + 存相册，然后**留在取景界面**继续拍（真机相机的"按几下拍几下"） |
| **拍照成功不弹气泡** | 没有"确认/重拍"这个反悔设计，快门本身就是反馈；只有**存盘失败**才弹一条提示 |
| **版面与聊天内相机逐项一致** | 顶栏两段文字、四角取景标记、准星、圆形快门、底部黑边都相同——两套相机是同一台手机上的两个入口 |
| **左下缩略图是纯装饰** | 不参与命中（没有光标），玩家想看照片去聊天页的相册 |
| **键位** | 侧键 4 = 拍照存盘；侧键 5 = 退出回桌面；长按无第二级语义 |
| **注册方式** | **走公开注册通路**（`PhoneAppRegisterEvent`），一行没往内置表里塞 —— 见 `com.huhutalk.client.CameraAppRegistration` |

> **关于底部黑边**：它不是为了好看，而是<b>遮住模型没掏空的那条带子</b>——手机 3D 模型在屏幕下部
> 预留了一条"本该镂空"的横带，但模型那边没真的掏空，于是那里会露出机壳。
> 黑边**上沿 == 四角取景标记的下沿 == `viewBottom()`**，三者同一个值，所以天然对齐、连成完整取景框。

它与**聊天内相机**（`ChatPage` 里的 📷）刻意区分：
聊天内相机是"拍了要发给女仆"，所以有确认/重拍两态；独立相机是"拍了就存"，所以只有取景态。

---

## 8. 相关文件

| 文件 | 说明 |
|---|---|
| `api/phone/camera/PhoneCameraHost.java` | **公开接口**（做相机页要实现它） |
| `api/phone/camera/PhoneCameraPage.java` | **公开页面**（HuhuTalk 桌面相机 App 的实现，可作参考） |
| `client/CameraAppRegistration.java` | 桌面相机 App 的注册（公开通路的示例） |
| `phone/PhoneOverlay.java` | `isCameraMode()` / `cameraSideButton` / `tickCameraHold` / `requestWorldCapture` / `showBubble` / `launchDesktopApp` |
| `client/PhotoLibrary.java` | 相册落盘与纹理 |
| `phone/pages/ChatPage.java` | 聊天内相机（多状态相机的参考：取景中 / 已拍成） |
| [phone-app-api.md](phone-app-api.md) | App 注册（含 `.slot()` 与启动动画） |
| [phone-model-screen-handoff.md](phone-model-screen-handoff.md) | 手机贴到 3D 模型屏上的完整交接（相机模式姿态、擦除纹理的来龙去脉） |
