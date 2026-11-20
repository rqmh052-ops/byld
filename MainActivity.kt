package app.htmlbuilder

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : Activity() {

    private var icon: ByteArray? = null
    private var apk: File? = null
    private var apkName = "app"
    private var web: WebView? = null
    private lateinit var etName: EditText
    private lateinit var etPkg: EditText
    private lateinit var etHtml: EditText
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        // If an HTML file is embedded, this APK is a generated app: just show it.
        if (assets.list("www")?.contains("index.html") == true) {
            val w = WebView(this)
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.webViewClient = WebViewClient()
            w.loadUrl("file:///android_asset/www/index.html")
            web = w
            setContentView(w)
        } else {
            buildUi()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (40 * dp).toInt(), pad, pad)
        }
        fun label(t: String) = TextView(this).apply {
            text = t; setTextColor(Color.LTGRAY); setPadding(0, (14 * dp).toInt(), 0, 4)
        }
        fun field(h: String, multi: Boolean = false) = EditText(this).apply {
            hint = h; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            if (multi) {
                minLines = 8; gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else setSingleLine()
        }
        fun button(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
        }

        root.addView(TextView(this).apply {
            text = "HTML إلى APK"; textSize = 24f; setTextColor(Color.WHITE)
        })
        root.addView(label("اسم التطبيق"))
        etName = field("مثال: تطبيقي"); root.addView(etName)
        root.addView(label("الـ Package"))
        etPkg = field("com.example.myapp"); etPkg.setText("com.example.myapp"); root.addView(etPkg)
        root.addView(label("الأيقونة (اختياري)"))
        root.addView(button("اختيار صورة") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 2
            )
        })
        root.addView(label("كود HTML"))
        etHtml = field("الصق الكود هنا", true); root.addView(etHtml)
        root.addView(button("أو اختر ملف HTML") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1
            )
        })
        root.addView(button("بناء APK") { startBuild() })
        status = TextView(this).apply { setTextColor(Color.parseColor("#7CE0B0")); setPadding(0, pad, 0, pad) }
        root.addView(status)
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        actions.addView(button("تثبيت") { install() })
        actions.addView(button("حفظ في التنزيلات") { saveToDownloads() })
        root.addView(actions)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101814")); addView(root)
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (res != RESULT_OK || uri == null) return
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (req == 1) {
                etHtml.setText(String(bytes, Charsets.UTF_8))
            } else if (req == 2) {
                val bmp = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size), 192, 192, true
                )
                val o = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
                icon = o.toByteArray()
                Toast.makeText(this, "تم اختيار الأيقونة", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            status.text = "خطأ في قراءة الملف: ${e.message}"
        }
    }

    private fun startBuild() {
        val name = etName.text.toString().trim()
        val pkg = etPkg.text.toString().trim()
        val html = etHtml.text.toString()
        if (name.isEmpty() || html.isBlank()) { status.text = "اكتب الاسم والـ HTML"; return }
        if (!Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$").matches(pkg) || pkg == packageName) {
            status.text = "الـ Package غير صالح أو مستخدم"; return
        }
        status.text = "جاري البناء..."
        actions.visibility = View.GONE
        Thread {
            try {
                val f = File(cacheDir, "out.apk")
                ApkBuilder.build(this, html.toByteArray(Charsets.UTF_8), name, pkg, icon, f)
                apk = f
                apkName = name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
                runOnUiThread { status.text = "تم البناء (${f.length() / 1024} KB)"; actions.visibility = View.VISIBLE }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "فشل: ${e}" }
            }
        }.start()
    }

    private fun install() {
        val f = apk ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            Toast.makeText(this, "فعّل السماح بالتثبيت ثم ارجع واضغط تثبيت", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val pi = packageManager.packageInstaller
            val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            pi.openSession(id).use { s ->
                s.openWrite("app.apk", 0, f.length()).use { o ->
                    f.inputStream().use { it.copyTo(o) }
                    s.fsync(o)
                }
                val pending = PendingIntent.getBroadcast(
                    this, id, Intent(this, InstallReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                s.commit(pending.intentSender)
            }
        } catch (e: Throwable) {
            status.text = "فشل التثبيت: $e"
        }
    }

    private fun saveToDownloads() {
        val f = apk ?: return
        try {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$apkName.apk")
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            status.text = "تم الحفظ في التنزيلات: $apkName.apk"
        } catch (e: Throwable) {
            status.text = "فشل الحفظ: $e"
        }
    }
}
