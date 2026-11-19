package com.laksh.coffee.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * The share route: an Android share intent. The share sheet hands the text to whichever app
 * you pick (Claude, Notes, email), and that app is where the conversation happens.
 *
 * This file never touches the network. In-app analysis lives in data/ai and only goes online
 * when you tap a button that runs an analysis or a connection test (Analyse, Save & analyse,
 * Find patterns, Re-run, Try again, Save and test, Test connection) or Browse models.
 */
fun shareText(context: Context, text: String, subject: String = "Brew log") {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    context.startActivity(Intent.createChooser(send, "Send brew log to"))
}

fun copyText(context: Context, text: String, label: String = "Brew log") {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13+ shows its own copy confirmation, so do not double up.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}
