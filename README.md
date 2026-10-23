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

The module zip is `out/stylusctrl.zip`. Flash it in Magisk and reboot. The settings app is `out/stylusctrl.apk`. Both can be found in releases.
