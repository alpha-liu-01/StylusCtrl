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
