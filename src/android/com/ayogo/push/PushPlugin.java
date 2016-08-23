// Copyright 2014 Ayogo Health Inc.

package com.ayogo.push;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONObject;

public class PushPlugin extends CordovaPlugin
{
    private final String TAG = "PushPlugin";

    private final static int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    private static final String PROPERTY_REG_ID = "registration_id";
    private static final String PROPERTY_APP_VERSION = "appVersion";

    /** The Sender ID for GCM. */
    protected String mSenderID = null;


    @Override
    protected void pluginInitialize() {
        LOG.v(TAG, "Initializing");

        // Check device for Play Services APK.
        if (!checkPlayServices()) {
            LOG.w(TAG, "No valid Google Play Services APK found.");
            return;
        }

        mSenderID = this.preferences.getString("fcm_sender_id", null);

        onNewIntent(cordova.getActivity().getIntent());
    }


    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callback)
    {
        if (action.equals("register") || action.equals("getRegistration")) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        String deviceToken = FirebaseInstanceId.getInstance().getToken(mSenderID, FirebaseMessaging.INSTANCE_ID_SCOPE);

                        callback.sendPluginResult(new PluginResult(PluginResult.Status.OK, wrapRegistrationData(deviceToken)));
                    } catch (IOException ex) {
                        callback.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "AbortError"));
                    }
                }
            });
            return true;
        }


        if (action.equals("unregister")) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        FirebaseInstanceId.getInstance().deleteToken(mSenderID, FirebaseMessaging.INSTANCE_ID_SCOPE);

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
        }
    }

    private void handlePushIntent(Intent intent) {
        if(intent.getExtras() != null && intent.getExtras().getString("url") != null) {
            final String url = intent.getExtras().getString("url");
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    webView.loadUrl("javascript:window.handleOpenURL('" + url + "')");
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
