# gru：没有 Magisk 时读不到配置

日期：2026-10-24。实机 `adb connect 10.0.0.108:5555`。系统是 LineageOS 23.2，`getenforce` 是 Enforcing。这次启动的构建时间是 Sat Oct 24 15:58:38 EDT 2026，super SHA-256 `46397948f5be0063ee0b75f1a271bdaa3e009382a4a7970f15904e4e8df3ef15`。`stylusctld` 以 root 跑在 `u:r:stylusctrl:s0`，`/dev/uinput` 已经在。

## 结论

Lineage 这边上次挡住目录搜索的 MLS 已经放开。守护进程现在能走进 `/data/user/0`，但读不了配置文件。文件是应用自己的 `0600`，应用目录是 `0700`。内核先要 `dac_read_search`，再要 `dac_override`，两条都被拒绝。配置一直读不进去，`internal.enabled` 不生效，所以压感曲线和快捷键完全没变化。

不要在 sepolicy 里放行这两个 capability。`domain.te` 把 `dac_override` 限制在一份名单里，注释写明优先改文件权限，而不是给域加能力。`stylusctrl` 的允许规则是所有 `app_data_file`，不是只有 `app.stylusctrl`。放行之后这个进程可以无视权限位，去读任意应用的私有文件。

改 `writeLocal()`。保存之后把应用目录改成别人可以穿过，把 `config.json` 改成别人可以读。`files/` 已经是 `0771`，不用动。

## 这次启动的日志

`stylusctld` pid 3142，从 16:08:59 起每秒两条，一直到测试结束：

```
avc: denied { dac_read_search } for comm="stylusctld" capability=2 scontext=u:r:stylusctrl:s0 tcontext=u:r:stylusctrl:s0 tclass=capability
avc: denied { dac_override } for comm="stylusctld" capability=1 scontext=u:r:stylusctrl:s0 tcontext=u:r:stylusctrl:s0 tclass=capability
```

kmsg 里对应的一行是：

```
/data/user/0/app.stylusctrl/files/config.json: Permission denied
```

没有 `app_data_file` 或 `system_data_file` 的新拒绝。上一轮的目录 `search` 拒绝没有再出现。

`run-as app.stylusctrl` 看到的权限：

| 路径 | 模式 | 属主 |
| --- | --- | --- |
| `/data/user/0/app.stylusctrl` | `0700` | `u0_a144` |
| `/data/user/0/app.stylusctrl/files` | `0771` | `u0_a144` |
| `.../files/config.json` | `0600` | `u0_a144`，767 字节 |

`/data`、`/data/user`、`/data/user/0` 对 other 都有执行位，根进程穿得过。停在应用目录。

配置内容已经是内屏打开的那一份。`internal.enabled` 为 true，内屏压感曲线和 `BTN_STYLUS` 快捷键都写在 `internal` 里。顶层 `match` 是 `0531:0104`。`try_add()` 里厂商号比对只对非内屏笔做，这支笔不受它影响。文件能读之后就会按内屏那一份抓。

## 要改的代码

`app/src/main/java/app/stylusctrl/RootStore.java` 的 `writeLocal()`。`renameTo` 成功之后：

1. `context.getDataDir().setExecutable(true, false)`。`0700` 变成 `0711`。别人只能穿过，不能列出。
2. `out.setReadable(true, false)`。`0600` 变成 `0644`。
3. `context.getFilesDir().setExecutable(true, false)`。现在已是 `0771`，加上这一步避免以后安装把 `files/` 收成 `0700`。

Magisk 那条 `chmod 644` 留在 root 写入 `/data/adb/stylusctrl/config.json` 的路径上，不用动。

守护进程每秒 `stat` 这个路径。权限改对之后，下一次 `stat` 成功就会重读，不用发信号，也不用换 super。当前这台机器上的守护进程已经是会重读的那一版。

## 改完怎么确认

1. 再保存一次。`run-as app.stylusctrl ls -ld` 看到应用目录是 `0711`，`config.json` 是 `0644`。
2. `logcat -b kernel` 里不再出现 `dac_read_search` 和 `dac_override`。
3. 出现 `internal grabbed` 和 `hid-over-i2c 2D1F:0036 Stylus`。
4. `dumpsys input` 里多一只名字以 `StylusCtrl` 开头的 `DIRECT` 设备。只用电笔尖、按键 1、按键 2 各画一下。压感走 `internal.pressure`，按键 1 走第一侧键。

## 2026-10-24 16:35 这次启动

构建时间 Sat Oct 24 16:26:18 EDT 2026。`getenforce` 是 Enforcing。`stylusctld` pid 3144，域 `u:r:stylusctrl:s0`。`/dev/uinput` 是 `0660 uhid:uhid`，标签 `uhid_device`。内核日志里没有 `stylusctrl` 的 `avc`。

权限已经按上一节改过。应用目录是 `0711`，`files/` 是 `0771`，`config.json` 是 `0644`、767 字节。16:35:41 进程刚起来时文件还是旧的 `0600`，打了一行 `Permission denied`。16:37:56 保存之后读到了配置，后面四次重读都成功，`dac_read_search` 和 `dac_override` 没有再出现。

16:37:56 独占成功。内核输入子系统登记了 `StylusCtrl hid-over-i2c 2D1F:0036 Stylus`。`getevent -p` 对得上：

| 节点 | 名字 | 属性 |
| --- | --- | --- |
| `/dev/input/event3` | `hid-over-i2c 2D1F:0036 Stylus` | 已被独占。`INPUT_PROP_DIRECT`。轴 `0–14746`、`0–19661`、压力 `0–4095`。键含 `BTN_STYLUS2`（`0x14c`） |
| `/dev/input/event9` | `StylusCtrl hid-over-i2c 2D1F:0036 Stylus` | `INPUT_PROP_DIRECT`，轴和 event3 相同。当时坐标 `7102,13184`，和被独占的那只一样 |
| `/dev/input/event8` | `StylusCtrl keys` | 只有 `KEY_LEFTCTRL` 和 `KEY_Z`，对应配置里的 `CTRL+Z` |

`dumpsys input` 里这只虚拟笔是 `DIRECT`，`IsExternal: false`，来源含 `STYLUS`，配置文件是 `Vendor_2d1f_Product_0036.idc`。Elan 触摸屏还在。快捷键设备重建过三次（`input9`、`input11`、`input12`），`sync_keyboard()` 会先拆掉上一只，最后只剩 event8。

内置笔不走 `open_pen_hidraw()`。这支笔的第二侧键已经在 event3 上是 `BTN_STYLUS2`，当前配置又把橡皮位放在 `BTN_TOOL_RUBBER`，所以这条路径这次用不上。

读内核日志时，`stylusctld: ` 前缀和正文里的冒号会被拆成两行。`2D1F:0036` 把独占那一行切成了 `virtual /dev/input/event9` 和下一行的 `, x 0-14746, y 0-19661, pressure 0-4095`。

### 还在每秒失败的一件

外接那一份的 `mapping.mode` 是 `fit`。`need_display()` 只看这一份，不看有没有外接笔。内屏笔被独占之后，`refresh_display()` 每秒 `popen("dumpsys display")`。`sh` 拉起了，`/system/bin/dumpsys` 返回 `Permission denied`，stdout 是空的，于是打 `dumpsys has no internal display`。从 16:37:56 到 16:41:18 有 153 次。旁边还有 `logd: HexToBytes: Invalid size`。16:41:18 出现 `audit_lost=4 audit_rate_limit=5 audit_backlog_limit=64`。

内屏坐标原样转发，不读屏幕尺寸。压感和快捷键因此不受影响，和这次的测试一致。外接笔的等比裁切和拉伸拿不到屏幕宽高，映射不会生效。

不要为了这一处给 `stylusctrl` 放行 `dumpsys`。那要再允许执行 shell，并让它通过 binder 问 SurfaceFlinger。内屏笔打开时不必每秒调用。`dumpsys` 失败之后应当停下来，而不是每秒再试。

`/dev/input/event4`（`hid-over-i2c 2D1F:0036`，没有 `INPUT_PROP_DIRECT`）没有被独占。同一份 `Vendor_2d1f_Product_0036.idc` 仍让 inputflinger 把它标成 `DIRECT`。它的轴停在 `0,0,0`，虚拟笔和 event3 停在 `7102,13184`，这次没有把落笔再送进一套。

### 同一次日志里和笔无关的

16:36:12，`init` 的四条 `exec_background` 没有域转换，`/system/bin/sh` 是 `shell_exec`，从 `u:r:init:s0` 过不去。`pm enable com.android.bluetooth`、`boot_settings.sh`、两处 `settings put` 都没跑。`tinymix2` 是直接执行的，跑完了。16:39:54 休眠时有一次 `clk_spi1 already disabled`，调用栈在 `rockchip_spi_suspend`，不是 `stylusctld`。

## CPU 和发热

16:54 测的，开机后 1179 秒。`CLK_TCK` 是 100。`stylusctld` 仍是 pid 3144，单线程。

| 进程 | 自己的 CPU | 已退出的子进程 | 占一个核 |
| --- | --- | --- | --- |
| `stylusctld` | 2.77 秒 | 18.17 秒 | 自己 0.23%，加上子进程 1.8% |
| `app.stylusctrl` | 58.1 秒 | 0 | 进程活着的大约 17 分钟里，平均一个核的 6% |
| `system_server` | 93.8 秒 | | 8.0% |
| `surfaceflinger` | 50.7 秒 | | 4.3% |
| `com.android.systemui` | 27.4 秒 | | 2.3% |
| `logd` | 5.5 秒 | | 0.47% |

子进程的 18 秒就是每秒拉起又马上失败的 `sh` 和 `dumpsys`。测的那 10 秒里，守护进程自己只增加了 1 个 tick（10 毫秒），子进程增加了 0.23 秒。设置应用这 10 秒是 0。`dumpsys cpuinfo` 里 16:52–16:53 那一分钟，整机合计 2.9%（六个核加起来的 600% 里），`stylusctld` 记了 0.2%。

有一次快照看到守护进程在 `D`（不可中断睡眠），当时没有子进程。随后两次是 `S`。这和每秒一次失败的 `exec` 对得上，进程没有卡死。

大核 `cpu4`、`cpu5` 用 `schedutil`。开机到这次采样，`cpu4` 有 38% 的时间在 2.016 GHz，56% 在 816 MHz。小核 `cpu0` 有 78% 的时间在 600 MHz。机身发烫对得上大核在 2 GHz 上的这段时间。`stylusctld` 自己的 2.8 秒摊不满这段满频。停掉每秒一次的 `dumpsys` 能拿回大约一个核的 1.5%，日志也不再每秒刷。这点占不满这次开机里大核的满频时间。

## 外接 Wacom One，2026-10-24 18:25

同一次 super，构建时间仍是 Sat Oct 24 16:26:18 EDT 2026。板子插上之后内核先报 `idVendor=0531, idProduct=0100`，3 秒后重新枚举成 `0531:0104`。两次的产品名都是 `Wacom Co.,Ltd. Wacom One pen tablet small`。配置里的 `match` 是 `0531:0104`，所以 `0100` 那一下不会被抓。

18:25:12 守护进程抓到了笔接口：

```
grabbed /dev/input/event11 "Wacom Co.,Ltd. Wacom One pen tablet small", virtual /dev/input/event13
x 0-15200, y 0-9500, pressure 0-4095
```

`getevent -p` 里 event11 和 event13 都是 `INPUT_PROP_POINTER`，当时坐标都是 `9325,1850`。虚拟设备在转发。Elan 和内屏笔还在。数位板光标能走，是因为坐标原样交回 inputflinger，由系统自己把指针铺到屏幕上。

### 界面上看不到

`RootStore.readDevices()` 在 `id -u` 不是 0 时把 `error` 设成 `no_root` 并直接返回，不跑 `getevent`。没有 Magisk 时 libsu 的 shell 不是 root，设备列表是空的。守护进程已经按 `0531:0104` 独占，设置页不知道这件事。

没有 root 时用 `InputManager.getInputDeviceIds()`。`InputDevice` 有名字、厂商号、产品号、来源和轴范围。名字以 `StylusCtrl` 开头的跳过，和 `classify()` 现在的规则一样。`read()` 里读屏幕尺寸的 `dumpsys display` 也只在 root 分支里，一并改成 `Display.getRealSize()` 和当前旋转。

### 区域、裁切、旋转没有作用

外接配置是 `fit`。`map_point()` 在 `have_display` 为假时原样返回。`have_display` 只由 `dumpsys display` 置位，而那条命令每秒被拒绝，见上一节。18:27:42 和 18:27:57 的日志有 `tablet rotation 90` 和 `tablet rotation 180`，配置读进去了，坐标没改。18:27:43 有一次 `/dev/input/event11 SYN_DROPPED`。

压感不看屏幕尺寸。`apply_axes()` 对非内屏笔照样调用 `pressure_out()`。侧键 `BTN_STYLUS`（`0x14b`）和橡皮 `BTN_TOOL_RUBBER`（`0x141`）在 event11 上，快捷键不依赖 hidraw。倾斜在 `rotation` 非 0 时会转。用户能感觉到的「控制没用」，是区域、裁切和坐标旋转。

把当前屏幕的像素宽高和旋转写进配置，守护进程用这份数值设置 `disp_w`、`disp_h`、`orientation`、`have_display`，不要再为了这个去执行 `dumpsys`。内屏笔本来就不改坐标，外接笔没有打开时也不要每秒调用。

### 第二侧键

event11 没有 `BTN_STYLUS2`。第二下走 HID Invert，要开 `/dev/hidraw1`。节点是 `0660 system:system`，标签 `hidraw_device`。守护进程是 root，但不在 `system` 组，也没有 `dac_override`，18:25:12 的日志是 `open /dev/hidraw1 failed: Permission denied`。

这一处在 Lineage 侧改：`ueventd` 把 `/dev/hidraw*` 设成 `0660 system input`，`stylusctrl.te` 允许读 `hidraw_device`。属主仍是 `system`，`system_server` 还能打开。守护进程已经在 `input` 组里。
