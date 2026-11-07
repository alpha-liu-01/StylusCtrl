# 预设，以及按设备和前台应用选用

日期：2026-11-06。只读调查。P620 用有线 adb，序列号 `R52X800YGRN`。没有 `adb root`。

## 结论

两件事都能做，而且都该先做成「选哪一份手感」，不要做成「换一份完全不同的守护进程配置」。

手感是现在 json 里这些字段：`mapping`、`pressure`、`buttons`、`tools`，加上内屏那份 `internal.pressure`、`internal.buttons`、`internal.tools`。屏幕尺寸、抓哪一只节点、内屏开没开，都不属于一份预设。

- 预设切换只动设置应用。库放在本机，选中的那一份写进守护进程已经在读的 `config.json`。守护进程不用改。
- 分设备和分应用要改守护进程。现在所有外接笔共用一份手感，所有内屏笔共用另一份，中间只按 `INPUT_PROP_DIRECT` 切开。前台包名要由设置应用写一个很小的文件，守护进程在已有的那次每秒检查里读它。

## 现在的配置

守护进程只读一个文件。P620 上是 `/data/adb/stylusctrl/config.json`。没有 Magisk 时是 `/data/user/0/app.stylusctrl/files/config.json`。mtime 变了就重读，最多大约一秒。P620 上进程是 root、`u:r:magisk:s0`，设置应用还能发 `SIGUSR1`。gru 上没有 root，只有这一秒的轮询。

重读成功之后，已经抓着的笔不会拆掉重建。`cfg_loaded` 本来就是 1，虚拟设备留着。按住的快捷键会先松开。下一帧报告用新的曲线、侧键和区域。一笔还没抬起来时，映射可以在这一帧跳一下。

解析器不认识的键会跳过。坏掉的已知值才会整份拒绝、沿用上一份。所以新字段可以先写进文件，旧的守护进程仍然只看见现在这套手感。

`view_for_pen` 只看这只笔是不是 `DIRECT`。两只外接板子会用同一份 `mapping` 和 `pressure`。`match` 也只有一组厂商号和产品号，对不上的外接节点不会抓。同时抓取的上限是 4。

## 这次在 P620 上看到的前台

`ro.build.type` 是 `userdebug`。shell 不用 su 就能读窗口和用法统计。当时设置应用开着：

```
mCurrentFocus=Window{b69ed4e u0 app.stylusctrl/app.stylusctrl.MainActivity}
topResumedActivity=ActivityRecord{24127918 u0 app.stylusctrl/.MainActivity t7}
```

`dumpsys usagestats` 里同一段有成对的事件，包名就是应用包名：

```
time="2026-11-06 13:20:13" type=ACTIVITY_RESUMED package=app.stylusctrl class=app.stylusctrl.MainActivity
time="2026-11-06 13:21:16" type=ACTIVITY_PAUSED package=app.stylusctrl class=app.stylusctrl.MainActivity
time="2026-11-06 13:21:16" type=ACTIVITY_RESUMED package=com.android.systemui class=com.android.systemui.usb.UsbDebuggingActivity
time="2026-11-06 13:21:18" type=ACTIVITY_RESUMED package=app.stylusctrl class=app.stylusctrl.MainActivity
```

普通应用没有 dump 权限，不能自己跑 `dumpsys`。gru 的守护进程域也不能加 shell。两条机器都能走的是：设置应用向用户要一次「使用情况访问」，用 `UsageStatsManager` 看 `ACTIVITY_RESUMED`，再把包名写到守护进程已经能读的目录里。

## 预设切换

一份预设是一份手感，带一个本机名字。库放在设置应用的文件目录，例如 `presets/<id>.json`，外加一份名单。P620 上另存一份到 `/data/adb/stylusctrl/presets/`，卸掉或清掉应用数据之后还能拷回来。守护进程不读这个目录。

切换时设置应用读出那份手感，写回正在生效的 `config.json`，并留下文件里原有的这些字段：

- `display`
- `match`，以及以后的 `match.name`
- `internal.enabled` 和 `internal.match`

只换手感时，抓着的节点不变，屏幕尺寸也不被一份旧预设盖掉。写完之后 P620 发 `SIGUSR1`，gru 等下一秒的 mtime。

界面放在设备页已有的「加载配置」旁边：列出预设、保存当前手感、改名、删除、选用。选用和现在的保存走同一条写入。

内屏笔继续忽略 `mapping`。一份预设里可以同时有外接区域和内屏曲线；用在内屏上时，守护进程只取内屏那一组，这和现在 `view_for_pen` 的行为一样。

### 实现步骤

1. 定预设文件的字段，就用现在的 `mapping`、`pressure`、`buttons`、`tools`，再加可选的 `internal` 手感。不要放 `display` 和 `match`。
2. 设置应用增加名单和单份预设的读写。第一次打开时，把当前 `config.json` 里的手感存成一份，名字用「当前」。
3. 选用时合并进 `config.json`，保留上一节列出的字段，然后走现有的 `RootStore.write`。
4. 设备页加列表和四个动作：保存、改名、删除、选用。删除正在用的那份时，生效文件不动，只是库里少一份。
5. 在 P620 上改一条曲线再选用另一份，不重插笔，确认绘画应用里下一笔已经换成新曲线，虚拟设备没有被拆掉。gru 上同样做一次，接受大约一秒的延迟。

### 实现笔记

2026-11-06：库在设置应用的 `presets/`。设备页可以保存、改名、删除、选用。选用会把这份手感写成每只设备的基准，并写回 `config.json`，屏幕尺寸和匹配留下。删除正在用的那份时，顶层手感不动，守护进程找不到对应预设就继续用顶层这份。

### 事件



## 按设备和前台应用选用

每个设备先有一份基准预设，用在所有没有单独记录的应用上。设备是内屏、数位板 A、数位板 B 这种已经抓到的笔，不是手机里的蓝牙设备。某个应用需要不一样的曲线或侧键时，只在这个设备下记一条覆盖，包名对上才用。

生效的手感是：这只笔属于哪个设备，前台包名在这个设备下有没有覆盖。没有覆盖就用基准。

设置应用负责两件与守护进程无关的事：编辑库，以及在前台变化时写下包名。守护进程负责在报告送出前选中那一份手感。不要在每次切换应用时重写整份 `config.json`。那样会和正在编辑的界面抢文件，也会每秒松开快捷键。

前台文件例如 `foreground`，和 `config.json` 放在同一目录，内容是一行包名。设置应用拿到使用情况访问之后，用一个前台服务看 `ACTIVITY_RESUMED`，包名变了才写。几秒没写就视为观察停了，守护进程退回该设备的基准，避免卡在最后一次看到的应用上。

这些包名不触发覆盖：`app.stylusctrl` 自己、`com.android.systemui`、启动器。笔在测试页上时沿用切换前的那份。分屏时用法统计里可以同时有两个 `ACTIVITY_RESUMED`，笔实际进入的是焦点窗口。第一版用最后一次 resumed 的包名。P620 上若发现分屏选错，再让 Magisk 域的守护进程读 `mCurrentFocus`，gru 不加这条。

同一厂商号和产品号的两只板子，第一版合成一个设备。要分开时再读 `EVIOCGUNIQ`。CTC4110WL 的 uniq 是 `4DH01F1000849`，节点上有这个字符串。

建议的文件形状。没有 `devices` 时，守护进程保持今天的行为：

```json
{
  "display": { "width": 2000, "height": 1200, "rotation": 1 },
  "match": { "vendor": "0531", "product": "0104" },
  "presets": {
    "writing": {
      "mapping": { "mode": "fit", "crop": "center", "rotation": 0 },
      "pressure": { "curve": [0, 0, 0.4, 0.7, 0.4, 0.7, 1, 1], "deadzone": 0, "ceiling": 1 },
      "buttons": { "BTN_STYLUS": { "action": "passthrough" }, "BTN_TOOL_RUBBER": { "action": "eraser" } },
      "tools": { "eraserWins": true }
    }
  },
  "devices": [
    {
      "id": "internal",
      "match": { "name": "sec_e-pen" },
      "baseline": "writing",
      "apps": { "com.steadfastinnovation.android.projectpapyrus": "sketch" }
    },
    {
      "id": "wacom-one",
      "match": { "vendor": "0531", "product": "0104" },
      "baseline": "writing",
      "apps": {}
    }
  ]
}
```

顶层的 `mapping`、`pressure`、`buttons` 仍然是「现在编辑的那一份」，给还没读 `devices` 的守护进程用，也给定预设之前的界面用。`devices[].match` 和现在的 `internal.match`、顶层 `match` 用同一套字段。

### 实现步骤

预设切换做完再做这一节。

1. 守护进程在读到 `devices` 时，按每只已抓笔的身份选设备。内屏用 `internal.match` 那套比较，外接用厂商号、产品号和可选名字。对上的设备用它的 `baseline` 预设代替现在这一份共用手感。对不上的外接笔仍然不抓。
2. 两只不同厂商号的笔同时插上时，各自用自己的基准。拔掉一只，另一只不变。
3. 设置应用申请 `PACKAGE_USAGE_STATS`，做一个前台服务，把最后一次 `ACTIVITY_RESUMED` 的包名写入 `foreground`。忽略上一节列出的包名。
4. 守护进程在每秒的配置检查里看这个文件的 mtime 和内容。包名能在该设备的 `apps` 里找到预设，就用覆盖；找不到用基准。文件超过数秒没更新，用基准。
5. 按住侧键时不要换快捷键目标。抬笔之后的下一帧再用新预设。区域可以在下一帧就换。
6. 在 P620 上准备两份预设，把 Concepts 的包名 `com.steadfastinnovation.android.projectpapyrus` 配成覆盖，启动器和其他应用走基准。切到 Concepts，下一笔用覆盖；回到桌面，下一笔用基准。gru 上用同一套文件再走一次，确认没有 dump 权限也能换。

### 实现笔记

2026-11-06：守护进程读到 `devices` 之后，外接笔必须对上其中一条，内屏笔仍按原来的规则抓。对上的设备用 `baseline`，`foreground` 里的包名有覆盖才换。文件超过 5 秒没更新就退回基准。笔尖或侧键还按着时，压感和快捷键留在按下前的那份，区域马上换。观察前台要在系统设置里允许使用情况访问，服务把包名写到配置旁边的 `foreground`。启动器会清空包名，StylusCtrl 自己和系统界面不会改掉上一个绘画应用。

应用页选择内屏或外接，列出已安装的应用。每一行可以停在默认，或者切到自定义后从下拉选一份预设。加号按当前基准复制出一份新预设，左边的铅笔改显示名，右边的垃圾桶删掉这份预设以及指向它的覆盖。全局那一份不能从这里删。其它页编辑的就是这一份：全局时改这只设备的基准，自定义时改该应用的那一份。标题下写明对象，例如「内屏在「Squid」的配置「预设1」」。

2026-11-06：P620 上 SpeedyNote（`org.speedynote.app`）的「搜索」已经写进内屏覆盖，内屏第一侧键是 Ctrl+F。使用情况访问当时是默认拒绝，观察服务没有启动，所以 `foreground` 不会更新，守护进程一直用基准。应用页会写明这一点；允许之后服务随应用恢复和开机启动。

观察前台改在后台线程写文件，不再占着界面线程。应用列表只创建屏幕上看得到的几行。

2026-11-06：最近任务会让 `com.android.launcher3` 的 QuickstepLauncher 恢复一两秒，退回原应用时经常没有新的 `ACTIVITY_RESUMED`。观察服务若把这次恢复当成回桌面，覆盖会被清掉，数位板方向退回全局。现在只有启动器还在前台、并且上一个应用已经停止时，才退回基准。

主机测试覆盖了预设选择和厂商号过滤。下面这步要在平板上做。

### 事件


