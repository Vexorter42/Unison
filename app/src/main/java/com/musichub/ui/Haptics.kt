package com.musichub.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** Короткий отклик «подтверждение»: переключение трека свайпом, лайк. */
fun View.hapticConfirm() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        },
    )
}
