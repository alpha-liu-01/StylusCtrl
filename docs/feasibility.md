# Android 数位板控制面板：现状、可行性与实现路径

调查日期：2026-10-22。对象是「插在 Android 上的数位板 / 数位屏」的系统级控制面板：区域映射、压感曲线、侧键。方向与 OpenTabletDriver、Wacom Center（桌面）、KDE Plasma Drawing Tablet 相同。

把 Android 平板本身当成电脑的数位板（InkBridge、LinTab、GfxTablet、AndroidTabletServer）是相反方向，下文只在「现有项目」里点名，不作为方案。

## 结论

Android 没有这类开源控制面板，官方也没有对应设置项。即插即用已经存在，可配置性停在 inputflinger 写死的等比裁切和线性压感。

在这台已 root 的平板上实现是可行的，而且不需要改 LineageOS。功能上能达到桌面驱动的系统级效果：所有应用、悬停、倾斜、压感、侧键都走同一条路径。做法是 Magisk 模块里的常驻守护进程：独占真实 evdev，做映射和曲线，再经 `/dev/uinput` 交回系统。

Vector（这台机器上的 LSPosed 后继）做不到同等集成。它只能改 Java 层已经生成好的事件，盖不住 native 输入，也盖不住系统光标。

把 `/run/media/alpha/YMTC/lineage-23.2/` 拉来改 `gta4xlswifi` 设备树，改不到这条路径。映射代码在 `frameworks/native/services/inputflinger`，全设备共用。设备树里没有这份代码，当前源码树里也没有这台机器的设备树。

## 这台机器上实测到的东西

`adb` 序列号 `R52X800YGRN`。`getprop`：

| 项 | 值 |
| --- | --- |
| 型号 | SM-P620 |
| 商品名 | Galaxy Tab S6 Lite 2024 (Wi-Fi) |
| Lineage 代号 | `gta4xlswifi`（官方机型，维护者 Linux4） |
| 系统 | `23.2-20260829-NIGHTLY-gta4xlswifi`，Android 16，SDK 36 |
| SoC | Exynos 1280（`s5e8825`） |
| 屏幕 | 物理 1200×2000，物理密度 240，覆盖密度 280 |

用户口头说的「w620」对得上 SM-P620，对不上任何 `w620` 代号。官方设备树仓库是 `LineageOS/android_device_samsung_gta4xlswifi`，内核仓库是 `LineageOS/android_kernel_samsung_gta4xls`（5.10）。

Root 与注入框架：

- Magisk 30.7（versionCode 30700），`adb root` 可用（`userdebug`）
- Zygisk Next 1.5.0
- Vector v2.2（`zygisk_vector`，JingMatrix，versionCode 3080），模块目录在 `/data/adb/modules/zygisk_vector`，数据在 `/data/adb/lspd`
- `org.lsposed.manager` 未安装。Vector 是 LSPosed 的后继，不是另一个叫 Matrix 的独立框架

内核配置（`/proc/config.gz`）已经包含后面方案要用的开关，全部编进内核，不是模块：

- `CONFIG_HID_GENERIC=y`
- `CONFIG_HID_WACOM=y`（只认 USB vendor `056a`）
- `CONFIG_HIDRAW=y`
- `CONFIG_INPUT_UINPUT=y`
- `CONFIG_UHID=y`

`/dev/uinput` 存在，属主 `uhid:uhid`，SELinux 类型 `uhid_device`。`/sys/bus/hid/drivers/` 里同时有 `wacom`、`hid-generic`、`uclogic`。

内置笔和触摸屏如下。外接板的节点在下一节。

- 节点：`/dev/input/event10`，名字 `sec_e-pen`
- 坐标：`ABS_X` 0–13536，`ABS_Y` 0–22560（相对 1200×2000 约为 11.28 单位/像素，和屏幕等比）
- 压感：`ABS_PRESSURE` 0–4095
- 高度：`ABS_DISTANCE` 0–255
- 倾斜：`ABS_TILT_X/Y` −63–63
- 键：`BTN_TOOL_PEN`、`BTN_TOOL_RUBBER`、`BTN_TOUCH`、`BTN_STYLUS`
- 配置：`/vendor/usr/idc/sec_e-pen.idc` 只有两行，`touch.deviceType = touchScreen`，`touch.orientationAware = 1`
- 没有 `INPUT_PROP_DIRECT`。屏幕熄灭时 inputflinger 把这台设备标成 `DISABLED`，因为关联 viewport 的 `isActive` 为 0。亮屏后按 IDC 会走触摸屏的 DIRECT 映射

`sec_touchscreen`（`event9`）有 `INPUT_PROP_DIRECT`，报的是手指多点，不是笔压。`hall_wacom` 只是笔收纳的霍尔开关。

### CTC4110WL 已经插上

第二次调查走无线 adb `10.0.0.116:37067`。板子插在平板 USB 上，处于 Android 模式，不是 PC 模式。

| 项 | 值 |
| --- | --- |
| USB | `0531:0104`，总线 `usb2/2-1`，接口驱动 `usbhid` |
| HID 驱动 | `hid-generic`。`hid-wacom` 没有绑定这只设备 |
| 序列号 | `4DH01F1000849`（evdev `Uniq`，三只节点相同） |
| 活动区域 | 152.00 mm × 95.00 mm（`ABS` resolution = 100 单位/mm） |

`hid-generic` 从同一份报告描述符拆出三只 evdev。inputflinger 因为 `Uniq` 相同，把它们合成 **一个** 输入设备，名字落在 System Control 那只上。`ConfigurationFile` 是 `<none>`，没有 IDC。

| 节点 | 名字 | 内容 | 要不要 grab |
| --- | --- | --- | --- |
| `event13` | `Wacom Co.,Ltd. Wacom One pen tablet small` | 笔。`INPUT_PROP_POINTER` | 要。这是坐标、压感、倾斜、笔尖、橡皮、侧键 |
| `event12` | `... System Control` | `KEY_SLEEP` (142)、`KEY_WAKEUP` (143) | 不要。这是板子电源键，留给系统 |
| `event14` | 同名，无 pointer 属性 | `BTN_0` + `ABS_MISC`，被收成摇杆 `GENERIC_1` | 第一版不要。它是厂商报告被 hid-generic 误读的残留 |

`event13` 的能力：

| 轴 / 键 | 范围 | 含义 |
| --- | --- | --- |
| `ABS_X` | 0–15200，resolution 100 | 152 mm |
| `ABS_Y` | 0–9500，resolution 100 | 95 mm |
| `ABS_PRESSURE` | 0–4095 | 4096 级 |
| `ABS_TILT_X` / `ABS_TILT_Y` | −90–90，resolution 57 | 角度。应用侧变成 `AXIS_TILT` 0–π/2、`AXIS_ORIENTATION` −π–π |
| `ABS_MISC` | 0–1 | 工具在位一类的标志，不是高度 |
| `BTN_TOOL_PEN` (0x140) | | 笔在范围内 |
| `BTN_TOOL_RUBBER` (0x141) | | 橡皮工具位。原装笔的第二侧键走这里，见下文 |
| `BTN_TOUCH` (0x14a) | | 笔尖接触 |
| `BTN_STYLUS` (0x14b) | | 第一侧键。`Generic.kl` 把它映射成 `STYLUS_BUTTON_PRIMARY` |

没有 `ABS_DISTANCE`。悬停是「在范围内、笔尖没按下」，高度只有有无。报告描述符里的 Digitizer 集合（Report ID `0x06`）有 Tip Switch、一个 Barrel Switch、Eraser、Invert、In Range。标准集合里没有 Secondary Barrel Switch（`0x0D005A`）。厂商集合（Report ID `0x1F`，Usage Page `0xFF0D`）里有一个像第二侧键的位，但 hid-generic 把它收成了那只无用的摇杆节点，没有变成 `BTN_STYLUS2`。

Android 有第二个侧键。`Generic.kl` 的扫描码 332 是 `STYLUS_BUTTON_SECONDARY`，对应 `BTN_STYLUS2`。这只板子在 Android 模式 `0531:0104` 下没有把原装笔的第二键送成这个码。原装 CTC4110WL 笔（两个侧键、没有尾部橡皮）在 18:48–18:49 的记录里，一个键是 `BTN_STYLUS`，另一个键让工具变成 `BTN_TOOL_RUBBER`，倾斜仍和笔尖同一姿态，不是把笔翻过来。系统于是把它当成橡皮。PC 上的 Wacom 驱动看到的是两颗可改动作的键，橡皮只是第二键的默认动作。这里默认动作被写进了 Android 模式的 HID 报告，到用户态时已经是工具类型，不再是一颗键。

守护进程可以在这只板子的档案里把橡皮工具位当作逻辑第二键。默认动作仍是橡皮，也可以改成快捷键或 `BTN_STYLUS2`。HP 笔的尾部橡皮走的是同一个位，所以「橡皮位」的绑定对两种笔一起生效，除非以后能从笔的序列号把它们分开。

`Generic.kl` 只能把一个扫描码换成一个键码，变不成 Ctrl+Z。要撤销，仍然得由守护进程注入组合键。

inputflinger 对这只合成设备的分类：

- 来源：`KEYBOARD | MOUSE | STYLUS | JOYSTICK`
- `Touch Input Mapper` 模式 `POINTER`，`DeviceType: POINTER`，`OrientationAware: false`
- 压感标定 `physical`。`PressureScale` 打出来是 `0.000`，因为 dumpsys 用三位小数，`1/4095 ≈ 0.000244`。应用看到的 `AXIS_PRESSURE` 最大值是 1.0，曲线是直线
- 当时屏幕是横屏：viewport `orientation=3`（270°），逻辑画面 2000×1200，自然方向 1200×2000
- 变换比例 `0.1316 = 2000/15201`（分母是 `max-min+1`）。X 正好铺满 2000。Y 算出来的范围是 0–1249.9，屏幕只有 1200，所以 **横屏时 Y 轴高端约 4%（约 3.8 mm）在屏幕外**
- 竖屏若用同一算法：`fixedScale = max(1200/15200, 2000/9500) = 2000/9500`。可用的 X 只有约 5700/15200，也就是 **板面宽度大约 37% 有效，其余从坐标大的那一端切掉**。这就是「竖屏只用左侧一块、而且改不了」

仿射矩阵是单位矩阵。没有用户配置的平移、缩放或裁切窗口。

报告描述符 491 字节，标准笔数据在 Digitizer / Stylus / Report ID `0x06`。后面还有一串 Feature Report（ID `0x02`、`0x14`、`0xCB`、`0x37`、`0x38`、`0x39`），多半是切 PC/Android 模式用的。当前已经是 `0104`，第一版不用写这些 feature。

守护进程因此可以定成：只 `EVIOCGRAB` 带 `INPUT_PROP_POINTER` 且有 `ABS_PRESSURE` 的那只节点（这次是 `event13`）。`event12` 继续给系统当电源键。虚拟设备不必伪造 `ABS_DISTANCE`。第一侧键转发 `BTN_STYLUS`。第二侧键转发前先读 `BTN_TOOL_RUBBER`，按档案决定送出橡皮还是另一颗键。

## 1. 已有的开源方案

没有「在 Android 上控制系统级数位板」的成品。桌面那三类功能（映射、压感曲线、侧键）在 Android 上是散的，而且缺曲线和侧键。

### 和目标相反的项目

这些项目把 Android 的笔变成电脑上的数位板：

| 项目 | 做什么 |
| --- | --- |
| [OpenTabletDriver](https://github.com/OpenTabletDriver/OpenTabletDriver) | Windows / Linux / macOS 用户态驱动。配置格式和插件是桌面端的。没有 Android 守护进程。支持列表里有 CTC-4110WL |
| [AndroidTabletServer](https://github.com/dbalatoni13/AndroidTabletServer) | 把 Android 笔通过 USB/IP 暴露给电脑上的 OpenTabletDriver。压感和两个笔键，没有倾斜 |
| [InkBridge](https://github.com/dagaza/InkBridge)、[LinTab](https://github.com/dev-fcastro/LinTab)、[GfxTablet](https://github.com/wech71/GfxTablet) | Android 采集 `MotionEvent`，电脑上建 `uinput` 虚拟数位板。曲线和映射在电脑端 |

OpenTabletDriver 的映射、曲线、按钮模型值得抄。它的进程不能搬到 Android 上跑：它依赖 hidraw/USB 独占、桌面合成器和 .NET 运行时。

### 方向正确、但不是产品

[wacom-on-android](https://github.com/noxbitx/wacom-on-android)（NoxProductions，GPL-3.0）是目前最接近的代码。它在已 root 的 Android 上用 libusb 读 Intuos，用 `/dev/uinput` 注回悬停、压感和笔键。作者在 Galaxy Tab S2、LineageOS 21 上验证过 CTL-480。

它证明了「root + uinput 可以让绘画应用看到一只有压感的笔」，同时它本身不能当控制面板用：

- Python，在 Termux 里前台跑，进程一停就没了
- 屏幕尺寸和压感参数写死在源码里（`PRESSURE_EASING`、`PRESSURE_OFFSET`）
- 没有映射 UI，没有热插拔，没有曲线编辑器
- 绕开内核里已经能用的 HID 驱动，自己解析一种板子的协议
- 侧键写成 Ctrl+Z / Ctrl+Y，不是可配置绑定

### 只覆盖手势、不覆盖数位板

[FocusPen Pro X](https://github.com/HMQYHM/FocusPenProX) 是 Vector/LSPosed 模块，对象是小米焦点触控笔 Pro 的捏合手势和激光笔。它在 `system_server` 里跑，不管 USB 数位板，不管压感曲线，不管活动区域。

### 厂商在 Android 上实际提供了什么

Wacom Center（`com.wacom.momijiprefs`）只服务 CTC4110WL / CTC6110WL，而且只在 Android 8–13。它做一件事：按屏幕比例裁掉数位板的一块，避免拉伸。活动区域可以在左、中、右三档里选。没有压感曲线，没有侧键。Wacom 的说明是 Android 14 起系统自己做了无拉伸映射，Android 14 及以后应卸载这个应用。

[Wacom 的 Android 支持页](https://support.wacom.com/hc/en-us/articles/1500006263521-How-do-I-change-the-mapping-Active-area-on-the-Wacom-Intuos-when-using-an-Android-tablet) 写明活动区域不能改。竖屏时往往只用数位板左侧一块，横屏时用得更多。这和下面 inputflinger 的算法一致。

[7P Drawing tablets 的 Android 说明](https://docs.thesevenpens.com/drawtab/guides/operating-systems/android/using-drawtabs-with-android) 把驱动级压感曲线、屏幕切换、板载键、屏上快捷键都标成 Android 不支持。笔键最多由单个绘画应用自己认。

LineageOS 里能找到的相关改动是设备 IDC 里的 `touch.pressure.scale`（例如 OnePlus erhai 把笔压量程从 0–4 改成 0–65536）。那是给某一支内置笔补一个线性系数，不是控制面板。

## 2. 系统现在怎么处理一支笔

插上 USB 之后的路径是固定的。CTC4110WL 和传统 Wacom 在内核里还不是同一个驱动。

```
USB HID
  ├─ vendor 056a  → hid-wacom     → /dev/input/event*
  └─ vendor 0531  → hid-generic   → /dev/input/event*     （CTC4110WL）
        ↓
EventHub 分类（TOUCH / CURSOR / EXTERNAL_STYLUS / KEYBOARD）
        ↓
TouchInputMapper
  ├─ DIRECT   触摸屏，X/Y 各自缩放到屏幕，允许拉伸
  └─ POINTER  数位板，X/Y 用同一个比例，多出来的板面被裁掉
        ↓
压感：pressure = raw / raw_max          （线性，无曲线）
倾斜：ABS_TILT_* → AXIS_TILT / AXIS_ORIENTATION
笔键：BTN_STYLUS → MotionEvent 的 stylus button，不是键盘键
        ↓
应用看到的 MotionEvent，压感已经被归一化到大约 0–1
```

依据是本机这份 Lineage 23.2 树里的 `frameworks/native/services/inputflinger`（与 2026-08-29 nightly 同分支）。

### CTC4110WL 不会进 hid-wacom

[linux-wacom Device IDs](https://github.com/linuxwacom/input-wacom/wiki/Device-IDs)：

| USB ID | 型号 | 模式 |
| --- | --- | --- |
| `0531:0100` | CTC4110WL | PC |
| `0531:0104` | CTC4110WL | Android |
| `0531:0102` / `0531:0105` | CTC6110WL | PC / Android |

`hid-wacom` 只绑定 vendor `056a`。这次插上的 CTC4110WL 由 `hid-generic` 解析成标准数位板轴，PID 是 Android 模式的 `0104`。即插即用来自通用 HID，不来自 Wacom 专有驱动。PC 模式的 `0100` 这次没有出现。

Intuos、Cintiq 以及其他 `056a` 设备会进 `hid-wacom`。`uclogic` 驱动也在，Huion / XP-Pen 一类有机会直接变成 evdev。两条路最后都是 `/dev/input/event*`，用户态不必先重写 USB 协议。

### 映射是写死的等比裁切

`TouchInputMapper::computeInputTransforms()` 里，POINTER 模式的注释写明了设计：数位板和屏幕比例通常不同，所以 X 和 Y 用同一个缩放，保证能碰到整个屏幕，同时笔画不变形。实现是：

```text
xScale = displayWidth / tabletWidth
yScale = displayHeight / tabletHeight
fixedScale = max(xScale, yScale)
```

比例更大的那一轴会把数位板的另一轴推出屏幕。原点在原始坐标 0，所以被裁掉的是右缘或下缘，用户不能改成居中，也不能改成「整块板子映射进屏幕、屏幕两侧留空」。DIRECT 模式则是 `xScale` 和 `yScale` 分开，笔迹会被拉成屏幕比例。

设备类型从 `INPUT_PROP_DIRECT` / `INPUT_PROP_POINTER` 推断，IDC 的 `touch.deviceType` 可以改成 `touchScreen`、`pointer` 或 `touchNavigation`。这只能在「拉伸铺满」和「等比裁一边」之间二选一，不能给出矩形。

Android 14 修的就是以前 X/Y 独立缩放造成的拉伸。Wacom 因此宣布 Android 14 起不再需要 Wacom Center。修完之后映射仍然不可调。用户说「能画，但映射改不了」，和这段代码一致。

### 压感只有一个线性系数

默认：

```text
pressureScale = 1 / ABS_PRESSURE.max
output = rawPressure * pressureScale
```

IDC 可以写 `touch.pressure.calibration`（`none` / `physical` / `amplitude`）和 `touch.pressure.scale`。`scale` 是常数。把系数调大，轻压会更早顶到 1，手感变「软」，但中间没有控制点，也没有死区。系数在设备配置时读一次，改文件要重新识别设备。

桌面驱动的曲线（OpenTabletDriver 的贝塞尔，`xsetwacom PressureCurve` 的两段）发生在归一化之前。Android 把归一化做完就交给应用。绘画应用各自有曲线时，同一支笔在不同应用里手感不同；没有曲线的应用就只能用这条直线。

### 笔键到不了键盘映射

`BTN_STYLUS` / `BTN_STYLUS2` / `BTN_STYLUS3` 被收成 `MotionEvent` 的 stylus button。`.kl` 键盘布局只翻译按键扫描码。多数绘画应用不读 stylus button，所以侧键表现为没反应。要得到撤销，得在进入 inputflinger 之前把笔键变成 Ctrl+Z，或者在应用里单独做。

`ExternalStylusInputMapper` 是另一类设备：只有压感或笔键、没有 `ABS_X/Y`，用来把压感融合进触摸屏。`sec_e-pen` 自己带坐标，不走这条。CTC4110WL 也不走这条。

### 系统里已经有的、但不够用的扩展点

| 机制 | 能做什么 | 做不到什么 |
| --- | --- | --- |
| `/vendor/usr/idc`、`/system/usr/idc` | 设备类型、线性压感系数、方向感 | 曲线、任意矩形、运行时修改 |
| `/system/usr/keylayout` | 快递键、真正的键盘扫描码 | `BTN_STYLUS` 这种笔键 |
| `IInputFilter`（`@hide`） | `AccessibilityManagerService` 在分发前看一眼事件，可以吃掉再注入 | 全系统只有一个 filter，和无障碍抢；事件已经是屏幕坐标和线性压感 |
| inputflinger 的 Rust `InputFilter` | 无障碍的 bounce / slow / sticky keys | 没有第三方注册接口 |

`IInputFilter.filterInputEvent()` 确实能在分发前吃掉事件。调用点只有无障碍服务。Vector 模块可以钩住 `WindowManagerService.setInputFilter()` 再包一层，但会和 TalkBack 一类服务互相覆盖，而且改的是已经映射完的坐标。

## 3. 可行性

可行。前提是 root，这台机器已经满足。

原因：内核已经把板子解码成 evdev，`/dev/uinput` 可用，inputflinger 会把一只报告了笔轴的虚拟设备当成数位板。缺的是中间那一层变换。这一层在 Linux 上就是 OpenTabletDriver 或 xf86-input-wacom 的用户态部分，不需要新的内核驱动。

不需要改的部分：

- 内核 HID 解析（`056a` 用 wacom，`0531` 用 hid-generic）
- 绘画应用。它们继续读 `AXIS_PRESSURE`、`AXIS_TILT`、`AXIS_ORIENTATION`、`AXIS_DISTANCE`、`TOOL_TYPE_ERASER`
- 内置 S Pen 的面板标定。`sec_e-pen` 的轴已经和 1200×2000 等比

需要新写的部分是用户态的映射、曲线、按钮，外加一个设置界面。

做不到的部分，在没有板子固件协议文档时：

- 板载 ExpressKey 的 LED、笔身按键的固件宏。这些要发 HID feature report。第一版把它们当成 evdev 键来绑定即可
- 不 root 的系统级效果。普通应用进不了 `uhid` 组，拿不到 `EVIOCGRAB`，也没有 `INJECT_EVENTS`。应用只能改自己的 `onTouchEvent`

延迟：同进程里读 evdev、写 uinput，中间不经 Python、不经 binder，通常是亚毫秒。wacom-on-android 的 Termux + Python 路径只适合验证，不适合当驱动。

### 建议的运行结构

```text
USB 板子
  → 内核 evdev（原设备）
  → 守护进程 EVIOCGRAB，原设备不再把事件送给 inputflinger
  → 裁切 / 旋转 / 压感曲线 / 笔键改写
  → /dev/uinput 虚拟数位板（+ 可选的虚拟键盘）
  → 现有 TouchInputMapper
  → 所有应用

设置应用（普通 APK）
  → 写 /data/adb/stylusctrl/config.json
  → 通知守护进程重载
```

守护进程用 C 或 Rust。Magisk 的 `service.sh` 以 root 拉起，监听 netlink uevent 或 `/dev/input` 的 inotify。

识别设备用 VID/PID 加 evdev 名字，第一批：

- `056a:*`，内核驱动 `wacom`
- `0531:0100`、`0531:0104`、`0531:0102`、`0531:0105`，CTC4110WL / CTC6110WL
- 之后按 `getevent -p` 的结果加 Huion、XP-Pen、UGEE，能走 `uclogic` 的优先走 evdev，不优先重写 USB

`EVIOCGRAB` 是为了避免原设备和虚拟设备各送一套坐标。Linux 的 grab 是独占：inputflinger 那个已经打开的 fd 在 grab 期间收不到新事件，设备节点仍在。守护进程必须一直拿着这个 fd。热插拔时 grab 要尽早，插上瞬间可能漏几个点。

虚拟设备建议：

- 保留 `BTN_TOOL_PEN`、`BTN_TOOL_RUBBER`、`BTN_TOUCH`、`ABS_PRESSURE`、`ABS_TILT_X/Y`。CTC4110WL 没有 `ABS_DISTANCE`，悬停用「有笔、没接触」表示，虚拟设备不要凭空造高度轴
- 压感在守护进程里变成曲线之后，仍以固定量程（例如 0–4095）写出去，让 inputflinger 只做它那次线性归一化
- 坐标策略分两档：
  - 只改「板子上哪一块有效、裁哪一边、要不要拉伸」：改虚拟设备的 `ABS_X/Y` 最小最大值，或在写出去之前夹紧。`touch.orientationAware = 1`，旋转仍交给 inputflinger
  - 要映射到屏幕上的一块矩形（类似桌面的「映射到显示器的一个区域」）：守护进程输出已经落在屏幕坐标里的值，虚拟设备用 DIRECT，并且自己跟旋转。设置应用注册 `DisplayListener` 把旋转告诉守护进程
- 笔键两种模式都要有。「原样当作 stylus button」留给认这个按钮的应用；「发送快捷键」用第二只 uinput 键盘注入，例如 Ctrl+Z。`Generic.kl` 已经认识这些键
- IDC 用 Magisk 覆盖放进 `/system/usr/idc/Vendor_<vid>_Product_<pid>.idc`，把虚拟设备固定成想要的 `touch.deviceType`。运行时会变的参数不要放进 IDC

设置应用用 libsu 写配置。它不需要是系统应用，也不需要 Vector。界面至少包括：当前设备和轴范围、活动区域（铺满 / 等比裁切 / 居中裁切 / 自定义矩形）、压感曲线和死区、笔键绑定、旋转、一块能读到 `AXIS_PRESSURE` 的测试画布。

内置 `sec_e-pen` 可以做成可选配置档，用同一条曲线。grab 只针对 `event` 节点名字是 `sec_e-pen` 的那个，不要动 `sec_touchscreen`，否则手指会一起被吃掉。

SELinux：守护进程跑在 Magisk 的 su 域里，`adb root` 已经能看到 `uhid_device`。模块里加 `sepolicy.rule`，允许这个域打开 `uhid_device` 和 `input_device`。第一块板子插上时用 `dmesg` 和 `logcat` 确认有没有 avc denial。

### CTC4110WL 的采集已经做完

2026-10-22 无线 adb 上的结果写在上面「CTC4110WL 已经插上」。和实现直接相关的决定：

- 模式是 `0531:0104`，驱动是 `hid-generic`，笔在带 `INPUT_PROP_POINTER` 的那只节点上
- grab 目标按能力选，不按 `event13` 这个号。号会随插拔顺序变
- 合成设备没有 IDC，`OrientationAware` 为 false。转屏仍然由 inputflinger 用 viewport 旋转补上，本次横屏的矩阵是 `ROT_270` 加统一比例 `0.1316`
- 侧键扫描码 331 已经映射到 `STYLUS_BUTTON_PRIMARY`。改 `.kl` 换不成带修饰键的快捷键

## 4. 分发：ROM、设备树、Vector

三件事的集成度不一样。功能集成（所有应用都吃到新坐标和新压感）和设置集成（出现在系统设置里）要分开看。

### 改 Lineage 设备树

当前源码树 `/run/media/alpha/YMTC/lineage-23.2/` 有 `frameworks/native/services/inputflinger`，可以当阅读副本。`device/samsung` 不在这棵树里，`.repo/project.list` 里也没有 `gta4xlswifi`。就算把 [android_device_samsung_gta4xlswifi](https://github.com/LineageOS/android_device_samsung_gta4xlswifi) sync 下来，里面也只有这台平板的分区、init、厂商 blob 引用和内核配置。数位板映射不在那里。

设备树能改的，是已经在机子上的那种 IDC，例如 `sec_e-pen.idc`。那解决不了曲线和矩形。

为这一项去 `lunch lineage_gta4xlswifi-userdebug` 全编，每次官方 nightly 都要重复合并 `frameworks/native`。用户现在用的是 2026-08-29 nightly，官方 2026-10-17 的包已经在下载页上。用自编译 ROM 换官方更新，换来的只是本可以用 Magisk 覆盖的行为。

### 改 inputflinger，做成 Lineage 的功能

这是「变成系统标准」的那条路，也是工作量最大的一条。改动落在：

- `TouchInputMapper`：运行时读取活动区域和压感曲线，而不是只读 IDC 里的线性系数
- 设置应用：Lineage 的设置或 `LineageParts`
- 配置存系统设置，插拔和旋转后仍然生效

这样做完，不需要 grab，也不需要第二只虚拟设备，旋转、多显示器、无障碍都还在原来的管道里。它和机型无关，所以不该写进 `gta4xlswifi`。合适的投放方式是 Lineage 的通用补丁，而不是这台平板的私有 ROM。

在有可工作的守护进程之前就走这条路，评审周期会压过功能本身。守护进程的配置文件（区域、曲线控制点、按钮）可以直接变成以后 inputflinger 的配置格式。

### Vector / LSPosed

这台机器上的注入框架是 Vector，不是旧的 LSPosed Manager。它能钩 Zygote 孵出来的 Java 进程，包括勾选范围内的 `system_server`。

它达不到守护进程的集成度，原因是坐标和压感在 `libinputflinger` 里已经算完，应用进程里的 `MotionEvent` 只是结果：

- 钩 `ViewRootImpl` / `dispatchTouchEvent` 只能覆盖走 View 树的应用。`ANativeActivity`、部分游戏和直接读 `AInputQueue` 的绘制引擎拿不到这次修改
- 一个 `MotionEvent` 里有历史采样。只改当前点，快画时曲线是断的
- POINTER 模式的光标由 native 的 `PointerChoreographer` 画。应用里改坐标，光标和笔尖会分开
- 压感没有稳定的公开 setter，要拆掉事件再 `MotionEvent.obtain`。这套内部结构在 Android 16 上已经和前几个版本不同，nightly 一更新就可能要改
- 模块要勾上全部应用才像「系统级」。漏一个绘画应用，那一个应用就是旧手感
- 钩 `setInputFilter` 能看到全系统事件，但这个位子属于无障碍服务，两边会互相卸掉

Vector 适合以后做附加能力：某个应用不认快捷键、只认自己的菜单时，给那个应用单独下发「撤销」；或者在系统设置里加一个跳转到本应用的入口。第一版不必依赖它。

### 三者对照

|  | Magisk 守护进程 | Vector 模块 | 改 inputflinger 的 ROM |
| --- | --- | --- | --- |
| 所有应用的坐标和压感 | 能。事件在进 inputflinger 前就改完 | 只能覆盖 Java 分发 | 能 |
| 悬停、倾斜、橡皮擦 | 能，轴原样转发 | 取决于能不能改到对应的 `MotionEvent` | 能 |
| 侧键到快捷键 | 能，第二只 uinput 键盘 | 只能在钩得到的进程里伪造按键 | 能 |
| 跟官方 nightly 更新 | 模块还在，内核接口稳定 | 钩 Java 内部，更新后要复测 | 每次都要自己编译 |
| 不 root 的别人 | 不能装 | 不能装 | 只能刷整包 |
| 和这台设备树的关系 | 无 | 无 | 改的是 `frameworks/native`，设备树只负责编译目标 |
| 和无障碍共存 | 共存。无障碍看到的是改完的事件 | `IInputFilter` 方案会抢占 | 共存 |

## 5. 建议的实施顺序

实现顺序以 [roadmap.md](roadmap.md) 为准。下面是调查结束时的步骤记录。

1. CTC4110WL 的 `lsusb`、`getevent -p`、`dumpsys input` 已经在 2026-10-22 抓过，见「CTC4110WL 已经插上」。hid-generic 会给出笔轴。
2. 只读探针是 `tools/penprobe.py`。它不 grab，笔还能画。同一支笔在内屏和 CTC4110WL 上的侧键、橡皮结果在下一节。`tools/pengrab.c` 是两秒独占读取，用来确认 grab 之后应用收不到原笔。
3. 最小 uinput 回环：坐标原样、压感原样。在已有的绘画应用里确认悬停和 4096 级压感还在。这台机器上已安装的相关应用包括 Concepts（`com.steadfastinnovation.android.projectpapyrus`）。
4. 加上裁切和一条贝塞尔曲线，配置先用 json。
5. 再做设置 APK 和 Magisk 打包（`service.sh`、`sepolicy.rule`、IDC 覆盖）。
6. 曲线和区域在这台平板上稳定之后，再考虑要不要把同一份配置推进 inputflinger。那是上游化，不是第一版的分发方式。

内置 S Pen 可以作为第二配置档，用来在没有外接板子时调试曲线。不要把它当成 CTC4110WL 的协议样本，两者的 evdev 节点不是同一种设备。

## 6. 探针：HP ZBook x2 G4 的笔

探针用的不是三星原装 S Pen，是 HP ZBook x2 G4 的笔。它是无电池的 Wacom EMR 笔：4096 级压感、一个侧键、尾部物理橡皮，能悬停和倾斜。HP 的规格写的就是这一个侧键。它能同时用在 SM-P620 的内屏和 CTC4110WL 上，是因为两块板都是 EMR，不是因为 Android 认 HP。

`tools/penprobe.py` 不抢占设备。18:35–18:36 是内屏，18:37 是数位板。数位板上的四下是：笔尖书写、按住侧键用笔尖、橡皮、按住侧键用橡皮。电源键节点和那只被误读成摇杆的节点都没有事件。

`getevent -l` 把 `0x140` 印成 `BTN_DIGI`。这个码就是 `BTN_TOOL_PEN`。橡皮是 `BTN_TOOL_RUBBER`，侧键是 `BTN_STYLUS`。没有 `BTN_STYLUS2`。压感峰值是这次手劲，不是硬件上限。

| 状态 | 内屏 `sec_e-pen` | CTC4110WL |
| --- | --- | --- |
| 笔尖悬停 | 压感 0，高度最高 69 | 压感 0，没有高度轴 |
| 笔尖接触 | 压感最高 3591 / 4095，高度 0 | 压感最高 1997 / 4095 |
| 按住侧键，笔尖悬停 | `BTN_STYLUS`，工具仍是笔 | 相同 |
| 按住侧键，笔尖接触 | 压感最高 2449，工具仍是笔 | 压感最高 1548，工具仍是笔 |
| 橡皮端悬停 | 高度最高 80，`BTN_TOOL_RUBBER` 替换 `BTN_DIGI` | 没有高度轴，工具是橡皮 |
| 橡皮端接触 | 压感最高 3201 | 压感最高 4041 |
| 按住侧键，再用橡皮 | 整段 `side=up` | 整段 `side=up`，包括 18:37:51 那一笔 |

第四下做了，两面的事件里都没有和橡皮同时出现的 `BTN_STYLUS`。侧键只在笔尖那一端处于感应范围内时出现。描述符里 Barrel Switch 和 Eraser 在同一份笔报告里，所以不是 Android 把它们拆丢了，是这支笔在橡皮线圈工作时没有把侧键位送出来。守护进程不能靠「橡皮加侧键」做第二种橡皮功能，除非换一支在橡皮包里仍然报告 barrel 的笔。

笔尖那一端的侧键和橡皮是分开的。侧键在悬停和接触时都能按住，不会把工具切成橡皮。倾斜在两面都有，原始 log 里接近 2³² 的数是负数。

不同的部分：

- 内屏有 `ABS_DISTANCE`（0–255）。接触时为 0，悬停可以到几十。CTC4110WL 没有这个轴，悬停只剩下「笔在范围内、`BTN_TOUCH` 松开、压感 0」。
- 内屏用橡皮时，`BTN_TOOL_RUBBER` 会换掉 `BTN_DIGI`。数位板上橡皮接触时，这两个键会同时按下。守护进程转发时要认橡皮优先，否则应用会把橡皮端当成笔尖。
- 内屏倾斜轴是 −63–63。数位板是 −90–90，这次笔尖接触时 X 大约在 +23 到 +46。
