# Preview signing and application data

Android updates require the matching application identity and an authorized signing identity. The v0.6/v0.7 private debug key was kept only in the local workspace and excluded from source archives and deliverables. Workspace maintenance removed it. The saved APK contains its public certificate, which cannot replace its private key.

The 0.8.0 build therefore uses app.independentwallet.android.syncpreview and a fresh private local debug key. It installs beside app.independentwallet.android.resumetest. It is not an in-place update or automatic migration. Keep the old installation and data. Pair Keystone again or import a written stagenet backup into this preview; the two apps have independent node settings and encrypted cache/vault files. Do not uninstall an old wallet to resolve a signing mismatch.

Version 0.10.0 retains the 0.8 package and local certificate and can update it in place. It still cannot update the older resumetest package.

Version 0.10.1 retains that same package/certificate with version code 14. It can update Sync Preview v0.8 through v0.10.0 in place; do not uninstall or clear data to apply this update.

Version 0.10.2 retains the same package/certificate with version code 15 and can update v0.10.1 in place. It does not migrate the separate older resumetest installation.

For durable releases, the developer should create and protect the signing key in storage they control outside a transient build workspace, keep an independent protected backup, and use that same identity for future signed builds. README.md describes the local development-key setup. Never commit signing keys or include them in a source ZIP. No private signing key is uploaded as part of this release.

No claim is made that a temporary debug signing workflow is suitable for production or secures future update availability. Production signing needs an owner-managed workflow before publication.

Version 0.10.3 retains the same Sync Preview package/certificate with version code 16. Install over v0.8 through v0.10.2 without uninstalling or clearing data.

Version 0.11.0 retains the Sync Preview certificate/package with code 17. Update in place; the original authenticated wallet/setup files and aliases stay readable. Mainnet copies use separate encrypted files and aliases.

Version 0.11.1 keeps this package/certificate with code 18. Update in place to retain wallet, setup, node and scan data.

Version 0.11.2 retains this package/certificate with code 19. Install as an update; all encrypted wallet/setup/node/cache formats remain compatible.

Version 0.11.3 uses Android code 20 and the retained Sync Preview certificate. Install in place over build 19. The existing encrypted node file gains a backward-reading IWN3 payload for remembered wallet/node/route preferences; wallet/setup/cache formats and keys remain unchanged.

Version 0.11.4 retains the Sync Preview package/certificate with code 21. Install over build 20 without uninstalling or clearing data. IWN4 stores up to five named user endpoints inside the existing encrypted node file. Old single-node/IWN2/IWN3 records remain readable. Wallet/setup/native journal formats are unchanged.

## 0.11.5 signing blocker

Automated workspace pruning removed the private Sync Preview keystore. The source archive excludes it by design; the last signed APK contains only its public certificate. Source/build checks and an unsigned candidate can be produced with `-PunsignedPreview=true`. A matching original keystore must be restored at `.private-signing/preview-debug.keystore` before building an in-place signed update. Expected certificate SHA256: `63b5045ee6e8a9de7fe90fb05f18ea9397a248360ed4ee496b16b1519c7f31e4`. Do not create a new key under that path and claim update compatibility. Do not uninstall/clear the current wallet. No private key is uploaded in deliverables.

## Preview 2 — explicitly separate replacement channel

The user subsequently requested a new signed APK. Build 23 deliberately uses app.independentwallet.android.syncpreview2, label Independent Preview 2, and a new protected signing identity, certificate SHA256 `01afd9a36efbb0d6573d8f12764164a14af3d99faa4a97ccfce45dff82cd52b5`. It installs beside the existing wallet; it cannot update or automatically copy the old app. The original-key blocker above still applies to the original package. The new APK-signing key and separate private recovery file are backed up separately for the owner, outside source/APK/report artifacts. See PREVIEW2_SIGNING.md and current VALIDATION.md. Keep old wallet data until recovery is verified.
