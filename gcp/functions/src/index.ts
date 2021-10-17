import * as functions from 'firebase-functions'
import {auth} from '@googleapis/oauth2'
import * as config from './config.json'

export const youtubeRestApi = functions.https.onRequest((request, response) => {
  if (request.path === '/generateAuthUrl') {
    const oauth2Client = new auth.OAuth2(
        config.clientId,
        process.env.YOUTUBE_REST_API_SECRET,
        config.apiBaseUrl + '/tokenResponse'
    )

    const scopes = [
      'https://www.googleapis.com/youtube/readonly',
      'https://www.googleapis.com/youtube/upload'
    ]

    const url = oauth2Client.generateAuthUrl({
      // 'online' (default) or 'offline' (gets refresh_token)
      access_type: 'offline',
      scope: scopes
    })

    response.send({
      url
    })
  } else if (request.path === '/tokenResponse') {
    response.send('TODO:: script that posts message to webview')
  } else {
    response.sendStatus(404)
  }
})
