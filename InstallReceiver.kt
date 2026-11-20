package app.htmlbuilder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        if (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val x = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            x.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(x)
        }
    }
}
