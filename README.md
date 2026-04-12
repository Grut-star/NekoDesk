# NekoDesk (Based on NekoBox)

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![Releases](https://img.shields.io/github/v/release/ТВОЙ_ЮЗЕРНЕЙМ/NekoDesk)](https://github.com/ТВОЙ_ЮЗЕРНЕЙМ/NekoDesk/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

Universal proxy toolchain, empowered by sing-box. 


## Downloads

[![GitHub All Releases](https://img.shields.io/github/downloads/Grut-star/NekoDesk/total?label=downloads-total&logo=github&style=flat-square)](https://github.com/Grut-star/NekoDesk/releases)

[Download from GitHub Releases](https://github.com/Grut-star/NekoDesk/releases)

## Changelog & Community

* **Telegram Channel:** [Link to your Telegram](https://t.me/#)
* **Project Documentation:** [NekoDesk Docs](https://#.github.io)

## Supported Proxy Protocols

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* VMess
* Trojan
* VLESS
* AnyTLS
* ShadowTLS
* TUIC
* Hysteria 1/2
* WireGuard
* Trojan-Go (trojan-go-plugin)
* NaïveProxy (naive-plugin)
* Mieru (mieru-plugin)

## Supported Subscription Formats

* Widely used formats (like Shadowsocks, ClashMeta, and v2rayN)
* sing-box outbound

*Note: Only resolving outbound, i.e., nodes, is supported. Information such as routing rules is ignored.*

## FAQ

**How does NekoDesk differ from the original NekoBox?** <br/>
NekoDesk is an independent fork of NekoBox. This project was created to match my own vision of the project's development. In particular, the inclusion of a number of additional functions and the closing of a number of vulnerabilities that many consider minor and/or the correct operation of the application.
**What different features:<br/>
-*Physical networks are strictly hidden from filtered applications (this may affect WebRTC; testing is required).
-*Support for application-based filtering has been added (in VPN/Proxy modes).
-*The local port is now opened ONLY if we are not in VPN mode or the user has EXPLICITLY specified its use in the application.
-*Added a kill switch via the default setBlocking(true)

## Credits

This project stands on the shoulders of giants. Special thanks to the original creators and the open-source community:

- **[MatsuriDayo/NekoBox](https://github.com/MatsuriDayo/NekoBoxForAndroid)** - The original project NekoDesk is based on.
- **[SagerNet/sing-box](https://github.com/SagerNet/sing-box)** - Core
- **[shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)** - Android GUI
- **[SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)** - Android GUI
- **[MetaCubeX/Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)** - Web Dashboard
