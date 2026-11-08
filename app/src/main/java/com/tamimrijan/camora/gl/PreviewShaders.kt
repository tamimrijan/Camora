package com.tamimrijan.camora.gl

/**
 * GLSL ES 3.00 shaders for the live viewfinder. The colour maths (sRGB transfer, local
 * tone curve, face lighting, highlight soft-clip, LUT blend) and the skin smoothing
 * mirror com.tamimrijan.camora.engine so the preview matches the processed photo.
 */
object PreviewShaders {

    const val VERTEX = """#version 300 es
in vec4 aPosition;
in vec4 aTexCoord;
uniform mat4 uTexMatrix;
out vec2 vTex;
void main() {
    gl_Position = aPosition;
    vTex = (uTexMatrix * aTexCoord).xy;
}
"""

    private const val COMMON = """
vec3 toLin(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}
vec3 toSrgb(vec3 l) {
    l = clamp(l, 0.0, 1.0);
    return mix(l * 12.92, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, l));
}
"""

    /** OES camera frame -> small linear-average illumination texture (stored sRGB-encoded). */
    const val DOWNSAMPLE = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform vec2 uStep;
in vec2 vTex;
out vec4 oColor;
""" + COMMON + """
void main() {
    vec3 acc = vec3(0.0);
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 off = (vec2(float(i), float(j)) - 1.5) * 0.25 * uStep;
            acc += toLin(texture(uTex, vTex + off).rgb);
        }
    }
    oColor = vec4(toSrgb(acc / 16.0), 1.0);
}
"""

    /** Tent blur of the illumination texture (smooth base layer). */
    const val BLUR = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uStep;
in vec2 vTex;
out vec4 oColor;
""" + COMMON + """
void main() {
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            float w = (3.0 - abs(float(i))) * (3.0 - abs(float(j)));
            acc += w * toLin(texture(uTex, vTex + vec2(float(i), float(j)) * uStep).rgb);
            wsum += w;
        }
    }
    oColor = vec4(toSrgb(acc / wsum), 1.0);
}
"""

    const val MAIN = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision mediump sampler3D;
uniform samplerExternalOES uTex;
uniform sampler2D uIllum;
uniform sampler3D uLut;
uniform sampler2D uMask;
uniform vec3 uWb;
uniform float uExposureEv;
uniform vec2 uTone;      // x = shadow lift EV, y = highlight compression EV
uniform float uLutMix;   // 0 = profile off
uniform float uBlur;     // portrait blur radius in texture units, 0 = off
uniform vec2 uAspect;    // makes the blur kernel circular in pixels
uniform mat3 uMaskXf;    // camera buffer uv -> segmentation mask uv
// faces (q-space = buffer pixels / buffer width)
uniform int uFaceCount;
uniform vec4 uFace[2];   // ellipse centre xy, radii zw
uniform vec4 uEyes[2];   // left eye xy, right eye zw
uniform vec4 uMouth[2];  // mouth xy, eye distance z, skin blur radius w
uniform vec3 uSkin;      // skin model: cb, cr, sigma
uniform vec3 uBeauty;    // smoothing, even tone, enabled
uniform vec2 uFaceLight; // face lift EV, face log2 luminance
uniform float uQy;       // buffer height / width
uniform vec2 uTexel;     // 1/width, 1/height of the camera buffer
in vec2 vTex;
out vec4 oColor;
""" + COMMON + """
float gainEv(float b, float lift, float comp) {
    float s = lift * smoothstep(-10.5, -6.5, b) * (1.0 - smoothstep(-5.0, -2.2, b));
    float h = -comp * smoothstep(-2.2, 0.2, b);
    return s + h;
}
vec3 softClip(vec3 c) {
    float m = max(c.r, max(c.g, c.b));
    if (m <= 0.9) return c;
    float mn = 0.9 + 0.1 * tanh((m - 0.9) / 0.1);
    vec3 v = c * (mn / m);
    float over = clamp((m - 1.0) / 1.5, 0.0, 1.0) * 0.6;
    return v + (vec3(mn) - v) * over;
}
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float spot(vec2 q, vec2 p, float r) { return 1.0 - smoothstep(0.75, 1.15, length(q - p) / r); }
void main() {
    vec2 q = vec2(vTex.x, vTex.y * uQy);
    vec3 c0 = texture(uTex, vTex).rgb;
    vec3 col = c0;

    // ---- faces: lighting mask and skin mask ------------------------------------------
    float faceW = 0.0;
    float beautyMask = 0.0;
    float radius = 0.0;
    for (int i = 0; i < 2; i++) {
        if (i >= uFaceCount) break;
        float d = length((q - uFace[i].xy) / uFace[i].zw);
        faceW = max(faceW, 1.0 - smoothstep(0.7, 1.0, d));
        if (uBeauty.z > 0.5) {
            float ell = 1.0 - smoothstep(0.75, 1.05, d);
            if (ell > 0.0) {
                float ed = uMouth[i].z;
                float feat = max(max(spot(q, uEyes[i].xy, ed * 0.45), spot(q, uEyes[i].zw, ed * 0.45)), spot(q, uMouth[i].xy, ed * 0.5));
                float bm = ell * (1.0 - feat);
                if (bm > beautyMask) {
                    beautyMask = bm;
                    radius = uMouth[i].w;
                }
            }
        }
    }

    // ---- skin smoothing with texture retention (mirrors BeautyPass) ------------------
    if (beautyMask > 0.01) {
        float y0 = luma(c0);
        vec2 dch = vec2(c0.b - y0, c0.r - y0) - uSkin.xy;
        float skin = exp(-dot(dch, dch) / (2.0 * uSkin.z * uSkin.z));
        float gate = smoothstep(0.06, 0.14, y0) * (1.0 - smoothstep(0.96, 1.0, y0));
        float m = beautyMask * skin * gate;
        if (m > 0.01) {
            // same slider curve as BeautyCurve in the photo engine
            float sv = uBeauty.x;
            float amt = 0.7 * sv + 0.1 * sv * sv;
            float keep = 0.6 - 0.18 * sv;
            float sig = 0.004 * (1.0 + 0.8 * sv);
            vec3 acc = c0;
            float ws = 1.0;
            for (int k = 0; k < 12; k++) {
                float fk = float(k);
                float rr = sqrt((fk + 0.5) / 12.0) * radius;
                float th = fk * 2.39996323;
                vec3 s = texture(uTex, vTex + vec2(cos(th), sin(th) / uQy) * rr).rgb;
                float dl = luma(s) - y0;
                float w = exp(-dl * dl / sig);
                acc += s * w;
                ws += w;
            }
            vec3 sm = acc / ws;
            vec3 nb = 0.25 * (texture(uTex, vTex + vec2(uTexel.x, 0.0)).rgb + texture(uTex, vTex - vec2(uTexel.x, 0.0)).rgb +
                texture(uTex, vTex + vec2(0.0, uTexel.y)).rgb + texture(uTex, vTex - vec2(0.0, uTexel.y)).rgb);
            col = mix(c0, sm + (c0 - nb) * keep, m * amt);
            // clean: lift small dark spots towards the surrounding skin
            float dark = luma(sm) - luma(col);
            if (dark > 0.0) col += vec3(m * 0.55 * sv * dark * (1.0 - smoothstep(0.10, 0.22, dark)));
            if (uBeauty.y > 0.0) {
                float yy = luma(col);
                vec2 ch = vec2(col.b - yy, col.r - yy);
                float ys = luma(sm);
                float k2 = m * uBeauty.y * 0.62;
                ch = mix(ch, vec2(sm.b - ys, sm.r - ys), k2);
                ch.y -= k2 * 0.35 * max(0.0, ch.y - uSkin.y);
                float r = yy + ch.y;
                float b = yy + ch.x;
                col = vec3(r, (yy - 0.299 * r - 0.114 * b) / 0.587, b);
            }
        }
    }

    vec3 lin = toLin(col);
    if (uBlur > 0.0) {
        float a = texture(uMask, (uMaskXf * vec3(vTex, 1.0)).xy).r;
        if (a < 0.98) {
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 24; i++) {
                float fi = float(i);
                float r = sqrt((fi + 0.5) / 24.0) * uBlur;
                float th = fi * 2.39996323;
                vec3 s = toLin(texture(uTex, vTex + vec2(cos(th), sin(th)) * r * uAspect).rgb);
                acc += s * (1.0 + 1.6 * smoothstep(0.55, 1.0, max(s.r, max(s.g, s.b))));
            }
            lin = mix(acc / 24.0, lin, a);
        }
    }

    // ---- exposure: global + local tone + lighting-aware face light (mirrors GainMap) -
    vec3 il = toLin(texture(uIllum, vTex).rgb);
    float rawBase = log2(max(dot(il, vec3(0.2126, 0.7152, 0.0722)), 1.0 / 16384.0));
    float local = gainEv(rawBase + uExposureEv, uTone.x, uTone.y);
    float gEv = uExposureEv + local;
    if (faceW > 0.0) {
        float d = (rawBase - uFaceLight.y) * 1.3;
        float wf = faceW * exp(-d * d / 2.88);
        gEv += wf * (uFaceLight.x - 0.65 * local);
    }
    vec3 s = toSrgb(softClip(lin * exp2(gEv) * uWb));
    if (uLutMix > 0.0) {
        vec3 lc = s * (32.0 / 33.0) + 0.5 / 33.0;
        s = mix(s, texture(uLut, lc).rgb, uLutMix);
    }
    oColor = vec4(s, 1.0);
}
"""
}
