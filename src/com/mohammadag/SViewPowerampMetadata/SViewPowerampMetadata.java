package com.mohammadag.SViewPowerampMetadata;

import static de.robv.android.xposed.XposedHelpers.getObjectField;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.maxmpz.audioplayer.player.PowerAMPiAPI;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class SViewPowerampMetadata implements IXposedHookLoadPackage {
	
	private Context mContext = null;
	private TextView mTrackTitle = null;
	private ImageView mAlbumArtWithImage = null;
	
	private Object mMusicWidgetObject = null;
	
	private Intent mTrackIntent;
	private Intent mAAIntent;
	private Intent mStatusIntent;

	private Bundle mCurrentTrack;
	
	private boolean isPlaying = false;
	private String mTrackTitleString = "";
	private String mArtistNameString = "";
	private Bitmap mAlbumArt = null;

	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("android"))
			return;
		
		XposedBridge.log("Initializing S View hooks...");
		
		Class<?> SViewCoverManager = XposedHelpers.findClass("com.android.internal.policy.impl.sviewcover.SViewCoverManager",
				lpparam.classLoader);
		
		XposedBridge.hookAllConstructors(SViewCoverManager, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				if (mContext == null)
					mContext = (Context) getObjectField(param.thisObject, "mContext");
				registerAndLoadStatus();
			}
		});
		
		Class<?> MusicWidget = XposedHelpers.findClass("com.android.internal.policy.impl.sviewcover.SViewCoverWidget$MusicWidet",
				lpparam.classLoader);
		
		XposedHelpers.findAndHookMethod(MusicWidget, "onFinishInflate", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				mMusicWidgetObject = param.thisObject;
				
				mTrackTitle = (TextView) getObjectField(param.thisObject, "mTrackTitle");
				mAlbumArtWithImage = (ImageView) getObjectField(param.thisObject, "mAlbumArtWithImage");
				
				updateRemoteFieldsFromLocalFields();
				
				if (isPlaying) {
					setVisibilityOfMusicWidgets(View.VISIBLE);
					setTrackTitleText(getTextToSet(mTrackTitleString, mArtistNameString));
					if (mAlbumArt != null) {
						mAlbumArtWithImage.setImageBitmap(mAlbumArt);
					}
				} else {
					setVisibilityOfMusicWidgets(View.GONE);
				}
			}
		});
		

	}
	
	private String getTextToSet(String title, String artist) {
		return title + " - " + artist;
	}
	
	private void setTrackTitleText(String text) {
		if (mTrackTitle != null) {
			mTrackTitle.setText(text);
			mTrackTitle.setSelected(true);
			if (!text.isEmpty()) {
				setVisibilityOfMusicWidgets(View.VISIBLE);
			}
		}
	}
	
	private void setAlbumArt(Bitmap bitmap) {
		mAlbumArt = bitmap;
		
		if (mAlbumArtWithImage != null)
			mAlbumArtWithImage.setImageBitmap(bitmap);
	}
	
	private void updateRemoteFieldsFromLocalFields() {
		setTrackTitleText(getTextToSet(mTrackTitleString, mArtistNameString));
		setAlbumArt(mAlbumArt);
		
		if (mMusicWidgetObject != null) {
			XposedHelpers.setObjectField(mMusicWidgetObject, "currentTitle", mTrackTitleString);
			XposedHelpers.setObjectField(mMusicWidgetObject, "currentArtist", mArtistNameString);
			XposedHelpers.setObjectField(mMusicWidgetObject, "mAlbumArtBitmap", mAlbumArt);
			XposedHelpers.setBooleanField(mMusicWidgetObject, "mIsPlaying", isPlaying);
		}
	}
	 
	private void registerAndLoadStatus() {
		XposedBridge.log("Register broadcast receivers");
		
		if (mContext == null)
			return;
		
		mAAIntent = mContext.registerReceiver(mAAReceiver, new IntentFilter(PowerAMPiAPI.ACTION_AA_CHANGED));
		mTrackIntent = mContext.registerReceiver(mTrackReceiver, new IntentFilter(PowerAMPiAPI.ACTION_TRACK_CHANGED));
		mStatusIntent = mContext.registerReceiver(mStatusReceiver, new IntentFilter(PowerAMPiAPI.ACTION_STATUS_CHANGED));
		
		XposedBridge.log("Successfully registered receivers");
	}
	
	private BroadcastReceiver mTrackReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mTrackIntent = intent;
			mCurrentTrack = null;
			if (mTrackIntent != null) {
				mCurrentTrack = mTrackIntent.getBundleExtra(PowerAMPiAPI.TRACK);
				updateTrackUI();
			}
			XposedBridge.log("mTrackReceiver " + intent);
		}
	};
	
	private BroadcastReceiver mAAReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mAAIntent = intent;
			updateAlbumArt();
			XposedBridge.log("mAAReceiver " + intent);
		}
	};
	
	private BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mStatusIntent = intent;
			updateStatusUI();		
			XposedBridge.log("mStatusReceiver" + intent);
		}
	};
	
	private void setVisibilityOfMusicWidgets(int visibility) {
		if (mTrackTitle != null) {
			mTrackTitle.setVisibility(visibility);
			mTrackTitle.setSelected(true);
		}
		
		if (mAlbumArtWithImage != null)
			mAlbumArtWithImage.setVisibility(visibility);
	}

	protected void updateAlbumArt() {
		String directAAPath = mAAIntent.getStringExtra(PowerAMPiAPI.ALBUM_ART_PATH);

		 if (mAAIntent.hasExtra(PowerAMPiAPI.ALBUM_ART_BITMAP)) {
			Bitmap albumArtBitmap = mAAIntent.getParcelableExtra(PowerAMPiAPI.ALBUM_ART_BITMAP);
			if (albumArtBitmap != null) {
				setAlbumArt(albumArtBitmap);
			}
		} else 	if (!TextUtils.isEmpty(directAAPath)) {
			if (mAlbumArtWithImage != null) {
				mAlbumArtWithImage.setImageURI(Uri.parse(directAAPath));
			}
		} else {
			setAlbumArt(null);
		}
		 
		 updateRemoteFieldsFromLocalFields();
	}

	protected void updateStatusUI() {
		if (mStatusIntent != null) {
			boolean paused = true;

			int status = mStatusIntent.getIntExtra(PowerAMPiAPI.STATUS, -1);

			switch (status) {
				case PowerAMPiAPI.Status.TRACK_PLAYING:
					paused = mStatusIntent.getBooleanExtra(PowerAMPiAPI.PAUSED, false);
					break;

				case PowerAMPiAPI.Status.TRACK_ENDED:
				case PowerAMPiAPI.Status.PLAYING_ENDED:
					break;
			}
			if (mMusicWidgetObject != null)
				XposedHelpers.setBooleanField(mMusicWidgetObject, "mIsPlaying", !paused);
			
			if (paused) {
				setVisibilityOfMusicWidgets(View.GONE);
				
			} else {
				setVisibilityOfMusicWidgets(View.VISIBLE);
			}
			
			isPlaying = !paused;
		}
		
		updateRemoteFieldsFromLocalFields();
	}

	private void updateTrackUI() {
		String textToSet = "";
		if (mTrackIntent != null) {
			mTrackTitleString = mCurrentTrack.getString(PowerAMPiAPI.Track.TITLE);
			mArtistNameString = mCurrentTrack.getString(PowerAMPiAPI.Track.ARTIST);
			
			textToSet = getTextToSet(mTrackTitleString, mArtistNameString);
		} else {
			mTrackTitleString = mArtistNameString = "";
		}
		
		setTrackTitleText(textToSet);
		updateRemoteFieldsFromLocalFields();
	}
}
