package StarBase.Android.Forum.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing a downloaded build to the system installer.
 *
 * The app never installs anything itself - it asks the platform installer to,
 * and the platform shows its own confirmation screen. On Android 8 and up that
 * ask is gated by a per-app permission the user grants in Settings, so
 * [canInstall] is checked before offering the button and
 * [openInstallPermission] is where the user is sent if it is off.
 */
private const val APK_MIME = "application/vnd.android.package-archive"

/** The FileProvider declared in the manifest, whose authority follows the id. */
private fun authorityOf(context: Context): String = "${context.packageName}.files"

/** Where downloads land: app cache, so the system cleans up after us. */
fun apkCacheDir(context: Context): File = File(context.cacheDir, "apk")

/** True when this app may ask the platform to install a package. */
fun canInstall(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else {
        true
    }

/** Sends the user to the one Settings page that can turn [canInstall] on. */
fun openInstallPermission(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Some builds hide the per-app page; the global list is the fallback.
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e2: ActivityNotFoundException) {
            Toast.makeText(context, "请在系统设置里允许安装未知应用", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Opens the installer on [file]. Returns false when there was nothing able to
 * handle it, so the caller can say so instead of appearing to do nothing.
 */
fun installApk(context: Context, file: File): Boolean {
    val uri = try {
        FileProvider.getUriForFile(context, authorityOf(context), file)
    } catch (e: IllegalArgumentException) {
        return false
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
