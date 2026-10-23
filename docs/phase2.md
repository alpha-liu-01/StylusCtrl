# 阶段 2：映射、曲线、侧键

日期：2026-10-22。顺序在 [roadmap.md](roadmap.md)。阶段 1 的进程还是 `tools/stylusctld.c`，这一阶段给它加上配置。

## 这一阶段要证明的事

- 配置是一个 json。守护进程收到 `SIGUSR1` 后重读，不用重插板子。
- 活动区域可以拉伸铺满、等比裁切，也可以先取板子上的一块矩形再套这两种。
- 压感走一条贝塞尔，可以加死区。输出仍是 0–4095。
- `BTN_STYLUS` 可以原样留下，也可以注入一组键。`BTN_TOOL_RUBBER` 默认还是橡皮，也可以改成快捷键或 `BTN_STYLUS2`。
- Concepts 里的笔画范围和压感跟着 json 变。侧键的快捷键在认 Ctrl+Z 的应用里生效。

内屏 `sec_e-pen` 仍然不抓。屏幕上任意一块矩形这一阶段不做，那一项要等设置应用能报告旋转。

## 进程

没有配置文件时，行为和阶段 1 一样：原样转发，不写 IDC。

有配置时，虚拟设备仍是 `INPUT_PROP_POINTER`。拉伸和等比都由守护进程先把坐标变到「系统那一次均匀缩放之后会落在想要的屏幕位置」，再交给 inputflinger。这样悬停光标还在，来源仍是 `MOUSE | STYLUS`。

外接板子不跟屏幕转轴。`touch.orientationAware` 保持关闭，守护进程也不写 IDC。按虚拟设备名字曾经写过的那份，在打开虚拟设备时删掉。不写 `Vendor_0531_Product_0104.idc`。等比和拉伸都在板子自己的 X、Y 上算，宽高用当前 viewport。inputflinger 仍用它原来的均匀缩放，再把 viewport 的旋转消掉。横屏时板子长边对着屏幕长边。竖屏时屏幕是 12:20，等比会在板子长边上裁出同样比例的一块，这块的长边等于板子短边。左和右仍是板子的左和右。只改曲线、裁切或按键时不重建虚拟设备，笔的按下状态留着。配置从无到有、或从有到无时仍重建一次。

屏幕尺寸和旋转从 `dumpsys display` 的内屏 viewport 读。笔按下期间不读。笔离开大约五秒，或者重读配置时，再读一次。转屏之后如果笔还停在板子上，裁切边要等这一次更新。

`fit` 用和系统一样的比例盖住整块屏幕，多出来的板面按 `crop` 丢掉：`left` / `right` / `top` / `bottom` / `center`。`right` 和 `bottom` 丢掉高的那一端，和系统原来的裁法一致。`stretch` 让 X、Y 各自铺满。`mapping.rect` 是板子原始坐标上的 `[x0, y0, x1, y1]`，先圈住这块，再按 `mode` 映射。矩形外面的点贴在边上。

压感曲线是 0–1 上的三次贝塞尔。八个数是四个控制点。路线图里那六个数把中间那个点同时当作两个控制点。死区也是 0–1，低于死区的压力变成 0，剩下的再送进曲线。输出乘回轴的最大最小值。

`BTN_STYLUS` 的 `shortcut` 不转发侧键本身，另建一只名为 `StylusCtrl keys` 的键盘，按下时先修饰键再主键，松开时反过来。`BTN_TOOL_RUBBER` 的 `eraser` 仍是橡皮优先。`stylus2` 改送 `BTN_STYLUS2`，笔尖工具位保留。`shortcut` 同样不送橡皮。HP 笔的尾部橡皮和原装笔的第二侧键仍是同一个位。

`SIGINT`、`SIGTERM`、`SIGHUP` 还是退出并放开独占。重读只用 `SIGUSR1`。

## 配置

先找 `/data/adb/stylusctrl/config.json`，没有再读 `/data/local/tmp/stylusctrl.json`。`--config` 可以指定别的路径。

```json
{
  "match": { "vendor": "0531", "product": "0104" },
  "mapping": { "mode": "fit", "crop": "center" },
  "pressure": { "curve": [0.0, 0.0, 0.4, 0.7, 1.0, 1.0], "deadzone": 0.0 },
  "buttons": { "BTN_STYLUS": { "action": "shortcut", "keys": ["CTRL", "Z"] } },
  "tools": { "eraserWins": true }
}
```

`match` 写上之后，只抓这个厂商和产品号。内屏本来就没有 `INPUT_PROP_POINTER`，不会被选中。

`mapping.mode` 还可以是 `stretch` 或 `passthrough`。`buttons.BTN_TOOL_RUBBER.action` 可以是 `eraser`、`shortcut`、`stylus2`。侧键动作 `passthrough` 是原样留下。

## 构建和运行

```sh
tools/build.sh
adb push out/stylusctld /data/local/tmp/stylusctld
adb shell /data/local/tmp/stylusctld --check
adb shell /data/local/tmp/stylusctld
```

已经在跑的进程要先停掉，再开这一版。停的时候用 Ctrl-C。改 json 之后：

```sh
adb shell kill -USR1 $(pidof stylusctld)
```

`-v` 仍是工具、接触或侧键变化时打一行。

## 实现笔记

2026-10-22 这次运行之后补上。进程没加 `-v`，日志里没有每一帧的工具类型和压感数值。

配置在启动时读入，这次没有发 `SIGUSR1`。等比、居中、示例曲线、侧键 Ctrl+Z 都印在进程日志里。IDC 只挂在虚拟设备上，内容是 `touch.orientationAware = 1`。板子原来那台合成设备的 ConfigurationFile 仍是 none，方向感知仍是关的。电源键节点 `event12` 还开着，没有被独占。

横屏、旋转 3、逻辑分辨率 2000×1200 时，数位板左下角落到了屏幕左上角。方向感知打开之后，守护进程还会按同一旋转预先改坐标，两步叠在一起，板子靠近自己的那条边没有留在屏幕下边。阶段 1 没写这份 IDC。灭屏之后再抓的 `dumpsys` 里，虚拟笔的触摸映射已经是 DISABLED，变换矩阵是单位阵，那份矩阵不能拿来对这个角点。

随后确认：横屏和竖屏的有效区域都是板子里 12:20 的一块，长边等于板子短边（约 5700×9500）。这不符合等比裁切。横屏的屏幕是 20:12，板子大约 16:10，应该几乎用整块板面，短边两头各留约 190 个原始单位。12:20 只该出现在竖屏：屏幕自己就是 12:20，不拉伸就得裁掉板子的长边。原因是那份 IDC 把原始轴转了 90°，横屏被算成了竖屏的裁法。守护进程已改为不写这份 IDC，并在板子自己的坐标上裁切。设备上还要停掉旧进程再开，新的映射才生效。

快捷键设备是一只完整的字母键盘（`KeyboardType: 2`，`ALPHAKEY`），键位表是 `Generic.kl`。SpeedyNote 在前台时，输入法区域三次出现约 98px 的一条，随后又消失。和侧键次数对不上整，但多出来的是这只键盘，不是板子原来的节点。

20:24:00 屏幕不再 interactive，InputReader 因为 viewport 不活跃，关掉了虚拟笔（device 23），同时给它和原来的合成设备发了 DeviceReset。这份 dumpsys 停在灭屏之后，还没看到重新打开。

20:22:53 SpeedyNote 丢掉一批 inconsistent 事件。同一秒内核里是内屏 `sec_e-pen` 的笔尖（工具号 140）和触摸屏的手掌，不是 USB 笔的逐帧记录。虚拟设备和原来的合成设备当时都还在。

## 事件

2026-10-22，阶段 1 的进程停掉之后重新打开。新进程是 pid 15100。

进程日志：

- 配置：等比裁切，居中，侧键快捷键，橡皮位仍是橡皮，死区 0.00。只抓 `0531:0104`。压感曲线已加载。
- 快捷键设备 `StylusCtrl keys`。
- IDC：`/data/system/devices/idc/StylusCtrl_Wacom_Co__Ltd__Wacom_One_pen_tablet_small.idc`。
- 独占 `/dev/input/event13`，虚拟笔 `/dev/input/event16`，轴仍是 `x 0-15200`、`y 0-9500`、`pressure 0-4095`。
- 屏幕 `2000x1200`，旋转 3。

随后的 `dumpsys input`（20:25，屏幕已经灭）：

- `event16` 是 `StylusCtrl ...`，外接，`DeviceType: POINTER`，来源 `KEYBOARD | MOUSE | STYLUS`。触摸映射的 `orientationAware` 是 true。没有 `ABS_DISTANCE`。压感 0–4095，倾斜 ±90，分辨率 57。键盘映射那一项自己的 `orientationAware` 仍是 false。
- `event15` 是 `StylusCtrl keys`，`KEYBOARD | ALPHAKEY`，`0531:0001`，当时没有键按着。
- 原板子仍是合成设备：`event12` 电源键、`event13` 笔、`event14` 摇杆。`event13` 的轴停在 `0,0`，没有键按着。虚拟笔的轴停在 `2336,4979`，压感 0。Input Reader 里已经没有指针。
- `InputDispatcher` 对 `vendor 1329 / product 1`（`0531:0001`）记了 20 次按键事件，动作号 6，也就是按下和松开都算。这只键盘只送 Ctrl 和 Z，一次完整的 Ctrl+Z 是 4 个事件，20 次对应 5 下。来源号是 `{2}`。日志里没有逐键的 `KEYCODE_Z` 行。
- 同一张表里 `vendor 1329 / product 260`（`0531:0104`）的手写流仍有成对的按下和抬起，以及一万多次移动。这张表从开机起累计，分不出虚拟设备和原来的产品号。

内核里 `stylusctld`（pid 15100）的 Input Booster 从 20:21:52 到 20:22:43，大约一百条。这段里 Concepts 的 `NoteEditorActivity` 还在前台，20:22:15 被上滑手势带走。20:22:17 打开 SpeedyNote（`org.speedynote.app`）。20:22:23 到 20:22:43 的输入交互在 SpeedyNote 上。Ctrl+Z 是在这个应用里生效的。

验收还没勾完。改 json 而不重启进程、以及压感数值有没有跟着曲线变，这次日志都看不出来。

## 验收

- [ ] 改 json 后不重插板子，Concepts 里的笔画范围跟着变
- [ ] 改 json 后压感跟着变
- [x] 侧键的快捷键在认 Ctrl+Z 的应用里生效
