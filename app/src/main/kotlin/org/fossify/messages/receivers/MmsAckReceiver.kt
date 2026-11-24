package org.fossify.messages.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import org.fossify.messages.helpers.Diag
import org.fossify.messages.messaging.MmsAck
import java.io.File

/**
 * What became of an acknowledgement [MmsAck] handed to the platform: one line in the “MMS transport”
 * diagnostic — the platform's result and the MMSC's HTTP status — and the composed file is removed.
 */
class MmsAckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = resultCode
        intent.getStringExtra(MmsAck.EXTRA_FILE)?.let { runCatching { File(it).delete() } }
        Diag.log(Diag.Kind.MMS) {
            val result = if (code == Activity.RESULT_OK) "OK" else "ERROR ${errorName(code)}"
            val http = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
            "ack result $result, http=${if (http == 0) "not reported" else http.toString()}: " +
                intent.getStringExtra(MmsAck.EXTRA_WHAT)
        }
    }

    private fun errorName(code: Int) = when (code) {
        SmsManager.MMS_ERROR_UNSPECIFIED -> "unspecified ($code)"
        SmsManager.MMS_ERROR_INVALID_APN -> "invalid APN ($code)"
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "unable to connect to the MMS network ($code)"
        SmsManager.MMS_ERROR_HTTP_FAILURE -> "HTTP failure ($code)"
        SmsManager.MMS_ERROR_IO_ERROR -> "I/O error ($code)"
        SmsManager.MMS_ERROR_RETRY -> "retry ($code)"
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "configuration error ($code)"
        SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "no data network ($code)"
        else -> "code $code"
    }
}
