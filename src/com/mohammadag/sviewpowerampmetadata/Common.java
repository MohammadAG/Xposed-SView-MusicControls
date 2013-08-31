package com.mohammadag.sviewpowerampmetadata;

import android.view.MotionEvent.PointerCoords;

public class Common {
	public static final String PACKAGE_NAME = "com.mohammadag.sviewpowerampmetadata";
	
	// This intent is sent by our settings activity, this way we don't use prefs.reload()
	// in every method.
	public static final String SETTINGS_UPDATED_INTENT = "com.mohammadag.sviewpowerampmetadata.SETTINGS_UPDATED";
	
	// Gesture related fields
	public static PointerCoords mDownPos = new PointerCoords();
	public static PointerCoords mUpPos = new PointerCoords();
	public static final int MIN_DISTANCE = 100;
	
	// Settings keys
	public static final String SETTINGS_LONGPRESS_KEY = "longpress_to_toggle_playback";
	public static final String SETTINGS_MEDIAPLAYER_KEY = "current_media_player";
	public static final String SETTINGS_DISABLE_SAMSUNG_METADATA_UPDATES = "disable_samsung_music_updates";
	public static final String SETTINGS_LONGPRESS_TIMEOUT_KEY = "longpress_timeout";
	public static final String SETTINGS_DID_WE_SHOW_LONG_PRESS_INSTRUCTIONS = "did_we_show_instructions";
	
	// Content providers to query
	public static final String GOOGLE_PLAY_CONTENT_PROVIDER_URI = "content://com.google.android.music.MusicContent/albumart";
	public static final String MEDIA_STORE_CONTENT_PROVIDER_URI = "content://media/external/audio/albumart";
	
	// Mediaplayer values
	public static final String SAMSUNG_MEDIAPLAYER = "com.sec.android.app.music";
	public static final String POWERAMP_MEDIAPLAYER = "com.maxmpz.audioplayer";
	public static final String GOOGLEPLAY_MEDIAPLAYER = "com.android.music";
	public static final String EMULATE_MEDIA_KEYS_MEDIAPLAYER = "emulate_media_buttons";
	
	public static final int DEFAULT_LONG_PRESS_TIME_MS = 500;
	
	public static final String[] CLOCK_VIEW_SUBVIEW_NAMES = {"mTime",  "mAmPm",  "mDayofWeek",  "mMonthandDay"};
}
