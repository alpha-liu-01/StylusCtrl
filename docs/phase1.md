# 阶段 1：独占原设备，原样送回

日期：2026-10-22。顺序在 [roadmap.md](roadmap.md)。这一阶段不改坐标，也不改压感曲线。

## 这一阶段要证明的事

- 守护进程按能力找到笔节点，不写死 event 号。
- `EVIOCGRAB` 只抓笔节点。电源键和摇杆节点留着。
- uinput 原样送回轴和键。橡皮和笔尖同时按下时只送橡皮。
- Concepts 里能悬停、能画、压感还在、翻过来是橡皮、笔尖端侧键仍是 stylus button。
- grab 期间绘画应用不再跟原节点走。进程退出后，系统原来的映射恢复，虚拟设备不留。
- 不按数位板电源键。USB 插上就能用，那颗键是蓝牙模式用的，第一版不做蓝牙。进程仍然不抓它，避免把 `KEY_SLEEP` 吃掉。

## 进程

`tools/stylusctld.c` 编出来是 `out/stylusctld`。它是静态 musl 二进制。主机上的 clang 没有 aarch64 链接器，所以用 [musl.cc](https://musl.cc/aarch64-linux-musl-cross.tgz) 的 `aarch64-linux-musl-gcc`。

扫描 `/dev/input/event*`。选中的节点要同时有 `INPUT_PROP_POINTER`、`ABS_X`、`ABS_Y`、`ABS_PRESSURE`，并且没有 `INPUT_PROP_DIRECT`。名字以 `StylusCtrl` 开头的跳过，避免抓到自己建的虚拟设备。内屏 `sec_e-pen` 的 properties 是 0，不会被选中。电源键节点没有压感，摇杆节点没有 pointer 属性，都不会被抓。

虚拟设备的名字是 `StylusCtrl` 加上原节点的名字。总线标成 USB，厂商和产品号从原节点复制，inputflinger 就把它当成外接指针设备。带 `INPUT_PROP_POINTER`，映射模式是 POINTER，`orientationAware` 保持关闭。不写 uniq，避免和板子原来共用 Uniq 的三只节点合成一台。轴的最小、最大、fuzz、flat、resolution 照抄。不造 `ABS_DISTANCE`。键只有 `BTN_TOOL_PEN`、`BTN_TOOL_RUBBER`、`BTN_TOUCH`、`BTN_STYLUS`。

坐标和压感原样转发。一帧结束时如果橡皮是按下的，这一帧送出的笔尖工具位是松开。橡皮松开后，如果笔尖工具位在物理上还按着，再把笔尖工具位送回去。侧键在橡皮那一帧里仍然照转。

拔掉板子就放开并拆掉虚拟设备，然后继续等下一次插入。`SIGINT`、`SIGTERM`、`SIGHUP` 同样放开。虚拟设备的 fd 关掉之后，内核会把它拆掉。

`--check` 不抓任何节点。它按 CTC4110WL 的轴建一只虚拟设备，核对 sysfs 上的名字、USB 厂商号、pointer 属性、轴和键，然后拆掉，并确认节点消失。

## 构建和运行

工具链不在仓库里。`tools/build.sh` 依次用 `STYLUSCTRL_CC`、PATH 上的 `aarch64-linux-musl-gcc`、`~/.cache/stylusctrl/aarch64-linux-musl-cross/`。

```sh
tools/build.sh
adb push out/stylusctld /data/local/tmp/stylusctld
adb shell /data/local/tmp/stylusctld --check
adb shell /data/local/tmp/stylusctld
```

这台 userdebug 上先 `adb root`。之后的 shell 是 `u:r:su:s0`，并且在 `uhid` 组里，能打开 `/dev/uinput`。

停进程用 Ctrl-C。`-v` 在工具、接触或侧键变化时打一行。

## 实现笔记

2026-10-22 这次运行之后补上。

独占只盖住笔那只 evdev。inputflinger 仍把电源键、笔、摇杆合成一台设备，因为三只节点的 `Uniq` 相同。笔节点停在「悬停中」时被独占，读端收不到离开，这台合成设备的最后一帧就一直停着。虚拟设备是另一台，光标要等它的悬停事件才开始跟着走。笔离开板面之后，旧的那一帧还在。下次要在独占之后让那一帧失效，或者在节点刚出现时就抢到，不给系统留下悬停。

插上之后的空档主要在板子自己。内核里它先以 PC 模式 `0531:0100` 出现，大约 0.4 秒后自己断开，再过大约 2.6 秒以 Android 模式 `0531:0104` 回来。守护进程的扫描间隔是 1 秒，这次没接住那一下 `0100`。`0104` 的节点出来之后，系统先收到悬停，守护进程才独占。

这次进程没加 `-v`，日志里没有 USB 笔每一帧的工具类型。内屏走 `sec_e-pen`，不经过这个进程。

灭屏不会拆掉独占，也不会让 USB 数位板重新枚举。这段睡眠里内核没有进入 suspend：`xhci-hcd-exynos` 一直在 pending wakeup sources 里，USB 主机没睡。所以这次还不能说明深度睡眠或 USB 自动挂起之后独占还在。灭屏时 inputflinger 清掉了原合成设备上那帧停住的悬停；evdev 上 `BTN_DIGI` 仍按着，因为离开事件被独占吃掉了。Concepts 的 `NoteEditorActivity` 在笔画开始时会丢掉一批 “inconsistent” 事件，和原节点、虚拟设备各留一份指针有关。

## 事件

2026-10-22，守护进程在 `adb shell` 里一直开着。两支笔各在内屏和数位板上试过，中途拔过一次板子。

进程日志：

- 独占 `/dev/input/event13`，名字是 `Wacom Co.,Ltd. Wacom One pen tablet small`，虚拟设备 `/dev/input/event15`，轴 `x 0-15200`、`y 0-9500`、`pressure 0-4095`。
- 拔掉时读端返回 `No such device`，进程放开 `event13`，然后等待。
- 再次独占，节点号仍是 `event13`，新的虚拟设备仍是 `event15`。

随后的 `dumpsys input`：

- `event15` 是单独一台 `StylusCtrl ...`，`DeviceType: POINTER`，外接，`orientationAware` 关闭，没有 `ABS_DISTANCE`。压感 0–4095，倾斜 ±90，分辨率 57。屏幕映射比例 `0.1316`，和调查时的系统映射一致。来源是 `KEYBOARD | MOUSE | STYLUS`。
- 原板子仍是一台合成设备，事件节点 `event12`（电源键）、`event13`（笔）、`event14`（摇杆）。电源键和摇杆都还开着。笔这一路的最后一帧停在 raw `4447,1485`，工具是 `STYLUS`，`isHovering=1`。这一帧的时间比 `0104` 节点出现晚大约 0.7 秒。虚拟设备当时已经没有悬停指针。
- `InputDispatcher` 对 `vendor 1329 / product 260`（也就是 `0531:0104`）记了成对的按下和抬起，以及一万次以上的移动。

内核时间，拔出大约在 3802 秒。USB 主机控制器随之拆掉，约 3815 秒才回来。`0531:0100` 在 3815.75 秒枚举，3816.18 秒断开。`0531:0104` 的三只输入节点在 3818.85 秒出现。

内屏 `sec_e-pen` 仍是 `DIRECT` 触摸屏。内核 `sec_input` 在这段里打了 8 次笔尖接触，工具号都是 `140`（`BTN_TOOL_PEN`）。没有 `141`。USB 笔的橡皮和侧键没有逐帧记录。

同一次进程后来又睡过一轮。19:26:24 按下的是平板自己的电源键：`s2mpu13` PMIC 报了扫描码 116（`KEY_POWER`）按下和松开，`WindowManager` 记了 `powerPress`。19:26:25 进入 Asleep，19:38:18 仍由这颗键唤醒，中间 11 分 53 秒。数位板的 `KEY_SLEEP` / `KEY_WAKEUP` 没有出现。

守护进程这期间没有新日志，还是原来的 pid 9698，没有第二次「断开」。`event13` 和 `event15` 的路径、描述符都没变。内核日志里没有 USB disconnect，也没有 `Freezing user space`。整段时间 `PM: Pending Wakeup Sources` 里都有 `xhci-hcd-exynos.3.auto`。

唤醒后：

- 19:38:20 Concepts（`com.steadfastinnovation.android.projectpapyrus`）回到前台，`NoteEditorActivity` 还是原来的输入通道。19:38:24 它开始收笔画，同时丢掉一批 inconsistent 事件。睡前 19:26:01 也有同样的丢掉。
- 内屏在 19:38:24 之后有笔尖接触，工具号 `140`。19:38:27 到 19:38:28 有一笔完整的橡皮，按下和抬起都是 `141`。19:38:34 又按下一次 `141`。
- 19:38:37 `stylusctld` 的 evdev 上还有 Input Booster 计数。虚拟设备最后一帧在内核时间 5049.8 秒，大约是 19:38:39，坐标已经变成 `5552,7806`，压感 0，没有悬停指针。原合成设备的 Input Reader 状态被清成没有指针；`event13` 的键状态仍是 `BTN_DIGI`，坐标还停在 `4447,1485`。

验收还没勾完。数位板电源键不测：USB 模式下不用开机，那颗键留给蓝牙。进程还没退出，所以还没看到虚拟设备被拆干净。

## 验收

- [ ] Concepts 里能悬停、能画、压感还在、翻过来是橡皮、笔尖端侧键仍是 stylus button
- [ ] grab 期间绘画应用不再跟原节点走
- [ ] 放开之后，系统原来的映射恢复
- [x] 数位板电源键：不测。USB 插上即可用，电源键归蓝牙模式。节点保持不抓
- [ ] 进程退出后没有残留的虚拟设备
