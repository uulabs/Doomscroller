# Release note template

Every release description carries this block, in addition to its own notes.

---

## Which file do I want?

| File | What it does |
| --- | --- |
| `...apk` | The app. When there is a new version it downloads it and asks Android to install it, so updating is one tap. |
| `...-no-self-update.apk` | The same app, but it never downloads or installs anything. When there is a new version it opens this page and you install it yourself. |

Android sometimes warns about apps that install their own updates. It has not
warned during testing here, but if you would rather not allow that at all, take
the second file. Nothing else about the app changes.

Both are the same app with the same signer, so either one installs over the other
and keeps your settings.
