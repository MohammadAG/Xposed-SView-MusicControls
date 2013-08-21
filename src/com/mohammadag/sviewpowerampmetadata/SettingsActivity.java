package com.mohammadag.sviewpowerampmetadata;

import java.util.List;

import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;

public class SettingsActivity extends PreferenceActivity {
	@SuppressWarnings("deprecation")
	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);
		getPreferenceManager().setSharedPreferencesMode(MODE_WORLD_READABLE);
		getPreferenceScreen().setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				emitSettingsChanged();
				return false;
			}
		});

		setupSimplePreferencesScreen();
	}

	@SuppressWarnings("deprecation")
	private void setupSimplePreferencesScreen() {
		addPreferencesFromResource(R.xml.pref_general);
	}
	
	@Override
	protected void onPause() {
		super.onPause();
		emitSettingsChanged();
	}

	@Override
	public void onBuildHeaders(List<Header> target) {
		loadHeadersFromResource(R.xml.pref_headers, target);
	}

	public static class GeneralPreferenceFragment extends PreferenceFragment {
		@Override
		public void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			addPreferencesFromResource(R.xml.pref_general);
		}
	}
	
	private void emitSettingsChanged() {
		Intent i = new Intent(SViewPowerampMetadata.SETTINGS_UPDATED_INTENT);
		sendBroadcast(i);
	}
}
