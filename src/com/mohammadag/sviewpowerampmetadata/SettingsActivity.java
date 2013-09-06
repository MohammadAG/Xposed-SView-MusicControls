package com.mohammadag.sviewpowerampmetadata;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;

public class SettingsActivity extends PreferenceActivity {
	private EditTextPreference mLongPressTimeoutEditText = null;
	private ListPreference mCurrentPlayerList = null;
	private CheckBoxPreference mLongPressCheckBox = null;
	
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
		
		mLongPressCheckBox = (CheckBoxPreference) findPreference(Common.SETTINGS_LONGPRESS_KEY);
		mLongPressCheckBox.setChecked(prefs.getBoolean(Common.SETTINGS_LONGPRESS_KEY, true));
		mLongPressCheckBox.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				mLongPressTimeoutEditText.setEnabled((Boolean)newValue);
				return true;
			}
		});
		
		mLongPressTimeoutEditText.setEnabled(mLongPressCheckBox.isChecked());
		
		findPreference("show_in_launcher").setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			
			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				// Thanks to Chainfire for this
				// http://www.chainfire.eu/articles/133/_TUT_Supporting_multiple_icons_in_your_app/
				PackageManager pm = getPackageManager();
			    pm.setComponentEnabledSetting(
			            new ComponentName(getApplicationContext(), Common.PACKAGE_NAME + ".SettingsActivity-Launcher"), 
			            (Boolean)newValue ? 
			                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED : 
			                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 
			            PackageManager.DONT_KILL_APP
			    );
				return true;
			}
		});
		
		findPreference(Common.SETTINGS_SWIPE_ONLY_WHEN_PLAYING_KEY).setOnPreferenceChangeListener(genericSettingsChangedListener);
		findPreference(Common.SETTINGS_DISABLE_SAMSUNG_METADATA_UPDATES).setOnPreferenceChangeListener(genericSettingsChangedListener);
		
		Preference copyrightPreference = findPreference("copyright_key");
        String installer = getPackageManager().getInstallerPackageName(Common.PACKAGE_NAME);
        if (installer == null) {
        	copyrightPreference.setSummary(R.string.pref_copyright_description_not_play_store);
        	copyrightPreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
				@Override
				public boolean onPreferenceClick(Preference preference) {
	            	Intent i = new Intent(Intent.ACTION_VIEW);
	            	i.setData(Uri.parse("https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=4A9688QTM8E94"));
	            	startActivity(i);
					return false;
				}
			});
        }
		
		if (!isXposedInstallerInstalled()) {
	    	AlertDialog.Builder alertDialog = new AlertDialog.Builder(this)
	    	.setTitle(getString(R.string.error_xposed_not_installed))
	    	.setMessage(getString(R.string.error_no_xposed_description))
	    	.setPositiveButton(R.string.button_text_install, new DialogInterface.OnClickListener() {
	            public void onClick(DialogInterface dialog, int which) {
	            	Intent i = new Intent(Intent.ACTION_VIEW);
	            	i.setData(Uri.parse(Common.XPOSED_INSTALLER_WEBSITE));
	            	startActivity(i);
	            	finish();
	            }
	    	})
	    	.setNegativeButton(R.string.button_text_quit, new DialogInterface.OnClickListener() {
	            public void onClick(DialogInterface dialog, int which) {
	            	finish();
	            }
	        })
	        .setOnCancelListener(new OnCancelListener() {
				@Override
				public void onCancel(DialogInterface dialog) {
					finish();
				}
			});
			
	        alertDialog.show();
		} else {
			if (!isModuleActivated()) {
		    	AlertDialog.Builder alertDialog = new AlertDialog.Builder(this)
		    	.setTitle(getString(R.string.error_module_not_activated))
		    	.setMessage(getString(R.string.error_module_not_activated_description))
		    	.setPositiveButton(R.string.button_text_open, new DialogInterface.OnClickListener() {
		            public void onClick(DialogInterface dialog, int which) {
		            	PackageManager pm = getPackageManager();
		            	Intent intent = pm.getLaunchIntentForPackage(Common.XPOSED_INSTALLER_PACKAGE_NAME);
		            	startActivity(intent);
		            }
		    	});
				
		        alertDialog.show();
			}
		}
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
	
    public boolean isPackageExists(String targetPackage) {
    	PackageManager pm = getPackageManager();
    	try {
    		@SuppressWarnings("unused")
			PackageInfo info = pm.getPackageInfo(targetPackage,PackageManager.GET_META_DATA);
		} catch (NameNotFoundException e) {
			return false;
		}
    	return true;
    }
    
    private boolean isXposedInstallerInstalled() {
    	return isPackageExists(Common.XPOSED_INSTALLER_PACKAGE_NAME);
    }
    
    // Thanks to Tungstwenty's Master Key Patch source code for the concept here
    private boolean isModuleActivated() {
    	return false;
    }
}
