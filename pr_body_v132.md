## v1.3.2 — Connect zoom + Profile overhaul

### Globe
- Higher-detail rasterisation (3px sample step, ~2.7× more land samples)
- Latitude/longitude graticule (every 30°) drawn under the continents
- **Mullvad-style connect zoom**: when you connect, the camera animates so the user→server midpoint sits on the centre meridian, the globe scales up ~1.18×, and the connection arc draws in progressively from your location to the server (with pulse pin endpoints)
- Centered in its container (was top-aligned)

### Connect screen cleanup
- Removed Kill Switch / Stealth / Quantum text badges
- Removed public IP from the connected location label

### Profile tab
- All Account items moved here from Settings: Subscription, Redeem voucher, Manage on web, Privacy Policy, Terms of Service
- **Delete Account** styled identically to Sign Out (red ProfileActionRow)
- **Subscription card redesigned**: gradient plan badge, status pill (Active/Inactive), benefits chips (devices, bandwidth, premium), prominent gradient Manage/Upgrade CTA
- **Renewal date formatted as yyyy-MM-dd** (no time portion); accepts ISO-8601 with offset, plain date, or T-suffixed strings

### Settings
- Account section removed (lives in Profile now)
