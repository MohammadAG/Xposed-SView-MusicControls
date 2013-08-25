package com.mohammadag.sviewpowerampmetadata;

import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;

public class SettingsActivity extends PreferenceActivity {
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
