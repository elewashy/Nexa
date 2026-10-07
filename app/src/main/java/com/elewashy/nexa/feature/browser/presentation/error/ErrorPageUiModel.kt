package com.elewashy.nexa.feature.browser.presentation.error

import androidx.annotation.StringRes
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.browser.domain.model.CertificateProblem
import com.elewashy.nexa.feature.browser.domain.model.PageLoadError
import com.elewashy.nexa.feature.browser.domain.model.PageLoadErrorType

/** Something the user can do from the error page. */
enum class ErrorPageAction {
    /** Load the page again. */
    Reload,

    /** Reload a POST result, resending the form data the user confirmed. */
    Resend,

    /** Step back in the tab's history. */
    GoBack,

    /** Open the home page. */
    GoHome,

    /** Leave a dangerous page: back when there is history, otherwise home. */
    BackToSafety,

    /** Open the system's internet connectivity settings. */
    NetworkSettings,
}

/** Illustration shown at the top of the error page. */
enum class ErrorPageIllustration { Offline, Unreachable, Broken, Security, Blocked }

/**
 * Everything the error page renders for a [PageLoadError], following the structure of Chrome's
 * error pages: a title, what went wrong (naming the site), what to try, the error code, and the
 * most useful next step.
 */
data class ErrorPageUiModel(
    val illustration: ErrorPageIllustration,
    @param:StringRes val title: Int,
    /** Explanation; formatted with the site's host when [messageNamesHost] is true. */
    @param:StringRes val message: Int,
    val messageNamesHost: Boolean,
    /** Why the certificate was rejected, for certificate errors. */
    @param:StringRes val certificateReason: Int?,
    /** "Try:" suggestions, most useful first. */
    val suggestions: List<Int>,
    val primaryAction: ErrorPageAction,
    val secondaryAction: ErrorPageAction?,
) {
    /** Security interstitials steer the user away from the site instead of retrying it. */
    val isSecurityWarning: Boolean
        get() = illustration == ErrorPageIllustration.Security
}

/**
 * Maps a failure to its error page. [canGoBack] picks "Go back" or "Go to home page" as the way
 * out, so the page never offers an action that would do nothing.
 */
fun PageLoadError.toErrorPageUiModel(canGoBack: Boolean): ErrorPageUiModel {
    val leave = if (canGoBack) ErrorPageAction.GoBack else ErrorPageAction.GoHome
    return when (type) {
        PageLoadErrorType.NoInternet -> ErrorPageUiModel(
            illustration = ErrorPageIllustration.Offline,
            title = R.string.error_page_title_offline,
            message = R.string.error_page_message_offline,
            messageNamesHost = false,
            certificateReason = null,
            suggestions = listOf(
                R.string.error_page_suggestion_connection,
                R.string.error_page_suggestion_airplane_mode,
            ),
            primaryAction = ErrorPageAction.Reload,
            secondaryAction = ErrorPageAction.NetworkSettings,
        )

        PageLoadErrorType.HostNotFound -> unreachable(
            message = R.string.error_page_message_host_not_found,
            suggestions = listOf(
                R.string.error_page_suggestion_address,
                R.string.error_page_suggestion_connection,
                R.string.error_page_suggestion_dns,
            ),
            leave = leave,
        )

        PageLoadErrorType.ConnectionRefused -> unreachable(
            message = R.string.error_page_message_refused,
            suggestions = listOf(
                R.string.error_page_suggestion_connection,
                R.string.error_page_suggestion_dns,
            ),
            leave = leave,
        )

        PageLoadErrorType.ConnectionInterrupted -> unreachable(
            message = R.string.error_page_message_interrupted,
            suggestions = listOf(
                R.string.error_page_suggestion_connection,
                R.string.error_page_suggestion_dns,
            ),
            leave = leave,
        )

        PageLoadErrorType.TimedOut -> unreachable(
            message = R.string.error_page_message_timed_out,
            suggestions = listOf(
                R.string.error_page_suggestion_connection,
                R.string.error_page_suggestion_dns,
                R.string.error_page_suggestion_later,
            ),
            leave = leave,
        )

        PageLoadErrorType.InvalidResponse -> notWorking(
            message = R.string.error_page_message_invalid_response,
            suggestions = listOf(R.string.error_page_suggestion_later),
            leave = leave,
        )

        PageLoadErrorType.TooManyRedirects -> notWorking(
            message = R.string.error_page_message_redirects,
            suggestions = listOf(R.string.error_page_suggestion_cookies),
            leave = leave,
        )

        PageLoadErrorType.InsecureConnection -> ErrorPageUiModel(
            illustration = ErrorPageIllustration.Security,
            title = R.string.error_page_title_not_private,
            message = R.string.error_page_message_not_private,
            messageNamesHost = true,
            certificateReason = when (certificateProblem) {
                CertificateProblem.DateInvalid -> R.string.error_page_certificate_date
                CertificateProblem.NameMismatch -> R.string.error_page_certificate_name
                CertificateProblem.Untrusted -> R.string.error_page_certificate_untrusted
                CertificateProblem.Invalid, null -> R.string.error_page_certificate_invalid
            },
            suggestions = if (certificateProblem == CertificateProblem.DateInvalid) {
                listOf(R.string.error_page_suggestion_clock)
            } else {
                emptyList()
            },
            primaryAction = ErrorPageAction.BackToSafety,
            secondaryAction = null,
        )

        PageLoadErrorType.SecureConnectionFailed -> ErrorPageUiModel(
            illustration = ErrorPageIllustration.Unreachable,
            title = R.string.error_page_title_insecure,
            message = R.string.error_page_message_insecure,
            messageNamesHost = true,
            certificateReason = null,
            suggestions = listOf(
                R.string.error_page_suggestion_clock,
                R.string.error_page_suggestion_site_owner,
            ),
            primaryAction = ErrorPageAction.Reload,
            secondaryAction = leave,
        )

        PageLoadErrorType.ProxyFailed -> ErrorPageUiModel(
            illustration = ErrorPageIllustration.Unreachable,
            title = R.string.error_page_title_proxy,
            message = R.string.error_page_message_proxy,
            messageNamesHost = false,
            certificateReason = null,
            suggestions = listOf(R.string.error_page_suggestion_dns),
            primaryAction = ErrorPageAction.Reload,
            secondaryAction = ErrorPageAction.NetworkSettings,
        )

        PageLoadErrorType.UnsupportedAddress -> ErrorPageUiModel(
            illustration = ErrorPageIllustration.Broken,
            title = R.string.error_page_title_invalid_address,
            message = R.string.error_page_message_invalid_address,
            messageNamesHost = false,
            certificateReason = null,
            suggestions = listOf(R.string.error_page_suggestion_address),
            primaryAction = leave,
            secondaryAction = null,
        )

        PageLoadErrorType.FileNotFound -> ErrorPageUiModel(
            illustration = ErrorPageIllustration.Broken,
            title = R.string.error_page_title_file_not_found,
            message = R.string.error_page_message_file_not_found,
            messageNamesHost = false,
            certificateReason = null,
            suggestions = listOf(R.string.error_page_suggestion_address),
            primaryAction = ErrorPageAction.Reload,
            secondaryAction = leave,
        )

        PageLoadErrorType.FormResubmission -> ErrorPageUiModel(
            illustration = ErrorPageIllustration.Broken,
            title = R.string.error_page_title_resubmission,
            message = R.string.error_page_message_resubmission,
            messageNamesHost = false,
            certificateReason = null,
            suggestions = emptyList(),
            primaryAction = ErrorPageAction.Resend,
            secondaryAction = leave,
        )

        PageLoadErrorType.AccessBlocked -> ErrorPageUiModel(
            illustration = ErrorPageIllustration.Blocked,
            title = R.string.error_page_title_blocked,
            message = R.string.error_page_message_blocked,
            messageNamesHost = true,
            certificateReason = null,
            suggestions = emptyList(),
            primaryAction = leave,
            secondaryAction = ErrorPageAction.Reload,
        )

        PageLoadErrorType.UnsafeSite -> ErrorPageUiModel(
            illustration = ErrorPageIllustration.Security,
            title = R.string.error_page_title_dangerous,
            message = R.string.error_page_message_dangerous,
            messageNamesHost = true,
            certificateReason = null,
            suggestions = emptyList(),
            primaryAction = ErrorPageAction.BackToSafety,
            secondaryAction = null,
        )

        PageLoadErrorType.Generic -> notWorking(
            message = R.string.error_page_message_generic,
            suggestions = listOf(
                R.string.error_page_suggestion_connection,
                R.string.error_page_suggestion_later,
            ),
            leave = leave,
        )
    }
}

private fun unreachable(
    @StringRes message: Int,
    suggestions: List<Int>,
    leave: ErrorPageAction,
) = ErrorPageUiModel(
    illustration = ErrorPageIllustration.Unreachable,
    title = R.string.error_page_title_unreachable,
    message = message,
    messageNamesHost = true,
    certificateReason = null,
    suggestions = suggestions,
    primaryAction = ErrorPageAction.Reload,
    secondaryAction = leave,
)

private fun notWorking(
    @StringRes message: Int,
    suggestions: List<Int>,
    leave: ErrorPageAction,
) = ErrorPageUiModel(
    illustration = ErrorPageIllustration.Broken,
    title = R.string.error_page_title_not_working,
    message = message,
    messageNamesHost = true,
    certificateReason = null,
    suggestions = suggestions,
    primaryAction = ErrorPageAction.Reload,
    secondaryAction = leave,
)
