# Acer Chromebook Tab 10（gru）探针

日期：2026-10-24。只读。`adb connect 10.0.0.108:5555`。没有 `adb root`，没有 Magisk，没有 `su`。后半段录了 120 秒落笔，见「落笔」。

## 结论

这支内置笔和 StylusCtrl 已经在 P620 内屏上走过的那条路是同一类：`hid-generic` 解出 evdev，带压感、倾斜和侧键，inputflinger 按 `INPUT_PROP_DIRECT` 把它铺满内屏。压感曲线和侧键可以加在独占之后、uinput 交回之前。不要用外接数位板那套 `INPUT_PROP_POINTER` 预补偿。

`0.4.6` 会把这支笔当成内屏笔：虚拟设备标 `INPUT_PROP_DIRECT`，坐标原样，只改内屏那份压感和侧键。要在内屏页打开抓取。刷机包里还没有 `/dev/uinput`，也还没有 init 服务。这两件在 Lineage 工作区做，步骤在文末。

## 机器

| 项 | 值 |
| --- | --- |
| 板子 | `gru`（Scarlet，Acer Chromebook Tab 10） |
| `getprop` | `ro.product.device=gru`，`ro.product.model=Google Gru`，`ro.product.name=lineage_gru` |
| 系统 | LineageOS 23.2，Android 16，SDK 36，`userdebug` |
| 内核 | `6.6.34-phase2`，`#4 SMP PREEMPT Tue Oct 20 23:09:06 EDT 2026`，aarch64 |
| 屏幕 | 逻辑帧 `2048×1536`，方向 270，密度 260，`xDpi`/`yDpi` 265.4。未旋转的显示边界是 `1536×2048` |
| shell | `u:r:shell:s0`，组里有 `input` 和 `uhid`。`/system/bin/su` 不存在 |

`/dev/input/event*` 是 `root:input` `0660`，shell 能打开。`/dev/hidraw0` 是 `system:system`，shell 打不开。

## 输入节点

`getevent -pl`：

| 节点 | 名字 | 用途 |
| --- | --- | --- |
| event0 | Elan Touchscreen | 手指。`INPUT_PROP_DIRECT`，`ABS_MT_*`，压感 0–255。驱动 `elants_i2c`。不要抓 |
| event1 | cros_ec | 键盘 |
| event2 | cros_ec_buttons | 音量、电源、`SW_TABLET_MODE` |
| event3 | hid-over-i2c 2D1F:0036 Stylus | 笔。见下表 |
| event4 | hid-over-i2c 2D1F:0036 | 同一只 HID 的第二只 evdev。见下 |
| event5 | gpio-keys | 只有 `SW_PEN_INSERTED`（0x0f）。笔槽，不要抓 |
| event6 / event7 | rk3399-gru-sound | 耳机孔、DP |

笔和 event4 的 sysfs 是同一个设备：`/sys/devices/platform/ff120000.i2c/i2c-2/2-0009/0018:2D1F:0036.0001`，驱动 `hid-generic`，总线是 i2c-hid（`i2c_hid_of`）。`dumpsys` 里 `UsiVersion` 未设置，这是 EMR，不是 USI。设备树里的说明写在 `device/google/gru/idc/Vendor_2d1f_Product_0036.idc`：竖屏数位板 `14746×19661`，对上 `1536×2048` 的 DSI 面板。IDC 只有三行：`device.internal = 1`，`touch.deviceType = touchScreen`，`touch.orientationAware = 1`。手指的 IDC 是 `Elan_Touchscreen.idc`，因为 Elan 的厂商号和产品号都是 0。

event3「Stylus」：

| 项 | 值 |
| --- | --- |
| 属性 | `INPUT_PROP_DIRECT` |
| `ABS_X` | 0–14746，resolution 100（147.46 mm） |
| `ABS_Y` | 0–19661，resolution 100（196.61 mm） |
| `ABS_PRESSURE` | 0–4095 |
| `ABS_TILT_X` / `ABS_TILT_Y` | −9000–9000，resolution 5730。5730 ≈ 100×180/π，单位是百分之一度，也就是 ±90° |
| 键 | `BTN_TOOL_PEN`、`BTN_TOOL_RUBBER`、`BTN_TOUCH`、`BTN_STYLUS`、`BTN_STYLUS2` |
| 没有 | `ABS_DISTANCE`、`ABS_MT_*` |

event4 轴范围和 event3 相同，键少了 `BTN_STYLUS2`，没有 `INPUT_PROP_DIRECT`，也没有 `INPUT_PROP_POINTER`。inputflinger 仍然把它标成 `TOUCH_SCREEN`，来源是 `KEYBOARD | TOUCHSCREEN | STYLUS`，和 event3 用同一份 IDC。两只的 `IsExternal` 都是 false。

落笔记录里 event4 一行都没有。坐标、压感、倾斜、侧键全在 event3。只独占名字里带 `Stylus` 的那只。

## 系统现在怎么用这支笔

`dumpsys input` 里 event3 的 Touch Input Mapper 是 `DIRECT`：

- 原始轴照抄上面的范围，`HaveTilt: true`
- `touch.pressure.calibration: physical`，压感先被收成 0–1
- 变换是等比 `0.1042`，再加旋转。`14746×0.1042` 约等于 1536，`19661×0.1042` 约等于 2048，铺满这块屏，没有外接板那种短边裁切
- 键盘映射 `OrientationAware: true`，当前是 `Rotation270`

所以这台机器上值得做的是压感曲线和侧键。区域映射如果以后要做，得在这支笔自己的 `ABS` 范围里缩放，再交给原来的 DIRECT 变换。外接板那种先抵消 POINTER 等比裁切的算法用在这里会画偏。

## 0.4.6 怎么认它

`is_builtin_pen` 收两类内屏笔：

- 名字恰好是 `sec_e-pen`
- 总线是 `BUS_I2C`（0x18），带 `INPUT_PROP_DIRECT` 和 `BTN_TOOL_PEN`，有 `ABS_PRESSURE`，没有 `ABS_MT_SLOT`

第二类盖住 event3。event4 没有 `DIRECT`，Elan 有 `ABS_MT_SLOT`、没有笔工具位，都不会被抓。名字以 `StylusCtrl` 开头的虚拟设备也不会。USB 上即使报了 `DIRECT` 也不走这条，外接板仍要 `INPUT_PROP_POINTER`，并且对上配置里的厂商号和产品号。

内屏笔要 `internal.enabled` 为真才抓。虚拟设备标 `INPUT_PROP_DIRECT`，轴用 `EVIOCGABS` 原样抄，不做 `map_point`。三星那只额外的笔槽、皮套、悬停距离和 Home 键只在名字是 `sec_e-pen` 时送回。这支笔的笔槽在 `gpio-keys` 上，虚拟设备不另造 `SW_PEN_INSERTED`。

按键 1 是 `BTN_STYLUS`，按键 2 在接触时是 `BTN_TOOL_RUBBER`，用现有的第一侧键和橡皮位。悬停里的 `BTN_STYLUS2` 不转发。6.6 上橡皮抬笔就松开，这支笔不读 hidraw。

虚拟设备的厂商号和产品号从原节点抄，bustype 仍写成 USB。IDC 按厂商号和产品号找，`Vendor_2d1f_Product_0036.idc` 会套到虚拟设备上。不要再写一份名字 IDC。守护进程会删掉 `/data/system/devices/idc/` 里按虚拟设备名字生成的那份。

## 落笔

120 秒，`getevent -lt`。event3 有 60178 行、15485 个报告，跨度 90.7 秒。event4 为 0 行。`getevent -l` 把 `BTN_TOOL_PEN`（0x140）印成 `BTN_DIGI`。

三种画法对得上三种报告，互不重叠：

| 操作 | 接触期间的键 | 压感 |
| --- | --- | --- |
| 只用电笔尖 | `BTN_DIGI` 在悬停时已经按下，落下加 `BTN_TOUCH`。抬起先松 `BTN_TOUCH`，人离开感应区再松 `BTN_DIGI` | 0–3985 |
| 按键 1 + 笔尖 | 上面那套还在，再加 `BTN_STYLUS`。笔工具不丢。键可以在落笔前按下，也可以落笔之后按下（记录里 8.0 秒和 22.8 秒）。松开有独立的 UP | 接触时仍到 3000 以上 |
| 按键 2 + 笔尖 | 进入时就是 `BTN_TOOL_RUBBER`，`BTN_DIGI` 为 0，再加 `BTN_TOUCH`。抬笔的同一时刻橡皮 UP、笔工具 DOWN，人还在感应区内大约 0.1 秒，然后才离开 | 0–3575 |

按键 1 和橡皮没有同时按下。橡皮抬笔就松开，不会一直锁到离开感应区。这是 6.6 的 `hid-input`，和 P620 上 5.10 把 `BTN_TOOL_RUBBER` 锁住的行为不一样。这次没有在同一次悬停里把橡皮再按第二下，所以「悬停中再按一次」还没录到。

`BTN_STYLUS2` 另有 4 次，都在悬停、压感 0、没有 `BTN_TOUCH`：大约 6.3–7.6 秒、21.1–22.3 秒、56.6–57.5 秒、88.2–89.1 秒。每次约 1 秒，和落笔错开。接触时的第二套画法走的是 `BTN_TOOL_RUBBER`，不是 `BTN_STYLUS2`。

倾斜在这次里到过 X −62.00°–4.00°、Y −38.00°–53.00°，落在描述符的 ±90° 里面。悬停时压感是 0。

## `/dev/uinput` 不在

`/proc/config.gz` 里是 `CONFIG_INPUT_UINPUT=m`，`CONFIG_UHID=m`。`/proc/modules` 里有 `uhid`，没有 `uinput`。`/dev/uinput` 不存在，`/sys/module/uinput` 也不存在。`/dev/uhid` 在，`0660 uhid:uhid`。

模块文件没有落在可 insmod 的位置：

- `/vendor/lib/modules` 是指向 `/vendor_dlkm/lib/modules` 的符号链接，目标不存在
- `/vendor_dlkm` 挂上了，里面只有 `etc`
- `modprobe uinput` 报 `/lib/modules/` 不存在
- 设备树 `device/google/gru/modules/` 里有 89 个 ko，`prebuilt/6.6.34-phase2/modules.load` 里没有 `uinput.ko`
- `ueventd.gru.rc` 给 `/dev/uhid` 写了权限，没有 `/dev/uinput`

守护进程要创建虚拟设备，内核得先把 `uinput` 编进镜像，或者把 `uinput.ko` 放进 ramdisk 的 `modules.load`。节点起来之后，ueventd 要给它权限。P620 上这个节点的属主是 `uhid:uhid`，SELinux 类型是 `uhid_device`。

## 搬到 Lineage 工作区

这个仓库里 `0.4.6` 已经做完的事：守护进程认这支笔，设置应用在没有 `su` 时把配置写到自己的目录。工作区要做的是让节点和进程在开机后存在。设备树是 `device/google/gru`。不要改 `inputflinger`，不要抓 Elan、`event4`、`cros_ec` 或 `gpio-keys`。

先编这个仓库：

```sh
tools/build.sh
tools/build-apk.sh
```

`out/stylusctld` 是给系统镜像的静态二进制。`out/stylusctrl.apk` 用 `adb install` 即可，不必打进镜像。应用包名是 `app.stylusctrl`。

### 1. 把 uinput 放进 ramdisk

`CONFIG_INPUT_UINPUT` 已经是 `m`，缺的是模块没有进 `modules.load`。在内核模块目录（里面要有 `modules.dep`）上，给 `device/google/gru/prepare-ramdisk-modules.sh` 的 `extra` 元组加一行再跑：

```text
kernel/drivers/input/misc/uinput.ko.xz
```

脚本用法是 `prepare-ramdisk-modules.sh /path/to/lib/modules/6.6.34-phase2`。它会重写 `modules.mk`、`prebuilt/6.6.34-phase2/modules.load` 和对应的 ko。若那份模块树已经不在，就把 `CONFIG_INPUT_UINPUT` 改成 `y` 重新编内核。编进内核之后这一节可以跳过。

`ueventd.gru.rc` 加上，和现有的 `/dev/uhid` 那行并列：

```text
/dev/uinput                                                                 0660   uhid       uhid
```

节点起来后看 `ls -lZ /dev/uinput`。AOSP 通常已经把它标成 `uhid_device`。若是 `unlabeled`，再在 `sepolicy` 的 `file_contexts` 加：

```text
/dev/uinput    u:object_r:uhid_device:s0
```

### 2. init 服务

`init.gru.rc`：

```text
service stylusctrl /system/bin/stylusctld
    class late_start
    user root
    group root input uhid
    seclabel u:r:stylusctrl:s0
```

二进制放到 `/system/bin/stylusctld`，源文件要有执行位。例如在 `device.mk`：

```make
PRODUCT_COPY_FILES += \
    /path/to/StylusCtrl/out/stylusctld:system/bin/stylusctld
```

`late_start` 时 `/data` 已经挂上。应用目录可以晚一点才出现，守护进程每秒看一次配置文件的 mtime，文件晚到也会读。

没有 Magisk 时，配置路径是 `/data/user/0/app.stylusctrl/files/config.json`。`/data/adb/stylusctrl/config.json` 或 `/data/local/tmp/stylusctrl.json` 若已经可读，仍优先用那两个，这是 P620 和临时探针的路径。这台机器上两个都没有，所以走应用目录。

内屏页的开关写 `internal.enabled`。没打开时守护进程不抓笔，系统原来的即插即用还在。

### 3. sepolicy

`sepolicy/private/stylusctrl.te`：

```text
type stylusctrl, domain, coredomain;
type stylusctrl_exec, system_file_type, exec_type, file_type;

init_daemon_domain(stylusctrl)

allow stylusctrl input_device:dir r_dir_perms;
allow stylusctrl input_device:chr_file rw_file_perms;
allow stylusctrl uhid_device:chr_file rw_file_perms;

allow stylusctrl sysfs:dir r_dir_perms;
allow stylusctrl sysfs:file r_file_perms;

allow stylusctrl system_data_file:dir search;
allow stylusctrl app_data_file:dir { search getattr };
allow stylusctrl app_data_file:file { getattr open read };
```

`sepolicy/private/file_contexts`：

```text
/system/bin/stylusctld    u:object_r:stylusctrl_exec:s0
```

屏幕的像素宽高和旋转写在配置的 `display` 里。守护进程用它设置 `disp_w`、`disp_h`、`orientation`、`have_display`，不再执行 `dumpsys`。不要为这一处放行 shell。

外接笔的第二侧键走 `/dev/hidraw*`。`ueventd.gru.rc` 增加 `/dev/hidraw*    0660    system    input`。`stylusctrl.te` 允许读 `hidraw_device`。属主保持 `system`，`system_server` 还能打开。守护进程已经在 `input` 组里。

Android 16 若因为 ioctl 的 neverallow 编不过，先在 `stylusctrl.te` 加 `permissive stylusctrl;`，userdebug 上抓一次笔，再按 `dmesg` 里的拒绝收紧。预期要的是 `input_device` 上的 `EVIOCGRAB` 和读事件，以及 `uhid_device`（`/dev/uinput`）上的 `UI_SET_*` / `UI_DEV_CREATE`。

日志在 stderr。要进 logcat 就把服务改成 `service stylusctrl /system/bin/logwrapper /system/bin/stylusctld`。

### 4. 装上之后怎么确认

1. `ls -l /dev/uinput` 存在。
2. 安装 apk，打开内屏页，打开抓取，保存。
3. `logcat -s stylusctld` 或进程的 stderr 里有 `internal grabbed` 和 `hid-over-i2c 2D1F:0036 Stylus`。
4. `dumpsys input` 里虚拟设备是 `DIRECT`，配置文件是 `Vendor_2d1f_Product_0036.idc`，手指的 Elan 还在。
5. 只用电笔尖、按键 1、按键 2 各画一下。压感曲线改的是 `ABS_PRESSURE`。按键 1 走第一侧键，按键 2 走橡皮位。
