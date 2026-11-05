# Camora on the Samsung Galaxy M35 5G

## Hardware
| | |
|---|---|
| SoC | Exynos 1380 (4× Cortex-A78 + 4× Cortex-A55), Mali-G68 MP5 GPU |
| Rear main | 50 MP, f/1.8, **OIS** (pixel-binned to about 12.5 MP by default) |
| Rear ultra-wide | 8 MP, f/2.2, 123° |
| Rear macro | 2 MP, f/2.4 |
| Front | 13 MP, f/2.2, fixed focus |

## What third-party apps get
Samsung exposes fewer capabilities to third-party apps than to its own camera
app, and the set varies by firmware. Camora checks everything at runtime. To
see the real values on your phone, open **Settings → App → Device camera
report**.

| Feature | Expected on the M35 | If not available |
|---|---|---|
| Visible cameras | Rear main and front. The ultra-wide and macro may be hidden | The .6× chip only appears if an ultra-wide is exposed |
| Full 50 MP | Usually not offered (CameraX can't use the maximum-resolution pixel mode) | 12.5 MP binned photos, which have lower noise in most light |
| OIS | Main camera | Applied only if the camera reports it |
| Manual ISO/shutter | Rear (`MANUAL_SENSOR`), maybe not the front | The Pro controls are dimmed |
| Manual focus | Rear only (the front is fixed focus) | MF is dimmed |
| Depth sensor | None | Portrait uses person segmentation, so it works for people only |
| Shutter sound | Some regions force it | Camora follows the regional rule |

HDR On is limited to photos of 16 MP or less, for memory. At 50 MP, a single
frame is developed.

## Test checklist
**Selfie:** indoor ceiling light, window light, and backlit. Skin should look
natural, with no halo around the head. Try skin smoothing at 0, 50 and 100:
texture should always remain. Check the screen flash in a dark room and the
mirror setting.

**Rear daylight:** blue sky with no colour shift, foliage that isn't neon,
fine texture without edge halos, and HDR Auto, On and Off on a high-contrast
scene.

**Indoor:** a warm lamp should stay pleasantly warm. Check faces at 1–3 m.

**Portrait:** a person at 1–2 m with the background further away. Look at the
blur slider, the edges, and whether the preview matches the result.

**Pro:** ISO and shutter change the preview live, the histogram moves, and
the WB and MF controls work.

**General:**
- Rotate the phone: icons should turn and photos should save upright.
- Volume key shutter, timer, the 1:1 and Full aspect ratios, and HEIF.
- Gallery viewer: Edit, Share, Details, Delete.
- Settings tabs, the About links, and light and dark themes.

## Reporting a problem
Send the **Device camera report** (Settings → App → Copy), a description of
what happened, and the photo if it's an image-quality issue. The photo's
Details show what the engine did. For crashes, connect the phone and run:
```bash
adb logcat -d -s AndroidRuntime CamoraPreview CameraController CapturePipeline
```
