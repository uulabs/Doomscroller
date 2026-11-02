# Release note template

Every release description carries this block, in addition to its own notes.

---

## Two APKs, and one warning

UU Doomscroller is not on Google Play and never will be; it is distributed here.

| File | Use |
| --- | --- |
| `...-standard.apk` | Installs nothing by itself. It reports a newer version and opens this release page. |
| `...-selfupdate.apk` | The same app, but it downloads updates and hands them to the Android installer. |

**Google Play Protect will flag the self-update build as harmful.** That warning
is not a false positive: downloading and launching an installer is exactly how a
dropper behaves, so Play Protect cannot distinguish this app from one. The choice
is whether to trust it.

If you do not, install the standard build. The only function you give up is
in-app updating - it will send you to this page to download each new version.

Both builds share an application id and a signing key, so either installs over
any earlier release and keeps your settings.
