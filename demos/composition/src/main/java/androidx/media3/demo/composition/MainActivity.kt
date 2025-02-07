package androidx.media3.demo.composition

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.media3.demo.composition.ui.theme.Androidxmedia3Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Androidxmedia3Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PresetSelector(
                        startPreviewActivity = ::startPreviewActivity,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    fun startPreviewActivity() {
        val intent = Intent(this, CompositionPreviewActivity::class.java)
        startActivity(intent)
    }
}

@Composable
fun PresetSelector(startPreviewActivity: () -> Unit, modifier: Modifier = Modifier) {
    val presetOptions = stringArrayResource(R.array.preset_configuration)
    var selectedPreset by remember { mutableStateOf(presetOptions[0]) }
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = "Choose a preset:",
            modifier = modifier
        )
        Box {
            OutlinedTextField(
                value = selectedPreset,
                onValueChange = { },
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        // Detect click event on TextField to expand/close dropdown
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            val upEvent = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                            upEvent?.let {
                                expanded = !expanded
                            }
                        }
                    },
                readOnly = true
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                presetOptions.forEach { presetText ->
                    DropdownMenuItem(
                        text = { Text(text = presetText) },
                        onClick = {
                            expanded = false
                            selectedPreset = presetText
                        })
                }
            }
        }
        Button(onClick = {
            startPreviewActivity()
            Log.d("MainActivity", "Selected: $selectedPreset")
        }) {
            Text("Select")
        }
    }
}