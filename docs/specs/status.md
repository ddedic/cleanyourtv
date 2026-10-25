# Status widget, spec 1

The status pill (`status_widget` in `config.xml` or a theme) is one small pill in the top bar, before Wi-Fi,
whose text another app provides: a room temperature, a printer's ink level, a door that is open. The launcher
has no network access; the app fetches and serves the row through an exported content provider, and OK on the
pill opens that app's own screen for the controls. The [Netatmo add-on](../../addons/netatmo/) is one.

## The provider

The owner sets `status_widget` to the provider's authority, for example `io.github.ddedic.netatmo.status`.
As with [the featured feed](feed.md), the app needs a `LEANBACK_LAUNCHER` activity so that Android 11 and newer
let the launcher see its provider.

### `content://<authority>/status`

A query with no projection, selection or sort. The launcher reads the first row; no row, or an empty `text`,
hides the pill. Every column is a string; a missing column or a null value counts as empty.

| Column | Shown as | Limit |
|---|---|---|
| `text` | The pill's one line, such as `21.4° → 22°`. Required. | 24 characters, cut with an ellipsis |
| `icon` | One of the launcher's own icons: `thermostat`, or `flame` (drawn warm orange). Anything else shows none. | |
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
