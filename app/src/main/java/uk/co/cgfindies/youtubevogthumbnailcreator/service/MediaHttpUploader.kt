package uk.co.cgfindies.youtubevogthumbnailcreator.service

import com.google.api.client.googleapis.MethodOverride
import com.google.api.client.http.*
import com.google.api.client.util.Beta
import com.google.api.client.util.ByteStreams
import com.google.api.client.util.Preconditions
import com.google.api.client.util.Sleeper
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

/**
 * Media HTTP Uploader, with support for resumable media uploads. Documentation is
 * available [here](https://googleapis.github.io/google-api-java-client/media-upload.html).
 *
 *
 * When the media content length is known, if the provided [InputStream] has [InputStream.markSupported] as `false` then it is wrapped in an
 * [BufferedInputStream] to support the [InputStream.mark] and [InputStream.reset]
 * methods required for handling server errors. If the media content length is unknown then each
 * chunk is stored temporarily in memory. This is required to determine when the last chunk is
 * reached.
 *
 *
 * See [.setDisableGZipContent] for information on when content is gzipped and how
 * to control that behavior.
 *
 *
 * Back-off is disabled by default. To enable it for an abnormal HTTP response and an I/O
 * exception you should call [HttpRequest.setUnsuccessfulResponseHandler] with a new [HttpBackOffUnsuccessfulResponseHandler] instance and [HttpRequest.setIOExceptionHandler]
 * with [HttpBackOffIOExceptionHandler].
 *
 *
 * Upgrade warning: in prior version 1.14 exponential back-off was enabled by default for an
 * abnormal HTTP response and there was a regular retry (without back-off) when I/O exception was
 * thrown. Starting with version 1.15 back-off is disabled and there is no retry on I/O exception by
 * default.
 *
 *
 * Upgrade warning: in prior version 1.16 [.serverErrorCallback] was public but starting
 * with version 1.17 it has been removed from the public API, and changed to be package private.
 *
 *
 * Implementation is not thread-safe.
 *
 * @since 1.9
 * @author rmistry@google.com (Ravi Mistry)
 * @author peleyal@google.com (Eyal Peled)
 */
class MediaHttpUploader(
    mediaContent: AbstractInputStreamContent,
    transport: HttpTransport,
    httpRequestInitializer: HttpRequestInitializer?
) {

    /**
     * This class is designed to add the nextChunk() method to the Android youtube library
     *
     * The class com.google.api.client.googleapis.media.MediaHttpUploader is used as a template.
     *
     * The fields [MediaHttpUploader.uploadUrl] and [MediaHttpUploader.isPaused] have been added,
     * along with the method [MediaHttpUploader.pause].
     *
     * The method [MediaHttpUploader.resumableUpload] has been split in two,
     * with the initialisation still happening in the original method
     * followed by a call to the new method [MediaHttpUploader.resume].

     * These two methods called in succession behave identically to the original
     * [MediaHttpUploader.resumableUpload] method, except that they will return null if
     * [MediaHttpUploader.pause] method is called before the upload completes
     * and the [MediaHttpUploader.resume] must be called again to continue the upload
     *
     * See the original at
     * https://github.com/googleapis/google-api-java-client/blob/main/google-api-client/src/main/java/com/google/api/client/googleapis/media/MediaHttpUploader.java
     */

    /** Upload state associated with the Media HTTP uploader.  */
    enum class UploadState {
        /** The upload process has not started yet.  */
        NOT_STARTED,

        /** Set before the initiation request is sent.  */
        INITIATION_STARTED,

        /** Set after the initiation request completes.  */
        INITIATION_COMPLETE,

        /** Set after a media file chunk is uploaded.  */
        MEDIA_IN_PROGRESS,

        /** Set after the complete media file is successfully uploaded.  */
        MEDIA_COMPLETE
    }
    /**
     * Gets the current upload state of the uploader.
     *
     * @return the upload state
     */
    /** The current state of the uploader.  */
    @Suppress("MemberVisibilityCanBePrivate")
    var uploadState = UploadState.NOT_STARTED
        private set

    /** The HTTP content of the media to be uploaded.  */
    private val mediaContent: AbstractInputStreamContent = Preconditions.checkNotNull(mediaContent)

    /** The request factory for connections to the server.  */
    private val requestFactory: HttpRequestFactory =
        if (httpRequestInitializer == null) {
            transport.createRequestFactory()
        } else {
            transport.createRequestFactory(
                httpRequestInitializer
            )
        }

    /** Returns the transport to use for requests.  */
    /** The transport to use for requests.  */
    @Suppress("Unused")
    val transport: HttpTransport = Preconditions.checkNotNull(transport)

    /** Returns HTTP content metadata for the media request or `null` for none.  */
    /** HTTP content metadata of the media to be uploaded or `null` for none.  */
    @Suppress("MemberVisibilityCanBePrivate")
    var metadata: HttpContent? = null
        private set

    /**
     * Uses lazy initialization to compute the media content length.
     *
     *
     * This is done to avoid throwing an [IOException] in the constructor.
     */
    /**
     * The length of the HTTP media content.
     *
     *
     * `0` before it is lazily initialized in [.getMediaContentLength] after which it
     * could still be `0` for empty media content. Will be `< 0` if the media content
     * length has not been specified.
     */
    @get:Throws(IOException::class)
    private var mediaContentLength: Long = 0
        get() {
            if (!isMediaContentLengthCalculated) {
                field = mediaContent.length
                isMediaContentLengthCalculated = true
            }
            return field
        }

    /**
     * Determines if media content length has been calculated yet in [.getMediaContentLength].
     */
    private var isMediaContentLengthCalculated = false

    /**
     * Returns the HTTP method used for the initiation request.
     *
     *
     * The default value is [HttpMethods.POST].
     *
     * @since 1.12
     */
    /**
     * The HTTP method used for the initiation request.
     *
     *
     * Can only be [HttpMethods.POST] (for media upload) or [HttpMethods.PUT] (for
     * media update). The default value is [HttpMethods.POST].
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var initiationRequestMethod = HttpMethods.POST
        private set

    /** Returns the HTTP headers used for the initiation request.  */
    /** The HTTP headers used in the initiation request.  */
    @Suppress("MemberVisibilityCanBePrivate")
    var initiationHeaders = HttpHeaders()
        private set

    /**
     * The HTTP request object that is currently used to send upload requests or `null` before
     * [.upload].
     */
    private var currentRequest: HttpRequest? = null

    /** An Input stream of the HTTP media content or `null` before [.upload].  */
    private var contentInputStream: InputStream? = null

    /**
     * Determines whether direct media upload is enabled or disabled. If value is set to `true`
     * then a direct upload will be done where the whole media content is uploaded in a single request
     * If value is set to `false` then the upload uses the resumable media upload protocol to
     * upload in data chunks. Defaults to `false`.
     */
    private var directUploadEnabled = false

    /** Returns the progress listener to send progress notifications to or `null` for none.  */
    /** Progress listener to send progress notifications to or `null` for none.  */
    @Suppress("MemberVisibilityCanBePrivate")
    var progressListener: ((uploader: MediaHttpUploader) -> Unit)? = null
        private set

    /**
     * The media content length is used in the "Content-Range" header. If we reached the end of the
     * stream, this variable will be set with the length of the stream. This value is used only in
     * resumable media upload.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var mediaContentLengthStr = "*"
    /**
     * Gets the total number of bytes the server received so far or `0` for direct uploads when
     * the content length is not known.
     *
     * @return the number of bytes the server received so far
     */
    /**
     * The number of bytes the server received so far. This value will not be calculated for direct
     * uploads when the content length is not known in advance.
     */
    // TODO(rmistry): Figure out a way to compute the content length using CountingInputStream.
    @Suppress("MemberVisibilityCanBePrivate")
    var numBytesUploaded: Long = 0
        private set
    /**
     * Returns the maximum size of individual chunks that will get uploaded by single HTTP requests.
     * The default value is [.DEFAULT_CHUNK_SIZE].
     */
    /**
     * Maximum size of individual chunks that will get uploaded by single HTTP requests. The default
     * value is [.DEFAULT_CHUNK_SIZE].
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var chunkSize = DEFAULT_CHUNK_SIZE
        private set

    /**
     * Used to cache a single byte when the media content length is unknown or `null` for none.
     */
    private var cachedByte: Byte? = null

    /**
     * The number of bytes the client had sent to the server so far or `0` for none. It is used
     * for resumable media upload when the media content length is not specified.
     */
    private var totalBytesClientSent: Long = 0

    /**
     * The number of bytes of the current chunk which was sent to the server or `0` for none.
     * This value equals to chunk size for each chunk the client send to the server, except for the
     * ending chunk.
     */
    private var currentChunkLength = 0

    /**
     * The content buffer of the current request or `null` for none. It is used for resumable
     * media upload when the media content length is not specified. It is instantiated for every
     * request in [.buildContentChunk] and is set to `null` when the request is
     * completed in [.upload].
     */
    private var currentRequestContentBuffer: ByteArray? = null
    /**
     * Returns whether to disable GZip compression of HTTP content.
     *
     * @since 1.13
     */
    /**
     * Whether to disable GZip compression of HTTP content.
     *
     *
     * The default value is `false`.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var disableGZipContent = false
        private set
    /**
     * Returns the sleeper.
     *
     * @since 1.15
     */
    /** Sleeper. *  */
    @Suppress("MemberVisibilityCanBePrivate")
    var sleeper: Sleeper = Sleeper.DEFAULT

    private var uploadUrl: GenericUrl? = null
    private var isPaused = false

    /**
     * Executes a direct media upload or resumable media upload conforming to the specifications
     * listed [here.](https://developers.google.com/api-client-library/java/google-api-java-client/media-upload)
     *
     *
     * This method is not reentrant. A new instance of [MediaHttpUploader] must be
     * instantiated before upload called be called again.
     *
     *
     * If an error is encountered during the request execution the caller is responsible for
     * parsing the response correctly. For example for JSON errors:
     *
     * <pre>`if (!response.isSuccessStatusCode()) {
     * throw GoogleJsonResponseException.from(jsonFactory, response);
     * }
    `</pre> *
     *
     *
     * Callers should call [HttpResponse.disconnect] when the returned HTTP response object
     * is no longer needed. However, [HttpResponse.disconnect] does not have to be called if the
     * response stream is properly closed. Example usage:
     *
     * <pre>`HttpResponse response = batch.upload(initiationRequestUrl);
     * try {
     * // process the HTTP response object
     * } finally {
     * response.disconnect();
     * }
    `</pre> *
     *
     * @param initiationRequestUrl The request URL where the initiation request will be sent
     * @return HTTP response
     */
    @Throws(IOException::class)
    @Suppress("Unused")
    fun upload(initiationRequestUrl: GenericUrl): HttpResponse? {
        Preconditions.checkArgument(uploadState == UploadState.NOT_STARTED)
        if (directUploadEnabled) {
            return directUpload(initiationRequestUrl)
        }
        return resumableUpload(initiationRequestUrl)
    }

    /**
     * Direct Uploads the media.
     *
     * @param initiationRequestUrl The request URL where the initiation request will be sent
     * @return HTTP response
     */
    private fun directUpload(initiationRequestUrl: GenericUrl): HttpResponse {
        updateStateAndNotifyListener(UploadState.MEDIA_IN_PROGRESS)

        val content = if (metadata != null) {
            initiationRequestUrl.put("uploadType", "multipart")
            MultipartContent().setContentParts(listOf(metadata, mediaContent))
        } else {
            initiationRequestUrl.put("uploadType", "media")
            null
        }

        val request = requestFactory
            .buildRequest(initiationRequestMethod, initiationRequestUrl, content)
        request.headers.putAll(initiationHeaders)
        // We do not have to do anything special here if media content length is unspecified because
        // direct media upload works even when the media content length == -1.
        val response = executeCurrentRequest(request)
        var responseProcessed = false
        try {
            if (isMediaLengthKnown) {
                numBytesUploaded = mediaContentLength
            }
            updateStateAndNotifyListener(UploadState.MEDIA_COMPLETE)
            responseProcessed = true
        } finally {
            if (!responseProcessed) {
                response.disconnect()
            }
        }
        return response
    }

    /**
     * Uploads the media in a resumable manner.
     *
     * @param initiationRequestUrl The request URL where the initiation request will be sent
     * @return HTTP response or null. If null is returned, the upload was paused and must be resumed using a call to [MediaHttpUploader.resume]
     */
    @Throws(IOException::class)
    private fun resumableUpload(initiationRequestUrl: GenericUrl): HttpResponse? {
        // Make initial request to get the unique upload URL.
        val initialResponse = executeUploadInitiation(initiationRequestUrl)
        if (!initialResponse.isSuccessStatusCode) {
            // If the initiation request is not successful return it immediately.
            return initialResponse
        }

        uploadUrl = try {
            GenericUrl(initialResponse.headers.location)
        } finally {
            initialResponse.disconnect()
        }

        // Convert media content into a byte stream to upload in chunks.
        contentInputStream = mediaContent.inputStream
        if (contentInputStream?.markSupported() != true && isMediaLengthKnown) {
            // If we know the media content length then wrap the stream into a Buffered input stream to
            // support the {@link InputStream#mark} and {@link InputStream#reset} methods required for
            // handling server errors.
            contentInputStream = BufferedInputStream(contentInputStream)
        }

        return resume()
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun resume(): HttpResponse? {
        isPaused = false
        var response: HttpResponse

        // Upload the media content in chunks.
        while (true) {
            if (isPaused) {
                return null
            }

            val contentChunk = buildContentChunk()
            currentRequest = requestFactory.buildPutRequest(uploadUrl, null)
            currentRequest?.content = contentChunk.content
            currentRequest?.headers?.contentRange = contentChunk.contentRange

            // set mediaErrorHandler as I/O exception handler and as unsuccessful response handler for
            // calling to serverErrorCallback on an I/O exception or an abnormal HTTP response
            MediaUploadErrorHandler(this, currentRequest!!)
            response = if (isMediaLengthKnown) {
                // TODO(rmistry): Support gzipping content for the case where media content length is
                // known (https://github.com/googleapis/google-api-java-client/issues/691).
                executeCurrentRequestWithoutGZip(currentRequest)
            } else {
                executeCurrentRequest(currentRequest)
            }
            var returningResponse = false
            try {
                if (response.isSuccessStatusCode) {
                    numBytesUploaded = mediaContentLength
                    if (mediaContent.closeInputStream) {
                        contentInputStream!!.close()
                    }
                    updateStateAndNotifyListener(UploadState.MEDIA_COMPLETE)
                    returningResponse = true
                    return response
                }
                if (response.statusCode != 308) {
                    if (mediaContent.closeInputStream) {
                        contentInputStream!!.close()
                    }
                    returningResponse = true
                    return response
                }

                // Check to see if the upload URL has changed on the server.
                val updatedUploadUrl = response.headers.location
                if (updatedUploadUrl != null) {
                    uploadUrl = GenericUrl(updatedUploadUrl)
                }

                // we check the amount of bytes the server received so far, because the server may process
                // fewer bytes than the amount of bytes the client had sent
                val newBytesServerReceived = getNextByteIndex(response.headers.range)
                // the server can receive any amount of bytes from 0 to current chunk length
                val currentBytesServerReceived = newBytesServerReceived - numBytesUploaded
                Preconditions.checkState(
                    currentBytesServerReceived in 0..currentChunkLength
                )
                val copyBytes = currentChunkLength - currentBytesServerReceived
                if (isMediaLengthKnown) {
                    if (copyBytes > 0) {
                        // If the server didn't receive all the bytes the client sent the current position of
                        // the input stream is incorrect. So we should reset the stream and skip those bytes
                        // that the server had already received.
                        // Otherwise (the server got all bytes the client sent), the stream is in its right
                        // position, and we can continue from there
                        contentInputStream!!.reset()
                        val actualSkipValue = contentInputStream!!.skip(currentBytesServerReceived)
                        Preconditions.checkState(currentBytesServerReceived == actualSkipValue)
                    }
                } else if (copyBytes == 0L) {
                    // server got all the bytes, so we don't need to use this buffer. Otherwise, we have to
                    // keep the buffer and copy part (or all) of its bytes to the stream we are sending to the
                    // server
                    currentRequestContentBuffer = null
                }
                numBytesUploaded = newBytesServerReceived
                updateStateAndNotifyListener(UploadState.MEDIA_IN_PROGRESS)
            } finally {
                if (!returningResponse) {
                    response.disconnect()
                }
            }
        }
    }

    fun pause() {
        isPaused = true
    }

    fun resumeIfPaused() {
        if (isPaused && uploadUrl != null) {
            resume()
        }
    }

    /** @return `true` if the media length is known, otherwise `false`
     */
    @get:Throws(IOException::class)
    private val isMediaLengthKnown: Boolean
        get() = mediaContentLength >= 0

    /**
     * This method sends a POST request with empty content to get the unique upload URL.
     *
     * @param initiationRequestUrl The request URL where the initiation request will be sent
     */
    @Throws(IOException::class)
    private fun executeUploadInitiation(initiationRequestUrl: GenericUrl): HttpResponse {
        updateStateAndNotifyListener(UploadState.INITIATION_STARTED)
        initiationRequestUrl.put("uploadType", "resumable")
        val content = if (metadata == null) EmptyContent() else metadata!!
        val request =
            requestFactory.buildRequest(initiationRequestMethod, initiationRequestUrl, content)
        initiationHeaders[CONTENT_TYPE_HEADER] = mediaContent.type
        if (isMediaLengthKnown) {
            initiationHeaders[CONTENT_LENGTH_HEADER] =
                mediaContentLength
        }
        request.headers.putAll(initiationHeaders)
        val response = executeCurrentRequest(request)
        var notificationCompleted = false
        try {
            updateStateAndNotifyListener(UploadState.INITIATION_COMPLETE)
            notificationCompleted = true
        } finally {
            if (!notificationCompleted) {
                response.disconnect()
            }
        }
        return response
    }

    /**
     * Executes the current request with some minimal common code.
     *
     * @param request current request
     * @return HTTP response
     */
    @Throws(IOException::class)
    private fun executeCurrentRequestWithoutGZip(request: HttpRequest?): HttpResponse {
        // method override for non-POST verbs
        MethodOverride().intercept(request)
        // don't throw an exception so we can let a custom Google exception be thrown
        request!!.throwExceptionOnExecuteError = false
        // execute the request
        return request.execute()
    }

    /**
     * Executes the current request with some common code that includes exponential backoff and GZip
     * encoding.
     *
     * @param request current request
     * @return HTTP response
     */
    @Throws(IOException::class)
    private fun executeCurrentRequest(request: HttpRequest?): HttpResponse {
        // enable GZip encoding if necessary
        if (!disableGZipContent && request!!.content !is EmptyContent) {
            request!!.encoding = GZipEncoding()
        }
        // execute request
        return executeCurrentRequestWithoutGZip(request)
    }

    /**
     * Sets the HTTP media content chunk and the required headers that should be used in the upload
     * request.
     */
    @Throws(IOException::class)
    private fun buildContentChunk(): ContentChunk {
        val blockSize: Int = if (isMediaLengthKnown) {
            // We know exactly what the blockSize will be because we know the media content length.
            min(chunkSize.toLong(), mediaContentLength - numBytesUploaded).toInt()
        } else {
            // Use the chunkSize as the blockSize because we do know what what it is yet.
            chunkSize
        }
        val contentChunk: AbstractInputStreamContent
        var actualBlockSize = blockSize
        if (isMediaLengthKnown) {
            // Mark the current position in case we need to retry the request.
            contentInputStream!!.mark(blockSize)
            val limitInputStream = ByteStreams.limit(contentInputStream, blockSize.toLong())
            contentChunk = InputStreamContent(mediaContent.type, limitInputStream)
                .setRetrySupported(true)
                .setLength(blockSize.toLong())
                .setCloseInputStream(false)
            mediaContentLengthStr = mediaContentLength.toString()
        } else {
            // If the media content length is not known we implement a custom buffered input stream that
            // enables us to detect the length of the media content when the last chunk is sent. We
            // accomplish this by always trying to read an extra byte further than the end of the current
            // chunk.
            val actualBytesRead: Int
            val bytesAllowedToRead: Int

            // amount of bytes which need to be copied from last chunk buffer
            var copyBytes = 0
            if (currentRequestContentBuffer == null) {
                bytesAllowedToRead = if (cachedByte == null) blockSize + 1 else blockSize
                currentRequestContentBuffer = ByteArray(blockSize + 1)
                if (cachedByte != null) {
                    currentRequestContentBuffer!![0] = cachedByte!!
                }
            } else {
                // currentRequestContentBuffer is not null that means one of the following:
                // 1. This is a request to recover from a server error (e.g. 503)
                // or
                // 2. The server received less bytes than the amount of bytes the client had sent. For
                // example, the client sends bytes 100-199, but the server returns back status code 308,
                // and its "Range" header is "bytes=0-150".
                // In that case, the new request will be constructed from the previous request's byte buffer
                // plus new bytes from the stream.
                copyBytes = (totalBytesClientSent - numBytesUploaded).toInt()
                // shift copyBytes bytes to the beginning - those are the bytes which weren't received by
                // the server in the last chunk.
                System.arraycopy(
                    currentRequestContentBuffer!!,
                    currentChunkLength - copyBytes,
                    currentRequestContentBuffer!!,
                    0,
                    copyBytes
                )
                if (cachedByte != null) {
                    // add the last cached byte to the buffer
                    currentRequestContentBuffer!![copyBytes] = cachedByte!!
                }
                bytesAllowedToRead = blockSize - copyBytes
            }
            actualBytesRead = ByteStreams.read(
                contentInputStream,
                currentRequestContentBuffer,
                blockSize + 1 - bytesAllowedToRead,
                bytesAllowedToRead
            )
            if (actualBytesRead < bytesAllowedToRead) {
                actualBlockSize = copyBytes + max(0, actualBytesRead)
                if (cachedByte != null) {
                    actualBlockSize++
                    cachedByte = null
                }
                if (mediaContentLengthStr == "*") {
                    // At this point we know we reached the media content length because we either read less
                    // than the specified chunk size or there is no more data left to be read.
                    mediaContentLengthStr = (numBytesUploaded + actualBlockSize).toString()
                }
            } else {
                cachedByte = currentRequestContentBuffer!![blockSize]
            }
            contentChunk = ByteArrayContent(
                mediaContent.type, currentRequestContentBuffer, 0, actualBlockSize
            )
            totalBytesClientSent = numBytesUploaded + actualBlockSize
        }
        currentChunkLength = actualBlockSize
        val contentRange: String = if (actualBlockSize == 0) {
            // No bytes to upload. Either zero content media being uploaded, or a server failure on the
            // last write, even though the write actually succeeded. Either way,
            // mediaContentLengthStr will contain the actual media length.
            "bytes */$mediaContentLengthStr"
        } else {
            ("bytes "
                    + numBytesUploaded
                    + "-"
                    + (numBytesUploaded + actualBlockSize - 1)
                    + "/"
                    + mediaContentLengthStr)
        }
        return ContentChunk(contentChunk, contentRange)
    }

    private class ContentChunk constructor(
        val content: AbstractInputStreamContent,
        val contentRange: String
    )

    /**
     * Sets whether direct media upload is enabled or disabled.
     *
     *
     * If value is set to `true` then a direct upload will be done where the whole media
     * content is uploaded in a single request. If value is set to `false` then the upload uses
     * the resumable media upload protocol to upload in data chunks.
     *
     *
     * Direct upload is recommended if the content size falls below a certain minimum limit. This
     * is because there's minimum block write size for some Google APIs, so if the resumable request
     * fails in the space of that first block, the client will have to restart from the beginning
     * anyway.
     *
     *
     * Defaults to `false`.
     *
     * @since 1.9
     */
    @Suppress("Unused")
    fun setDirectUploadEnabled(directUploadEnabled: Boolean): MediaHttpUploader {
        this.directUploadEnabled = directUploadEnabled
        return this
    }

    /**
     * Returns whether direct media upload is enabled or disabled. If value is set to `true`
     * then a direct upload will be done where the whole media content is uploaded in a single
     * request. If value is set to `false` then the upload uses the resumable media upload
     * protocol to upload in data chunks. Defaults to `false`.
     *
     * @since 1.9
     */
    @Suppress("Unused")
    fun isDirectUploadEnabled(): Boolean {
        return directUploadEnabled
    }

    /**
     * [Beta] <br></br>
     * The call back method that will be invoked on a server error or an I/O exception during
     * resumable upload inside [.upload].
     *
     *
     * This method changes the current request to query the current status of the upload to find
     * how many bytes were successfully uploaded before the server error occurred.
     */
    @Beta
    @Throws(IOException::class)
    fun serverErrorCallback() {
        Preconditions.checkNotNull(currentRequest, "The current request should not be null")

        // Query the current status of the upload by issuing an empty PUT request on the upload URI.
        currentRequest?.content = EmptyContent()
        currentRequest?.headers?.contentRange = "bytes */$mediaContentLengthStr"
    }

    /**
     * Returns the next byte index identifying data that the server has not yet received, obtained
     * from the HTTP Range header (E.g a header of "Range: 0-55" would cause 56 to be returned).
     * `null` or malformed headers cause 0 to be returned.
     *
     * @param rangeHeader in the HTTP response
     * @return the byte index beginning where the server has yet to receive data
     */
    private fun getNextByteIndex(rangeHeader: String?): Long {
        return if (rangeHeader == null) {
            0L
        } else rangeHeader.substring(rangeHeader.indexOf('-') + 1).toLong() + 1
    }

    /** Sets HTTP content metadata for the media request or `null` for none.  */
    @Suppress("Unused")
    fun setMetadata(metadata: HttpContent?): MediaHttpUploader {
        this.metadata = metadata
        return this
    }

    /** Returns the HTTP content of the media to be uploaded.  */
    @Suppress("Unused")
    fun getMediaContent(): HttpContent {
        return mediaContent
    }

    /** Sets the progress listener to send progress notifications to or `null` for none.  */
    @Suppress("Unused")
    fun setProgressListener(progressListener: ((uploader: MediaHttpUploader) -> Unit)?): MediaHttpUploader {
        this.progressListener = progressListener
        return this
    }

    /**
     * Sets the maximum size of individual chunks that will get uploaded by single HTTP requests. The
     * default value is [.DEFAULT_CHUNK_SIZE].
     *
     *
     * The minimum allowable value is [.MINIMUM_CHUNK_SIZE] and the specified chunk size must
     * be a multiple of [.MINIMUM_CHUNK_SIZE].
     */
    @Suppress("Unused")
    fun setChunkSize(chunkSize: Int): MediaHttpUploader {
        Preconditions.checkArgument(
            chunkSize > 0 && chunkSize % MINIMUM_CHUNK_SIZE == 0,
            "chunkSize must be a positive multiple of $MINIMUM_CHUNK_SIZE."
        )
        this.chunkSize = chunkSize
        return this
    }

    /**
     * Sets whether to disable GZip compression of HTTP content.
     *
     *
     * By default it is `false`.
     *
     *
     * If [.setDisableGZipContent] is set to false (the default value) then content
     * is gzipped for direct media upload and resumable media uploads when content length is not
     * known. Due to a current limitation, content is not gzipped for resumable media uploads when
     * content length is known; this limitation will be removed in the future.
     *
     * @since 1.13
     */
    @Suppress("Unused")
    fun setDisableGZipContent(disableGZipContent: Boolean): MediaHttpUploader {
        this.disableGZipContent = disableGZipContent
        return this
    }

    /**
     * Sets the sleeper. The default value is [Sleeper.DEFAULT].
     *
     * @since 1.15
     */
    @Suppress("Unused")
    fun setSleeper(sleeper: Sleeper): MediaHttpUploader {
        this.sleeper = sleeper
        return this
    }

    /**
     * Sets the HTTP method used for the initiation request.
     *
     *
     * Can only be [HttpMethods.POST] (for media upload) or [HttpMethods.PUT] (for
     * media update). The default value is [HttpMethods.POST].
     *
     * @since 1.12
     */
    @Suppress("Unused")
    fun setInitiationRequestMethod(initiationRequestMethod: String): MediaHttpUploader {
        Preconditions.checkArgument(
            initiationRequestMethod == HttpMethods.POST || initiationRequestMethod == HttpMethods.PUT || initiationRequestMethod == HttpMethods.PATCH
        )
        this.initiationRequestMethod = initiationRequestMethod
        return this
    }

    /** Sets the HTTP headers used for the initiation request.  */
    @Suppress("Unused")
    fun setInitiationHeaders(initiationHeaders: HttpHeaders): MediaHttpUploader {
        this.initiationHeaders = initiationHeaders
        return this
    }

    /**
     * Sets the upload state and notifies the progress listener.
     *
     * @param uploadState value to set to
     */
    @Throws(IOException::class)
    private fun updateStateAndNotifyListener(uploadState: UploadState) {
        this.uploadState = uploadState
        progressListener?.invoke(this)
    }

    /**
     * Gets the upload progress denoting the percentage of bytes that have been uploaded, represented
     * between 0.0 (0%) and 1.0 (100%).
     *
     *
     * Do not use if the specified [AbstractInputStreamContent] has no content length
     * specified. Instead, consider using [.getNumBytesUploaded] to denote progress.
     *
     * @throws IllegalArgumentException if the specified [AbstractInputStreamContent] has no
     * content length
     * @return the upload progress
     */
    @get:Throws(IOException::class)
    @Suppress("Unused")
    val progress: Double
        get() {
            Preconditions.checkArgument(
                isMediaLengthKnown,
                "Cannot call getProgress() if "
                        + "the specified AbstractInputStreamContent has no content length. Use "
                        + " getNumBytesUploaded() to denote progress instead."
            )
            return if (mediaContentLength == 0L) 0.toDouble() else numBytesUploaded.toDouble() / mediaContentLength
        }

    companion object {
        /**
         * Upload content type header.
         *
         * @since 1.13
         */
        const val CONTENT_LENGTH_HEADER = "X-Upload-Content-Length"

        /**
         * Upload content length header.
         *
         * @since 1.13
         */
        const val CONTENT_TYPE_HEADER = "X-Upload-Content-Type"
        @Suppress("MemberVisibilityCanBePrivate")
        const val MB = 0x100000
        private const val KB = 0x400

        /** Minimum number of bytes that can be uploaded to the server (set to 256KB).  */
        const val MINIMUM_CHUNK_SIZE = 256 * KB

        /**
         * Default maximum number of bytes that will be uploaded to the server in any single HTTP request
         * (set to 10 MB).
         */
        const val DEFAULT_CHUNK_SIZE = 10 * MB
    }
}
