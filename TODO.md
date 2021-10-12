# TODO
How I think this will need to work is:
- App pings the backend and receives a link to auth with Youtube and a unique user token
- App displays the link in a webview
- Auth happens
- Webview redirects back to backend, which sends back a html response
- Javascript in the response posts a message with the credentials back to Android (https://stackoverflow.com/questions/19164205/can-the-postmessage-api-be-used-to-communicate-with-an-android-webview)
- App can now use the credentials
