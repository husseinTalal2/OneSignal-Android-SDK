/**
 * Modified MIT License
 *
 * Copyright 2017 OneSignal
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * 2. All copies of substantial portions of the Software may only be used in connection
 * with services provided by OneSignal.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.onesignal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.DeadSystemException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationManagerCompat;

import com.onesignal.language.LanguageContext;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class NotificationChannelManager {
   
   // Can't create a channel with the id 'miscellaneous' as an exception is thrown.
   // Using it results in the notification not being displayed.
   // private static final String DEFAULT_CHANNEL_ID = "miscellaneous"; // NotificationChannel.DEFAULT_CHANNEL_ID;

   private static final String DEFAULT_CHANNEL_ID = "fcm_fallback_notification_channel";
   private static final String RESTORE_CHANNEL_ID = "restored_OS_notifications";
   private static final String CHANNEL_PREFIX = "OS_";
   private static final Pattern hexPattern = Pattern.compile("^([A-Fa-f0-9]{8})$");
   
   static String createNotificationChannel(OSNotificationGenerationJob notificationJob) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
         return DEFAULT_CHANNEL_ID;

      Context context = notificationJob.getContext();
      JSONObject jsonPayload = notificationJob.getJsonPayload();

      NotificationManager notificationManager = OneSignalNotificationManager.getNotificationManager(context);

      if (notificationJob.isRestoring())
         return createRestoreChannel(notificationManager);
      
      // Allow channels created outside the SDK
      if (jsonPayload.has("oth_chnl")) {
         String otherChannel = jsonPayload.optString("oth_chnl");
         if (notificationManager.getNotificationChannel(otherChannel) != null)
            return otherChannel;
      }
      
      if (!jsonPayload.has("chnl"))
         return createDefaultChannel(notificationManager);
      
      try {
         return createChannel(context, notificationManager, jsonPayload);
      } catch (JSONException e) {
         OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "Could not create notification channel due to JSON payload error!", e);
      }
      
      return DEFAULT_CHANNEL_ID;
   }

   // Creates NotificationChannel and NotificationChannelGroup based on a json payload.
   // Returns channel id after it is created.
   // Language dependent fields will be passed localized
   @RequiresApi(api = Build.VERSION_CODES.O)
   private static String createChannel(Context context, NotificationManager notificationManager, JSONObject payload) throws JSONException {
      // 'chnl' will be a string if coming from FCM and it will be a JSONObject when coming from
      //   a cold start sync.
      Object objChannelPayload = payload.opt("chnl");
      JSONObject channelPayload = null;
      if (objChannelPayload instanceof String)
         channelPayload = new JSONObject((String)objChannelPayload);
      else
         channelPayload = (JSONObject)objChannelPayload;
      
      String channel_id = channelPayload.optString("id", DEFAULT_CHANNEL_ID);
      // Ensure we don't try to use the system reserved id
      if (channel_id.equals(NotificationChannel.DEFAULT_CHANNEL_ID))
         channel_id = DEFAULT_CHANNEL_ID;
      
      JSONObject payloadWithText = channelPayload;
      if (channelPayload.has("langs")) {
         JSONObject langList = channelPayload.getJSONObject("langs");
         String language = LanguageContext.getInstance().getLanguage();
         if (langList.has(language))
            payloadWithText = langList.optJSONObject(language);
      }
      
      String channel_name = payloadWithText.optString("nm", "Miscellaneous");
   
      int importance = priorityToImportance(payload.optInt("pri", 6));
      NotificationChannel channel = new NotificationChannel(channel_id, channel_name, importance);
      channel.setDescription(payloadWithText.optString("dscr", null));

      if (channelPayload.has("grp_id")) {
         String group_id = channelPayload.optString("grp_id");
         CharSequence group_name = payloadWithText.optString("grp_nm");
         notificationManager.createNotificationChannelGroup(new NotificationChannelGroup(group_id, group_name));
         channel.setGroup(group_id);
      }

      if (payload.has("ledc")) {
         String ledc = payload.optString("ledc");
         Matcher matcher = hexPattern.matcher(ledc);
         BigInteger ledColor;

         if (!matcher.matches()) {
            OneSignal.Log(OneSignal.LOG_LEVEL.WARN, "OneSignal LED Color Settings: ARGB Hex value incorrect format (E.g: FF9900FF)");
            ledc = "FFFFFFFF";
         }

         try {
            ledColor = new BigInteger(ledc, 16);
            channel.setLightColor(ledColor.intValue());
         } catch (Throwable t) {
            OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "Couldn't convert ARGB Hex value to BigInteger:", t);
         }
      }
      channel.enableLights(payload.optInt("led", 1) == 1);

      if (payload.has("vib_pt")) {
         long[] vibrationPattern = OSUtils.parseVibrationPattern(payload);
         if (vibrationPattern != null)
            channel.setVibrationPattern(vibrationPattern);
      }
      channel.enableVibration(payload.optInt("vib", 1) == 1);

      if (payload.has("sound")) {
         // Sound will only play if Importance is set to High or Urgent
         String sound = payload.optString("sound", null);
         Uri uri = OSUtils.getSoundUri(context, sound);
         if (uri != null)
            channel.setSound(uri, null);
         else if ("null".equals(sound) || "nil".equals(sound))
            channel.setSound(null, null);
         // null = None for a sound.
      }
      // Setting sound to null makes it 'None' in the Settings.
      // Otherwise not calling setSound makes it the default notification sound.

      channel.setLockscreenVisibility(payload.optInt("vis", Notification.VISIBILITY_PRIVATE));
      channel.setShowBadge(payload.optInt("bdg", 1) == 1);
      channel.setBypassDnd(payload.optInt("bdnd", 0) == 1);

      OneSignal.onesignalLog(OneSignal.LOG_LEVEL.VERBOSE, "Creating notification channel with channel:\n" + channel.toString());
      try {
         notificationManager.createNotificationChannel(channel);
      } catch (IllegalArgumentException e) {
         // TODO: Remove this try-catch once it is figured out which argument is causing Issue #895
         //    try-catch added to prevent crashing from the illegal argument
         //    Added logging above this try-catch so we can evaluate the payload of the next victim
         //    to report a stacktrace
         //    https://github.com/OneSignal/OneSignal-Android-SDK/issues/895
         e.printStackTrace();
      }
      return channel_id;
   }
   
   @RequiresApi(api = Build.VERSION_CODES.O)
   private static String createDefaultChannel(NotificationManager notificationManager) {
      NotificationChannel channel = new NotificationChannel(DEFAULT_CHANNEL_ID,
          "Miscellaneous",
          NotificationManager.IMPORTANCE_DEFAULT);
      
      channel.enableLights(true);
      channel.enableVibration(true);
      notificationManager.createNotificationChannel(channel);
      
      return DEFAULT_CHANNEL_ID;
   }

   @RequiresApi(api = Build.VERSION_CODES.O)
   private static String createRestoreChannel(NotificationManager notificationManager) {
      NotificationChannel channel = new NotificationChannel(RESTORE_CHANNEL_ID,
            "Restored",
            NotificationManager.IMPORTANCE_LOW);

      notificationManager.createNotificationChannel(channel);

      return RESTORE_CHANNEL_ID;
   }
   
   static void processChannelList(@NonNull Context context, @Nullable JSONArray list) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
         return;

      if (list == null || list.length() == 0)
         return;

      NotificationManager notificationManager = OneSignalNotificationManager.getNotificationManager(context);
      
      Set<String> syncedChannelSet = new HashSet<>();
      int jsonArraySize = list.length();
      for (int i = 0; i < jsonArraySize; i++) {
         try {
            syncedChannelSet.add(createChannel(context, notificationManager, list.getJSONObject(i)));
         } catch (JSONException e) {
            OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "Could not create notification channel due to JSON payload error!", e);
         }
      }

      if (syncedChannelSet.isEmpty())
         return;

      List<NotificationChannel> existingChannels = getChannelList(notificationManager);
      if (existingChannels == null) {
         return;
      }

      // Delete old channels - Payload will include all changes for the app. Any extra OS_ ones must
      //                       have been deleted from the dashboard and should be removed.
      for (NotificationChannel existingChannel : existingChannels) {
         String id = existingChannel.getId();
         if (id.startsWith(CHANNEL_PREFIX) && !syncedChannelSet.contains(id))
            notificationManager.deleteNotificationChannel(id);
      }
   }

   @RequiresApi(api = Build.VERSION_CODES.O)
   private static List<NotificationChannel> getChannelList(NotificationManager notificationManager) {
      try {
         return notificationManager.getNotificationChannels();
      } catch (NullPointerException e) {
         // Catching known Android bug, Sometimes throws,
         // "Attempt to invoke virtual method 'boolean android.app.NotificationChannel.isDeleted()' on a null object reference"
         // https://github.com/OneSignal/OneSignal-Android-SDK/issues/1291
         OneSignal.onesignalLog(OneSignal.LOG_LEVEL.ERROR, "Error when trying to delete notification channel: " + e.getMessage());
      }
      catch (Exception e) {
         // Suppressing DeadSystemException as the app is already dying for
         // another reason and allowing this exception to bubble up would
         // create a red herring for app developers. We still re-throw
         // others, as we don't want to silently hide other issues.
         if (!(e instanceof DeadSystemException)) {
            throw e;
         }
      }
      return null;
   }

   private static int priorityToImportance(int priority) {
      if (priority > 9)
         return NotificationManagerCompat.IMPORTANCE_MAX;
      if (priority > 7)
         return NotificationManagerCompat.IMPORTANCE_HIGH;
      if (priority > 5)
         return NotificationManagerCompat.IMPORTANCE_DEFAULT;
      if (priority > 3)
         return NotificationManagerCompat.IMPORTANCE_LOW;
      if (priority > 1)
         return NotificationManagerCompat.IMPORTANCE_MIN;
      
      return NotificationManagerCompat.IMPORTANCE_NONE;
   }
}
