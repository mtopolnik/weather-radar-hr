package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import android.view.LayoutInflater
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

const val TAG_ABOUT = "dialog_about"

suspend fun showAboutDialogFragment(activity: FragmentActivity) {
    suspendCancellableCoroutine<Unit> { cont ->
        AboutDialogFragment().apply {
            continuation = cont
            retainInstance = true
        }.show(activity.supportFragmentManager, TAG_ABOUT)
    }
}

class AboutDialogFragment : DialogFragment() {

    var continuation: Continuation<Unit>? = null

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = activity!!
        val rootView = LayoutInflater.from(activity).inflate(R.layout.about, null)
        val version =
                try { activity.packageManager?.getPackageInfo(activity.packageName, 0)?.versionName }
                catch (e: Exception) { null } ?: "??"
        rootView.findViewById<TextView>(R.id.about_text_view).apply {
            text = getString(R.string.about_text, version)
        }
        return AlertDialog.Builder(activity)
                .setTitle(R.string.app_name)
                .setIcon(R.mipmap.ic_launcher)
                .setView(rootView)
                .setPositiveButton(android.R.string.ok) { _, _ -> continuation?.resume(Unit) }
                .create()
    }

    override fun onDestroyView() {
        // handles https://code.google.com/p/android/issues/detail?id=17423
        dialog?.takeIf { retainInstance }?.apply {
            setDismissMessage(null)
        }
        super.onDestroyView()
    }
}
