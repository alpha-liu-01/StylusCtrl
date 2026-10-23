# 阶段 3：装上就能用

日期：2026-10-22。顺序在 [roadmap.md](roadmap.md)。映射、曲线和侧键仍是阶段 2 的 json。这一阶段把进程放进 Magisk 模块，并在节点出现时重新抓。

## 这一阶段要证明的事

- 重启后插上板子就能画，不用再开一次 `adb shell`。
- 守护进程崩溃后，模块里的循环会再拉起。
- 热插拔不写死 event 号。节点出现或消失时按 VID/PID 和能力重新抓。第一份档案仍是 `0531:0104`。
- 刷一次 Lineage 小版本后模块还在，行为不变。
- 卸掉模块并重启后，板子回到系统原来的映射。
- 模块不放 IDC。虚拟设备保持指针，`touch.orientationAware` 保持关闭。

设置应用是普通 APK，包名 `app.stylusctrl`，源码在 `app/`。它用 libsu 写 `/data/adb/stylusctrl/config.json`，再向守护进程发 `SIGUSR1`。界面见下面的「设置界面」。

## 模块

源文件在 `magisk/stylusctrl/`。打包后的 zip 是 `out/stylusctrl.zip`。

- `module.prop`：id `stylusctrl`。
- `service.sh`：late_start 里转圈执行模块目录中的 `stylusctld`。进程退出就过两秒再开。标准输出和错误输出追加到 `/data/adb/stylusctrl/stylusctld.log`。Magisk 安装时把普通文件收成 `0644`，脚本每次拉起前把二进制改成 `0755`。模块目录改不了权限时，复制到 `/data/adb/stylusctrl/stylusctld` 再执行。
- 没有 `config.json` 时，把 `config.json.default` 复制过去。已有的文件不覆盖。默认内容和路线图里的示例一样：等比、居中、示例曲线、侧键 Ctrl+Z。
- `sepolicy.rule`：允许 `magisk` 域打开 `input_device` 和 `uhid_device`。`adb root` 仍是 `su`，不靠这几行。
- 不放 IDC，也不往 `/system/usr/idc` 或 `/data/system/devices/idc` 写方向感知。

守护进程先读 `/data/adb/stylusctrl/config.json`，没有再读 `/data/local/tmp/stylusctrl.json`。模块装上之后走前一条。

卸掉模块后，下次开机 `service.sh` 不再运行。进程退出时关掉 uinput，虚拟设备由内核拆掉。没有 IDC 要清。

## 热插拔

`tools/stylusctld.c` 用 inotify 盯 `/dev/input` 的创建、删除和移动。有事件就再扫一遍。外接笔仍按 `INPUT_PROP_POINTER` 加 `ABS_PRESSURE` 挑选，跳过名字以 `StylusCtrl` 开头的节点。配置里的 `match` 继续过滤外接笔的厂商和产品号。

名字正好是 `sec_e-pen` 的内屏笔另算。`internal.enabled` 为真、而且配置已经读到时才抓它，不要求 `INPUT_PROP_POINTER`，也不走 `match`。虚拟设备带 `INPUT_PROP_DIRECT`，坐标原样送出，压感曲线和侧键仍按配置。关掉开关再重读配置时放开这只笔。

inotify 打不开时，仍靠原来的一秒钟轮询。笔还按着的时候不读屏幕尺寸。节点消失走原来的 `POLLHUP`，放开独占并拆掉对应的虚拟设备。

日志里会有一行 `监听 /dev/input`。

## 构建和安装

```sh
tools/pack-module.sh
```

先跑 `tools/build.sh`，再把二进制和模块文件打进 `out/stylusctrl.zip`。

装之前先停掉 `adb shell` 里那个 `stylusctld`。两个进程会抢同一只笔节点。用 Magisk 刷这个 zip，然后重启。不要和正在跑的 adb 进程叠在一起。

```sh
tools/build-apk.sh
adb install -r out/stylusctrl.apk
```

`tools/build-apk.sh` 把 `sdk.dir` 写进 `local.properties`，编 debug 包，再复制到 `out/stylusctrl.apk`。第一次打开会向 Magisk 要 root。没有模块时，保存仍会写 `/data/adb/stylusctrl/config.json`，守护进程没在跑，刷上模块并重启后才会生效。

主机编 APK 用的 SDK 在 `~/Android/Sdk`，`ANDROID_HOME` 指到这里。平台是 `android-36`，构建工具是 `36.1.0`。命令行工具在 `cmdline-tools/latest`。Java 是系统里的 OpenJDK 17。libsu 从 JitPack 取 `com.github.topjohnwu.libsu:core:5.2.2`。

## 设置界面

2026-10-22 晚，第一版界面能改映射并马上生效。试用里有这些问题，和界面一起改：

1. 插上板子之后，系统把它当成接了一只键盘，软键盘不再弹出，数字填不进去。原因是快捷键虚拟设备注册了整套字母，`KeyboardType` 成了字母键盘。守护进程改为只注册这份配置里实际用到的键。设置界面里映射和曲线改成拖动，不再靠填数字。
2. 快捷键原来界面上只有一个修饰键加一个主键。守护进程本来就能一次按下最多八个键，界面改成按这个上限加键。除了字母和数字，还可以加空格、回车、Tab、Esc、方向键、退格和删除。
3. 测试画布写一笔就断，是因为画布躺在整页滚动里面，系统把后续移动拿去滚动了。这不用改系统设置。测试单独一页，不跟滚动抢；一笔按压力分成许多小段，不会整笔被取消。
4. 界面上的等比居中，第一次实际是板子左侧一块，右侧被裁掉。改成别的再改回来才对。灭屏时 `dumpsys display` 的 viewport 是 `isActive=false`，尺寸会回到竖屏的自然宽高。守护进程若把这个尺寸当成当前屏幕，横屏下的等比就会按竖屏去算，笔的有效区域贴在板子左侧。现在不活动的 viewport 不覆盖已经读到的尺寸，笔放下大约一秒后会重读活动中的屏幕。

界面按 Material 3 重做，左边是导航：

- 映射：左边数位板、右边屏幕，四个角用线连上。预设有拉伸铺满、等比居中、靠左、靠右、靠上、靠下。拖动一角、一条边或整块之后，变成两个矩形拉伸对应，写进 `mapping.rect` 和 `mapping.screen`。
- 压感：一条可以拖的三次曲线。最左节点的横位置是低位死区 `deadzone`，最右节点是高位死区 `ceiling`。高于高位就顶到曲线终点。
- 按键：第一侧键和橡皮位。快捷键是一组芯片，最多八个。
- 测试：整页画布。
- 内屏：这一页留空。只留一个已经有的开关，打开后抓 `sec_e-pen`，坐标仍然原样。区域映射不加到内屏上。
- 设备：root、进程和轴范围。

`crop` 的 `left` / `right` / `top` / `bottom` 改成「有效区域靠这一边」。以前这四个词表示裁掉那一边。默认的 `center` 没有改。

映射页会跟着当前窗口的宽高重画。应用声明了自己处理旋转，所以转屏时要在 `onConfigurationChanged` 里读窗口尺寸，不能等下一次读取配置。数位板方向是 `mapping.rotation`，0、90、180、270，先转板子坐标，再做裁切和矩形。压感页有复位，回到示例曲线，低位 0、高位 1。

快捷键设备不再报一整套字母之后，系统才不会把它当成硬件键盘。已经在跑的旧进程要换成 `out/stylusctrl.zip` 里的二进制才有这条，也才认 `mapping.screen` 和 `pressure.ceiling`。

## 实现笔记

2026-10-22 刷上 `out/stylusctrl.zip` 并重启之后。模块在、已启用，`service.sh` 从开机就由 `busybox sh` 跑着，域是 `u:r:magisk:s0`。守护进程本身没有起来。

`stylusctld` 被装成 `-rw-r--r--`，上下文 `u:object_r:system_file:s0`。Magisk 的 `set_perm_recursive` 对模块里的普通文件用 `0644`，只有 `system/bin` 一类目录才是 `0755`。`service.sh` 由 shell 解释执行，所以监督循环还在；直接执行二进制则是 Permission denied。开机后大约七分钟里这条写了 211 次，每两秒一次。`dmesg` 里没有针对这个二进制的 avc。`/data/system/devices/idc` 是空的。

这段时间 `getevent` 里没有 `StylusCtrl` 设备。板子仍是原来的三只节点，侧键走系统自己的笔键，所以不再是 Ctrl+Z。json 没有改：`/data/adb/stylusctrl/config.json` 和模块里的默认文件都还是侧键 `CTRL`+`Z`。`/data/local/tmp/stylusctrl.json` 也还是这份，守护进程没起来，用不到它。

21:16 把已安装的二进制改成 `0755` 之后，同一个 `service.sh`（pid 1023）拉起了 `stylusctld`（pid 4706），没有再重启机器。日志接着是：等比居中、侧键快捷键、橡皮位橡皮、只抓 `0531:0104`、监听 `/dev/input`、独占 `event13`、虚拟笔 `event16`、屏幕 `2000x1200` 旋转 3。这次 dumpsys 时屏幕是 Asleep，虚拟笔的触摸映射是 DISABLED，变换是单位阵，和阶段 2 灭屏时一样，不能拿来对笔画范围。

仓库里的 `service.sh` 已补上执行位，`out/stylusctrl.zip` 已按这份重新打包。当时刷进去的那份没有这行。这台机器上的二进制已经是 `0755`，下次开机现在这份脚本也能执行。再刷已经补过的 zip 也会在拉起前改权限。

补上执行位之后又重启过一次。开机后不用再开 `adb shell`，板子就能画，侧键仍是 Ctrl+Z。随后卸掉模块。这台 P620 没有 A/B 分区，刷 Lineage 小版本之后要重新 root，这次不做。

## 事件

2026-10-22 21:16，开机约 7 分钟。adb 是 `10.0.0.116:36211`。负载大约 14。

模块目录 `/data/adb/modules/stylusctrl` 没有 `disable` 或 `remove`。`pidof stylusctld` 一开始是空的。监督进程是 pid 1023。

`/data/adb/stylusctrl/stylusctld.log` 开头全是：

`/data/adb/modules/stylusctrl/service.sh: line 11: /data/adb/modules/stylusctrl/stylusctld: Permission denied`

配置文件 296 字节，侧键动作是 `shortcut`，键是 `CTRL` 和 `Z`。

补上执行位之后的进程日志：

- 配置：等比裁切，居中，侧键快捷键，橡皮位橡皮，死区 0.00。只抓 `0531:0104`。压感曲线已加载。
- 快捷键设备 `StylusCtrl keys`。监听 `/dev/input`。
- 独占 `/dev/input/event13`，虚拟笔 `/dev/input/event16`，轴 `x 0-15200`、`y 0-9500`、`pressure 0-4095`。
- 屏幕 `2000x1200`，旋转 3。

随后的 `dumpsys input`（屏幕 Asleep，`Display not interactive: {0}`）：

- `event16` 是 `StylusCtrl ...`，外接，`DeviceType: POINTER`，`orientationAware` 关闭，没有 IDC，没有 `ABS_DISTANCE`。来源 `KEYBOARD | MOUSE | STYLUS`。内核轴停在 `8008,4513`，压感 0。Input Reader 里没有指针。
- `event15` 是 `StylusCtrl keys`，`KEYBOARD | ALPHAKEY`，`0531:0001`，`KeyboardType: 2`，没有键按着。分发器里没有 `product 1` 的按键计数，这次还没有一笔 Ctrl+Z。
- 原板子仍是合成设备：`event12` 电源键、`event13` 笔、`event14` 摇杆。`event13` 的轴也是 `8008,4513`，和虚拟笔同一点。ConfigurationFile 是 none。`sec_e-pen` 还在，没有被抓。

验收里，重启可用和卸掉模块已经勾上。Lineage 小版本这次不做。

当前没有模块。21:29 在 `10.0.0.116:40955` 上又看过一次：`stylusctld` 不在进程里，`getevent` 只有板子原来的三只节点，没有 `StylusCtrl`。

同一天 21:41，第一版设置应用编出来并装上了。`tools/build.sh` 通过，`out/stylusctrl.apk` 约 5.6 MB，`adb install -r` 成功。`am start` 拉起 `app.stylusctrl/.MainActivity`，进程里出现 libsu 的 `RootServerMain`，没有崩溃。当时锁屏挡在前面，屏幕随后又灭了，Magisk 授权和界面上的保存没有在屏幕上确认。

随后这版界面被换掉，见「设置界面」。新的 `out/stylusctrl.zip` 是 v0.4.0，含屏幕矩形、高位死区，以及只注册实际用到的快捷键。

## 调查：内屏、断开和其它板子

2026-10-22 22:53，adb 是 `10.0.0.116:36321`。外接板没插着：`getevent` 里没有 Wacom 节点。`StylusCtrl keys` 还在，说明守护进程没退出。`sec_e-pen` 仍是系统自己的 `event10`，内屏开关当时是关的。

### 内屏只要快捷键和压感

内屏节点这次读到的能力：

- `ABS_X` 0–13536，`ABS_Y` 0–22560，和 1200×2000 的屏等比。
- `ABS_PRESSURE` 0–4095。
- `ABS_DISTANCE` 0–255。外接板没有这根轴。
- `ABS_TILT_X/Y` −63–63。
- 键：`BTN_TOOL_PEN`、`BTN_TOOL_RUBBER`、`BTN_TOUCH`、`BTN_STYLUS`。没有 `BTN_STYLUS2`。另外还有 `KEY_HOMEPAGE`（`0x00ac`）和 `0x00fd`。`0x00fd` 在这台主机的输入头文件里没有名字。
- 开关：`SW_PEN_INSERTED`、`SW_MACHINE_COVER`。没有 `INPUT_PROP_DIRECT`，也没有 `INPUT_PROP_POINTER`。IDC 仍是 `touch.deviceType = touchScreen`、`touch.orientationAware = 1`。

守护进程在 `internal.enabled` 为真时已经会抓这个名字，虚拟设备带 `INPUT_PROP_DIRECT`，`map_point` 跳过，压感曲线和侧键走和外接笔同一份 json。所以「内屏不做映射、只做曲线和快捷键」这条计算路径是有的，还没在亮屏上验过。

缺的是分开的配置，以及抓取时不能把笔槽和皮套一起吃掉。

- 现在外接和内屏共用一条曲线、同一组侧键。内屏页要有自己的曲线和快捷键，json 里单独放，例如 `internal.pressure` 和 `internal.buttons`。映射字段继续只用于外接笔。
- `EVIOCGRAB` 独占整只节点。现在转发的只有笔的坐标、压感、倾斜和 `BTN_TOUCH` / `BTN_TOOL_RUBBER` / `BTN_STYLUS`。`ABS_DISTANCE`、`KEY_HOMEPAGE`、`0x00fd`、`SW_PEN_INSERTED`、`SW_MACHINE_COVER` 都进了守护进程就没再送出。笔槽检测、皮套和这个 `KEY_HOMEPAGE` 会停。虚拟设备要按原值把这些再写出去，坐标和压感仍走曲线，不要对它们做映射。
- 虚拟设备不要去写 `sec_e-pen.idc`。`INPUT_PROP_DIRECT` 已经会让 inputflinger 按触摸屏处理，`orientationAware` 对触摸屏默认是开的，这和贴在面板上的笔一致。

侧键能做成快捷键，是因为内屏也报 `BTN_STYLUS`。曲线能做，是因为压感先被改成 0–4095 里的另一个值，系统再按这只设备自己的 `max` 归一化。这两件都不需要屏幕矩形。

### 板子拔掉就藏起映射，换一只就刷新

守护进程已经在听 `/dev/input`。同一只 `0531:0104` 拔掉再插上，会放开旧节点再抓新节点。设置应用只在打开时读一次 `getevent`，没有第二次。

拔掉之后界面仍用上次的轴，或者用写死的 15200×9500，映射页还在。应当认「外接笔」为：有 `ABS_X/Y/PRESSURE`，带 `INPUT_PROP_POINTER`，不是 `StylusCtrl`，也不是 `sec_e-pen`。一个都没有时，导航里的映射页藏起来。压感和按键留着，那是给下一次插上用的，也给内屏用。

换一只板子时，要刷新的是名字、厂商、产品号和轴范围，让矩形的坐标跟着新的 `max`。若旧矩形铺满上一只板子的全范围，就改成新板子的全范围。若是一块局部，按两只板子的跨度比例缩放，不能把 15200 原样套到另一只的轴上。`match` 仍是 `0531:0104` 时，别的产品号守护进程不会抓。刷新信息之外，要让用户能把 `match` 改成眼前这只；不要在后台悄悄改掉。

界面认设备时还写死了名字里要有 `Wacom`。Huion 即使已经被守护进程抓到，轴范围也不会进映射图。这里改成和守护进程同一条规则。

### 映射写死了多少

运行时的几何没有把 15200×9500 或 2000×1200 写进公式。`map_point` 用的是这只笔 `EVIOCGABS` 的最小、最大，和 `dumpsys` 里当前内屏的逻辑宽高。压感输出乘的是这只笔的 `pmax - pmin`。虚拟设备的轴范围、分辨率从原节点拷来。倾斜旋转是对有符号的倾斜做交换和变号，范围仍是硬件报的那一组。

真正的边界是编码方式，不是这只 Wacom 的尺寸。外接虚拟设备是 `INPUT_PROP_POINTER`，并且没有把 `orientationAware` 打开的 IDC。守护进程把目标像素除以 `max(屏幕宽 / 原始宽, 屏幕高 / 原始高)`，再写回原始轴。这是在抵消 inputflinger 对指针设备的等比、原点在 0、多出来的一边被裁掉。换一台平板或换一只指针式数位板，只要系统仍是这套 POINTER 算法，映射就成立。

下面这些情况公式不适用，要换路径，不是改一个常数：

- 设备本身是 `INPUT_PROP_DIRECT`，或 IDC 把它标成 `touchScreen`。坐标已经贴在屏上，再做这套预补偿会错。内屏走的就是这条，所以明确不做映射。
- 有人把虚拟设备的 `touch.orientationAware` 设成 1。轴会被屏幕方向再转一次，横屏又会收成 12:20 那一块。
- 多块显示器、外接显示器。现在只读第一块活动的 `type=INTERNAL`。
- 非矩形的板面，或固件里已经转过、但 `ABS` 最小最大仍按未转的轴来报。现在只做旋转 0/90/180/270 之后的轴对齐矩形。
- 侧键不是 `BTN_STYLUS` 和 `BTN_TOOL_RUBBER`。快递键、另一只 evdev 上的 `BTN_0`、真正的 `BTN_STYLUS2`，这份 json 都没有位置。`056a` 的 `hid-wacom` 和 Huion / XP-Pen 要先看它们的 evdev 把笔键放在哪，再加档案，不改映射公式。
- 两只外接笔同时插着。进程最多抓四只，但 json 只有一份，会共用同一块矩形和同一条曲线。

15200、9500、`0531:0104` 出现在界面的默认值、模块自带的 json，以及主机测试里。那是这只板子的例子，不是映射函数的输入。界面在读到轴之前用这些数画预览。没有 `mapping.span` 的旧配置，换算时把上一只板子当成 15200×9500。

## 内屏曲线和拔插

压感页和按键页上方可以选择「外接数位板」或「内屏笔」。两份曲线、两份侧键分开存在 json 里：外接仍是原来的 `pressure` / `buttons`，内屏是 `internal.pressure` / `internal.buttons` / `internal.tools`。旧配置没有内屏这一段时，界面先抄外接的那一份，保存之后才写成独立的。映射还是只给外接笔。内屏页只留抓取开关。

守护进程看到 `internal.pressure` 或 `internal.buttons` 才用内屏这份；没有的话，内屏笔仍走外接的曲线和快捷键。抓 `sec_e-pen` 时，虚拟设备补上 `ABS_DISTANCE`、`KEY_HOMEPAGE`、`0x00fd`、`SW_PEN_INSERTED`、`SW_MACHINE_COVER`，原值送出，不参与映射。快捷键键盘把两份配置里实际用到的键合在一起。

设置应用打开期间大约每 1.5 秒读一次输入设备。这台机器的 `getevent -p` 把轴印成十六进制，界面认 `0000`、`0001`、`0018` 为 X、Y、压感。外接笔是：这三根轴都在，带 `INPUT_PROP_POINTER`，没有 `INPUT_PROP_DIRECT`，名字不是 `StylusCtrl` 也不是 `sec_e-pen`。一只都没有时，导航里的映射藏起来，正在看映射就改去压感。插上之后显示，并按 `getevent` 的轴最大更新画板。`mapping.span` 记下矩形是按哪一档轴编辑的。全幅矩形换成新板子的全幅，局部矩形按两档轴的比例缩放。`match` 和眼前这只的厂商、产品号不同时，提示「改成这只」，要再按保存才写入。

这一版是 `0.4.2`。内屏单独曲线、笔槽开关和距离转发都在守护进程里，要刷 `out/stylusctrl.zip` 并重启才生效。只更新 apk 的话，界面能分开编辑，旧进程会忽略内屏那一段，内屏笔仍用外接的曲线和快捷键。

## 功能键，以及橡皮位连发

2026-10-22 23:30 之后，adb 是 `10.0.0.116:39585`。当时外接板没插着。`StylusCtrl sec_e-pen` 在，带 `INPUT_PROP_DIRECT`，内屏抓取是开的。`StylusCtrl keys` 上只有三颗键：`0x001d`（左 Ctrl）、`0x0021`（F）、`0x002c`（空格）。笔没有在动，`getevent` 等了半分钟没有新事件。`adb shell su` 仍然读不了守护进程日志。

### 快捷键里没有 F1–F12

缺的是名单，不是系统不认。这台机器的 `/system/usr/keylayout/Generic.kl` 把 Linux 扫描码映射成 Android 的功能键：

| 扫描码 | 键 |
| --- | --- |
| 59–68 | F1–F10 |
| 87 | F11 |
| 88 | F12 |

应用在 `onKeyDown` 里读到的是 `KEYCODE_F1` 到 `KEYCODE_F12`。守护进程的 `key_code()` 和设置界面的可选列表都停在字母、数字、空格、回车、Tab、Esc、方向键、退格、删除。名字 `F1` 现在会被拒绝。加上去就是这 12 个扫描码，仍然只注册这份配置里真正用到的键。只有功能键、没有字母时，虚拟键盘不会变成字母键盘，软键盘不会因此被藏起来。

### 系统功能能不能走同一条路

能走的，是 `Generic.kl` 已经映射、而且实体键盘能触发的那些。守护进程还是往 `StylusCtrl keys` 写扫描码，不新开一条 Android API。这台平板的 `Generic.kl` 里，和笔上快捷键比较接近的有：

- 返回 `158`（`KEY_BACK`），主页 `172`（`KEY_HOMEPAGE`），最近任务 `120`（`RECENT_APPS`）。`580` 也映射成 `APP_SWITCH`。
- 菜单 `127` / `139`。
- 复制 `133`、剪切 `137`、粘贴 `135`。
- 音量加减和静音 `115` / `114` / `113`，亮度 `224` / `225`。
- 播放暂停 `164`，上一首 `165`，下一首 `163`。
- 电源 `116`，睡眠 `142`，唤醒 `143`。电源和睡眠会不会被这次注入真正执行，这次没试。

`Generic.kl` 里注释掉的扫描码不会变成按键。撤销 `131`、重做 `182` 就是这样，发出去应用也收不到，所以撤销仍然得用 Ctrl+Z，不能改发 `KEY_UNDO`。

截图在这份文件里不是一颗普通扫描码，只有 HID Usage `0x0c0065` 的回退。单发 `KEY_SYSRQ`（`99`，名字是 `SYSRQ`）这次没有当成截图来验证。

通知栏、快捷设置、分屏、锁屏这一类没有对应扫描码。要做就得在设置应用里再挂一个无障碍服务，用全局动作，不是再加一颗键。笔的快递键如果落在另一只 evdev 上，这条键盘路径也够不着。

内屏节点上的 `0x00ac` 就是 `KEY_HOMEPAGE`，抓取时已经原样转发。`0x00fd` 在 `Generic.kl` 里没有条目（253 附近是空的），转发出去不会变成 F 键或系统动作。

### 橡皮位的自定义功能会连发

守护进程没有在每一帧重发快捷键。`BTN_TOOL_RUBBER` 从 0 变成 1 时，把整组键按下并按住；变回 0 时再松开。`StylusCtrl keys` 没有 `EV_REP`，内核不会对这只虚拟键盘做自动重复。

连发来自按键被按住之后，窗口自己的重复。Android 对已经交给焦点窗口的 `ACTION_DOWN`，大约 400 毫秒后再开始重复，之后每隔几十毫秒一次。这和「按住期间连发、松手就停」一致。

橡皮位特别容易踩中，是因为这颗信号不是一颗点一下的键：

- 尾部有橡皮的笔（这支 HP 笔）把笔翻过来，橡皮端一进入感应范围就是 `BTN_TOOL_RUBBER` 按下，悬停的整段时间都按着，不用碰到屏幕。快捷键于是被按住好几秒，重复定时器一定会跑。
- 当成橡皮工具时不注入键盘，只维持工具类型，所以没有键可重复，笔画是正常的。
- 第一侧键是 `BTN_STYLUS`，代码也是按下按住、松开再放。点一下通常短于重复超时，看起来就一次。按住超过大约半秒，预期会跟橡皮位一样连发。这次没有单独做这个长按。

只在打开内屏抓取时出现，是因为不抓 `sec_e-pen` 的话，内屏橡皮端只是系统的橡皮工具，不会变成键盘。一抓，内屏这份橡皮快捷键和数位板那份都写进同一只 `StylusCtrl keys`，两边都是按住不放。数位板插不插不改变这条路径。外接板没插时，只有内屏这一路会按住那组键。

这次笔是停着的，没有录到连发的那一阵事件，所以上面是从代码、`Generic.kl` 和这只键盘的能力对上的，不是对着一段 `getevent` 数出来的。

要改成只触发一次，应在橡皮位变成快捷键时做成一次点按：按下后马上松开，不要盖住整个悬停。若还想避免「橡皮端刚靠近就触发」，再加上 `BTN_TOUCH`，只在橡皮端真正碰到时点一次。第一侧键如果也要避免长按连发，用同一种点按即可。

`0.4.3` 按这个改了。F1–F12，以及返回、主页、最近任务、切换应用、菜单、复制、剪切、粘贴、音量、静音、亮度、播放、上一首、下一首、电源、睡眠、唤醒，都可以加进快捷键。仍然只注册用到的键。侧键和橡皮位的快捷键都改成点按：信号从 0 变成 1 时按下并马上松开，松开时不再发一次。重读配置也不会因为橡皮端还悬停着再按一次。橡皮端刚进入感应范围仍会点一次；还没有改成必须碰到屏幕。电源和睡眠这次仍没在机器上试。

### 第二侧键在悬停里再按一次

`0.4.3` 的点按只认 `BTN_TOOL_RUBBER` 从 0 到 1。这台平板的内核是 5.10。这份 `hid-input` 收到 Invert 时只改一个内部标志，真正发出工具键是在处理 In Range 的时候：范围内就送当前工具的按下，离开范围才把笔和橡皮都松开。所以第二颗侧键按下去之后，`BTN_TOOL_RUBBER` 会一直保持按下，松开再按，evdev 上没有新的边沿。笔必须离开感应范围，内核才会把这个键清掉。尾部橡皮本来就是整段悬停都算橡皮，这条没有差别。

原始报告里的 Invert 位是跟着第二颗侧键走的，内核把它丢掉了，hidraw 还能读到。`0.4.4` 在外接笔上打开同一只 HID 设备的 hidraw，从报告描述符里找出 Digitizer Invert。这一位从 0 变 1，并且笔还在范围内，就再点一次快捷键。同一位如果被设成「第二侧键」（`BTN_STYLUS2`），按下是 1，松开是 0，不再跟着被内核粘住的工具位。橡皮工具仍按 evdev 上的工具类型转发。内屏笔没有这份 HID 报告，还是看 `BTN_TOOL_RUBBER` 的边沿。打不开 hidraw 时也退回那条边沿。

尾部橡皮的 Invert 在整个悬停里都是 1，所以还是进入感应范围点一次，离开再进来才有下一次。第二颗侧键可以在不离开悬停的情况下松开再按。

`0.4.5` 起，第一侧键可以选「按住为橡皮」。`BTN_STYLUS` 按下期间送出橡皮工具并藏起笔尖，松开立刻回到笔尖。这是按住，不是点一下。P620 原装笔只有这一颗键，默认不是橡皮，所以这条单独放在第一侧键上，不占用橡皮位。快捷键仍然只点一次。

## 验收

- [x] 重启后插上板子就能画
- [x] 刷一次 Lineage 小版本：不做。这台机器没有 A/B 分区，更新之后要重新 root
- [x] 卸掉模块并重启后，板子回到系统原来的映射
