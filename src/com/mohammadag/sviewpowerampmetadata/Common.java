package com.mohammadag.sviewpowerampmetadata;

import android.view.MotionEvent.PointerCoords;

public class Common {
	// Disable experimental code 
	public static final boolean PUBLIC_RELEASE = true;
	
	public static final String PACKAGE_NAME = "com.mohammadag.sviewpowerampmetadata";
	
	// Static package names
	public static final String SPOTIFY_PACKAGE_NAME = "com.spotify.mobile.android.ui";
	
	// // Intents sent by our package
	// This intent is sent by our settings activity, this way we don't use prefs.reload()
	// in every method.
	public static final String SETTINGS_UPDATED_INTENT = "com.mohammadag.sviewpowerampmetadata.SETTINGS_UPDATED";
	public static final String SPOTIFY_METACHANGED_INTENT = "com.mohammadag.hooks.spotify.metachanged";
	
	// Gesture related fields
	public static PointerCoords mDownPos = new PointerCoords();
	public static PointerCoords mUpPos = new PointerCoords();
	public static final int MIN_DISTANCE = 100;
	
	// Default values by Samsung
	public static final long DEFAULT_SVIEW_SCREEN_TIMEOUT = 8000;
	
	// Settings keys
	public static final String SETTINGS_LONGPRESS_KEY = "longpress_to_toggle_playback";
	public static final String SETTINGS_MEDIAPLAYER_KEY = "current_media_player";
	public static final String SETTINGS_DISABLE_SAMSUNG_METADATA_UPDATES = "disable_samsung_music_updates";
	public static final String SETTINGS_LONGPRESS_TIMEOUT_KEY = "longpress_timeout";
	public static final String SETTINGS_SWIPE_ONLY_WHEN_PLAYING_KEY = "swipe_only_while_playing";
	public static final String SETTINGS_DID_WE_SHOW_LONG_PRESS_INSTRUCTIONS = "did_we_show_instructions";
	public static final String SETTINGS_USERDEBUG_KEY = "user_debug_mode";
	
	// Content providers to query
	public static final String GOOGLE_PLAY_CONTENT_PROVIDER_URI = "content://com.google.android.music.MusicContent/albumart";
	public static final String MEDIA_STORE_CONTENT_PROVIDER_URI = "content://media/external/audio/albumart";
	
	// Mediaplayer values
	public static final String SAMSUNG_MEDIAPLAYER = "com.sec.android.app.music";
	public static final String POWERAMP_MEDIAPLAYER = "com.maxmpz.audioplayer";
	public static final String GOOGLEPLAY_MEDIAPLAYER = "com.android.music";
	public static final String SPOTIFY_MEDIAPLAYER = SPOTIFY_PACKAGE_NAME;
	public static final String EMULATE_MEDIA_KEYS_MEDIAPLAYER = "emulate_media_buttons";
	
	// Default values for settings
	public static final int DEFAULT_LONG_PRESS_TIME_MS = 500;
	public static final String DEFAULT_MEDIAPLAYER = SAMSUNG_MEDIAPLAYER;
	
	public static final String[] CLOCK_VIEW_SUBVIEW_NAMES = {"mTime",  "mAmPm",  "mDayofWeek",  "mMonthandDay"};
}
