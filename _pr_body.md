## Mobile UI/UX overhaul (v1.2.5)

A comprehensive visual + interaction refresh of the entire Android app, built on a new design-system foundation so future screens stay consistent.

### New design system
- **Spacing / Shapes / Motion / Brand tokens** — `ui/theme/{Spacing,Shapes,Motion,Brand}.kt`
- **Brand palette** — purple→pink primary gradient, tiered surfaces (Surface0–Surface3), gradient hairlines, ambient idle/connected/error blooms
- **Motion** — durations + custom easings for cohesive animation rhythm

### New component library (`ui/components/`)
- `BirdoCard`, `BirdoSubCard`, `BirdoSectionHeader`
- `BirdoButton` (Primary / Brand-gradient / Secondary / Ghost / Danger × Small/Medium/Large) with press-scale animation
- `BirdoTextField` with brand-purple focus border + glass container
- `BirdoTopBar` + `BirdoIconAction` (status-padded glass header)
- `BirdoListItem`, `BirdoToggleRow`, `BirdoNavRow`
- `BirdoBadge` (6 tones) + `PulsingDot`
- `BirdoEmptyState`
- `BirdoAuroraBackground`

### Screens refreshed
- **Home** — gradient brand lockup, animated status pill (pulse dot when protected), state-driven ambient glow, 168dp connect button with dual pulse rings + brand-gradient halo + radial inner fill, polished stats trio, feature badges, server selector card.
- **Login** — gradient shield brand mark, brand-purple input focus borders, preserved 2FA flow.
- **Servers** — `BirdoTopBar` w/ subtitle count + refresh action, `BirdoTextField` search w/ inline clear, brand-tinted filter pills, server cards with gradient load bar + gradient selected ring, `BirdoEmptyState` w/ retry CTA.
- **Settings** — `BirdoTopBar`, brand section headers, tinted icon pills per row, brand-purple switches, gradient about lockup, branded delete-account row, segmented theme selector.
- **Bottom Nav** — brand-purple active tint, soft purple indicator, taller 72dp bar with hairline top divider, semibold labels.

### Quality
- `:app:compileDebugKotlin` passes
- All TestTags preserved
- No behavior / API changes — pure visual + interaction refresh

### Version
`1.2.4` → `1.2.5` (versionCode `10204` → `10205`)
