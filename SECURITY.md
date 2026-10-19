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
it is the default. There is no "never" option.

Two short exceptions: VaultPony's own file pickers don't lock the vault while
they are open (for at most 3 minutes), and a bulk import or export that is
running when the lock is due gets up to 5 seconds to stop cleanly before the
lock goes ahead.

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
