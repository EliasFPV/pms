# SkyHorizon

Native Android app (Kotlin + Jetpack Compose) that computes and draws the exact
positions of the Sun and the Moon relative to the local horizon for any place,
date and time.

Built and tuned for a Pixel 7 (1080 x 2400, 20:9) running Android 13 or newer,
but it runs on anything from API 26 upwards.

## Features

**Location engine**
- GPS mode via `FusedLocationProviderClient` (`ACCESS_FINE_LOCATION` /
  `ACCESS_COARSE_LOCATION`, requested at runtime), including altitude and
  accuracy when the fix provides them.
- Manual mode: a full-screen OpenStreetMap picker (osmdroid, no API key) where
  you tap the map or drag the pin; latitude and longitude can also be typed in.
- The active coordinates, the elevation and a reverse-geocoded place name are
  shown at all times.

**Astronomy engine** — pure Kotlin, no service calls, in `app/src/main/java/com/skyhorizon/app/astro/`
- Sun: Meeus chapter 25 (apparent longitude, radius vector, equation of time).
- Moon: Meeus chapter 47, the full 60-term ELP series for longitude and
  distance plus the 60-term latitude series (~10" accuracy).
- Nutation and the true obliquity of date (chapter 22).
- Diurnal parallax, so positions are topocentric rather than geocentric — worth
  up to a degree for the Moon (chapter 40).
- Bennett's atmospheric refraction, which lifts a body on the geometric horizon
  by about 34'.
- Illuminated fraction, phase angle, phase name and the position angle of the
  bright limb (chapter 48).

**Time controls**
- Material 3 date and time pickers to jump to any past or future moment.
- Step buttons: ±10 min, ±1 hour, ±1 day.
- A slider that scrubs continuously through the displayed day.
- "Now" returns to live time, which then ticks once a second.
- Times can be read in the device zone or in the mean solar time of the chosen
  meridian.

**Real skyline** — the horizon you would actually see from the chosen spot
- Public elevation tiles (AWS "Terrain Tiles", Terrarium encoding, no API key)
  are fetched for the surroundings and cached on disk.
- `HorizonCalculator` ray-casts outwards along every azimuth, keeping the highest
  apparent elevation angle. Earth curvature and standard atmospheric refraction
  (k = 0.13, the 7/6 R effective-radius rule) are both applied, so the result is
  the ridge line a person standing there would see.
- Two resolution tiers keep the download to roughly 25 tiles: about 53 m per
  sample within 15 km, about 210 m beyond, out to 70 km.
- Accuracy check: from Zermatt the computed Matterhorn horizon is +17.6° at
  bearing 235.5° and 8.61 km, against a true +18.5° at 234.8° and 8.57 km. The
  remaining ~0.9° is SRTM smoothing the summit pyramid, not the ray-cast.
- The renderer tints each column by how far away that terrain is, so distant
  ranges wash out towards the sky the way aerial perspective really works.
- Without a network the app falls back to a flat horizon and says so.

**Sky visualisation** — a custom Compose `Canvas`
- Panoramic horizon: drag to pan through the full 360°, pinch to change the
  field of view (20°–200°).
- Sky gradient that follows the Sun's altitude from daylight through the golden
  hour and each twilight stage into night, with a star field that fades in.
- Altitude grid every 15° and a compass strip with N/NE/E/… and degree ticks.
- The Sun as a glowing disc, drawn shaded with a dashed outline once it drops
  below the horizon.
- The Moon drawn with its real phase: the terminator is the projected ellipse
  with semi-axis `r(2k − 1)`, rotated by the bright-limb position angle
  corrected for the parallactic angle, so the crescent tilts the way it really
  does in the sky.
- Dashed arcs showing the path each body traces over the displayed day.
- Tap either summary card to swing the view onto that body.

## Building

```bash
cd SkyHorizon
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Requirements: JDK 17, Android SDK with platform 34 and build-tools 34. The
Gradle wrapper (8.9) fetches Gradle itself; the Android Gradle Plugin (8.5.2)
and the AndroidX artifacts come from Google's Maven repository.

`.github/workflows/skyhorizon-android.yml` runs the same two commands on CI and
publishes `app-debug.apk` as a build artifact.

## Tests

```bash
./gradlew testDebugUnitTest
```

`HorizonCalculatorTest` checks the skyline maths against landscapes with a known
answer: a flat plain (where the result must equal the textbook dip of the
horizon, `sqrt(2h/R)`, at `sqrt(2hR)` away), a plateau at a known height and
distance, a single ridge, and the seam where the panorama wraps through north.

`AstroAccuracyTest` checks the engine against the worked examples in Meeus
(*Astronomical Algorithms*, 2nd ed.): example 25.b for the Sun, 47.a for the
Moon and 48.a for the illuminated fraction, plus invariants such as the solar
altitude at the solstice, the sense of the azimuth in both hemispheres, and the
direction of the parallax shift.

## Attribution

Map tiles © OpenStreetMap contributors, rendered with
[osmdroid](https://github.com/osmdroid/osmdroid).
