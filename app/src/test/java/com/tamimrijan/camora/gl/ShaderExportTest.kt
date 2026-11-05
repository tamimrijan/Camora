package com.tamimrijan.camora.gl

import com.tamimrijan.camora.engine.ColorProfileId
import com.tamimrijan.camora.engine.LutBuilder
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Exports the preview shaders and the Natural LUT so they can be run with WebGL2 (also
 * GLSL ES 3.00) as a desktop check:
 *  - shaders.html compiles and links every program
 *  - parity.html renders the colour chart through the real MAIN shader and compares it
 *    with the CPU engine's LUT output (chart_natural.png), i.e. preview vs photo colour.
 */
class ShaderExportTest {
    private fun js(s: String) = "`" + s.replace("\\", "\\\\").replace("`", "\\`") + "`"

    private val web = "function web(src){ return src.replace(/#extension GL_OES_EGL_image_external_essl3 : require\\n/, '').replace(/samplerExternalOES/g, 'sampler2D'); }\n"

    @Test
    fun exportShaders() {
        val dir = File("build/engine-samples").apply { mkdirs() }
        val shaders = mapOf(
            "VERTEX" to PreviewShaders.VERTEX,
            "DOWNSAMPLE" to PreviewShaders.DOWNSAMPLE,
            "BLUR" to PreviewShaders.BLUR,
            "MAIN" to PreviewShaders.MAIN,
        )
        val srcJs = buildString {
            append("const S = {\n")
            shaders.forEach { (k, v) -> append("$k: ${js(v)},\n") }
            append("};\n")
            append(web)
        }
        File(dir, "shaders.js").writeText(srcJs)
        val lut = LutBuilder.forProfile(ColorProfileId.NATURAL)!!
        File(dir, "lut_natural.js").writeText("const LUT_SIZE = ${lut.size}; const LUT_B64 = '${Base64.getEncoder().encodeToString(lut.toRgba8())}';\n")

        File(dir, "shaders.html").writeText(
            """
            <!doctype html><html><body style='font:13px monospace;background:#111;color:#eee'><pre id=o></pre>
            <script src='shaders.js'></script><script>
            const gl = document.createElement('canvas').getContext('webgl2');
            const out = [];
            function compile(type, name, src){
              const s = gl.createShader(type); gl.shaderSource(s, web(src)); gl.compileShader(s);
              const ok = gl.getShaderParameter(s, gl.COMPILE_STATUS);
              out.push(name + ': ' + (ok ? 'OK' : 'FAIL\n' + gl.getShaderInfoLog(s))); return ok ? s : null;
            }
            const vs = compile(gl.VERTEX_SHADER, 'VERTEX', S.VERTEX);
            for (const n of ['DOWNSAMPLE','BLUR','MAIN']) {
              const fs = compile(gl.FRAGMENT_SHADER, n, S[n]);
              if (vs && fs) { const p = gl.createProgram(); gl.attachShader(p, vs); gl.attachShader(p, fs);
                gl.bindAttribLocation(p, 0, 'aPosition'); gl.bindAttribLocation(p, 1, 'aTexCoord'); gl.linkProgram(p);
                out.push(n + ' link: ' + (gl.getProgramParameter(p, gl.LINK_STATUS) ? 'OK' : 'FAIL ' + gl.getProgramInfoLog(p))); }
            }
            document.getElementById('o').textContent = out.join('\n'); window.RESULT = out.join('\n');
            </script></body></html>
            """.trimIndent(),
        )

        File(dir, "parity.html").writeText(
            """
            <!doctype html><html><body style='font:13px monospace;background:#111;color:#eee'><pre id=o>running…</pre>
            <script src='shaders.js'></script><script src='lut_natural.js'></script><script>
            function load(src){ return new Promise(r => { const i = new Image(); i.onload = () => r(i); i.src = src + '?t=' + Date.now(); }); }
            function pixels(img){ const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
              const x = c.getContext('2d'); x.drawImage(img, 0, 0); return x.getImageData(0, 0, img.width, img.height).data; }
            (async () => {
              const src = await load('chart_original.png'); const ref = await load('chart_natural.png');
              const W = src.width, H = src.height;
              const cv = document.createElement('canvas'); cv.width = W; cv.height = H;
              const gl = cv.getContext('webgl2', { preserveDrawingBuffer: true });
              function sh(t, s){ const o = gl.createShader(t); gl.shaderSource(o, web(s)); gl.compileShader(o); return o; }
              const p = gl.createProgram(); gl.attachShader(p, sh(gl.VERTEX_SHADER, S.VERTEX)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, S.MAIN));
              gl.bindAttribLocation(p, 0, 'aPosition'); gl.bindAttribLocation(p, 1, 'aTexCoord'); gl.linkProgram(p); gl.useProgram(p);
              function tex(unit, target){ const t = gl.createTexture(); gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(target, t);
                gl.texParameteri(target, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(target, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
                gl.texParameteri(target, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(target, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
                if (target === gl.TEXTURE_3D) gl.texParameteri(target, gl.TEXTURE_WRAP_R, gl.CLAMP_TO_EDGE); return t; }
              // camera frame stand-in (nearest, so patch edges don't blend)
              tex(0, gl.TEXTURE_2D); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
              gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
              gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, src);
              gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
              tex(1, gl.TEXTURE_2D); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([118,118,118,255]));
              tex(2, gl.TEXTURE_3D); const bin = Uint8Array.from(atob(LUT_B64), c => c.charCodeAt(0));
              gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1); gl.texImage3D(gl.TEXTURE_3D, 0, gl.RGBA8, LUT_SIZE, LUT_SIZE, LUT_SIZE, 0, gl.RGBA, gl.UNSIGNED_BYTE, bin);
              tex(3, gl.TEXTURE_2D); gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, 1, 1, 0, gl.RED, gl.UNSIGNED_BYTE, new Uint8Array([255]));
              const U = n => gl.getUniformLocation(p, n);
              gl.uniform1i(U('uTex'), 0); gl.uniform1i(U('uIllum'), 1); gl.uniform1i(U('uLut'), 2); gl.uniform1i(U('uMask'), 3);
              gl.uniform3f(U('uWb'), 1, 1, 1); gl.uniform1f(U('uExposureEv'), 0); gl.uniform2f(U('uTone'), 0, 0);
              gl.uniform1f(U('uLutMix'), 1); gl.uniform1f(U('uBlur'), 0); gl.uniform2f(U('uAspect'), 1, 1);
              gl.uniformMatrix3fv(U('uMaskXf'), false, [1,0,0,0,1,0,0,0,1]);
              gl.uniformMatrix4fv(U('uTexMatrix'), false, [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]);
              const pos = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, pos); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, 1,1]), gl.STATIC_DRAW);
              gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
              const tc = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, tc); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0,0, 1,0, 0,1, 1,1]), gl.STATIC_DRAW);
              gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 0, 0);
              gl.viewport(0, 0, W, H); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
              const g = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, g);
              const r = pixels(ref); let sum = 0, max = 0, n = 0, worst = '';
              // sample patch centres only (40 px into each 80 px patch)
              for (let py = 40; py < H; py += 80) for (let px = 40; px < W; px += 80) {
                const gi = ((H - 1 - py) * W + px) * 4, ri = (py * W + px) * 4;
                for (let k = 0; k < 3; k++) { const d = Math.abs(g[gi + k] - r[ri + k]); sum += d; n++; if (d > max) { max = d; worst = px + ',' + py; } }
              }
              window.RESULT = 'patches=' + (n / 3) + ' meanAbsDiff=' + (sum / n).toFixed(2) + ' maxDiff=' + max + ' (at ' + worst + ')';
              document.getElementById('o').textContent = window.RESULT;
            })();
            </script></body></html>
            """.trimIndent(),
        )
    }
}
