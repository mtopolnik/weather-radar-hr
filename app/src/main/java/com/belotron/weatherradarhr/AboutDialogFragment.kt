/*
 * Copyright (C) 2018-2023 Marko Topolnik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
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
        val activity = requireActivity()
        val rootView = layoutInflater.inflate(R.layout.about, null)
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
