package uk.co.cgfindies.youtubevogthumbnailcreator.service

import com.google.api.client.http.HttpIOExceptionHandler
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpResponse
import com.google.api.client.http.HttpUnsuccessfulResponseHandler
import com.google.api.client.util.Beta
import com.google.api.client.util.Preconditions
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

/*
* Copyright (c) 2013 Google Inc.
*
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
 * MediaUpload error handler handles an [IOException] and an abnormal HTTP response by calling
 * to [MediaHttpUploader.serverErrorCallback].
 *
 * @author Eyal Peled
 */
@Beta
internal class MediaUploadErrorHandler(uploader: MediaHttpUploader, request: HttpRequest) :
    HttpUnsuccessfulResponseHandler, HttpIOExceptionHandler {
    /** The uploader to callback on if there is a server error.  */
    private val uploader: MediaHttpUploader = Preconditions.checkNotNull(uploader)

    /** The original [HttpIOExceptionHandler] of the HTTP request.  */
    private val originalIOExceptionHandler: HttpIOExceptionHandler? = request.ioExceptionHandler

    /** The original [HttpUnsuccessfulResponseHandler] of the HTTP request.  */
    private val originalUnsuccessfulHandler: HttpUnsuccessfulResponseHandler? = request.unsuccessfulResponseHandler

    @Throws(IOException::class)
    override fun handleIOException(request: HttpRequest, supportsRetry: Boolean): Boolean {
        val handled = (originalIOExceptionHandler != null
                && originalIOExceptionHandler.handleIOException(request, supportsRetry))

        // TODO(peleyal): figure out what is best practice - call serverErrorCallback only if I/O
        // exception was handled, or call it regardless
        if (handled) {
            try {
                uploader.serverErrorCallback()
            } catch (e: IOException) {
                LOGGER.log(
                    Level.WARNING,
                    "exception thrown while calling server callback",
                    e
                )
            }
        }
        return handled
    }

    @Throws(IOException::class)
    override fun handleResponse(
        request: HttpRequest,
        response: HttpResponse,
        supportsRetry: Boolean
    ): Boolean {
        val handled = (originalUnsuccessfulHandler != null
                && originalUnsuccessfulHandler.handleResponse(request, response, supportsRetry))

        // TODO(peleyal): figure out what is best practice - call serverErrorCallback only if the
        // abnormal response was handled, or call it regardless
        if (handled && supportsRetry && response.statusCode / 100 == 5) {
            try {
                uploader.serverErrorCallback()
            } catch (e: IOException) {
                LOGGER.log(
                    Level.WARNING,
                    "exception thrown while calling server callback",
                    e
                )
            }
        }
        return handled
    }

    companion object {
        val LOGGER: Logger = Logger.getLogger(MediaUploadErrorHandler::class.java.name)
    }

    init {
        request.ioExceptionHandler = this
        request.unsuccessfulResponseHandler = this
    }
}
