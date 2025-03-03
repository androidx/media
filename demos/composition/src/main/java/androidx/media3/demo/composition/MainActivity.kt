package androidx.media3.demo.composition

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.unit.dp
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.COMPOSITION_LAYOUT
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.LAYOUT_EXTRA
import androidx.media3.demo.composition.ui.DropDownSpinner
import androidx.media3.demo.composition.ui.theme.CompositionDemoTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionDemoTheme {
                Scaffold(
                    topBar = { TopAppBar(title = { Text(text = "Composition Demo") }) },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    PresetSelector(
                        startPreviewActivity = ::startPreviewActivity,
                        modifier = Modifier.padding(innerPadding).padding(8.dp).fillMaxWidth()
                    )
                }
            }
        }
    }

    fun startPreviewActivity(layoutSelection: String) {
        val intent = Intent(this, CompositionPreviewActivity::class.java)
        Log.d("CPVMF", "Sending layout of $layoutSelection")
        intent.putExtra(LAYOUT_EXTRA, layoutSelection)
        startActivity(intent)
    }
}

@Composable
fun PresetSelector(startPreviewActivity: (String) -> Unit, modifier: Modifier = Modifier) {
    var selectedPreset by remember { mutableStateOf(COMPOSITION_LAYOUT[0]) }
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        Text(text = "Choose a layout preset:")
        DropDownSpinner(
            expanded,
            selectedPreset,
            COMPOSITION_LAYOUT,
            { newExpandedState -> expanded = newExpandedState },
            { newSelection -> selectedPreset = newSelection },
            Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                startPreviewActivity(selectedPreset)
                Log.d("MainActivity", "Selected: $selectedPreset")
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Select")
        }
    }
}