# HuhuTalk 狐狐手机 3D 模型 — 交接

> 结论先行：手机是**独立的 Java 物品模型**，与 `winefox.bbmodel` **完全解耦**。
> 挂点仍是 TLM 模型自带的 `RightHandLocator`（不需要动任何女仆模型）。
> 生成日期：本轮对话；参考图：两张狐狐手机壳渲染稿（正面 / 背面）。

---

## 一、为什么是「物品模型」而不是女仆模型里的一段骨骼

三种做法对比：

| 方案 | 能否随 mod 下发 | 能否日后热切换 | 需要改 Java |
|---|---|---|---|
| A. 加进 `winefox.bbmodel` | ❌ 只有装了那个模型包的客户端才有 | ❌ | 否 |
| **B. 独立 Java 物品模型（本方案）** | ✅ 随 jar，所有装了 HuhuTalk 的客户端都看得到 | ✅ 换模型/贴图即可 | **否** |
| C. Bedrock geo + 独立渲染层 | ✅ | ✅（可带骨骼动画） | 是（新渲染层） |

选 B 的依据（都已在源码里核对）：

1. `client/render/PhoneInHandLayer.java` 用
   `ItemInHandRenderer.renderItem(maid, phone, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, ...)`
   渲染的是一个 **ItemStack** → 手机外观 100% 由 `assets/huhutalk/models/item/*.json` 决定。
2. `geckolib3/util/RenderUtils.java` 的 `translateToPivotPoint` 写的是 `bone.getPivotX() / 16f`
   → **定位器空间是「方块」单位**；而 Java 物品模型的 element 坐标是 **1/16 方块** 单位。
   两者同一尺度 ⇒ 物品模型里的数值可以直接照着手部尺寸写。
3. 所以手机是**纯美术资源**：改它不用碰 Java，`PhoneInHandLayer` 一行都不用动。

> 这同时解决了旧文档 §9.5.1 记的那条：「手机物品目前是 `item/generated` 平面贴图，
> 渲染出来是一张纸片」——本轮就是把它换成带 `elements` 的 3D 物品模型。

---

## 二、交付文件

| 文件 | 说明 |
|---|---|
| `src/main/resources/assets/huhutalk/models/item/phone_3d.json` | **3D 物品模型**（16 个 element，含 `display`） |
| `src/main/resources/assets/huhutalk/textures/item/phone_3d.png` | **128×128 贴图**（正面屏幕 / 背面狐尾 / 耳朵 / 镜头） |
| `src/main/resources/assets/huhutalk/models/item/phone_smart.json` | 改为 `{"parent": "huhutalk:item/phone_3d"}` |
| `docs/blockbench/huhutalk_phone.bbmodel` | Blockbench 可编辑源文件（改美术从这里改） |

**回滚**：把 `phone_smart.json` 恢复成

```json
{
  "parent": "minecraft:item/generated",
  "textures": { "layer0": "huhutalk:item/phone_smart" }
}
```

即可。原 `phone_smart.png`（32×32 平面图标）**没有改动**。
`phone_watch.json`（电话手表）本轮**未动**，仍是原来的平面图标。

---

## 三、模型规格

- 整体 **2.6 宽 × 5.0 高 × 0.5 厚**（单位 = 1/16 方块，即 16 单位 = 1 格）。
  参照系：女仆掌心方块是 3×3×3 单位。
- 16 个 cube，分 5 组：

| 组 | cube | 作用 |
|---|---|---|
| `case` | `case_core` / `case_top` / `case_bottom` | 机身三段，上下两段各窄 0.2 ⇒ **一级倒角**，模仿参考图的圆角轮廓 |
| `ear_right` / `ear_left` | `ear_r1..r3` / `ear_l1..l3` | 狐耳，每只 3 段阶梯 + z 向逐级收窄（0.36→0.34→0.32），既做出外撇斜度又避免共面 z-fighting |
| `camera` | `camera_bump` + 3 个镜头 | 背面左上角摄像头凸起与三摄 |
| `buttons` | `btn_vol_up` / `btn_vol_dn` / `btn_power` | 侧键 |

**反 z-fighting 处理**（重要，别改回去）：相邻实心块互相**嵌入 0.01**，
而不是共面贴合 —— `case_bottom` 顶到 0.31、`case_top` 底到 4.69、
`camera_bump` 顶到 -0.24、按键 x 起点 1.29 / 终点 -1.29。

**贴图密度**：主体 8 px/单位（128 px ÷ 16 UV），耳朵与镜头用 **UV 岛放大**跑到
2~4 倍密度（耳朵 12.8×6.4 px、镜头 11.5×10.9 px），靠 UV 而不是加几何换细节。

---

## 四、进游戏后要调的三层旋钮（由粗到细）

| 层级 | 位置 | 现在写的是 | 作用 |
|---|---|---|---|
| 最粗 | `phone_3d.json` → `display.third_person_righthand` | `rotation [0,-90,55]`、`translation [0,4,0.5]`、`scale 1.6` | 手机相对手的出厂朝向与大小。**这个改动不用改 Java、不用重新编译 Java**（资源重载即可，F3+T） |
| 中等 | `PhoneInHandLayer.SCALE` | `1.0F` | 整体缩放，和上一行相乘 |
| 最细 | 动画 JSON 里 `RightHandLocator` 的 `rotation/position` 关键帧 | 无 | 逐帧微调，优先级最高（TLM 的 `use_mainhand:spyglass` 就是这么把望远镜对准眼睛的） |

`display` 用的是 Minecraft 标准的 `item/handheld` 取向（工具手持姿态），
对「竖着拿手机看屏幕」来说是对路的；**但只有实机才能最终确认**，见第六节。

---

## 五、关于「日后热切换手机模型」

- **换美术**（换配色/换壳图案）：改 `phone_3d.png` 或新增 `phone_3d_xxx.json` 再改
  `phone_smart.json` 的 `parent` —— 纯资源，包进 jar 后所有客户端都能看到。
- **运行时热切换**（不重启游戏、按玩家设置换 3D 模型）：物品模型是**启动时烘焙**的，
  纯资源做不到运行时换。可行路线有两条：
  1. 资源重载换模型（`F3+T`），或
  2. 给 mod 加一个「手机壳方块」→ 3D 模型的映射，渲染时改用自绘层 —— 即旧文档 §9.5.5 的
     **Bedrock geo + 独立渲染层**（`SimpleHatsRenderer.renderGeckoHat` 是现成先例）。
- 结论：**先把 B 方案（静态 3D 模型）跑通看观感**；确认要我把它升级成可热切换的 geo
  模型，再走第 2 条 —— 那块要动 Java，且挂点仍然是 `RightHandLocator`，geometry 可以直接复用。

---

## 六、还没验证的部分（需要你确认）

1. **手持朝向与大小**：只能进游戏看。若方向不对，优先只调
   `phone_3d.json` 的 `display.third_person_righthand.rotation`（不用重编译）。
2. **背面观感**：狐尾 + 三摄在**贴图上**已确认（见 `phone_3d.png`），
   但本轮只拿到了正面渲染截图，**背面渲染截图没截到**（Blockbench 在这个宿主上
   从 -Z 方向的截图不稳定）。进游戏顺手看一眼即可。
3. **与 TLM 自带手持层的冲突**：`PhoneInHandLayer` 在「主手非空」时会跳过渲染，
   这条逻辑没动。

---

## 七、怎么改（给下一次对话）

1. `docs/blockbench/huhutalk_phone.bbmodel` 用 Blockbench 打开（当前 Blockbench 里也已打开）。
2. 改完导出：
   - 模型 → `export_model(codec_id="java_block", path=".../models/item/phone_3d.json")`
   - 贴图 → `Blockbench.writeFile(".../textures/item/phone_3d.png", {content: canvas.toDataURL('image/png'), savetype:'image'})`
3. **导出后必须手工把 `"textures"` 里的值改成 `huhutalk:item/phone_3d`** ——
   Blockbench 的 java_block 编码器只会写 `命名空间:贴图名`（即 `huhutalk:phone`），
   不会带上 `item/` 路径。

踩过的坑（省得重走）：

- `risky_eval` 的代码里**不能出现 `//` 或 `/* */` 注释**，插件会直接拒绝。
- `face.texture` 要写**贴图 UUID 字符串**（`set_cube_uv` 官方工具写的就是 UUID），
  不是索引，也不是纯路径。
- `Project.display_settings` 里必须放 **`new DisplaySlot('thirdperson_righthand')` 实例**
  （赋 `rotation/translation/scale`），放普通对象的话 java_block 编码器**不会导出 `display`**。
- 本机上 `set_camera_angle` / `capture_screenshot` 经常回传**旧帧或空帧**；
  可靠做法是先做一个会弄脏场景的操作（如 `Canvas.updateAll()`、改贴图），再截图。
  **从 -Z 方向（看背面）几乎永远拿不到帧**，本轮试了 5 次都是空白，只能用贴图图集间接确认背面。

---

## 八、第二轮：右侧统一 + UV 重排（用户手改之后）

用户手动改造后，本轮做了四件事。

### 8.1 用户改动的规律（记下来，别再重新推）

| 位置 | 新样式 |
|---|---|
| `case_core.west`（-X 侧边） | `#d2780f` 深橙描边（上深下浅）+ `#eca747` 亮橙填充，顶部 5 px 一条 `#f0c22e` 黄带 |
| `btn_power`（-X 侧键，几何已改成 0.05 厚） | 外侧面同款「深橙边框 + 亮橙填充」；其余可见面填 `#d2780f` |
| `case_core.south` | 换成聊天界面：`#ffefec` 底 + `#54d1e8` / `#3d71ad` 气泡 |
| `case_core.north` | 后盖重画：黄框 + 橙底 + 米色狐尾 |
| `ear_l1/l2/l3` | 左耳三段重做：更宽更扁，顶端只到 y≈5.53（右耳原来是 5.98） |

### 8.2 本轮改动

1. **`case_core.east` ← 逐像素复制 `case_core.west`**（含黄带与上下渐变），左右边完全一致。
2. **`ear_r1/r2/r3` 镜像成 `ear_l1/l2/l3`**：几何按 x=0 镜像；
   贴图按面映射镜像 —— `south/north/up/down` 水平翻转，`east↔west` 互换**但不翻转**
   （因为这两面的 u 轴沿 Z，镜像 X 不影响）。
3. **UV 按新几何全部重排**：`camera_bump` / 三个按键的厚度、左耳三段、右耳三段都变了尺寸，
   旧 UV 岛与新几何不匹配（按键外侧面会被横向压缩约 3 倍）。
   做法是：先把所有旧矩形的像素抓进内存 → 清空受影响区域 → 按新尺寸重排 UV 岛 →
   用 `drawImage` + `imageSmoothingEnabled=false` **最近邻重采样**回填（不丢画）。
4. **三颗镜头贴回 bump**：`camera_bump` 被收薄到 0.10 后背板在 `z=-0.35`，
   镜头原来停在 `z=-0.72~-0.60`（悬空 0.37）且 x 没跟着 bump 挪。
   现在镜头是 `z=-0.43~-0.31`（嵌进 bump 0.04、凸出 0.08），
   x/y 也按 bump 的新范围重排成 2×2 网格（右下角一格留给闪光灯，画在 bump 上）。
   镜头厚度**刻意保持 0.12 不变**，这样它的 UV 岛不用动。

### 8.3 图集布局（当前）

```
x 0 ──────────────── 44        46 ──────── 83   86 ────── 117
y 0-4   case_core.up / down
y 4-39  case_core: west(0-3) south(4-24) east(25-28) north(29-49)
y 0-6   case_top:  west(52-55) south(56-73) east(74-77) north(78-94)
y 8-14  case_bottom: 同上
y 43-60 camera_bump(0-33) | lens_tl(48-79, y43-58) / lens_tr(y59-74) / lens_bl(y75-90)
y 43-87 按键 x84-95：btn_vol_up(y43-56) btn_vol_dn(y58-70) btn_power(y72-87)
y 92-104 右耳 ear_r1 x0-44 / ear_r2 x46-83 / ear_r3 x86-117
y 106-119 左耳 ear_l1 x0-44 / ear_l2 x46-83 / ear_l3 x86-117
```

**注意**：耳朵两行的偏移是 `[0|5.8|10.7]`（不是原来的 `[0|4.9|9.7]`）——
因为左耳被改宽后原偏移会互相压到。改耳朵尺寸时记得同步检查这里。

---

## 九、第三轮：模型「看不见」的根因 = 尺寸被手持缩放吃掉了

### 9.1 先排除的三件事（都有证据）

| 嫌疑 | 结论 |
|---|---|
| 模型 JSON 坏了 | **否**。`latest.log` 里 `Unable to load model` / `Missing model` **一条都没有**，说明能烘焙 |
| 贴图丢了 | **否**。`src` 与 `build/resources/main` 的 `phone_3d.png` 都是 128×128、内容完整 |
| 渲染层没挂上 | **否**。`GeckoEntityMaidRenderer:51-52` 遍历 `TouhouLittleMaid.EXTENSIONS` 调 `addAdditionGeckoMaidLayer` → `addGeoLayerRenderer` → `addLayer` |

### 9.2 真正的根因：手机比手掌还小，整个人塞进手掌方块里

定位器空间是**方块**单位（`RenderUtils.translateToPivotPoint` 里写的是 `pivot / 16f`），
物品模型烘焙出的顶点也是 `from / 16` 方块。换算到同一把尺子上：

| | 数值（单位 = 1/16 方块） |
|---|---|
| 女仆手掌方块 `RightHand` | **3 × 3 × 3** |
| 手机几何 | 2.6 × 5.0 × 0.5 |
| 手机**实际渲染**尺寸（× `display.scale` 0.5） | **1.3 × 2.5 × 0.25** |

1.3 × 2.5 × 0.25 完整落在 3 × 3 × 3 内部 ⇒ **被手掌网格完全包住，一个字都看不见。**
玩家手上同理，所以观感是「特别小巧，简直是个电话手表」。

**要记住的教训**：Minecraft 的手持物品是**故意放大**的 ——
剑 16 单位 × 0.85 = 13.6 单位，地图 ≈ 8.8 单位。
按「真实人手 / 手机比例」设计（5 单位）再乘 0.5，必然小到被手吞掉。

### 9.3 修法：只改一个数

`display` 的缩放从 `0.5` 提到 **`1.8`**：
手机渲染成 **4.68 × 9.0 × 0.9** 单位 ≈ 地图量级，明确突出于 3 单位的手掌。
GUI 图标同步放大到 1.8，物品栏里也看得清。

> **尺寸只有一个旋钮**：`phone_3d.json` → `display.third_person_righthand.scale`。
> 想大想小就改这一个数，**不要动几何** —— 几何一动，UV 岛和贴图全都要重做。

### 9.4 同时做的结构修正

对照 `build/maidsrc/assets/touhou_little_maid/models/item/camera_in_hand.json`
（同 NeoForge 1.21.1 环境下**确实能用**的三维手持物品模型），把 `phone_3d.json` 改成同一范式：

| 项 | 之前 | 现在 |
|---|---|---|
| `parent` | `minecraft:item/generated` | **删除**（独立模型） |
| `texture_size` / `particle` | 无 | `[128,128]` + particle |
| 几何空间 | **以原点为中心**（y 0~6） | **0~16 标准物品空间、居中于 (8,8,8)** |
| `display` | 只有 `third_person_righthand` | **8 个上下文全给** |

「只给一项 display」的坑：子模型一旦定义 `display`，父级整套 transform 就**不再继承**，
未列出的上下文退化成 `NO_TRANSFORM`。

### 9.5 诊断日志（下次实机直接看这个）

`PhoneInHandLayer` 现在每个早退分支和渲染成功都会打一行 `[HuhuTalk/Hand] ...`
（同一条消息最多 5 秒一次，不刷屏）：

```
grep "HuhuTalk/Hand" run/logs/latest.log
```

| 日志行 | 含义 / 下一步 |
|---|---|
| `RENDERED ... bones=N SCALE=1.0` | 层在正常工作。还看不见就调 `display.scale` |
| `skip: chat window inactive` | **正常** —— 手机只在聊天窗口开着时才挂手上 |
| `skip: main hand not empty` | 女仆主手有东西，按设计让位给 TLM 自带手持层 |
| `skip: maid has no phone tier` | 女仆没戴手机 |
| `skip: rightHandBones() empty` | 该模型没有 `RightHandLocator` |
| **一行都没有** | 层压根没挂上 → 回头查 `HuhuTalkTLMExtension` 与 EXTENSIONS 扫描 |
