package com.tamimrijan.camora.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimrijan.camora.camera.CameraCapabilities
import com.tamimrijan.camora.camera.CameraController
import com.tamimrijan.camora.camera.CameraState
import com.tamimrijan.camora.ui.theme.Accent
import com.tamimrijan.camora.util.signed
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Manual controls. Each parameter is only offered if the module reports the Camera2
 * capability for it (MANUAL_SENSOR for ISO/shutter, a focus range for MF, AWB presets for
 * white balance). Values shown with "A" are the live auto values from capture results.
 */
@Composable
fun ProPanel(ui: CameraUi, cs: CameraState, vm: CameraViewModel) {
    val lens = cs.lens
    val m = ui.manual
    PanelSurface {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val manualSensor = lens?.manualSensor == true
            ParamButton("ISO", m.iso?.toString() ?: "A ${cs.iso}", ui.proParam == ProParam.ISO, manualSensor) { vm.selectProParam(ProParam.ISO) }
            ParamButton(
                "S", m.shutterNs?.let { CameraCapabilities.formatShutter(it) } ?: ("A " + if (cs.exposureNs > 0) CameraCapabilities.formatShutter(cs.exposureNs) else ""),
                ui.proParam == ProParam.SHUTTER, manualSensor,
            ) { vm.selectProParam(ProParam.SHUTTER) }
            ParamButton("WB", m.kelvin?.let { "${it}K" } ?: "AWB", ui.proParam == ProParam.WB, (lens?.awbModes?.size ?: 0) > 2) { vm.selectProParam(ProParam.WB) }
            ParamButton("MF", m.focus?.let { "%.2f".format(it) } ?: "AF", ui.proParam == ProParam.FOCUS, lens?.canManualFocus == true) { vm.selectProParam(ProParam.FOCUS) }
            ParamButton("EV", signed(cs.evIndex * cs.evStep), ui.proParam == ProParam.EV, m.iso == null && m.shutterNs == null && cs.evMax > cs.evMin) { vm.selectProParam(ProParam.EV) }
        }
        when (ui.proParam) {
            ProParam.ISO -> {
                val r = lens?.isoRange
                if (r != null) {
                    val v = m.iso ?: cs.iso.coerceIn(r.lower, r.upper)
                    LogSlider("ISO", v.toFloat(), r.lower.toFloat(), r.upper.toFloat(), "$v") { vm.setManual(m.copy(iso = it.roundToInt())) }
                    AutoButton(m.iso == null) { vm.setManual(m.copy(iso = null)) }
                }
            }
            ProParam.SHUTTER -> {
                val r = lens?.exposureRangeNs
                if (r != null) {
                    val lo = maxOf(r.lower, 125_000L).toFloat() // 1/8000
                    val hi = minOf(r.upper, 1_000_000_000L).toFloat() // 1 s is plenty hand-held
                    val v = (m.shutterNs ?: cs.exposureNs.coerceIn(lo.toLong(), hi.toLong())).toFloat()
                    LogSlider("Shutter", v, lo, hi, CameraCapabilities.formatShutter(v.toLong())) { vm.setManual(m.copy(shutterNs = it.toLong())) }
                    AutoButton(m.shutterNs == null) { vm.setManual(m.copy(shutterNs = null)) }
                }
            }
            ProParam.WB -> {
                val k = (m.kelvin ?: 5500).toFloat()
                LabeledSlider("White balance", k, 2500f..8000f, "${k.roundToInt()}K") {
                    val kk = (it / 50f).roundToInt() * 50
                    vm.setManual(m.copy(kelvin = kk, awbMode = CameraController.awbForKelvin(kk)))
                }
                AutoButton(m.kelvin == null) { vm.setManual(m.copy(kelvin = null, awbMode = null)) }
            }
            ProParam.FOCUS -> {
                val f = m.focus ?: 0f
                LabeledSlider("Focus (∞ to close)", f, 0f..1f, if (f < 0.01f) "∞" else "%.0f cm".format(100f / (f * (lens?.minFocusDistance ?: 10f)).coerceAtLeast(0.01f))) {
                    vm.setManual(m.copy(focus = it))
                }
                AutoButton(m.focus == null) { vm.setManual(m.copy(focus = null)) }
            }
            ProParam.EV -> {
                LabeledSlider(
                    "Exposure", cs.evIndex.toFloat(), cs.evMin.toFloat()..cs.evMax.toFloat(), "${signed(cs.evIndex * cs.evStep)} EV",
                    steps = (cs.evMax - cs.evMin - 1).coerceAtLeast(0),
                ) { vm.setExposureIndex(it.roundToInt()) }
            }
            null -> Text("Tap a parameter to set it manually. Unsupported controls are dimmed.", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ParamButton(label: String, value: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val c = if (!enabled) Color.White.copy(alpha = 0.3f) else if (selected) Accent else Color.White
        Text(label, color = c.copy(alpha = c.alpha * 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AutoButton(isAuto: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            if (isAuto) "AUTO" else "Set to auto", color = if (isAuto) Accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(4.dp))
    }
}

/** Slider on a logarithmic scale (ISO and shutter speed are perceived in stops). */
@Composable
private fun LogSlider(label: String, value: Float, lo: Float, hi: Float, text: String, onChange: (Float) -> Unit) {
    val lnLo = ln(lo)
    val lnHi = ln(hi)
    val pos = ((ln(value.coerceIn(lo, hi)) - lnLo) / (lnHi - lnLo)).coerceIn(0f, 1f)
    LabeledSlider(label, pos, 0f..1f, text) { p -> onChange(exp(lnLo + (lnHi - lnLo) * p)) }
}
