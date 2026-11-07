# Camora: architecture and the Natural image engine

## The two paths: live preview and final photo

```
                ┌──────────── CameraX session (Camera2 interop where needed) ─────────────┐
 sensor ─ ISP ──┤ Preview stream ──► GPU SurfaceProcessor ──► viewfinder                   │
                │ ImageAnalysis 640×480 ──► ML Kit faces (metering) / segmentation (blur)  │
                │ ImageCapture JPEG q98 ──► CapturePipeline ──► Natural engine ──► Gallery │
                └──────────────────────────────────────────────────────────────────────────┘
```

The preview and the photo share the **same decisions and the same colour LUT**:

| Stage | Live preview (GPU, every frame) | Final photo (CPU, full resolution) |
|---|---|---|
| Scene analysis | 96 px illumination map, read back every 6th frame → `SceneAnalyzer` | box-downscaled analysis image → `SceneAnalyzer` |
| WB / exposure | uniforms from the analyser, smoothed over time | same `ToneParams` |
| Local HDR | blurred illumination map → `gainEv()` curve | guided-filter base layer → same curve, joint-bilateral upsampling |
| Highlights | the same tanh soft-clip, in GLSL | `SoftClip` |
| Colour profile | 33³ LUT as a 3D texture, mixed by intensity | the same LUT, tetrahedral interpolation |
| Portrait | 24-tap blur using the live ML Kit mask | segmentation, guided refinement, disc blur in linear light |
| Faces | live ML Kit tracking (with eyes and mouth) → same face lighting and skin smoothing / even tone, on skin only | BeautyPass + lighting-aware face exposure |
| Only in the photo | — | chroma/luma noise reduction, skin-aware sharpening, HDR burst merge |

A WebGL2 check renders a colour chart through the real preview shader. Its
output is within 1/255 of the CPU engine's (`ShaderExportTest` →
`app/build/engine-samples/parity.html`).

## Engine stages (`engine/NaturalEngine.kt`)
1. **Analysis image.** An area-average downscale to about 1024 px on the long
   side.
2. **SceneAnalyzer.** Computes luminance percentiles. Global exposure gets a
   gentle nudge, capped at −0.4/+0.6 EV, and never pushes highlights into
   clipping. Local HDR strength is scaled by the scene's real dynamic range.
   White balance is grey-world on near-neutrals only, at most ±7 %, and warm
   light keeps some of its warmth.
3. **Faces** (ML Kit) give a feathered mask. Face exposure is lighting-aware:
   only backlit or clearly underexposed faces are lifted, so skin tone is
   never lightened. A brightness gate keeps the correction off the sky around
   the head.
4. **Gain map.** An edge-aware base layer at 1/8 scale is turned into an EV
   map, then upsampled with joint-bilateral weights, so there are no halos.
5. **Beauty** (only when enabled) works on crops around faces. It uses a skin
   model learned from each face, excludes eyes, brows and lips, smooths with a
   guided filter, and adds back 60 % of the fine texture.
6. **Portrait.** The mask is refined with a guided filter. The background is
   blurred with a disc kernel in linear light, highlights become bokeh discs,
   the subject never bleeds into the blur, and matching grain is added.
7. **Main pass.** Bands of 64 rows run on every core. Each band applies chroma
   noise reduction, then luma noise reduction at high ISO, then WB × exposure
   × local gain in linear light, then the highlight soft-clip, then the
   profile LUT, then luma-only sharpening with coring and an overshoot clamp
   (reduced on faces, and none on portrait backgrounds).

**Colour profiles** (`engine/ColorProfile.kt`) are parametric looks evaluated
in OKLab/OKLCh:
* luminance-preserving white-balance shift
* a monotone tone curve on lightness
* vibrance
* hue-selective tweaks for skin, foliage, sky and reds, gated off near
  neutrals
* a soft chroma knee
* gamut mapping at constant lightness and hue

**HDR On** (`engine/MultiFrameMerger.kt`) shoots three frames at −1 EV with
AE/AWB locked. The sharpest frame becomes the reference. The others are
aligned per 128 px tile, coarse to fine, and merged with per-pixel robustness
weights so nothing ghosts. Tone mapping then adds +1 EV back.

## Why this technology mix
* **CameraX first.** When the phone refuses a stream combination, the
  controller automatically retries simpler configurations: without analysis,
  without high-res, then without the GPU effect.
* **Camera2 interop** is used only where needed, and every option is gated on
  the capabilities the module reports (`camera/CameraCapabilities.kt`).
* **GPU for the preview, CPU for the photo.** The CPU path has full float
  precision, works in bands (no GL texture size limit at 50 MP), and is
  unit-tested on the desktop.
* **No OpenCV.** Box and guided filters, disc blur, alignment and LUTs are all
  implemented in O(1)-per-pixel Kotlin. OpenCV would add about 20–30 MB of
  native libraries.
* **ML Kit with bundled models** runs fully offline, and its usage logging is removed
  in the manifest. The only network code is the user initiated update check
  (`data/UpdateManager.kt`), which talks to the GitHub releases API.

## Performance
* Photos develop on a background queue (up to 3 waiting), so the shutter
  stays responsive.
* Full-resolution bitmaps live in native memory. The engine reuses per-worker
  band buffers and guided-filter scratch arrays, so there is no per-band
  allocation churn.
* The viewfinder makes three small passes plus one full-screen pass per frame,
  with cached uniforms. The 27 KB statistics readback happens every 6th frame.
  The histogram and live ISO/shutter readout are only produced in Pro mode.
* Live statistics are kept out of the main camera UI state, so the camera
  screen doesn't recompose several times per second.
* A 12 MP photo takes about 1 s on a 6-core desktop JVM, and should take
  roughly 1.5–3 s on the Exynos 1380 in a release build.
