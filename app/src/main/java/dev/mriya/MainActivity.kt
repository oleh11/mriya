package dev.mriya

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.mriya.ui.MainViewModel
import dev.mriya.ui.MriyaApp
import dev.mriya.ui.MriyaTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShared(intent)
        setContent {
            MriyaTheme { MriyaApp(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /**
     * Something shared to Mriya (booking email text, boarding-pass PDF/screenshot, .ics file). It is untrusted data:
     * it is only parsed, offline, and the user reviews the result before anything is added.
     */
    private fun handleShared(i: Intent?) {
        i ?: return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
                val text = i.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    uri != null -> vm.importUri(uri)
                    !text.isNullOrBlank() -> vm.importText(listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), text).joinToString(separator = System.lineSeparator()))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
                if (uris.isNotEmpty()) vm.importUris(uris)
            }
            Intent.ACTION_VIEW -> i.data?.let { d ->
                if (d.scheme?.startsWith("com.googleusercontent.apps.") == true) vm.completeGmailSignIn(d.toString()) else vm.importUri(d)
            }
        }
        // Handled: don't re-run on rotation or when the activity is brought back to the front.
        setIntent(Intent(this, MainActivity::class.java))
    }
}
