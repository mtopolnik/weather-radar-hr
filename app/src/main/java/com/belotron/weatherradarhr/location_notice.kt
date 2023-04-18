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
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.belotron.weatherradarhr.UserReaction.PROCEED
import com.belotron.weatherradarhr.UserReaction.SKIP
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

private const val TAG_ASK_PERMISSION: String = "ask_permission_fragment"

suspend fun FragmentActivity.showFgLocationNotice() = suspendCancellableCoroutine<UserReaction> { cont ->
    info { "showFgLocationNotice" }
    LocationNoticeDialogFragment(false, cont).show(supportFragmentManager, TAG_ASK_PERMISSION)
}

suspend fun FragmentActivity.showBgLocationNotice() = suspendCancellableCoroutine<UserReaction> { cont ->
    info { "showBgLocationNotice" }
    LocationNoticeDialogFragment(true, cont).show(supportFragmentManager, TAG_ASK_PERMISSION)
}

class LocationNoticeDialogFragment(
    private val isForBg: Boolean,
    private val cont: Continuation<UserReaction>)
: DialogFragment() {
    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val rootView = layoutInflater.inflate(R.layout.location_notice, null).apply {
            findViewById<TextView>(R.id.location_notice_text).setText(
                if (isForBg) R.string.location_notice_bg else R.string.location_notice_fg)
            findViewById<Button>(R.id.location_notice_proceed).setOnClickListener {
                dismissAllowingStateLoss()
                cont.resume(PROCEED)
            }
            findViewById<Button>(R.id.location_notice_skip).setOnClickListener {
                dismissAllowingStateLoss()
                cont.resume(SKIP)
            }
        }
        return AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                .setTitle(if (isForBg) R.string.location_notice_title_bg else R.string.location_notice_title_fg)
                .setIcon(R.mipmap.ic_launcher)
                .setView(rootView)
                .create()
    }
}

enum class UserReaction {
    PROCEED, SKIP
}
