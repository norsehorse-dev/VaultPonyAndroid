# Security Policy

## Supported Versions

VaultPony ships as a rolling release. The latest version on GitHub and
F-Droid receives security fixes; older versions do not, so please update
before reporting.

| Version        | Supported |
| -------------- | --------- |
| 1.0.5 (latest) | Yes       |
| < 1.0.5        | No        |

## Auto-lock and keys in memory

By default VaultPony locks every open vault the moment the app leaves the
foreground or the screen turns off. Locking wipes the vault's keys from memory.

Settings > Auto-lock can delay this by up to an hour. While a vault waits to
lock, its keys stay in the app's memory in the background, and an unlocked
vault stays visible to other apps through the system file picker. Someone
with the unlocked phone, or with a way to read the app's memory, has that
window to reach the vault. "Immediately" leaves no such window, which is why
it is the default.

Two exceptions keep a vault open past that point:

- VaultPony's own file pickers don't lock the vault while they are open (for
  at most 3 minutes).
- A bulk import or export keeps its own vault unlocked until the copy ends,
  even with the app in the background, and for that vault the auto-lock delay
  only starts counting then. Any other unlocked vault still locks on time.
  While it runs, a "Copying files" notification shows its
  progress with Lock and Cancel buttons. Lock stops the copy after the
  current chunk (the half-copied file is removed) and then locks the vault,
  waiting at most 5 seconds for the copy to stop.

### "Until I lock it"

Since 2.0.0, Auto-lock also offers "Until I lock it", for keeping a vault
reachable from the Files app and other apps for as long as you need. With it,
an unlocked vault has no timer at all: its key stays in memory, and any app
you open it from can read and write the vault, until you lock it from the
"A vault is unlocked" notification, the "Lock vaults" tile or the app.
Restarting the phone or closing VaultPony also locks it, because the key only
ever lives in the app's memory.

That is the trade-off: the window described above lasts as long as you leave
it open. Someone who gets the unlocked phone, or a way to read the app's
memory, can reach the vault the whole time. Use it only while you need it.

So that you can always see that a vault is open and lock it in one tap, the
option is only offered while VaultPony may show notifications, and asks
before it is turned on. While it applies, the "A vault is unlocked"
notification shows whenever the app is in the background (during a copy,
the copy notification with its own Lock button takes its place), and comes
back if it is swiped away. If you turn VaultPony's notifications off later,
the setting falls back to "After 1 hour", counted from when you left the app.

## The vault list

Settings > Vault list decides what the home screen remembers. "Show names"
keeps each vault's file name. "Hide names" shows Vault 1, Vault 2 and so on
and stores no names, although VaultPony still keeps its saved permission to
each file, which records where the file is, so it can open it again. "Don't
keep" stores nothing: when a vault locks, VaultPony releases its permission
to the file and removes the vault's biometric unlock and remembered hash.
Choosing it deletes every entry already on the list in the same way. Every
lock (and every app start) releases all of VaultPony's saved file
permissions except those of vaults still unlocked, so a container you picked
but haven't unlocked yet has to be picked again after that.

## In-app thumbnails and playback

The browser's grid view makes image thumbnails in memory from the vault and
keeps them in memory only; they are never written to storage and are dropped
when the vault locks. Video and audio play straight from the vault through
Android's built-in player, reading only the parts being played: no temporary
file, and nothing about the file is handed to the system's media controls.

## Lock controls

While a vault stays unlocked with the app in the background, VaultPony shows
an "A vault is unlocked" notification with a Lock button. It is generic on
purpose: it never names a vault or a file, says nothing about hidden volumes,
and is hidden on the lock screen. A Quick Settings tile, "Lock vaults", locks
everything from anywhere, including the lock screen. If you deny the
notification permission, everything works the same without the notification.

## Other apps and the Files app

An unlocked vault appears as a location in the system Files app and in other
apps' file pickers. Since 2.0.0 that location is writable: an app you open it
from can read, create, change, rename, move and delete files in the vault for
as long as the vault stays unlocked, and its changes are written straight into
the container. Using the vault this way does not count as activity, so it
never delays the auto-lock; a file still open in another app when the vault
locks simply stops working.

VaultPony streams those files through the vault's encryption and writes no
plaintext to its own storage. Image thumbnails are made in memory and never
saved. What another app does with a file it opened (for example, keeping its
own copy or a cached preview) is outside VaultPony's control.

Access from other apps never survives a lock. Every unlock gives the vault new
random identifiers in the system file manager, so a folder or file another app
was given stops working the moment the vault locks, and the app has to be
pointed at the folder again after the next unlock. Nothing another app keeps
from an earlier unlock leads back into the vault, and it doesn't reveal which
container or which volume (outer or hidden) was open.

Read-only vaults (NTFS) refuse all writes from other apps. So does an outer
volume opened with hidden-volume protection once a write was blocked to
protect the hidden volume: from then on the vault is read-only until it is
locked.

## Reporting a Vulnerability

Report privately by email to NorseHorse@norsehor.se. Please do not open a
public issue for a security problem.

If you want to encrypt the report, my OpenPGP key for NorseHorse@norsehor.se
is on keys.pgpony.app.

Include the app version, the device and Android version, and enough detail
to reproduce. I read reports within a few days and will let you know whether
the issue is confirmed and when a fix will ship. Please allow a reasonable
window for a fix before any public disclosure.

Reports about the VeraCrypt container format itself belong upstream with
VeraCrypt. Anything about how VaultPony implements, stores, or exposes it is
in scope here.
