# 阶段 3：装上就能用

日期：2026-10-22。顺序在 [roadmap.md](roadmap.md)。映射、曲线和侧键仍是阶段 2 的 json。这一阶段把进程放进 Magisk 模块，并在节点出现时重新抓。

## 这一阶段要证明的事

- 重启后插上板子就能画，不用再开一次 `adb shell`。
- 守护进程崩溃后，模块里的循环会再拉起。
- 热插拔不写死 event 号。节点出现或消失时按 VID/PID 和能力重新抓。第一份档案仍是 `0531:0104`。
- 刷一次 Lineage 小版本后模块还在，行为不变。
- 卸掉模块并重启后，板子回到系统原来的映射。
- 模块不放 IDC。虚拟设备保持指针，`touch.orientationAware` 保持关闭。

设置应用是普通 APK，用 libsu 写 `/data/adb/stylusctrl/config.json`，再向守护进程发 `SIGUSR1`。界面包括当前设备和轴范围、三种映射、压感曲线和测试画布、侧键绑定、内屏笔的开关。测试画布要读 `AXIS_PRESSURE`、工具类型和侧键。这棵树里还没有这份 APK。

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

`tools/stylusctld.c` 用 inotify 盯 `/dev/input` 的创建、删除和移动。有事件就再扫一遍，仍按 `INPUT_PROP_POINTER` 加 `ABS_PRESSURE` 挑选，跳过名字以 `StylusCtrl` 开头的节点。配置里的 `match` 继续过滤厂商和产品号。

inotify 打不开时，仍靠原来的一秒钟轮询。笔还按着的时候不读屏幕尺寸。节点消失走原来的 `POLLHUP`，放开独占并拆掉对应的虚拟设备。

日志里会有一行 `监听 /dev/input`。

## 构建和安装

```sh
tools/pack-module.sh
```

先跑 `tools/build.sh`，再把二进制和模块文件打进 `out/stylusctrl.zip`。

装之前先停掉 `adb shell` 里那个 `stylusctld`。两个进程会抢同一只笔节点。用 Magisk 刷这个 zip，然后重启。不要和正在跑的 adb 进程叠在一起。

设置应用还没进仓库。在它写好之前，改 `/data/adb/stylusctrl/config.json`，再 `kill -USR1 $(pidof stylusctld)`。

主机编 APK 用的 SDK 在 `~/Android/Sdk`，`ANDROID_HOME` 指到这里。平台是 `android-36`，构建工具是 `36.1.0`。命令行工具在 `cmdline-tools/latest`。Java 是系统里的 OpenJDK 17。

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

## 验收

- [x] 重启后插上板子就能画
- [x] 刷一次 Lineage 小版本：不做。这台机器没有 A/B 分区，更新之后要重新 root
- [x] 卸掉模块并重启后，板子回到系统原来的映射
