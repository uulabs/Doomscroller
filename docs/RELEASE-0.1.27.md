# UU Doomscroller 0.1.27 — recovery release

Restores the complete 0.1.25 application implementation, including its original Doomscroller playback bar and TikTok playback handling. The only application-source difference from 0.1.25 is the version number: 0.1.27, versionCode 28. This lets installations already on 0.1.26 update normally while keeping settings.

0.1.26 was withdrawn after reports of muted TikTok playback and exposed provider overlays. Its new features are rolled back in this recovery release. They will not be reintroduced as part of this recovery.

The recommended file is `UUDoomscroller-0.1.27.apk`; `UUDoomscroller-0.1.27-selfupdate.apk` is its identical compatibility alias. The manual-update alternative is `UUDoomscroller-0.1.27-no-self-update.apk`. All share the existing signer and application ID. Verify downloads with SHA256SUMS.



## Two files, and one honest warning

UU Doomscroller is not on Google Play and never will be; it is distributed here.

| File | Use |
| --- | --- |
| `...apk` | The app. Checks for a newer version, downloads it and hands it to the Android installer. |
| `...-no-self-update.apk` | The same app, except it never installs anything: it opens this page and you install it yourself. |

**On Play Protect.** The self-updating build has not been flagged in testing, so
it is treated as safe by the devices used here. That is Google's verdict to
change, and "not flagged yet" is not the same as "safe": an app that downloads
and launches an installer is, by shape, doing what a dropper does.

If you would rather not grant that, use the second file. The only function you
give up is in-app updating. Both share a signer and application id, so they
install over one another and keep your settings.
