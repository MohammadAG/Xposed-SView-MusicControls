package com.mohammadag.sviewpowerampmetadata;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;

public class SettingsActivity extends PreferenceActivity {
	private EditTextPreference mLongPressTimeoutEditText = null;
	private ListPreference mCurrentPlayerList = null;
	
	@SuppressWarnings("deprecation")
	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);
		getPreferenceManager().setSharedPreferencesMode(MODE_WORLD_READABLE);

		addPreferencesFromResource(R.xml.pref_general);
		
		getPreferenceScreen().setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				emitSettingsChanged();
				return true;
			}
		});
		
		OnPreferenceChangeListener genericSettingsChangedListener = new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				emitSettingsChanged();
				return true;
			}
		};
		
		final SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
		
		mLongPressTimeoutEditText = (EditTextPreference) findPreference(Common.SETTINGS_LONGPRESS_TIMEOUT_KEY);
		
		mLongPressTimeoutEditText.setSummary(String.valueOf(prefs.getString(Common.SETTINGS_LONGPRESS_TIMEOUT_KEY,
				String.valueOf(Common.DEFAULT_LONG_PRESS_TIME_MS)) + " " + "ms"));
		
		mLongPressTimeoutEditText.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				mLongPressTimeoutEditText.setSummary(newValue.toString() + " " + "ms");
				emitSettingsChanged();
				return true;
			}
		});
		
		mCurrentPlayerList = (ListPreference) findPreference(Common.SETTINGS_MEDIAPLAYER_KEY);
		
		if (prefs.contains(Common.SETTINGS_MEDIAPLAYER_KEY)) {
			mCurrentPlayerList.setSummary("  ");
			mCurrentPlayerList.setSummary("%s");
			
			if (mCurrentPlayerList.getSummary().equals("%s")) {
				mCurrentPlayerList.setSummary(getString(R.string.pref_description_media_player));
			}
		} else {
			mCurrentPlayerList.setSummary(getString(R.string.pref_description_media_player));
		}
		
		mCurrentPlayerList.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				mCurrentPlayerList.setSummary(" ");
				mCurrentPlayerList.setSummary("%s");
				emitSettingsChanged();
				return true;
			}
		});
		
		findPreference(Common.SETTINGS_LONGPRESS_KEY).setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				mLongPressTimeoutEditText.setEnabled((Boolean)newValue);
				return true;
			}
		});
		
		findPreference(Common.SETTINGS_SWIPE_ONLY_WHEN_PLAYING_KEY).setOnPreferenceChangeListener(genericSettingsChangedListener);
		findPreference(Common.SETTINGS_DISABLE_SAMSUNG_METADATA_UPDATES).setOnPreferenceChangeListener(genericSettingsChangedListener);
	}
	
	@Override
	protected void onPause() {
		super.onPause();
		emitSettingsChanged();
	}
	
	private void emitSettingsChanged() {
		Intent i = new Intent(Common.SETTINGS_UPDATED_INTENT);
		sendBroadcast(i);
	}
}
