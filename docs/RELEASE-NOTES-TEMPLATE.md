# Release note template

Every release description must carry this block, in addition to its own notes.

---

## Google Play

The `-selfupdate` build downloads and installs its own updates. Google Play
Protect classifies that behaviour as a dropper, so it must **never** be uploaded
to Play. Only the `-play` build is eligible for Play submission; it checks for a
newer version but opens the release page instead of installing anything.

Install one or the other, not both. They share an application id and a signer,
so both can be installed over any earlier release and keep their settings.

Verify a download with `shasum -a 256 -c SHA256SUMS`.
