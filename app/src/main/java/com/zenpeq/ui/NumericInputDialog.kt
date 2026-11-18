package com.zenpeq.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.zenpeq.model.MAX_FREQ_HZ
import com.zenpeq.model.MAX_GAIN_DB
import com.zenpeq.model.MAX_PREAMP_DB
import com.zenpeq.model.MAX_Q
import com.zenpeq.model.MIN_FREQ_HZ
import com.zenpeq.model.MIN_PREAMP_DB
import com.zenpeq.model.MIN_Q
import java.util.Locale

enum class NumericField(val label: String, val unit: String, val min: Float, val max: Float, val decimals: Int) {
    FREQ("Frequency", "Hz", MIN_FREQ_HZ, MAX_FREQ_HZ, 1),
    GAIN("Gain", "dB", -MAX_GAIN_DB, MAX_GAIN_DB, 1),
    Q("Q factor", "", MIN_Q, MAX_Q, 2),
    PREAMP("Preamp", "dB", MIN_PREAMP_DB, MAX_PREAMP_DB, 1),
}

/** Accepts "4500", "4.5k", "4,5 kHz", "-3.5 dB". Returns null if unparsable or out of range. */
internal fun parseNumeric(text: String, field: NumericField): Float? {
    var t = text.trim().lowercase().replace(',', '.')
    t = t.removeSuffix("hz").removeSuffix("db").trim()
    var mult = 1f
    if (field == NumericField.FREQ && t.endsWith("k")) {
        t = t.dropLast(1).trim()
        mult = 1000f
    }
    val v = t.toFloatOrNull()?.times(mult) ?: return null
    return v.takeIf { it in field.min..field.max }
}

private fun fmt(v: Float, decimals: Int) =
    String.format(Locale.US, "%.${decimals}f", v).trimEnd('0').trimEnd('.')

@Composable
fun NumericInputDialog(
    field: NumericField,
    initial: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(fmt(initial, field.decimals)) }
    val value = parseNumeric(text, field)
    val suffix: (@Composable () -> Unit)? = if (field.unit.isEmpty()) null else ({ Text(field.unit) })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.label) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = value == null,
                suffix = suffix,
                supportingText = {
                    Text("${fmt(field.min, field.decimals)} to ${fmt(field.max, field.decimals)} ${field.unit}".trim())
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { value?.let(onConfirm) }),
            )
        },
        confirmButton = { TextButton(enabled = value != null, onClick = { value?.let(onConfirm) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
