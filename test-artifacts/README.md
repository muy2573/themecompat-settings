# Pure pathname normalization test artifact

`pure-pathname-only.mtz` is generated from the original NachoNeko MTZ for the
HyperOS 3 ThemeManager compatibility test.

Only the outer entry `com.android.thememanager` differs from the original MTZ.
No resource entry was added, removed, replaced or recompressed inside that module.

Verified properties:

- Outer entries: 67 in both files.
- Changed outer payloads: only `com.android.thememanager`.
- `description.xml`: byte-for-byte unchanged.
- ThemeManager entries: 206 before and after.
- Canonicalized pathnames: 9.
- Legacy fallback decodes: 0; all 9 names came from CRC-valid `0x7075` fields.
- Compressed payload bytes, CRC, method, size and entry order: unchanged.
- `U+FFFD` pathnames: 0.
- A second normalization pass is byte-for-byte idempotent.

Hashes:

- Original ThemeManager module: `4e27473dd7ba9c94ea47565957d5ef869c24b74fc463cb18698e6b54505149ac`
- Normalized ThemeManager module: `b3b8f4e5f576b64f206c33fbd01695d5c2263bb883a11088e3195117ca535dd5`
- Pure test MTZ: `5fe7f40269399228ae57d35872e13796832cbbfdb19aa8ec907fdcdec23251b3`

This package intentionally does not update the theme title, description or version.
Import/apply behavior for an already-known theme ID may therefore depend on
ThemeManager cache state; that is separate from the pathname experiment.

## Final v1.1.0 audit archive

The ignored local files `original-v336-audit.mtz` and `patched-v336-audit.mtz`
are the source/result pair used for the final generator audit. The generator
code is unchanged in v337; v337 only refreshes user-facing information and
Hook source excerpts.

Final results:

- Outer entries: 67.
- Inner ZIPs: 46.
- Inner entries: 5734; every outer and inner CRC verified by full inflation.
- Remaining pathname normalizations: 0.
- ThemeManager: 206 normalized baseline entries to 219 patched entries.
- ThemeManager delta: 205 raw-record-identical untouched entries,
  1 replacement (`theme_fallback.xml`), 13 additions, 0 removals.

The installable release and its checksum are archived locally under
`release-archive/v1.1.0-337/` (gitignored).
