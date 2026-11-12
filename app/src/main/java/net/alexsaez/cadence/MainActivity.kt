package net.alexsaez.cadence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.alexsaez.cadence.ui.CadenceRoot
import net.alexsaez.cadence.ui.theme.CadenceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer
        setContent {
            CadenceTheme {
                CadenceRoot(container)
            }
        }
    }
}
