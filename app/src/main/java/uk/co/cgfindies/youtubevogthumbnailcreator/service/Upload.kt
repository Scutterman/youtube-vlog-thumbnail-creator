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
import com.google.api.services.youtube.model.Video
import com.google.api.services.youtube.model.VideoSnippet
import com.google.api.services.youtube.model.VideoStatus
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

    private var uploader: MediaHttpUploader? = null
    private var cleanedUp = false
    private var isPaused = false
    private var progress: Double = 0.0
    private val uploadingId = 0

    private var notificationChannelId: String? = null
    private val notificationIds: MutableSet<Int> = mutableSetOf()

    var notificationId = 0
        private set

    private var pendingNotificationId = 0

    private val group = "YOUTUBE_VLOG_UPLOADER_NOTIFICATION_GROUP"

    private val queue = arrayOf(
        "A QA Tester went into a bar, you won't believe what happened next",
        "3 things you didn't know about software development - #7 will shock you",
        "Does Wheel of Time get better?",
        "Why don't all authors write what I like?",
        "How to tell you're acting entitled, and 7 things you can do about it"
    )

    init {
        Log.d("WORKER", "Creating network manager")
        networkMonitor.registerConnectionObserver(this)
        Log.d("WORKER", "Created network manager")
    }

    override suspend fun doWork(): Result {
        try {
            if (INSTANCE != null) {
                Log.d("WORKER", "Instance already exists, can't create a new one")
                throw Exception("Upload worker is designed to only have one instance running at a time")
            } else {
                Log.d("WORKER", "Creating a new reference to the instance")
                INSTANCE = WeakReference(this)
            }

            Log.d("WORKER", "Creating notification")
            createNotification()
            Log.d("WORKER", "Notification created")

            Log.d("WORKER", "About to start upload")

            val video = Video()
            val snippet = VideoSnippet()
            snippet.title = "Hello World"
            snippet.description = "Woohoo"
            video.snippet = snippet

            val status = VideoStatus()
            status.privacyStatus = "private"
            video.status = status

            val part = "snippet,status"

            val uri = Uri.parse(inputData.getString(KEY_URI_ARG))

            uploader = YouTube(context).upload(part, video, uri) {
                if (isStopped) {
                    Log.i("WORKER", "Worker stop detected in progress update, pausing")
                    uploader?.pause()
                } else {
                    Log.d("WORKER", "got progress ${ uploader?.progress }")
                    progress = uploader?.progress ?: 0.0
                    CoroutineScope(Dispatchers.Default).launch {
                        Log.i("WORKER", "Updating the notification")
                        createNotification(false)
                    }
                }
            }

            if (uploader == null) {
                Log.e("WORKER", "could not create uploader, nothing more to do")
                return Result.failure()
            }
            Log.d("WORKER", "Uploader set up, starting upload loop")

            while (true) {
                Log.d("WORKER", "loop")
                if (isStopped) {
                    Log.i("WORKER", "Worker stopped, exiting")
                    return Result.failure()
                }

                if (isPaused) {
                    Log.i("WORKER", "Not doing work because I'm paused")
                    SystemClock.sleep(5000)
                } else {
                    Log.i("WORKER", "Uploading until I'm paused again")
                    val response = uploader?.resumeIfPaused()
                    Log.d("WORKER", "Got response")
                    if (response == null) {
                        Log.d("WORKER", "uploader is paused, pausing worker")
                        pauseResume(true)
                    } else {
                        Log.d("WORKER", "uploader returned a response, upload should be complete.")
                        break
                    }
                }
            }

            return Result.success()
        } catch(e: java.lang.Exception) {
            Log.e("WORKER", "Failure during upload", e)
            return Result.failure()
        } finally {
            Log.i("WORKER", "Calling cleanup from Finally block of doWork()")
            cleanUp()
        }
    }

    private fun cleanUp() {
        Log.d("WORKER", "Cleanup called")
        if (!cleanedUp) {
            Log.d("WORKER", "Cleanup in progress")
            networkMonitor.unregisterConnectionObserver(this)
            cleanedUp = true
            INSTANCE = null
            Log.d("WORKER", "Cleanup completed")

            for (id in notificationIds) {
                try {
                    notificationManager.cancel(id)
                    Log.i("WORKER", "Notification $id has been cancelled")
                } catch (e: java.lang.Exception) {
                    Log.e("WORKER", "Could not cancel notification", e)
                }
            }
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
            createNotification(false)
        }
    }

    override fun connectionAvailabilityChanged(isConnected: Boolean) {
        Log.i("WORKER", "Connection state changed,  new state is connected: $isConnected and worker is paused: $isPaused")
        if ((!isConnected && !isPaused) || isConnected && isPaused) {
            pauseResume(!isPaused)
        }
    }

    private fun updateNotification(index: Int): Int {
        if (isStopped || cleanedUp) {
            return -1
        }

        if (pendingNotificationId == 0) {
            pendingNotificationId = context.resources.getInteger(R.integer.notification_id_upload_pending)
        }

        val upload = queue[index]
        val notificationId = pendingNotificationId + index

        val notificationBuilder = NotificationCompat.Builder(applicationContext, notificationChannelId ?: "")
            .setContentTitle(upload)
            .setSmallIcon(R.drawable.ic_baseline_publish_24)
            .setProgress(100, 0, true)
            .setGroup(group)

        notificationManager.notify(notificationId, notificationBuilder.build())
        return notificationId
    }

    private suspend fun createNotification(buildGroup: Boolean = true) {
        if (isStopped || cleanedUp) {
            return
        }

        if (notificationId == 0) {
            notificationId = context.resources.getInteger(R.integer.notification_id_upload_progress)
        }

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

        var inboxStyle = NotificationCompat.InboxStyle()
        var pendingCount = 0

        queue.forEachIndexed { index, upload ->

            if (buildGroup && index != uploadingId) {
                pendingCount++
                inboxStyle = inboxStyle.addLine(upload)
               notificationIds.add(updateNotification(index))
            }
        }

        val cancel = "cancel"
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        if (pendingCount > 0) {
            val summaryTitle = "$pendingCount waiting upload"

            val notificationBuilder =
                NotificationCompat.Builder(applicationContext, notificationChannelId ?: "")
                    .setContentTitle(summaryTitle)
                    .setTicker(summaryTitle)
                    .setSmallIcon(R.drawable.ic_baseline_publish_24)
                    .setOngoing(true)
                    .addAction(android.R.drawable.ic_delete, cancel, cancelIntent)
                    .setStyle(inboxStyle)
                    .setGroup(group)
                    .setGroupSummary(true)
            notificationManager.notify(pendingNotificationId, notificationBuilder.build())
            notificationIds.add(pendingNotificationId)
        }

        val progress = this.progress.toInt()
        val indeterminate = this.progress > 100
        val title = "Uploading " + queue[uploadingId]
        val resume = "resume"
        val pause = "pause"
        

        val notificationBuilder =
            NotificationCompat.Builder(applicationContext, notificationChannelId ?: "")
                .setContentTitle(title)
                .setTicker(title)
                .setContentInfo("$progress%")
                .setSubText("$progress%")
                .setSmallIcon(R.drawable.ic_baseline_publish_24)
                .setOngoing(true)
                .setProgress(100, progress, indeterminate)
                .addAction(android.R.drawable.ic_delete, cancel, cancelIntent)

        val intentAction = Intent(context, PauseResumeReceiver::class.java)

        @SuppressLint("UnspecifiedImmutableFlag")
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            intentAction,
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (isPaused) {
            Log.i("WORKER", "Is paused when creating notification, adding resume button")
            notificationBuilder.addAction(
                R.drawable.ic_baseline_publish_24,
                resume,
                pendingIntent
            )
        } else {
            Log.i("WORKER", "Is running when creating notification, adding pause button")
            notificationBuilder.addAction(R.drawable.pause, pause, pendingIntent)
        }

        val notification = notificationBuilder.build()

        setForeground(ForegroundInfo(notificationId, notification))
        notificationIds.add(notificationId)
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
