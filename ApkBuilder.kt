package app.htmlbuilder

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Copies this app's own APK, adds assets/www/index.html, patches name/package/icon,
 * then signs it. This app acts as its own template.
 */
object ApkBuilder {

    private class Count(o: OutputStream) : FilterOutputStream(o) {
        var n = 0L
        override fun write(b: Int) { out.write(b); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); n += len }
    }

    private fun put(z: ZipOutputStream, c: Count, name: String, data: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        if (stored) {
            e.method = ZipEntry.STORED
            e.size = data.size.toLong()
            e.compressedSize = e.size
            e.crc = CRC32().also { it.update(data) }.value
            // align data to 4 bytes (required for resources.arsc on Android 11+)
            val base = c.n + 30 + name.toByteArray().size + 4
            val pad = ((4 - base % 4) % 4).toInt()
            e.extra = ByteArray(4 + pad).also {
                it[0] = 0x35; it[1] = 0xD9.toByte(); it[2] = pad.toByte()
            }
        }
        z.putNextEntry(e)
        z.write(data)
        z.closeEntry()
    }

    fun build(ctx: Context, html: ByteArray, name: String, pkg: String, icon: ByteArray?, out: File) {
        val oldPkg = ctx.packageName
        val oldLabel = ctx.packageManager.getApplicationLabel(ctx.applicationInfo).toString()
        val tmp = File(ctx.cacheDir, "unsigned.apk")
        val skip = setOf("assets/key.pk8", "assets/cert.der")
        val iconRe = Regex("^res/.*ic_launcher\\.png$")

        ZipFile(ctx.applicationInfo.sourceDir).use { zf ->
            val c = Count(BufferedOutputStream(FileOutputStream(tmp)))
            ZipOutputStream(c).use { z ->
                for (e in zf.entries().toList()) {
                    val n = e.name
                    if (e.isDirectory || n.startsWith("META-INF/") || n in skip) continue
                    var data = zf.getInputStream(e).use { it.readBytes() }
                    if (n == "AndroidManifest.xml") {
                        data = ManifestPatcher.patch(data, oldPkg, pkg, oldLabel, name)
                    } else if (icon != null && iconRe.matches(n)) {
                        data = icon
                    }
                    put(z, c, n, data, n == "resources.arsc" || e.method == ZipEntry.STORED)
                }
                put(z, c, "assets/www/index.html", html, false)
            }
        }

        val key = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(ctx.assets.open("key.pk8").use { it.readBytes() })
        )
        val cert = ctx.assets.open("cert.der").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val cfg = ApkSigner.SignerConfig.Builder("htmlapk", key, listOf(cert)).build()
        if (out.exists()) out.delete()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(tmp)
            .setOutputApk(out)
            .setMinSdkVersion(29)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()
        tmp.delete()
    }
}
