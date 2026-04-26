## v1.3.1 — Connect & Settings polish

### Globe performance
- Pre-cache (px, py, latDeg, lonRel) disc grid via remember; only rotation varies per frame
- Batch all land pixels into a single drawPoints call (was thousands of drawCircle/frame)
- Slower 120s rotation (was 90s — felt "too much")
- Float math throughout (was Double)
- Single radial vignette overlay replaces per-pixel limb-darkening
- Result: smooth 60fps

### Banner
- HomeTopBar: more breathing room (heightIn 60dp, vertical 10dp)

### Server sheet
- Pause globe rotation while ServerSelectorSheet is open (was the source of "laggy / low framed" sheet drag)

### Settings → looks like Profile
- All rows now use BirdoCard with GlassStrokeGradient border
- Icon chips use surfaceRaised + hairlineSoft border (subtle ring)
- Hardcoded Color.White / BirdoWhite60 replaced with palette tokens
- About card uses AppIconMark
- Tighter 10dp spacing, 20dp horizontal padding matching Profile
