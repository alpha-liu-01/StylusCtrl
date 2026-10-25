# 给一台新机器写配置

StylusCtrl 已经认识两类笔，不用再改代码：

- 外接指针笔。内核带 `INPUT_PROP_POINTER` 和 `ABS_PRESSURE`，厂商号和产品号写在 `match` 里。坐标按配置做区域、裁切和旋转。轴的最小最大从这只笔自己的 `EVIOCGABS` 读，不写进配置。
- 内屏直连笔。没有 `internal.match` 时，名字恰好是 `sec_e-pen`，或者总线是 I2C、带 `INPUT_PROP_DIRECT` 和 `BTN_TOOL_PEN`、没有 `ABS_MT_SLOT`。坐标原样转发。三星笔槽、皮套、悬停距离、Home 只在这只节点自己报了这些事件时才送回去，不再认名字。

下一台机器如果落在这两类里，只要改 `match` 的厂商号和产品号，或者什么都不改。落在外面的，把下面这段交给会跑 `adb` 的人，让它按固定格式写一份 json，再在设置里的「设备」页加载。

不要写进配置的：

- 把一只 `INPUT_PROP_POINTER` 的板子标成内屏。那样会关掉区域映射。
- 轴的 15200、9500 这一类最大值。守护进程抓到节点时自己读。
- 侧键的扫描码。两台实机都是 `BTN_STYLUS` 和 `BTN_TOOL_RUBBER`。第二下如果内核没有边沿，守护进程自己在 HID 描述符里找 Invert。

## 提示词

把下面整段贴给负责探针的人。方括号里换成这台机器的序列号。

```
这台 Android 平板的 adb 序列号是 [序列号]。不要 adb root。不要改系统。只读。

目标是给 StylusCtrl 写一份配置。StylusCtrl 抓一只笔的 evdev，建一只虚拟设备交回 inputflinger。

请跑这些命令，把原始输出留下：

adb -s [序列号] shell getevent -p
adb -s [序列号] shell getevent -il

对每一只名字不像 StylusCtrl 的 event 节点，如果它有 ABS_X、ABS_Y、ABS_PRESSURE，再记：

- 名字
- EVIOCGID 的 bustype、vendor、product（getevent -p 开头的 bus、vendor、product）
- 有没有 INPUT_PROP_DIRECT，有没有 INPUT_PROP_POINTER
- 有没有 BTN_TOOL_PEN、BTN_STYLUS、BTN_STYLUS2、BTN_TOOL_RUBBER
- 有没有 ABS_MT_SLOT
- 有没有 ABS_DISTANCE、KEY_HOMEPAGE、SW_PEN_INSERTED、SW_MACHINE_COVER
- ABS_X、ABS_Y、ABS_PRESSURE 的 min 和 max

屏幕像素和旋转不用写。设置应用打开时会写 display。

然后只输出一个 json，不要解释。格式如下。没把握的字段不要写。

外接板（INPUT_PROP_POINTER，没有 INPUT_PROP_DIRECT）放进 match。vendor 和 product 是四位十六进制，不带 0x。同一只 USB 设备先出现另一个产品号再变成目标号的，match 写稳定的那个。name 只有在厂商号和产品号不够用时才写，而且必须是 getevent 的名字，不是 dumpsys 里多出来的后缀。

内屏笔（INPUT_PROP_DIRECT，笔尖已经铺满这块屏，没有 ABS_MT_SLOT）才写 internal.match。name 用 getevent 的整行名字。bus 用 i2c、usb、spi、bluetooth，或者十六进制。direct 为 true。pen 为 true 表示节点上有 BTN_TOOL_PEN。手指那块屏有 ABS_MT_SLOT，不要写进去。internal.enabled 为 true。

internal.pressure 和 internal.buttons 可以先抄外接那一份。mapping.mode 用 fit。buttons 里 BTN_STYLUS 和 BTN_TOOL_RUBBER 的 action 用 passthrough、shortcut 或 eraser。shortcut 的 keys 用大写键名，例如 CTRL、Z。

例子：

{
  "match": { "vendor": "0531", "product": "0104" },
  "mapping": { "mode": "fit", "crop": "center", "rotation": 0 },
  "pressure": { "curve": [0, 0, 0.4, 0.7, 0.4, 0.7, 1, 1], "deadzone": 0, "ceiling": 1 },
  "buttons": {
    "BTN_STYLUS": { "action": "shortcut", "keys": ["CTRL", "Z"] },
    "BTN_TOOL_RUBBER": { "action": "eraser" }
  },
  "tools": { "eraserWins": true },
  "internal": {
    "enabled": true,
    "match": { "name": "hid-over-i2c 2D1F:0036 Stylus", "bus": "i2c", "direct": true, "pen": true },
    "pressure": { "curve": [0, 0, 0.4, 0.7, 0.4, 0.7, 1, 1], "deadzone": 0, "ceiling": 1 },
    "buttons": {
      "BTN_STYLUS": { "action": "passthrough" },
      "BTN_TOOL_RUBBER": { "action": "eraser" }
    },
    "tools": { "eraserWins": true }
  }
}

没有内屏笔就不要写 internal.match，并把 internal.enabled 设成 false。
```

加载之后看守护进程日志。外接笔是 `match 0531:0104 only` 这种行。内屏规则被这份文件换掉时有 `internal match loaded`。没有这行时，仍是原来的 `sec_e-pen` 和 I2C 直连笔。
