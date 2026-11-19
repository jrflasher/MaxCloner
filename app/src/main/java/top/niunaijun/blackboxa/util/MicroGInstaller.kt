package top.niunaijun.blackboxa.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.frameworks.BActivityManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * MicroG — pengganti Google Play Services yang gratis dan open-source.
 *
 * GMS Manager bawaan hanya bisa memasang Google asli kalau HP sudah punya
 * Google Play Services. Di HP tanpa Google (atau yang Google-nya tidak mau
 * dipinjam ke ruang virtual), clone yang butuh Google tetap gagal jalan.
 * MicroG mengisi kekosongan itu: GmsCore-nya menyediakan login Google,
 * Maps, lokasi dan push notification untuk aplikasi clone.
 *
 * APK diunduh sekali dari rilis resmi microG di GitHub (±108 MB), lalu
 * dipasang ke semua ruang clone (semua user BlackBox).
 */
object MicroGInstaller {

    const val MICROG_VERSION = "v0.3.18.252432"

    const val MICROG_APK_URL =
        "https://github.com/microg/GmsCore/releases/download/v0.3.18.252432/com.google.android.gms-252432036.apk"

    const val GMS_PKG = "com.google.android.gms"

    /** Paket infra Google yang tidak boleh muncul di daftar clone (halaman utama). */
    val HIDDEN_SYSTEM_PACKAGES = setOf(
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.gsf.login",
        "com.android.vending"
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Pastikan MicroG aktif di semua ruang, tanpa campur tangan pengguna.
     * Dijalankan diam-diam di background thread saat aplikasi dibuka:
     * ruang yang belum punya MicroG akan dipasangi (file diunduh sekali
     * saja lalu disimpan di cache), lalu service broker MicroG
     * dinyalakan di background supaya clone yang butuh Google langsung
     * jalan. Tidak ada dialog, tidak ada toast.
     */
    fun ensureActive() {
        Thread {
            try {
                val users = try { BlackBoxCore.get().users } catch (e: Exception) { return@Thread }
                for (user in users) {
                    try {
                        if (!isInstalled(user.id)) {
                            installToUserSync(user.id)
                        }
                    } catch (e: Exception) {
                        // Lanjutkan ke ruang berikutnya; aktivasi bisa dicoba lagi lain kali.
                    }
                }
                for (user in users) {
                    if (isInstalled(user.id)) {
                        startBackground(user.id)
                    }
                }
            } catch (e: Exception) {
                // Diam-diam saja: MicroG tidak wajib untuk clone yang tidak butuh Google.
            }
        }.start()
    }

    /**
     * Nyalakan service broker MicroG di background untuk satu ruang, supaya
     * MicroG sudah berjalan sebelum ada clone yang memanggil API Google.
     * Best-effort: kegagalan diabaikan, karena service juga akan bangun
     * sendiri saat clone pertama kali memakai Google API.
     */
    fun startBackground(userId: Int) {
        try {
            val intent = Intent("com.google.android.gms.common.service.START")
                .setPackage(GMS_PKG)
            BActivityManager.get().startService(intent, null, false, userId)
        } catch (e: Exception) {
            // Abaikan: service akan jalan saat dibutuhkan oleh clone.
        }
    }

    fun isInstalled(userId: Int): Boolean {
        return try {
            BlackBoxCore.get().isInstalled(GMS_PKG, userId)
        } catch (e: Exception) {
            false
        }
    }

    fun isInstalledAnywhere(): Boolean {
        return try {
            BlackBoxCore.get().users.any { isInstalled(it.id) }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Aktifkan MicroG untuk satu ruang (satu user BlackBox) saja.
     * File MicroG diunduh sekali saja dan disimpan di cache — sesudah itu
     * aktivasi per ruang tidak perlu download lagi. Dipanggil dari
     * background thread; callback selalu di main thread.
     */
    fun installToUser(userId: Int, onResult: (Boolean) -> Unit) {
        Thread {
            val ok = try {
                installToUserSync(userId)
            } catch (e: Exception) {
                false
            }
            mainHandler.post { onResult(ok) }
        }.start()
    }

    fun installToUserSync(userId: Int): Boolean {
        val apkFile = microGApkFile()
        if (!apkFile.exists() || apkFile.length() < 10_000_000) {
            downloadApk(apkFile)
        }
        if (isInstalled(userId)) return true
        return BlackBoxCore.get().installPackageAsUser(apkFile, userId).success
    }

    /** Nonaktifkan MicroG untuk satu ruang. */
    fun uninstallFromUser(userId: Int, onResult: (Boolean) -> Unit) {
        Thread {
            val ok = try {
                BlackBoxCore.get().uninstallPackageAsUser(GMS_PKG, userId)
                !isInstalled(userId)
            } catch (e: Exception) {
                false
            }
            mainHandler.post { onResult(ok) }
        }.start()
    }

    private fun microGApkFile(): File {
        return File(BlackBoxCore.getContext().cacheDir, "microg-gmscore.apk")
    }

    /**
     * Download MicroG (sekali saja, file disimpan di cache) lalu pasang ke
     * semua ruang clone. Callback selalu dipanggil di main thread.
     * Result: (berhasil, pesan untuk ditampilkan ke pengguna).
     */
    fun install(context: Context, onResult: (Boolean, String) -> Unit) {
        Thread {
            try {
                val apkFile = File(context.cacheDir, "microg-gmscore.apk")
                if (!apkFile.exists() || apkFile.length() < 10_000_000) {
                    downloadApk(apkFile)
                }

                var installed = 0
                var total = 0
                for (user in BlackBoxCore.get().users) {
                    total++
                    if (isInstalled(user.id)) {
                        installed++
                        continue
                    }
                    val result = BlackBoxCore.get().installPackageAsUser(apkFile, user.id)
                    if (result.success) installed++
                }

                val ok = total > 0 && installed == total
                val msg = if (ok) {
                    "microg_success"
                } else {
                    "microg_partial:$installed/$total"
                }
                mainHandler.post { onResult(ok, msg) }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, "microg_fail:${e.message}") }
            }
        }.start()
    }

    private fun downloadApk(target: File) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(MICROG_APK_URL).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw RuntimeException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (target.length() < 10_000_000) {
                throw RuntimeException("file unduhan tidak lengkap")
            }
        } finally {
            connection?.disconnect()
        }
    }
}
