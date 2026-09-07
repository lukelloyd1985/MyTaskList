package com.github.lukelloyd1985.mytasklist.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

// Bundled into one function, gathered all at once on failure, rather
// than adding one diagnostic value per round - each round is a full
// Play Store closed-testing upload + review cycle (see the startup-
// crash investigation's git history for how slow that got). Every
// value here is something that could plausibly explain a Play-Store-
// install-only difference in behaviour, read directly from the device
// rather than assumed from what Play Console displays.
fun gatherDiagnostics(context: Context): String {
    val pm = context.packageManager
    val packageName = context.packageName

    val versionInfo = runCatching {
        val info = pm.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrElse { "unknown ($it)" }

    // The certificate Android's package manager considers this running
    // process to actually be signed with, computed on-device rather
    // than trusted from Play Console's own fingerprint page - turns "is
    // the classic SHA-1 registered in Google Cloud Console actually the
    // one in effect right now" into a directly-checkable fact.
    val signingCertSha1 = runCatching {
        val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        signers.joinToString(", ") { signer ->
            MessageDigest.getInstance("SHA-1")
                .digest(signer.toByteArray())
                .joinToString(":") { byte -> "%02X".format(byte) }
        }
    }.getOrElse { "unknown ($it)" }

    // com.android.vending = installed via the Play Store; anything else
    // (or none - sideloaded via adb/a file manager) means it wasn't -
    // verifies "this process actually came from Play" as a fact rather
    // than an assumption based on how it was obtained.
    val installer = runCatching {
        pm.getInstallSourceInfo(packageName).installingPackageName ?: "(none - sideloaded)"
    }.getOrElse { "unknown ($it)" }

    val playServicesVersion = runCatching {
        pm.getPackageInfo("com.google.android.gms", 0).versionName
    }.getOrElse { "unknown ($it)" }

    return """
        |app version: $versionInfo
        |package: $packageName
        |installer: $installer
        |signing cert SHA-1: $signingCertSha1
        |Play services version: $playServicesVersion
        |Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
        |device: ${Build.MANUFACTURER} ${Build.MODEL}
    """.trimMargin()
}
