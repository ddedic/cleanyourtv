# Status widget, spec 1

The status pill (`status_widget` in `config.xml` or a theme) is one small pill in the top bar, before Wi-Fi,
whose text another app provides: a room temperature, a printer's ink level, a door that is open. The launcher
has no network access; the app fetches and serves the row through an exported content provider, and OK on the
pill opens that app's own screen for the controls. The [Netatmo add-on](../../addons/netatmo/) is one.

## The provider

The owner sets `status_widget` to the provider's authority, for example `io.github.ddedic.netatmo.status`, or to
several authorities separated by commas for several pills, in that order.
As with [the featured feed](feed.md), the app needs a `LEANBACK_LAUNCHER` activity so that Android 11 and newer
let the launcher see its provider.

### `content://<authority>/status`

A query with no projection, selection or sort. The launcher reads the first row; no row, or neither `text` nor
an `icon`, hides the pill. Every column is a string; a missing column or a null value counts as empty.

| Column | Shown as | Limit |
|---|---|---|
| `text` | The pill's one line, such as `21.4°`. Empty with an `icon` makes a small round icon pill. | 24 characters, cut with an ellipsis |
| `icon` | One of the launcher's own icons: `thermostat`, `door`, or `flame` (drawn warm orange). Anything else shows none. | |
| `badge` | A mark at the pill's top end. A count, such as unseen doorbell rings, is a small orange bubble: `1` to `9`, then `9+`; empty or `0` shows none. Anything else is a short label in a light tag, such as a target temperature `22.5°`. | 5 characters for a label |
| `description` | What TalkBack reads instead of `text`, such as "Living room 21.4 degrees, heating to 22". | |
| `intent` | An `intent:` URI that OK starts, handled as the feed's `intent`: no selector, no URI grants, never one of the launcher's own screens. | |

### `call(<status URI>, "refresh", null, null)`

Called each time Home comes to the front. The provider refreshes if its row is stale (its own rule) and
returns at once; when the new row is ready it calls `notifyChange` on the status URI. The launcher listens
only while Home is on screen.

## What the launcher does

Nothing while `status_widget` is empty. With it set, the pill is hidden until the provider gives a row, sits
in the top bar's D-pad row like the other pills, and is never a way for the app to run code in the launcher:
only text, one of two icons and an intent the launcher starts on OK.
