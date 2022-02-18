package uk.co.cgfindies.youtubevogthumbnailcreator.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uk.co.cgfindies.youtubevogthumbnailcreator.NetworkMonitor
import uk.co.cgfindies.youtubevogthumbnailcreator.R
import java.lang.ref.WeakReference

const val KEY_URI_ARG = "URI"

class Upload(val context: Context, params: WorkerParameters) : CoroutineWorker(context, params), NetworkMonitor.ConnectionObserver {
    companion object {
        var INSTANCE: WeakReference<Upload>? = null
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as
                NotificationManager

    private val networkMonitor = NetworkMonitor.getUnmeteredInstance(context)

    private val uploader: MediaHttpUploader? = null

    private var cleanedUp = false

    private var isPaused = false

    private var notificationChannelId: String? = null
    var notificationId = 0
        private set

    init {
        networkMonitor.registerConnectionObserver(this)
    }

    override suspend fun doWork(): Result {
        try {
            if (INSTANCE != null) {
                throw Exception("Upload worker is designed to only have one instance running at a time")
            } else {
                INSTANCE = WeakReference(this)
            }

            createNotification()
            val uri = Uri.parse(inputData.getString(KEY_URI_ARG))

            while (true) {
                if (isStopped) {
                    Log.i("WORKER", "Worker stopped, exiting")
                    return Result.failure()
                }

                if (isPaused) {
                    Log.i("WORKER", "Not doing work because I'm paused")
                    SystemClock.sleep(5000)
                } else {
                    // TODO:: This should manage the upload, but I haven't gotten to that part yet
                    Log.i("WORKER", "Uploading the next chunk like an absolute boss")
                    SystemClock.sleep(5000)
                }
            }
            return Result.success()
        } finally {
            cleanUp()
        }

        return Result.failure()
    }

    private fun cleanUp() {
        if (!cleanedUp) {
            networkMonitor.unregisterConnectionObserver(this)
            cleanedUp = true
            INSTANCE = null
        }
    }

    fun togglePauseResume() {
        Log.i("WORKER", "toggling pause / resume. Current state is $isPaused")
        pauseResume(!isPaused)
    }

    private fun pauseResume(isPaused: Boolean) {
        this.isPaused = isPaused
        if (!isPaused) {
            uploader?.pause()
        } else {
            uploader?.resumeIfPaused()
        }

        CoroutineScope(Dispatchers.Default).launch {
            Log.i("WORKER", "re-creating the notification")
            createNotification()
        }
    }

    override fun connectionAvailabilityChanged(isConnected: Boolean) {
        Log.i("WORKER", "Connection state changed,  new state is connected: $isConnected and worker is paused: $isPaused")
        if ((!isConnected && !isPaused) || isConnected && isPaused) {
            pauseResume(!isPaused)
        }
    }

    private suspend fun createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationChannelId == null) {
            // Create the NotificationChannel
            notificationChannelId = context.getString(R.string.notification_channel_id)
            val name = context.getString(R.string.notification_channel_name)
            val descriptionText = context.getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel(notificationChannelId, name, importance)
            channel.description = descriptionText

            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            notificationManager.createNotificationChannel(channel)
        }

        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        PendingIntent.CREATOR

        val progress = if (!isPaused) "Running" else "Paused"
        val title = "YouTube Upload"
        val cancel = "cancel"
        val resume = "resume"
        val pause = "pause"

        if (notificationId == 0) {
            notificationId = context.resources.getInteger(R.integer.notification_id_upload_progress)
        }

        val notificationBuilder = NotificationCompat.Builder(applicationContext, notificationChannelId ?: "")
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(progress)
            .setSmallIcon(R.drawable.ic_baseline_publish_24)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, cancel, cancelIntent)

        val intentAction = Intent(context, PauseResumeReceiver::class.java)
        @SuppressLint("UnspecifiedImmutableFlag")
        val pendingIntent = PendingIntent.getBroadcast(context, 1, intentAction, PendingIntent.FLAG_UPDATE_CURRENT)

        if (isPaused) {
            Log.i("WORKER", "Is paused when creating notification, adding resume button")
            notificationBuilder.addAction(R.drawable.ic_baseline_publish_24, resume, pendingIntent)
        } else {
            Log.i("WORKER", "Is running when creating notification, adding pause button")
            notificationBuilder.addAction(R.drawable.pause, pause, pendingIntent)
        }

        val notification = notificationBuilder.build()
        setForeground(ForegroundInfo(notificationId, notification))
        Log.i("WORKER", "Set notification to foreground")
    }

}
class PauseResumeReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (Upload.INSTANCE == null) {
            Log.i("BROADCAST", "Upload worker has no instance set")
            return
        }
        Log.i("BROADCAST", "Got message, notification id is ${ Upload.INSTANCE?.get()?.notificationId }")
        Upload.INSTANCE?.get()?.togglePauseResume()
    }
}
