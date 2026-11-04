# Resume, explicit discard and manual copy retention — 0.6.1

Implemented for the user's request to keep progress when copying and leaving the app. Backgrounding now pauses a created wallet's setup instead of discarding that wallet. The lock screen offers Resume setup. The app also stops automatically clearing an explicitly copied recovery phrase on exit, lock, closing its page or a timer. Clear copy remains an explicit ownership-checked action.

## Starting over deliberately

Discard setup is a separate choice on the paused, backup, verification and unfinished dashboard screens. Its dialog offers Keep setup or Discard and start over, then requests Android device credentials. It never requires knowing the old seed. Canceling either confirmation preserves the draft; ordinary leaving/locking still pauses it.

Before requesting credentials, the app locks the native owner/display, finishes queued checkpoint writes and captures a SHA256 fingerprint of the exact encrypted draft. Only a current credential ticket may commit deletion. Under the shared setup-file monitor, deletion rechecks that fingerprint and verifies the file was removed. A changed/replaced draft rejects the stale request. No setup decryption is needed, so a corrupt encrypted payload or unavailable wrapping key does not by itself prevent starting over. Unreadable/oversized storage still fails closed instead of guessing which data to remove.

Discard removes only the unfinished setup file, not a completed wallet, selected node, sync/payment cache or clipboard text. It is unavailable when a completed wallet is present, and that guard is checked again before deletion. After successful removal the app returns to wallet creation/import; it does not silently generate a replacement. Recovery of the discarded wallet requires its existing recovery phrase/private key or hardware wallet. File deletion is not a guarantee of forensic secure erasure or rollback protection.

## Durable progress

Creation/import first requests Android device credentials. The native wallet is constructed offline and an encrypted setup checkpoint is committed before its keys or address are displayed. Setup has its own setup-v1.bin in noBackupFilesDir, separate from the completed wallet-v1.bin. Existing/corrupt/conflicting files are not silently replaced.

The native IWS1 checkpoint contains the existing key snapshot plus the backup-verification flag and original challenge positions. Opening it preserves an unfinished backup as unverified. The completed-wallet export and open-online gate still reject an unverified generated wallet. The standard completed-wallet constructor does not accept an IWS1 record. Hardware/view-only origins preserve their original authority and expose no recovery phrase or local spend scalar.

The encrypted application payload also records Backup, Verify or Ready, and the three bounded partial verification answers. Normal backgrounding checkpoints the current step/answers and hides the display. Resuming requires Android credentials and restores the same wallet/challenge. Successful verification is checkpointed as Ready; saving the completed wallet writes that wallet durably before retiring the redundant draft. User-created network sync state/pending-payment journals retain their existing encrypted checkpoint behavior.

Unsubmitted import fields and an incomplete camera scan are not created wallets. The durable guarantee begins after creation/import finishes its initial checkpoint. A sudden process kill before a final pause write can lose the latest partial answer/edit, while the already-created wallet remains in the earlier checkpoint. This is not a guarantee against app-data deletion, device loss, storage failure or Keystore invalidation; the offline backup still matters.

## Encryption and ownership

Setup uses the existing AES-256-GCM VaultEnvelope implementation through Android/JCA, with a fresh random 32-byte data key and a fresh GCM IV on each write. The data key is wrapped by a distinct Android Keystore AES key (independent-wallet-setup-wrapping-v1) requiring device authentication and an unlocked device. No custom cipher, plaintext preference backup, server key or new dependency is introduced. Android hardware-backed/StrongBox storage is not promised.

While setup is unlocked, its data key permits checkpoint writes after the Keystore's 30-second authentication window expires. The native owner/display lock immediately on pause; a bounded plaintext checkpoint buffer and live setup data key remain only until the queued encrypted write finishes, then are cleared. This lets a screen-off pause save progress without silently extending the Keystore's decryption permission. Opening an encrypted draft after lock/process recreation must unwrap the data key through authenticated Keystore access again.

The outer IWP1 record has a fixed 64-byte wrapped-key envelope and a bounded encrypted payload. The private IWU1 payload strictly parses step, length-delimited IWS1 bytes and exactly three bounded UTF-8 answers; it rejects unsupported steps, invalid lengths, invalid UTF-8 and trailing bytes. The whole on-disk record is capped at 4,196 bytes before allocation. Private setup records/state redact toString output.

Writes use AtomicFile and a process-wide monitor. A lease also checks the current encrypted file's SHA256 fingerprint before writing/removing it, so stale sessions cannot silently overwrite newer progress. A failed/uncertain commit keeps the existing file; a new authenticated open can recover a committed response whose acknowledgment was lost. No root/OS snapshot rollback protection is claimed. Kotlin/JCA/FFI copies cannot all be reliably erased.

## Clipboard contract

Copy is explicit and retains Android's sensitive-preview metadata. There is no app cleanup timer or lifecycle cleanup. A random owner token is stored in private preferences so a later process can recognize its own copy without reading clipboard text. Only this nonsecret token is persisted there, never the phrase. An uncertain clipboard reply retains ownership for a later explicit clear. Clear copy preserves a clip that another application replaced.

The app cannot force clipboard persistence: Android may clear it independently, and other applications may replace or retain it. Android's ownership check and clear are not atomic. The copy dialog explains the longer exposure and the explicit clear action. The earlier timed-cleanup contract in SECURITY_0_4.md and older validation records is historical and is superseded by this user-requested behavior.

## Verification scope

Rust tests cover both seed formats resuming as the same wallet with the original unverified challenge, denied completed export/networking before verification, malformed checkpoints and view-only authority. Actual JVM bridge tests exercise the native checkpoint. Host encryption/storage tests exercise credential-gated open, writes after the auth window, close/reopen, tampering, bounds, failed writes, lost replies and stale-writer protection. Clipboard tests cover retention across controller recreation, explicit clearing, foreign replacements and uncertain replies.

Android instrumentation includes the new created-wallet copy/exit/reopen path and updated package/Keystore fixture cleanup. Device execution is recorded separately in VALIDATION.md; compilation/host tests do not prove Android credential/lifecycle/Keystore behavior.

The 0.6.1 Kotlin storage tests also cover explicit discard without key unwrap, preparing/canceling without deletion, stale and reused requests, fresh creation after discard, silent delete failure and a lost delete reply. Android cases cover confirmation/auth cancellation, starting again, stale auth rejection and completed-wallet protection; execution status remains separate.

Primary platform references:

- https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
- https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder
- https://developer.android.com/reference/android/util/AtomicFile

Hardware signing/spent-balance sync remain outside this change; software spending remains stagenet only and Keystone mainnet support remains view-only.
