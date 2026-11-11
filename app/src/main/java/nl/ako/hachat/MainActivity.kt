package nl.ako.hachat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import nl.ako.hachat.ui.chat.ChatScreen
import nl.ako.hachat.ui.settings.SettingsScreen
import nl.ako.hachat.ui.settings.ToolsScreen
import nl.ako.hachat.ui.theme.AkoTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AkoTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "chat") {
                    composable("chat") { ChatScreen(onOpenSettings = { nav.navigate("settings") }) }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() }, onOpenTools = { nav.navigate("tools") })
                    }
                    composable("tools") { ToolsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}
