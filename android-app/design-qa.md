# Settings design QA — option 1

final result: passed

## Visual target and capture

- Source visual truth: `design/settings/option-1.png`, first displayed concept selected by the user.
- Source pixels: 853 × 1844. Aspect-preserving normalization to 390 × 844.
- Implementation: native Android Compose, not a browser prototype. Emulator screenshot: 1170 × 2676 at density 480 (3 pixels per dp). Remove 72 pixels of OS chrome at the top and bottom, then normalize the app content to 390 × 844.
- Same-state visual comparison: `design/settings/04-implemented-preview.png`, a debug-only activity rendering the actual SettingsScreen with the concept's server address and connected ZeroTier mock state. This is explicitly a visual fixture, not proof of server/ZeroTier connectivity.
- Full comparison: `design/settings/comparison-2.png`. Focused comparisons: `comparison-fields.png`, `comparison-update-footer.png`.
- Real release evidence: `07-release-configured.png`, `08-release-keyboard.png`, `09-release-network-details.png`, `10-release-large-font.png`, `11-release-small-screen.png`, `12-release-small-screen-scrolled.png`, `14-release-node-details.png`.

## Comparison history and findings

First comparison (`comparison-1.png`) found [P2] default Material secondary-purple update emphasis instead of the selected blue palette. Fixed explicit button colors. Also restored the outlined authentication disclosure and clear-address control visible in the selected target. Recaptured the same state in `comparison-2.png`; the mismatch is resolved.

No actionable P0/P1/P2 differences remain. The server-first grouping, collapsed credentials, network status and details, separate update area, fixed save action and existing navigation are present.

## Required fidelity surfaces

- Fonts and typography: native project typography and Android Chinese fallback retained; clear heading/section/body hierarchy. Labels remain readable at 1.3 font scale. Target text is represented with editable native controls.
- Spacing and layout rhythm: 16 dp page inset, 14 dp section gaps, 14 dp section padding, 16 dp outer radii. The three main groups follow the target. Persistent save and navigation remain outside the scrolling body; smaller screens scroll the form instead of hiding the save control.
- Colors and tokens: existing warm white, white surfaces, cobalt primary, slate secondary text, light outlines and green success. Update button explicitly uses primary-container/primary colors. Target gradients are replaced by the product's existing solid primary token.
- Image quality and assets: target contains standard UI icons, no raster assets. Matching Material rounded icons are used; no screenshot has been rasterized into the app. Minor icon silhouette differences are P3.
- Copy and content: selected Chinese grouping preserved. Version is V4.9.2 rather than mock V4.9.1. Actual runtime status text is shown instead of permanently claiming connectivity. The network status uses one compact message instead of duplicating “connected” twice. These are intentional content adjustments.

## Interaction and runtime checks

- Signed release build and Lint Vital passed; V4.9.1 → V4.9.2 covering installation passed on the emulator. Release signature unchanged. Debug visual fixture absent from installed release manifest.
- Real release: server address saved, HTTPS health check succeeded, settings/bottom navigation rendered without duplicate global heading.
- Authentication disclosure expanded and collapsed, optional inputs accepted draft text, password visibility switched between masked and visible (Android UI attributes checked).
- Real soft keyboard shown (input-method state verified): save action remained above keyboard and bottom navigation was hidden during input.
- Network toggle showed pending-save state accurately; expanded network ID field, authorization and FlClash help remained accessible through scrolling.
- Real SDK generated a node ID and reached “waiting for network authorization”; copied ID was pasted into an unsaved test field and compared for equality. No network authorization was performed.
- Manual update check accessible after scrolling a smaller viewport. Cold-start public update check returned no newer mobile package.
- 390 × 844 content viewport, smaller 390 × 640 content viewport, and font scale 1.3 inspected. Fixed save/nav remained usable.
- AndroidRuntime error log checked: no app crash entries during this validation.

## Follow-up polish and limits

- P3: native Material control/icon metrics and some line spacing differ slightly from the generated image; these preserve real Android interaction and the existing theme.
- Screen-reader navigation, extreme font scale and physical-device keyboard variants were not tested. This report does not claim accessibility compliance.
- Existing ZeroTier/FlClash and APK-install networking behavior was retained; this layout task does not re-certify end-to-end phone video/audio or update installation.

## Implementation checklist

- Selected layout implemented in native SettingsScreen.
- Foldouts and actionable controls exercised in the actual app.
- Comparison fix recaptured and accepted.
- Signed V4.9.2 APK plus SHA-256 sidecar generated locally; no new GitHub release published in this layout task.
