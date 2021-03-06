/**
 * Copyright 2018 Ayogo Health Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ayogo.cordova.push;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class PushPlugin extends CordovaPlugin
{
    private final String TAG = "PushPlugin";

    private final static int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    private static final String PROPERTY_REG_ID = "registration_id";
    private static final String PROPERTY_APP_VERSION = "appVersion";
    private Boolean isPageFinished = false;
    private Intent lastPushIntent;

    @Override
    protected void pluginInitialize() {
        LOG.v(TAG, "Initializing");

        // Check device for Play Services APK.
        if (!checkPlayServices()) {
            LOG.w(TAG, "No valid Google Play Services APK found.");
            return;
        }

        onNewIntent(cordova.getActivity().getIntent());
    }

    @Override
    public Object onMessage(String id, Object data) {
        if(id.equals("onPageStarted")) {
            this.isPageFinished = false;
        }
        if (id.equals("onPageFinished")) {
            this.isPageFinished = true;
            if (this.lastPushIntent != null) {
                final Intent pushIntent = this.lastPushIntent;

                /**
                * In the case that the page within the webview reloads on notification clicked the dispatched event fires into oblivion.
                * Wait 1s before dispatching the event to ensure that the page has finished loading. Listeners should be setup on an app level
                * and not dependent on certain pages loading lots of content thus the timeout here can be as small as 0.1s.
                */
                new android.os.Handler().postDelayed(
                    new Runnable() {
                        public void run() {
                            handleNotificationData(pushIntent);
                        }
                    },
                500);
            }
        }
        return super.onMessage(id, data);
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callback)
    {
        String senderID = null;
        Activity activity = cordova.getActivity();

        try {
            senderID = activity.getString(activity.getResources().getIdentifier("gcm_defaultSenderId", "string", activity.getPackageName()));
        } catch (Resources.NotFoundException e) {
            senderID = this.preferences.getString("fcm_sender_id", null);
        }

        final String fSenderID = senderID;

        if (action.equals("registerPush") || action.equals("getPushRegistration")) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        String deviceToken = FirebaseInstanceId.getInstance().getToken(fSenderID, FirebaseMessaging.INSTANCE_ID_SCOPE);

                        callback.sendPluginResult(new PluginResult(PluginResult.Status.OK, wrapRegistrationData(deviceToken)));
                    } catch (IOException ex) {
                        callback.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "AbortError"));
                    }
                }
            });
            return true;
        }


        if (action.equals("unregisterPush")) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        FirebaseInstanceId.getInstance().deleteToken(fSenderID, FirebaseMessaging.INSTANCE_ID_SCOPE);

                        callback.sendPluginResult(new PluginResult(PluginResult.Status.OK));
                    } catch (IOException ex) {
                        callback.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "AbortError"));
                    }
                }
            });
            return true;
        }


        if (action.equals("hasPermission")) {
            // Android doesn't require permission to send push notifications
            callback.sendPluginResult(new PluginResult(PluginResult.Status.OK, "granted"));
            return true;
        }

        LOG.i(TAG, "Tried to call " + action + " with " + args.toString());

        callback.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION));
        return false;
    }

    private JSONObject wrapRegistrationData(String registrationId) {
        Map<String,String> regData = new HashMap<String,String>();
        regData.put("endpoint", "android");
        regData.put("registrationId", registrationId);
        return new JSONObject(regData);
    }

    public void onNewIntent(Intent intent) {
        if(intent == null){
            return;
        }

        if(intent.getAction() != null && intent.getAction().equalsIgnoreCase("push")) {
            handlePushIntent(intent);
            this.lastPushIntent = intent;
            if(this.isPageFinished) {
                handleNotificationData(intent);
            }
        }
    }

    /**
     * Send intent extas to the application
     * @param intent
     */
    private void handleNotificationData(Intent intent) {
        JSONObject json = new JSONObject();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            Set<String> keys = extras.keySet();
            for (String key : keys) {
                try {
                    json.put(key, JSONObject.wrap(extras.get(key)));
                } catch (JSONException e) {
                // Do nothing for now
                }
            }
        }

        this.lastPushIntent = null;
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                webView.getEngine().evaluateJavascript("window.dispatchEvent(new CustomEvent('CDVnotificationClicked', { detail: "+ json +"}));", null);
            }
        });
    }

    private void handlePushIntent(Intent intent) {
        if(intent.getExtras() != null && intent.getExtras().getString("url") != null) {
            final String url = intent.getExtras().getString("url");
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    Context context = cordova.getActivity().getApplicationContext();
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
            });
        }
    }


    /**
     * Check the device to make sure it has the Google Play Services APK.
     *
     * If it doesn't, display a dialog that allows users to download the APK
     * from the Google Play Store or enable it in the device's system settings.
     */
    private boolean checkPlayServices() {
        GoogleApiAvailability googleApi = GoogleApiAvailability.getInstance();
        Activity act = this.cordova.getActivity();

        int resultCode = googleApi.isGooglePlayServicesAvailable(act.getApplicationContext());

        if (resultCode != ConnectionResult.SUCCESS) {
            if (googleApi.isUserResolvableError(resultCode)) {
                googleApi.getErrorDialog(act, resultCode, PLAY_SERVICES_RESOLUTION_REQUEST).show();
            } else {
                LOG.w(TAG, "This device is not supported.");
            }
            return false;
        }
        return true;
    }
}
