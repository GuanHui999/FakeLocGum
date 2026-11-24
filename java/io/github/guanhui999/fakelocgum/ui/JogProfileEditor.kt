package io.github.guanhui999.fakelocgum.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.guanhui999.fakelocgum.core.JogProfile
import io.github.guanhui999.fakelocgum.core.SpoofConfig

/** Profile settings stay local while a slider is dragged; commit once on release. */
@Composable
internal fun JogProfileEditor(config: SpoofConfig, onUpdate: ((SpoofConfig) -> SpoofConfig) -> Unit) {
    val p = config.jogProfile.validated()
    var open by rememberSaveable { mutableStateOf(false) }
    fun change(block: (JogProfile) -> JogProfile) = onUpdate { it.copy(jogProfile = block(it.jogProfile).validated()) }
    ToggleRow(title = "慢跑联动模型", subtitle = "同一运动轨迹驱动位置、步频、步幅和 Gum；手动覆盖模式优先",
        checked = p.enabled, onCheckedChange = { on -> change { it.copy(enabled = on) } })
    Text("基准 %.0f 步/分 × %.2f m ≈ %.3f m/s；默认目标 3 m/s".format(p.cadenceSpm, p.strideMeters, p.referenceSpeed),
        style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick = { open = !open }) { Text(if (open) "收起慢跑参数" else "配置慢跑参数") }
        TextButton(onClick = { onUpdate { it.copy(jogProfile = JogProfile(), maxSpeedMps = 3f, manualSensorMode = false) } }) { Text("恢复慢跑基准") }
    }
    if (open) {
        SliderRow("基准步频", p.cadenceSpm.toFloat(), 80f..220f, unit = " 步/分", decimals = 0,
            onValueChange = { v -> change { it.copy(cadenceSpm = v.toDouble()) } })
        SliderRow("参考速度附近步频下限", p.cadenceMinSpm.toFloat(), 60f..p.cadenceSpm.toFloat(), unit = " 步/分", decimals = 0,
            onValueChange = { v -> change { it.copy(cadenceMinSpm = v.toDouble()) } })
        SliderRow("参考速度附近步频上限", p.cadenceMaxSpm.toFloat(), p.cadenceSpm.toFloat()..240f, unit = " 步/分", decimals = 0,
            onValueChange = { v -> change { it.copy(cadenceMaxSpm = v.toDouble()) } })
        SliderRow("基准步幅", p.strideMeters.toFloat(), 0.3f..2f, unit = " m", decimals = 2,
            onValueChange = { v -> change { it.copy(strideMeters = v.toDouble()) } })
        SliderRow("步幅扰动限幅", (p.strideVariation * 100).toFloat(), 0f..15f, unit = " %", decimals = 1,
            onValueChange = { v -> change { it.copy(strideVariation = v.toDouble() / 100) } })
        SliderRow("速度—步频联动系数", p.speedElasticity.toFloat(), 0.1f..0.8f, decimals = 2,
            subtitle = "其余速度变化由步幅承担，始终满足速度 = 步频 × 步幅",
            onValueChange = { v -> change { it.copy(speedElasticity = v.toDouble()) } })
        SliderRow("起步渐升时长", p.rampUpSeconds.toFloat(), 0.5f..20f, unit = " s", decimals = 1,
            onValueChange = { v -> change { it.copy(rampUpSeconds = v.toDouble()) } })
        SliderRow("松手减速时长", p.rampDownSeconds.toFloat(), 0.2f..10f, unit = " s", decimals = 1,
            subtitle = "松手按此时长减速；关闭模拟仍立即停止",
            onValueChange = { v -> change { it.copy(rampDownSeconds = v.toDouble()) } })
        SliderRow("随机变化时间尺度", p.fluctuationSeconds.toFloat(), 0.5f..15f, unit = " s", decimals = 1,
            onValueChange = { v -> change { it.copy(fluctuationSeconds = v.toDouble()) } })
        TextButton(onClick = { change { it.copy(seed = System.nanoTime()) } }) { Text("更换随机种子（${p.seed}）") }
    }
    config.gaitFrame?.let { f ->
        Spacer(Modifier.height(4.dp))
        Text("实际 %.2f m/s · %.1f 步/分 · 即时步幅 %.3f m".format(f.speedMps, f.cadenceSpm, f.strideMeters),
            style = MaterialTheme.typography.bodySmall)
        Text("本段 %d 步 · %.2f m · %.1f s".format(f.steps, f.distanceMeters, f.elapsedSeconds),
            style = MaterialTheme.typography.bodySmall)
        Text("已落地距离 %.3f = %d × %.4f；步内余量 %.3f m".format(f.completedMeters, f.steps, f.averageStride, f.remainderMeters),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
