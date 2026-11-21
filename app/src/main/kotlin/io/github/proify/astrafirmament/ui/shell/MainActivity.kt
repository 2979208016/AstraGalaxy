package io.github.proify.astrafirmament.ui.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** 主界面：挂 Compose 外壳，主题跟随系统深浅色（COUI）。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            App(initialTab = intent?.getIntExtra(EXTRA_TAB, 0) ?: 0)
        }
    }

    companion object {
        const val EXTRA_TAB = "xinghe_tab"
    }
}
