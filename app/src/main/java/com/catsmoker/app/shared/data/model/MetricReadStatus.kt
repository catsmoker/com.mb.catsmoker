package com.catsmoker.app.shared.data.model

import androidx.annotation.StringRes
import com.catsmoker.app.R

/**
 * Why a metric holds the value it holds.
 *
 * A reading that could not be taken carries one of these instead of a number, so the UI can say
 * what happened rather than print a 0 the device never reported.
 *
 * @param labelRes string resource the UI shows in place of a value — a resource, not text,
 *   so the dashboard and the overlay report the reason in the app language.
 */
enum class MetricReadStatus(@StringRes val labelRes: Int) {
    /** No reading has been taken yet. */
    Loading(R.string.core_read_loading),

    /** A real value was read. */
    Ok(R.string.core_read_ok),

    /** The source answered, but with nothing in it. */
    EmptyOutput(R.string.core_read_empty),

    /** The source answered with something that could not be read as a value. */
    ParseFailed(R.string.core_read_parse),

    /** The source exists, but reaching it needs root or Shizuku. */
    PrivilegeDenied(R.string.core_read_privilege),

    /** This device or Android version does not expose the value at all. */
    Unsupported(R.string.core_read_unsupported);

    val isOk: Boolean get() = this == Ok
}
