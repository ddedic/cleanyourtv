# Featured feed, spec 1

The featured shelf (`featured_feed` in `config.xml` or a theme) shows rows that another app provides: a
rotating shelf above the dock with each item's backdrop, title, a line under it and a short summary. The
launcher has no network access and gets nothing from the internet itself; the feed app fetches, caches and
serves the items through an exported content provider. The [Plex feed add-on](../../addons/plex-feed/) is one.

## The provider

The owner sets `featured_feed` to the provider's authority, for example `io.github.ddedic.plexfeed.feed`.
The feed app must also have a `LEANBACK_LAUNCHER` activity: that is what makes the package, and so its
provider, visible to the launcher on Android 11 and newer.

### `content://<authority>/items`

A query with no projection, selection or sort. One row per item, in the order to show them; the launcher
uses the first 20 and skips rows without a title. Every column is a string; a missing column or a null value
counts as empty.

| Column | Shown as | Limit |
|---|---|---|
| `title` | The large line. Required. | 80 characters |
| `subtitle` | The line under it: year, length, `S2 · E5`, "3 new episodes". | 80 |
| `summary` | Two lines of text under that. | 400 |
| `badge` | A small upper-case label above the title: "Recently added", "Continue watching". | 32 |
| `image` | A `content://<authority>/...` URI of a 16:9 JPEG or PNG, about 1280×720, served by the provider's `openFile`. Pictures from any other authority or scheme are ignored. | |
| `intent` | An `intent:` URI (`Intent.toUri(Intent.URI_INTENT_SCHEME)`) that OK starts. The launcher drops its selector and URI grants and refuses one that names the launcher itself. | |
| `package` | The app OK opens when `intent` is empty or fails. | |
| `action` | The label of the button shown while the shelf is focused, such as "Open in Plex". Default: Open. | 24 |

Longer text is cut with an ellipsis.

### `call(<items URI>, "refresh", null, null)`

Called each time Home comes to the front. The provider refreshes if its items are stale (its own rule; the
Plex add-on uses 15 minutes) and returns at once: the work goes on in the background, and when it is done the
provider calls `notifyChange` on the items URI. The launcher listens for that only while Home is on screen,
and queries again. A provider without `call` still works; its items show as they are.

## What the launcher does

- Reads the rows and decodes one picture at a time, at most screen width, while Home is on screen; it keeps
  three decoded pictures at most and stops the rotation and the listener when Home leaves the screen.
- Shows the next item every `featured_seconds` (8 by default, 4 to 60). Up from the dock focuses the shelf;
  Left and Right step through it, the rotation pauses while it has focus, OK opens the item, Down goes back
  to the dock and Up to the top bar.
- While the shelf has items, the app name above the dock (`dock_title`) is hidden: the shelf takes its place.
  When focus goes into the grid below the dock, the shelf fades out and comes back with the dock.
- Launcher settings has a **Featured shelf** switch while a feed is set.

## Privacy

The provider is exported and readable by any app on the TV, so serve only what is fine to show on a home
screen: titles, summaries and pictures. Never put an account token or a server address in a column.
