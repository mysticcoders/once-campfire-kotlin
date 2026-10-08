# Frontend overrides

Files in `overrides/` shadow the reference app's assets of the same logical path (the path under
`app/javascript`, `app/assets/*` or a vendored gem's asset directory), so the port can change its
frontend without editing the `reference/` submodule. `generateAssets` (`src/generator`) puts that
directory first on the load path. The files are copied from `once-campfire-rust/crates/assets/overrides`.

| File | Differs from the reference by |
|---|---|
| `models/file_uploader.js` | No `X-CSRF-Token` header: pages carry no CSRF token (forgery protection is by `Sec-Fetch-Site`) |
| `controllers/copy_to_clipboard_controller.js` | A `url` value: a path, copied as an absolute URL against the page, so the cached message markup that carries it doesn't depend on the request's host |
| `lib/autocomplete/base_autocomplete_handler.js` | Asks for JSON (`Accept: application/json`). The reference passes `{ as: "json" }`, a `@rails/request.js` option, to plain `fetch`, gets HTML and never shows the new-ping suggestions |
| `install-edge.svg` | New: a copy of `external/install-edge.svg` where `pwa/_install_instructions` looks for it. Rails can't find it, so Edge gets a 500 on profile and room pages |
