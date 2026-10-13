package com.rejwane.tiktokgrab

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val count = importCookies(uri)
        Toast.makeText(this, if (count > 0) getString(R.string.imported, count)
        else getString(R.string.import_failed), Toast.LENGTH_LONG).show()
        if (count > 0 && Cookies.hasLogin()) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    /** Lets you reuse the PC export once instead of typing a password in the WebView. */
    private fun importCookies(uri: Uri): Int {
        var count = 0
        val manager = CookieManager.getInstance()
        try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { line ->
                    if (line.startsWith("#") || line.isBlank()) return@forEachLine
                    val parts = line.split("\t")
                    if (parts.size < 7) return@forEachLine
                    val value = parts[6].trim()
                    if (!parts[0].contains("tiktok.com") || value.isEmpty()) return@forEachLine
                    manager.setCookie("https://www.tiktok.com", "${parts[5]}=$value")
                    count++
                }
            }
        } catch (e: Exception) {
            return 0
        }
        manager.flush()
        return count
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_login)

        Cookies.enable()
        val web = findViewById<WebView>(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            // TikTok keeps trying to hand off to its own app (snssdk1233://…); following that
            // would wipe the login page and leave the WebView with no session at all.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url?.scheme ?: return true
                return scheme != "http" && scheme != "https"
            }
        }
        web.loadUrl("https://www.tiktok.com/login")

        findViewById<Button>(R.id.importBtn).setOnClickListener {
            picker.launch(arrayOf("text/plain", "*/*"))
        }
        findViewById<Button>(R.id.doneBtn).setOnClickListener {
            CookieManager.getInstance().flush()
            if (Cookies.hasLogin()) {
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                Toast.makeText(this, "Not logged in to TikTok — try again", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        super.onDestroy()
    }
}
