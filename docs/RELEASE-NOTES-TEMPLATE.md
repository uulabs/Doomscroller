# Release note template

Every release description carries this block, in addition to its own notes.

---

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
