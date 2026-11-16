package dev.sir.benderwifi.radio

/** Publish availability only after the owning service has requested its stop. */
internal fun finishRadioSession(teardown:()->Unit,release:()->Unit) {
    try {teardown()} finally {release()}
}
