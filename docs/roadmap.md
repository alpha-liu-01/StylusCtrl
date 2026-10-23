# 实现路线图

日期：2026-10-22。调查结论在 [feasibility.md](feasibility.md)。被动探针已经结束，可以按这个顺序写代码。

第一版的运行形态是 Magisk 模块里的常驻守护进程，加一个普通设置应用。改 Lineage 设备树和改 inputflinger 都排在可用的守护进程之后。

## 探针已经回答的事

这些不用再采：

- 平板是 SM-P620 / `gta4xlswifi`，LineageOS 23.2，Android 16。Magisk 30.7、Zygisk Next、Vector 都在。内核里有 `hid-generic`、`hid-wacom`、`uinput`。
- CTC4110WL 以 `0531:0104` 由 `hid-generic` 接上，活动区域 152.00 × 95.00 mm，压感 0–4095，倾斜 ±90°。笔在带 `INPUT_PROP_POINTER` 且有 `ABS_PRESSURE` 的那只节点上。另外两只节点是电源键和一只无用的摇杆轴，笔操作时它们没有事件。
- 系统映射是写死的等比裁切。当时横屏会切掉大约 4% 的短边。竖屏按同一公式大约只剩 37% 的板宽。压感是 `raw / 4095`。
- 测试笔是 HP ZBook x2 G4 的 EMR 笔：一个侧键、尾部橡皮、4096 级压感。内屏和数位板都能用。
- 数位板上的四下都录过。笔尖、按住侧键的笔尖、橡皮，都能在 evdev 里分开。按住侧键再使用橡皮时，侧键位不会和橡皮同时出现。这是笔的报告方式，不是还缺一次采样。
- 数位板没有 `ABS_DISTANCE`。内屏有。数位板上橡皮接触时 `BTN_TOOL_PEN` 和 `BTN_TOOL_RUBBER` 会同时按下，转发时橡皮优先。

`tools/stylusctld.c` 是阶段 1 的进程，记录在 [phase1.md](phase1.md)。`tools/pengrab.c` 不再单独跑。

## 第一版做到什么

在这台 P620 上，插着 CTC4110WL、用这支 HP 笔：

- 所有应用看到同一套活动区域和压感曲线。
- 可以在「拉伸铺满」「等比、并选择裁哪一边」「板子上的一块矩形」之间切换。
- 侧键可以保持为 stylus button，也可以改成一个快捷键。
- 尾部橡皮仍然是橡皮。
- 拔掉模块或停掉守护进程后，系统原来的即插即用还在。
- 官方 nightly 可以继续刷，模块跟着 Magisk 留下。

内屏 `sec_e-pen` 有自己的压感和快捷键，不做区域映射。它不是第一版的验收对象。

## 阶段

每个阶段都要在这台机器上能演示，再进入下一个。

### 1. 独占原设备，原样送回

实现和验收记录在 [phase1.md](phase1.md)。

写一个本机守护进程，语言用 C 或 Rust。按能力找节点：`INPUT_PROP_POINTER` 加上 `ABS_PRESSURE`，不要写死 `event13`。

`EVIOCGRAB` 只抓笔那只节点。`KEY_SLEEP` / `KEY_WAKEUP` 那只留着。摇杆那只先不动。

虚拟设备用 uinput 照抄笔的轴和键：`ABS_X/Y`、`ABS_PRESSURE` 0–4095、`ABS_TILT_X/Y`、`BTN_TOOL_PEN`、`BTN_TOOL_RUBBER`、`BTN_TOUCH`、`BTN_STYLUS`。不要造 `ABS_DISTANCE`。橡皮和笔尖同时按下时只送橡皮。

这一阶段的坐标和压感原样转发，曲线和裁切都先不做。

验收：

- Concepts（`com.steadfastinnovation.android.projectpapyrus`）里能悬停、能画、压感还在、翻过来是橡皮、笔尖端侧键仍是 stylus button。
- grab 期间绘画应用不再跟原节点走。放开 grab 后，现在的系统映射恢复。
- 不测数位板电源键。USB 插上就能画，那颗键是蓝牙模式用的。第一版不做蓝牙，但仍然不抓 `KEY_SLEEP` / `KEY_WAKEUP` 那只节点。
- 守护进程退出后没有残留的虚拟设备。

`tools/pengrab.c` 可以并进这个进程。主机上的 clang 没有 aarch64 链接器，第一件工程事是把二进制在设备上跑起来，而不是再探针。

### 2. 映射、曲线、侧键

实现和验收记录在 [phase2.md](phase2.md)。

配置先是一个 json 文件，守护进程收到信号后重读。设置界面还没有。

活动区域三种：

| 模式 | 行为 |
| --- | --- |
| 拉伸铺满 | X、Y 各自铺满屏幕 |
| 等比裁切 | 统一比例，多出来的板面可以靠左、靠右、靠上、靠下或居中 |
| 板内矩形 | 只用数位板上的一块，再按上面两种之一映射 |

外接板子不跟屏幕转轴，`touch.orientationAware` 保持关闭。守护进程按当前屏幕宽高，在板子自己的坐标上做拉伸或等比。屏幕上的目标矩形用当前方向下的比例保存，不跟某一次的像素宽高绑死。

压感是一条贝塞尔。`deadzone` 是低位，低于它保持曲线起点。`ceiling` 是高位，高于它顶到曲线终点。输出仍是 0–4095，让系统继续做那一次线性归一化。

侧键按这只板子实际送出的位来绑，不按笔上有几颗物理键：

- `BTN_STYLUS` 是第一侧键。可以原样留下，也可以注入一组键，例如 Ctrl+Z。
- `BTN_TOOL_RUBBER` 在 `0531:0104` 上同时是「橡皮」和原装笔的第二侧键。档案里把它做成逻辑第二键，默认动作是橡皮工具，也可以改成快捷键或 `BTN_STYLUS2`。HP 笔的尾部橡皮走同一个位，这一条绑定对两种笔一起生效。
- 不指望 Android 模式里出现 `BTN_STYLUS2`。标准 HID 报告没有这个用法，厂商报告里的那一位没有被 hid-generic 收成键。

松开快捷键时把修饰键和主键都放开。橡皮线圈工作时这支 HP 笔不会同时送出第一侧键，配置里不做「橡皮加侧键」。

验收：改 json 后，不重插板子，Concepts 里的笔画范围和压感跟着变。侧键的快捷键在认 Ctrl+Z 的应用里生效。

### 3. 装上就能用

实现和验收记录在 [phase3.md](phase3.md)。

Magisk 模块：

- `service.sh` 拉起守护进程，开机和守护进程崩溃后都拉起。
- `sepolicy.rule` 允许它打开 `input_device` 和 `uhid_device`。
- 模块不放 IDC。虚拟设备保持指针，`touch.orientationAware` 保持关闭。运行时会变的参数留在 json 里。

设置应用是普通 APK，用 libsu 写 `/data/adb/stylusctrl/config.json` 并通知守护进程。界面用 Material 3：数位板和屏幕两个矩形对着拖、压感曲线直接拖节点、侧键可以一组多个键、测试画布单独一页。内屏单独留一页，这一版几乎是空的。测试画布要读 `AXIS_PRESSURE`、工具类型和侧键。

热插拔听 uevent 或 `/dev/input`。节点号变化时按 VID/PID 和能力重新抓。第一份设备档案是 `0531:0104`。

验收：重启后插上板子就能画。刷一次 Lineage 小版本后模块还在，行为不变。卸掉模块后板子回到系统原来的映射。

### 4. 在这台 P620 上收口

用 HP 笔把第一版的四条路径走完：笔尖、侧键快捷键、橡皮、改过的压感曲线和一块自定义板面。横屏和竖屏各一次，确认等比裁切的边和 json 里选的边一致。

内屏配置档如果开着，只检查曲线和侧键，不拿它的高度轴去要求数位板。

这一阶段没有新功能。不过关的项回到阶段 2 或 3。

### 5. 以后才做

有人插上别的板子再加档案。`056a` 走现成的 `hid-wacom` 节点，Huion / XP-Pen 优先看 `uclogic` 已经给出的 evdev，不先重写 USB。映射公式本身不绑 15200×9500，边界写在 [phase3.md](phase3.md)。

设置项进系统设置、以及把同一份 json 吃进 inputflinger，都要等阶段 4 的行为稳定。那是 Lineage 通用补丁，不是 `gta4xlswifi` 设备树里的改动。Vector 只适合以后做某个应用自己的菜单动作，不进第一版。

## 配置文件

守护进程和以后的 inputflinger 用同一份形状：

```json
{
  "match": { "vendor": "0531", "product": "0104" },
  "mapping": { "mode": "fit", "crop": "center" },
  "pressure": { "curve": [0.0, 0.0, 0.4, 0.7, 1.0, 1.0], "deadzone": 0.0 },
  "buttons": { "BTN_STYLUS": { "action": "shortcut", "keys": ["CTRL", "Z"] } },
  "tools": { "eraserWins": true }
}
```

`fit` 是等比裁切，`crop` 的 `left` / `right` / `top` / `bottom` 表示有效区域靠哪一边，另一轴仍然居中。`stretch` 是拉伸铺满。`rect` 是板子坐标上的一块矩形。`screen` 是四个 0–1 的比例，表示当前屏幕方向里的目标矩形；不写就是整块屏幕。`rotation` 是数位板自己的方向，取 0、90、180、270，和裁切、矩形一起生效；不写就是 0。曲线是三次贝塞尔的四个点，八个数，输入和输出都是 0–1，再乘这只笔自己的压感跨度（`max - min`），不是写死的 4095。旧的六个数仍然认，中间那个控制点会用两次。`ceiling` 不写等于 1。哪些假设只对这只板子成立，写在 [phase3.md](phase3.md) 的调查一节。

## 第一版不做

- 不 root 的安装方式。
- 蓝牙连接，以及数位板电源键在蓝牙模式下的开关。USB 插上就能用。这次只测了 USB Android 模式。进程不抓电源键节点。
- 给 CTC4110WL 伪造高度轴。
- 板载灯、ExpressKey 固件宏、HID feature report。
- 把「侧键加橡皮」做成单独动作。
- 为了这个功能自己维护一套 Lineage 整包。
- 把这台平板变成电脑的数位板。
