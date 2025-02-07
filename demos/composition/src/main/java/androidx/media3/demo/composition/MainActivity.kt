package androidx.media3.demo.composition

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.media3.demo.composition.ui.DropDownSpinner
import androidx.media3.demo.composition.ui.theme.CompositionDemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionDemoTheme {
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
        DropDownSpinner(
            expanded,
            selectedPreset,
            presetOptions.toList(),
            { newExpandedState -> expanded = newExpandedState },
            { newSelection -> selectedPreset = newSelection }
        )
        Button(onClick = {
            startPreviewActivity()
            Log.d("MainActivity", "Selected: $selectedPreset")
        }) {
            Text("Select")
        }
    }
}