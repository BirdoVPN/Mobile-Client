## v1.3.3 — Perf + Settings cleanup

### Server list scroll perf
- ServerCard rewritten as a flat `Row` (no `BirdoCard` wrapper, no nested `Box` layers).
- Shapes hoisted to file-level vals; no per-frame `RoundedCornerShape` re-allocs.
- Load bar uses solid color (no `Brush.horizontalGradient` per frame); border uses solid color too.
- Lightweight `Box.clickable` for the favorite star instead of `IconButton` ripple stack.
- Result: smooth 60fps scroll on the Choose A Server list.

### Settings — full IA cleanup
- **Appearance moved to the top** (theme selector first thing you see).
- **Security** section directly below: Biometric Lock + Kill Switch live here together.
- **Connection**: Auto-connect, Notifications, system notifications.
- **VPN** (unified): Protocol & Network → Split Tunneling → Multi-Hop → Port Forwarding all grouped together for a clean, predictable layout.
- **About** at the bottom.

### Speed Test — fully removed
- `SpeedTestScreen`, `SpeedTestViewModel`, `SpeedTestUtil` deleted.
- `Screen.SpeedTest` route + nav entry removed.
- `onOpenSpeedTest` parameter and Settings link removed.
- Unused strings `settings_speed_test`/`_desc` deleted.

### Profile
- Removed duplicate **Subscription** row from the Account list (the redesigned Subscription card already has a prominent Manage button — no longer two entry points).
