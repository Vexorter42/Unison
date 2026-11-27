package com.musichub.playback

internal fun playbackProblem(detail: String?): String {
    val reason = detail.orEmpty().lowercase()
    return when {
        listOf("unknownhost", "unable to resolve", "no address", "network is unreachable").any(reason::contains) -> "Не удалось подключиться. Проверьте интернет и настройки сети."
        listOf("timeout", "timed out").any(reason::contains) -> "Сервис долго не отвечает. Проверьте сеть и попробуйте ещё раз."
        listOf("unauthorized", "login_required", "not a bot", " 401", "войдите").any(reason::contains) -> "Сервис требует вход или подтверждение доступа. Проверьте аккаунт в настройках."
        else -> "Не удалось загрузить трек. Возможно, сервис временно недоступен или трек удалён."
    }
}
