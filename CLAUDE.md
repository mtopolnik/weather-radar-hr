# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

## Project Overview

Android weather radar app ("Vrijeme na radaru") displaying animated radar
imagery from Croatian (DHMZ) and Slovenian (ARSO) sources, as well as satellite
imager from European (met.no) sources with geolocation overlay. Written in
Kotlin, licensed under GPLv3.

## Build Commands

```bash
./gradlew assembleDebug      # Debug build
./gradlew assembleRelease    # Release build (with ProGuard minification)
./gradlew clean              # Clean build artifacts
```

No automated tests exist in this project.

## Architecture

**Single-module Android app** (`app/`) with package
`com.belotron.weatherradarhr`.

### Core Data Flow

`AnimationSource` enum (in `application.kt`) is the central configuration hub —
each entry defines a radar source with its title, `MapProjection` (geographic
coordinate mapping), and `FrameSequenceLoader` (how to fetch/decode frames).
Nine radar sources are supported: HR_KOMPOZIT, AT_ZAMG, SLO_ARSO, and six
individual Croatian radar stations.

Frame loading pipeline: `FrameSequenceLoader` → HTTP fetch with caching
(`image_request.kt`) → GIF decoding (`gifdecode/`) → OCR timestamp extraction
(`ocr.kt`) → animation playback (`animation.kt`) → display in `MainFragment`.

### Key Components

- **MainFragment** — Primary UI showing radar animation with playback controls.
  Manages multiple `ImageBundle` instances (one per enabled radar source).
- **TouchImageView** — Custom `ImageView` with pinch-to-zoom, pan, and rotation
  gestures.
- **ImageViewWithLocation** — Extends ImageView to overlay user's GPS position
  on radar images using `MapProjection`.
- **location.kt** — Fused Location Provider integration, coordinate-to-pixel
  mapping via map projections.
- **ocr.kt** — `HrOcr` and `SloOcr` extract timestamps from radar image pixels.
  Two strategies: `ocrTimestampKompozit` (composite) and `ocrTimestampSingle`
  (individual stations).
- **widget.kt** — Eight home-screen widget providers (one per radar source
  except met.no), with `RefreshImageService` and `UpdateAgeService` for
  background updates.
- **gifdecode/** — Custom GIF decoder with `BitmapFreelists` for bitmap object
  pooling to reduce GC pressure.

### Async Model

Kotlin Coroutines throughout. `appCoroScope` (Main dispatcher) is created in
`MyApplication.onCreate()`. Frame fetching uses semaphore-controlled
parallelism. Jobs are structured for proper cancellation.

### Conventions

- Top-level Kotlin files with extension functions are preferred over utility
  classes (e.g., `application.kt`, `preferences.kt`).
- View Binding (not synthetics) for layout access.
- JVM toolchain targets Java 8.
- Localized for Croatian (hr), Slovenian (sl), Bosnian (bs), and Serbian Latin
  (b+sr+Latn).
