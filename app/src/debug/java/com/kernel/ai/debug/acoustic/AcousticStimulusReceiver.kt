package com.kernel.ai.debug.acoustic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

internal fun acousticStimulusResultCode(result: StimulusResult): Int = when {
    result.completionStatus == "completed" &&
        result.errorCategory == null &&
        !result.evidencePersistenceFailed ->
        AcousticStimulusContract.RESULT_OK
    result.overlapRejected || result.completionStatus == "rejected" ->
        AcousticStimulusContract.RESULT_REJECTED
    else -> AcousticStimulusContract.RESULT_FAILED
}

/**
 * Debug-only source playback endpoint. Invoke explicitly against com.kernel.ai.debug.
 */
class AcousticStimulusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    if (!isExplicitReceiverInvocation(appContext, intent)) {
                        finishInvalid(pendingResult, "explicit_component_required")
                        return@post
                    }
                    if (intent.action == AcousticStimulusContract.ACTION_CANCEL) {
                        when (val parsed = InvocationParser.parseCancellation(intent)) {
                            is CancellationParseResult.Invalid ->
                                finishInvalid(pendingResult, parsed.error.category)
                            is CancellationParseResult.Valid -> {
                                val cancelled = PlaybackGate.cancel(parsed.trialId)
                                pendingResult.setResultCode(
                                    if (cancelled) AcousticStimulusContract.RESULT_OK
                                    else AcousticStimulusContract.RESULT_REJECTED,
                                )
                                pendingResult.setResultData(
                                    if (cancelled) "cancelled" else "no_matching_active_trial",
                                )
                                pendingResult.finish()
                            }
                        }
                        return@post
                    }
                    createEngine(appContext).handle(InvocationParser.parse(intent)) { result ->
                        pendingResult.setResultCode(acousticStimulusResultCode(result))
                        pendingResult.setResultData(result.errorCategory ?: result.completionStatus)
                        pendingResult.finish()
                    }
                } catch (error: Exception) {
                    Log.e(ACOUSTIC_STIMULUS_LOG_TAG, "receiver_failed", error)
                    pendingResult.setResultCode(AcousticStimulusContract.RESULT_FAILED)
                    pendingResult.setResultData("receiver_failed")
                    pendingResult.finish()
                }
            }
        } catch (error: Exception) {
            Log.e(ACOUSTIC_STIMULUS_LOG_TAG, "receiver_dispatch_failed", error)
            pendingResult.setResultCode(AcousticStimulusContract.RESULT_FAILED)
            pendingResult.setResultData("receiver_dispatch_failed")
            pendingResult.finish()
        }
    }

    private fun finishInvalid(pendingResult: PendingResult, category: String) {
        pendingResult.setResultCode(AcousticStimulusContract.RESULT_REJECTED)
        pendingResult.setResultData(category)
        pendingResult.finish()
    }

    private fun isExplicitReceiverInvocation(context: Context, intent: Intent): Boolean =
        intent.component?.packageName == context.packageName &&
            intent.component?.className == AcousticStimulusReceiver::class.java.name

    private fun createEngine(context: Context): AcousticStimulusEngine = AcousticStimulusEngine(
        fixtures = FileFixtureRepository(AcousticFixtureStorage.fixtureDirectory(context)),
        audio = AndroidStimulusAudioController(context),
        playerFactory = AndroidMediaPlayerFactory(),
        scheduler = HandlerStimulusScheduler(),
        time = SystemStimulusTimeSource,
        resultWriter = AndroidStimulusResultWriter(context),
        eventLogger = AndroidStimulusEventLogger,
    )
}
