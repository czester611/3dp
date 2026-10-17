package pl.u1companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import pl.u1companion.ui.AppRoot
import pl.u1companion.ui.theme.U1Theme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // TODO(etap 2): obsługa intencji VIEW/SEND (import G-code/3MF/STL do biblioteki)
        setContent {
            U1Theme { AppRoot() }
        }
    }
}
