# Nexa icon source

Use **Material Symbols Rounded** from Google Fonts for every application icon.

Canonical Kotlin source URL:

```text
https://fonts.gstatic.com/render/v1/Material+Symbols+Rounded/24dp/<icon_name>.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
```

Replace `<icon_name>` with the snake-case Material Symbol name, such as `folder` or `bookmarks`.

Example:

```text
https://fonts.gstatic.com/render/v1/Material+Symbols+Rounded/24dp/bookmarks.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
```

When adding the generated file:

1. Change its package to `com.elewashy.nexa.ui.icons`.
2. Rename the public property to PascalCase (`create_new_folder` → `CreateNewFolder`).
3. Rename its private cache consistently.
4. Keep the 24dp viewport and the source path data unchanged.
5. Use the default rounded, unfilled configuration above. For an explicitly active state, keep the Rounded family and change only `FILL` to `1`.
6. Set `autoMirror = true` for directional symbols when the generated source does not already do so.
7. Use `Icon` tint from `MaterialTheme.colorScheme`; do not bake feature colors into vectors.

Browse names at: https://fonts.google.com/icons
