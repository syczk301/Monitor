# Product Design QA — Android V4.6.0

Final result: passed

## Comparison target

- Source visual truth: `.test-runs/v46-audit/00-monitor-reference.png`
- Source-state captures: `.test-runs/v46-audit/01-record-current.png`, `.test-runs/v46-audit/02-recordings-current.png`, `.test-runs/v46-audit/03-settings-current.png`
- Implementations: `.test-runs/v46-build/01-record.png`, `.test-runs/v46-build/02-recordings.png`, `.test-runs/v46-build/03-settings.png`
- Combined comparison input: `.test-runs/v46-build/07-reference-vs-implementation.png`
- Additional states: `.test-runs/v46-build/04-recordings-remote-filter.png`, `.test-runs/v46-build/05-recording-settings-expanded.png`, `.test-runs/v46-build/08-recordings-font120.png`
- Viewport: Android emulator, 1080 x 2400 pixels, portrait, light theme.
- Density normalization: all source and implementation screenshots were captured from the same emulator and have identical 1080 x 2400 pixel dimensions; no scaling was used for the individual comparisons.
- State: configured service, two online cameras, populated visitor and local-recording data, remote-recording empty state.

The reference is the accepted monitor-screen design language rather than a pixel-identical content mock. QA therefore compares typography, spacing, palette, surfaces, icons, interaction states, and information hierarchy across the three new screens.

## Full-view comparison evidence

- Typography: all four screens use the same system typeface, extra-bold navy page titles, compact slate supporting text, and blue emphasis. Long dynamic device labels truncate without collisions.
- Spacing and layout: 18 dp page margins, restrained 10–18 dp section gaps, 12–18 dp radii, one-pixel neutral outlines, and the shared top/bottom application chrome remain consistent. No content overlaps persistent navigation.
- Colors and tokens: warm-white background, white surfaces, cobalt selection/action states, emerald online/recording states, slate secondary text, and pale semantic containers match the monitor reference.
- Image and asset quality: these utility screens require no raster imagery. All visible actions use the existing Material icon family; no placeholder drawings, custom SVGs, emoji, or generated decorative assets were introduced.
- Copy and content: page titles, counts, device ownership, connection status, empty states, and recording-mode labels are concise and independently understandable.

## Focused region comparison evidence

- History cards: visitor identity remains dominant; recognition count and total stay form one compact summary; first/recent timestamps share equal-width cells; note, save, and delete controls retain practical tap targets.
- Recording controls: the two computer names and the all-devices state fit in one segmented row. The expanded schedule panel preserves all mode, time, weekday, and save controls without clipping.
- Settings form: current connection state precedes editable credentials, password visibility has an explicit accessible label, and save feedback remains in the original success/error regions.
- Text scaling: the recordings screen was rechecked at Android font scale 1.20. Dynamic local-device text ellipsizes intentionally; headings, filters, cards, playback actions, and bottom navigation remain usable.

## Interaction checks

- History refresh, person selection, selected-count update, and conditional bulk-delete action.
- Daily/weekly report entry points retained.
- Recording device filters: all, local, and remote; remote empty state correctly shown.
- Recording-settings expand/collapse and all mode/time/day/save controls visible.
- Recording playback buttons remain enabled for available local files.
- Settings address, username, password, password visibility, and save controls remain interactive.

## Comparison history

### Initial audit findings

- P2: history summary, toolbar, and visitor cards competed at the same visual weight.
- P2: the remote device filter was visibly clipped on the recordings screen.
- P2: the settings screen used excessive upper whitespace and lacked a clear current-connection summary.

### Fixes applied

- Added page-level titles, compact summaries, and consistent refresh actions.
- Rebuilt recording filters as equal-width device selectors with two-line computer labels.
- Reorganized settings around a connection-status card and compact credential section.
- Standardized card outlines, radii, spacing, semantic colors, and Material icons.

### Post-fix evidence

- The combined comparison input shows the same visual system across all four tabs.
- The remote filter, expanded schedule panel, selected-history state, and 1.20 font-scale state were exercised after implementation.
- No actionable P0, P1, or P2 visual, interaction, accessibility, or responsive findings remain.

## Residual test limits

- Screen-reader announcements and hardware-keyboard focus order were not verified from screenshots; semantic labels and practical touch targets were checked in the Android UI hierarchy.
- Empty, populated, selected, expanded, and remote-empty states were checked; network failure rendering was not forced during this pass.
