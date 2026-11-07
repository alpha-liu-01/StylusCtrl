# StylusCtrl


Maintainer: alpha-liu-01

StylusCtrl maps a drawing tablet's area, pressure curve, and side buttons on Android. The settings screen is a normal app. The Magisk module's `stylusctld` is what rewrites the input. The virtual device stays a pointer, and no IDC is installed.


## Screenshots

<div align="center">
  <img src="https://i.imgur.com/nvPPpZ1.png" alt="Mapping" width="32%">
  <img src="https://i.imgur.com/b0jLhba.png" alt="Pressure" width="32%">
  <img src="https://i.imgur.com/bbTihJi.png" alt="Shortcuts" width="32%">
</div>

## Build

```sh
tools/build.sh
tools/pack-module.sh
tools/build-apk.sh
```

## Installation

`tools/pack-module.sh` writes `out/stylusctrl-arm64.zip` and `out/stylusctrl-arm.zip`. Flash the one that matches the device in Magisk and reboot. `tools/build-apk.sh` writes `out/stylusctrl-arm64.apk` and `out/stylusctrl-arm.apk`. The app has no native code, so either apk installs on either device. The zip is the one that has to match: it carries `stylusctld`.

## License

StylusCtrl is [MIT](LICENSE), copyright alpha-liu-01.

`magisk/stylusctrl/META-INF/com/google/android/update-binary` is Magisk's `module_installer.sh`, copied unchanged and still under GPL-3.0. See [NOTICE](NOTICE).
