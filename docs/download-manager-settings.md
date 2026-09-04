# Download Manager settings

Implementation follows the official Android guidance for
[DataStore](https://developer.android.com/topic/libraries/architecture/datastore),
[Compose pagers](https://developer.android.com/develop/ui/compose/layouts/pager),
[adaptive layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes),
[network-usage controls](https://developer.android.com/develop/connectivity/network-ops/managing),
[adaptive quality guidance](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-2),
[Android UX quality guidance](https://developer.android.com/quality/user-experience),
[Sharesheet](https://developer.android.com/training/sharing/send), and
[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider).
The foreground host remains subject to Android's
[data-sync foreground-service timeout](https://developer.android.com/develop/background-work/services/fgs/timeout).

## Context-aware settings

A single `DownloadSettingsCatalog` defines logical sections, shared settings, and design-specific
applicability. The composable renders that model rather than maintaining separate screens. Shared
Appearance and Transfer controls are available in both designs; Media gallery filter controls are
omitted from Tabbed list because that design has no category-filter surface. Applying another design
updates the reactive DataStore-backed layout state, which immediately reprojects the visible
sections. Section headers and checked/unchecked controls reuse the app's general-settings components,
including minimum Material touch targets and adaptive list-width bounds.

## Concurrent-download limit

The first-run default remains 3. The supported range is 1–5. Five is derived from the engine rather
than chosen as a generic UI cap:

- One large file can use up to 16 range segments.
- The dedicated OkHttp dispatcher and connection pool each support 96 requests.
- Five worst-case files use at most 80 segment requests, leaving 16 request slots for probes,
  retries, redirects, and completion transitions.
- Six worst-case files would consume all 96 slots before that overhead and would remove safety
  headroom. Higher values would only queue inside OkHttp while increasing coroutine, socket,
  buffer, descriptor, radio, and server pressure.

The adjustable limiter is fair. Raising the setting wakes queued files. Lowering it never interrupts
active writes; the new limit takes effect as current files finish or pause.

## Speed limiting

The default is unlimited. Optional limits apply to aggregate download traffic, not independently to
each segment. One shared pacing gate meters 64 KiB network chunks before disk writes, stays outside
segment-state locks, cooperates with cancellation, and can be changed while transfers are active.
Alongside predefined values, Custom accepts a locale-digit-aware value in KiB/s from 1 KiB/s through
1 GiB/s. Invalid, empty, zero, negative, decimal, and overflowing values cannot be applied. The
validated byte value uses the same DataStore key and runtime flow as presets, preventing separate UI
and engine sources of truth.

## Filters

`All` is always present. Every other visible filter is user-configurable and persisted by stable ID.
The Media gallery exposes a category only when matching content exists in the current download
snapshot. Audio therefore disappears when no audio downloads are available. `Other` is computed as
the complement of the currently enabled dedicated categories, so a disabled PDF category is still
represented by `Other` rather than being lost.
Classification uses normalized MIME types first and filename extensions as a fallback. APK, PDF,
archive, image, video, audio, and uncategorized files are mutually exclusive, so disabling a chip
never silently changes another chip's meaning. Filter counts come from the same content projection as
the lists and can be hidden through a reactive, persisted appearance preference.

## Presentation

The **Media gallery** and **Tabbed list** designs can both show completed videos in a
playback-oriented card. One shared preference controls both and defaults to enabled. Disabling it
renders videos with the same standard file card as other downloads in either design. The
nonfunctional elevated disc behind the playback control was removed; the control remains the only
interactive element in that preview, and preview height derives from local width bounds.

## File actions

Deleting from the Download Manager also removes the file from the device, so every delete entry
point (card overflow menu and multi-select header, in both designs) first raises one
`DeleteConfirmation` in the ViewModel state. The shared Material 3 `ConfirmationDialog` renders it
with a Delete icon, a plural-aware title, a message stating that the file(s) will be permanently
deleted and cannot be undone, a dismissive **Cancel** action first and a destructive-styled
**Delete** action last (order mirrors in RTL). Nothing is committed until `confirmDelete()`, which
runs on the application scope so leaving the screen mid-commit cannot delete only part of a bulk
selection. Selection is preserved while the dialog is open so Cancel returns to the same state,
items removed elsewhere are pruned from an open confirmation, and the dialog closes itself when
nothing remains. The dialog replaces the former Undo snackbar: Material guidance calls for one
recovery mechanism per destructive action, and an Undo offered after a "permanently deleted"
confirmation would contradict the copy. An informational snackbar confirms the deletion afterwards.

Rename and Share are exposed only for completed files. Rename sanitizes the requested name, rejects
collisions, uses a same-filesystem atomic move when available, writes Room immediately, and rolls the
filesystem operation back if persistence fails. Share grants temporary read access to a content URI
through `FileProvider` and launches the Android Sharesheet; raw filesystem paths are never exposed.
