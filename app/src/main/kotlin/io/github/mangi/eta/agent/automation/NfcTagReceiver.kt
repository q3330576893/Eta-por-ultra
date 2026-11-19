package io.github.mangi.eta.agent.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.nfc.NdefRecord
import android.os.Parcelable

/** Legacy component retained for upgrades. NFC dispatch is an Activity/foreground-reader operation. */
internal class NfcTagReceiver : BroadcastReceiver() {
    // Deliberately inert even if an older manifest still exposes this receiver.
    override fun onReceive(context: Context, intent: Intent) = Unit

    companion object {
        internal const val EVENT_TYPE = "nfc_tapped"
        internal const val MAX_FIELD = NfcTagPayload.MAX_FIELD
        internal fun hasNfc(context: Context): Boolean =
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC)
        internal fun formatTagId(bytes: ByteArray?): String = NfcTagPayload.formatTagId(bytes)
        internal fun extractNdefText(messages: Array<out Parcelable?>?): String = NfcTagPayload.extractNdefText(messages)
        internal fun decodeTextRecord(record: NdefRecord): String = NfcTagPayload.decodeTextRecord(record)
    }
}
