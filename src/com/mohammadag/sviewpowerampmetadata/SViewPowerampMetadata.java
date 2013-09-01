package com.mohammadag.sviewpowerampmetadata;

import static de.robv.android.xposed.XposedBridge.hookAllConstructors;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;

import java.io.FileDescriptor;
import java.lang.reflect.Method;

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.XModuleResources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.maxmpz.audioplayer.player.PowerAMPiAPI;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class SViewPowerampMetadata implements IXposedHookLoadPackage, IXposedHookZygoteInit {
	// Needed for gestures
	private static PointerCoords mDownPos = new PointerCoords();
	private static PointerCoords mUpPos = new PointerCoords();
	private long mDownTime = 0;
	private int mDefaultTextColor = 0;
	private Runnable mColorChangingRunnable = null;
	
	// Pointers to fields from S-View classes
	private Context mContext = null;
	private TextView mTrackTitle = null;
	private ImageView mAlbumArtWithImage = null;
	private LinearLayout mClockView;
	private Object mMusicWidgetObject = null;
	
	// We need these to keep the screen alive while changing tracks
	private Object mKeyguardViewMediator = null;
	private Runnable mGoToSleepRunnable = null;
	
	// Poweramp API stuff
	private Intent mTrackIntent;
	private Intent mAAIntent;
	private Intent mStatusIntent;
	private Bundle mCurrentTrack;
	
	// Fields for internal use, the S-View screen is destroyed at certain times so we need
	// to restore state.
	private boolean mIsPlaying = false;
	private String mTrackTitleString = "";
	private String mArtistNameString = "";
	private Bitmap mAlbumArt = null;
	
	// Handler from S View classes
	private Handler mHandler = null;
	
	// Our own handler to handle metadata changes
	public Handler myHandler = null;
	
	// Avoid system warnings
	private UserHandle mCurrentUserHandle = null;
	
	// Lockscreen specific fields
	private PendingIntent mClientIntent;
	
	// Resources stuff
	private static String MODULE_PATH = null;
	private XModuleResources modRes = null;
	
	// Preferences
	private static XSharedPreferences prefs;
	private String mCurrentMediaPlayer = null;
	private boolean mEnableLongPressToToggle = false;
	private boolean mSwipeTracksOnlyWhilePlaying = false;
	private int mLongPressTimeout = Common.DEFAULT_LONG_PRESS_TIME_MS;
	
	// One time or internal use
	private boolean mShouldShowLongPressInstructions = false;
	private boolean mBlockOtherTextChanges = false;
	
	private enum MusicServiceCommands {
		PLAY_PAUSE,
		NEXT,
		PREVIOUS
	}

	public void initZygote(StartupParam startupParam) {
		prefs = new XSharedPreferences(Common.PACKAGE_NAME);
		MODULE_PATH = startupParam.modulePath;
	}
	
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("android"))
			return;
		
		if (prefs != null)
			prefs.reload();
		
		modRes = XModuleResources.createInstance(MODULE_PATH, null);
		
		// We need this to use send*AsUser
		mCurrentUserHandle = (UserHandle) getStaticObjectField(UserHandle.class, "CURRENT");
		
		// TODO: Move this somewhere else
		final OnTouchListener gestureListener = new OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				final View touchedView = v;
				 switch(event.getAction()) {
				 	case MotionEvent.ACTION_DOWN: {
				 		event.getPointerCoords(0, mDownPos);
				 		if (mEnableLongPressToToggle) {
					 		mDownTime = System.currentTimeMillis();
					 		mColorChangingRunnable = new Runnable() {
								@Override
								public void run() {
									showInstructionsForLongPressIfNeeded();
									setColorForView(touchedView, mContext.getResources().getColor(android.R.color.holo_blue_light));
								}
					 		};
							if (mHandler != null)
								mHandler.postDelayed(mColorChangingRunnable, mLongPressTimeout);
				 		}
				 		return true;
				 	}
				 	
				 	case MotionEvent.ACTION_MOVE: {
				 		if (mEnableLongPressToToggle) {
					 		boolean isLongClick = ((System.currentTimeMillis() - mDownTime) > mLongPressTimeout);
					 		if (isLongClick) {
					 			mHandler.post(mColorChangingRunnable);
					 		}
				 		}
				 		return false;
				 	}
	            
				 	case MotionEvent.ACTION_UP: {
				 		if (mEnableLongPressToToggle && mHandler != null && mColorChangingRunnable != null)
				 			mHandler.removeCallbacks(mColorChangingRunnable);

				 		event.getPointerCoords(0, mUpPos);
				 		boolean isLongClick = false;

				 		if (mEnableLongPressToToggle) {
				 			isLongClick = ((System.currentTimeMillis() - mDownTime) > mLongPressTimeout);
				 			setColorForView(v, mDefaultTextColor);
				 		}

						float dx = mDownPos.x - mUpPos.x;
						if (Math.abs(dx) > Common.MIN_DISTANCE) {
						    if (dx > 0)
						        onSwipeLeft();
						    else
						        onSwipeRight();
						    return true;
						}
						
						// We might use this later on to change playlists in PowerAMP
						float dy = mDownPos.y - mUpPos.y;
						if (Math.abs(dy) > Common.MIN_DISTANCE) {
							if (dy > 0)
								onSwipeUp();
							else
								onSwipeDown();
							return true;
						}

						if (isLongClick && mEnableLongPressToToggle) {
							extendTimeout();
							togglePlayback();
						}

						return true;
				 	}
				 }
				 return false;
			}

			private void onSwipeRight() {
				if (mSwipeTracksOnlyWhilePlaying && !mIsPlaying)
					return;
				
				previousTrack();
				extendTimeout();
			}
			
			private void onSwipeLeft() {
				if (mSwipeTracksOnlyWhilePlaying && !mIsPlaying)
					return;
				
				nextTrack();
				extendTimeout();
			}
			private void onSwipeUp() { }
			private void onSwipeDown() { }
		};
		
		XposedBridge.log("Initializing S View hooks...");
		
		Class<?> SViewCoverManager = XposedHelpers.findClass("com.android.internal.policy.impl.sviewcover.SViewCoverManager",
				lpparam.classLoader);
		final Class<?> keyguardViewMediator = XposedHelpers.findClass("com.android.internal.policy.impl.keyguard.KeyguardViewMediator",
				lpparam.classLoader);
		
		// We need an instance of KeyguardViewMediator to extend the S-View
		// timeout when the device is locked. When the device is unlocked, the
		// timeout is handled internally by SViewCoverManager, default timeout is
		// 8000 milliseconds.
		hookAllConstructors(keyguardViewMediator, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				mKeyguardViewMediator = param.thisObject;
			}
		});
		
		hookAllConstructors(SViewCoverManager, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				if (mContext == null)
					mContext = (Context) getObjectField(param.thisObject, "mContext");
				if (mHandler == null)
					mHandler = (Handler) getObjectField(param.thisObject, "mHandler");
				if (mGoToSleepRunnable == null)
					mGoToSleepRunnable = (Runnable) getObjectField(param.thisObject, "mGoToSleepRunnable");
				
				registerAndLoadStatus();
			}
		});
		
		Class<?> MusicWidget = XposedHelpers.findClass("com.android.internal.policy.impl.sviewcover.SViewCoverWidget$MusicWidet",
				lpparam.classLoader);
		Class<?> ClockWidget = XposedHelpers.findClass("com.android.internal.policy.impl.sviewcover.SViewCoverWidget$Clock",
				lpparam.classLoader);
		
		loadSettings();
		
		// The view is inflated each time it shows. We need to restore state each
		// time that happens.
		findAndHookMethod(MusicWidget, "onFinishInflate", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				mMusicWidgetObject = param.thisObject;
				mTrackTitle = (TextView) getObjectField(param.thisObject, "mTrackTitle");
				mTrackTitle.setOnTouchListener(gestureListener);
				mAlbumArtWithImage = (ImageView) getObjectField(param.thisObject, "mAlbumArtWithImage");
				
				updateRemoteFieldsFromLocalFields();
				
				restoreTextAfterTickerOrInflate();
			}
		});
		
		// Do the same with the clock view.
		findAndHookMethod(ClockWidget, "onFinishInflate", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				mClockView = (LinearLayout) getObjectField(param.thisObject, "mClockView");
				
				// In case the user changed the colours* of the S-View screen somehow,
				// load the colour currently used so we can HOLO it when it's long pressed.
				TextView view = (TextView) getObjectField(mClockView, "mTime");
				mDefaultTextColor = view.getCurrentTextColor();
				
				mClockView.setOnTouchListener(gestureListener);
			}
		});
		
		// This hook disables stock updates to the S-View screen, unless the user is using 
		// the stock player as his current player
		findAndHookMethod(MusicWidget, "handleMediaUpdate", int.class, int.class, Uri.class, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) {
				if (prefs.getBoolean(Common.SETTINGS_DISABLE_SAMSUNG_METADATA_UPDATES, false) && !mCurrentMediaPlayer.equals(Common.SAMSUNG_MEDIAPLAYER))
					param.setResult(false);
			}
		});
	}
	
	// One time instructions, not used at the moment.
	private void showInstructionsForLongPressIfNeeded() {
		if (!mShouldShowLongPressInstructions) 
			return;
		
		mShouldShowLongPressInstructions = false;
		
		showTickerText("Release your finger when a view is blue to toggle playback");
	}
	
	Runnable restoreTextRunnable = new Runnable() {
		@Override
		public void run() {
			restoreTextAfterTickerOrInflate();
			mBlockOtherTextChanges = false;
		}
	};
	
	private void showTickerText(String text) {
		setTrackTitleText(text);
		scrollText();
		mBlockOtherTextChanges = true;
		mHandler.postDelayed(restoreTextRunnable, 10000);
	}

	// Sets the colour for the currently pressed view
	protected void setColorForView(View v, int color) {
		if (color == 0)
			color = mContext.getResources().getColor(android.R.color.white);
		
		if (v.getClass().getName() == TextView.class.getName()) {
			((TextView)v).setTextColor(color);
		} else if (v.getClass().getName() == mClockView.getClass().getName()) {
			for (String viewName : Common.CLOCK_VIEW_SUBVIEW_NAMES) {
				TextView view = (TextView) getObjectField(v, viewName);
				view.setTextColor(color);
			}
		}
	}

	private void loadSettings() {
		mEnableLongPressToToggle = prefs.getBoolean(Common.SETTINGS_LONGPRESS_KEY, false);
		mLongPressTimeout = Integer.parseInt(prefs.getString(Common.SETTINGS_LONGPRESS_TIMEOUT_KEY,
				String.valueOf(Common.DEFAULT_LONG_PRESS_TIME_MS)));
		
		mCurrentMediaPlayer = prefs.getString(Common.SETTINGS_MEDIAPLAYER_KEY, Common.POWERAMP_MEDIAPLAYER);
		mSwipeTracksOnlyWhilePlaying = prefs.getBoolean(Common.SETTINGS_SWIPE_ONLY_WHEN_PLAYING_KEY, false);
		
		//mShouldShowLongPressInstructions = !prefs.getBoolean(Common.SETTINGS_DID_WE_SHOW_LONG_PRESS_INSTRUCTIONS, false);
	}

	// Extend the screen-off timeout whenever the user does useful interactions
	// with the S-View screen, if the touches are useless (for example, in a user's
	// pocket), we simply ignore them.
	
	// This is done in two ways, once by calling a method in Android itself, this is
	// needed when the screen is locked, and another time in S-View's internal classes
	// to reset the stock 8000ms timeout.
	protected void extendTimeout() {
		if (mKeyguardViewMediator != null)
			XposedHelpers.callMethod(mKeyguardViewMediator, "userActivity");
		
		if (mHandler != null && mGoToSleepRunnable != null) {	
			try {
				Method removeCallbacksMethod = mHandler.getClass().getMethod("removeCallbacks", Runnable.class);
				removeCallbacksMethod.invoke(mHandler, mGoToSleepRunnable);
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			try {
				Method postDelayedMethod = mHandler.getClass().getMethod("postDelayed", Runnable.class, long.class);
				postDelayedMethod.invoke(mHandler, mGoToSleepRunnable, (long)8000);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private String getTextToSet(String title, String artist) {
		String localTitle = title;
		String localArtist = artist;
		if (title.isEmpty())
			localTitle = "Unknown title";
		if (artist.isEmpty())
			localArtist = "Unknown artist";
		
		if (title.isEmpty() && artist.isEmpty())
			return "";
		
		return localTitle + " - " + localArtist;
	}
	
	// In the future we might add a "ticker" like thingy that shows text only once
	// then restore the metadata
	private void restoreTextAfterTickerOrInflate() {
		if (mCurrentMediaPlayer.equals(Common.SAMSUNG_MEDIAPLAYER))
			return;
		
		setTrackTitleText(getTextToSet(mTrackTitleString, mArtistNameString));
		if (mAlbumArt != null) {
			mAlbumArtWithImage.setImageBitmap(mAlbumArt);
		}
		
		if (mIsPlaying) {
			setVisibilityOfMusicWidgets(View.VISIBLE);
		} else {
			setVisibilityOfMusicWidgets(View.GONE);
		}
	}
	
	// Sets the TextView's text, this is a helper method that shows
	// the TextView when there's actual text.
	// This text has to be restored somehow as the S-View class forgets
	// its current values when it's reinflated.
	private void setTrackTitleText(String text) {
		if (mBlockOtherTextChanges)
			return;
		
		if (mTrackTitle != null) {
			mTrackTitle.setText(text);
			if (!text.isEmpty()) {
				setVisibilityOfMusicWidgets(View.VISIBLE);
			} else {
				setVisibilityOfMusicWidgets(View.GONE);
			}
		}
	}
	
	// Sets the album art onto the S-View ImageView, and stores the bitmap
	// internally so it's restored when the View is reinflated.
	private void setAlbumArt(Bitmap bitmap) {
		mAlbumArt = bitmap;
		
		if (mAlbumArtWithImage != null)
			mAlbumArtWithImage.setImageBitmap(bitmap);
	}
	
	// Sets the internal fields in the S-View classes to the values we have
	// in our current class.
	private void updateRemoteFieldsFromLocalFields() {
		if (mCurrentMediaPlayer.equals(Common.SAMSUNG_MEDIAPLAYER))
			return;
		
		setTrackTitleText(getTextToSet(mTrackTitleString, mArtistNameString));
		setAlbumArt(mAlbumArt);
		
		if (mMusicWidgetObject != null) {
			XposedHelpers.setObjectField(mMusicWidgetObject, "currentTitle", mTrackTitleString);
			XposedHelpers.setObjectField(mMusicWidgetObject, "currentArtist", mArtistNameString);
			XposedHelpers.setObjectField(mMusicWidgetObject, "mAlbumArtBitmap", mAlbumArt);
			XposedHelpers.setBooleanField(mMusicWidgetObject, "mIsPlaying", mIsPlaying);
		}
	}
	
	// Register some BroadcastReceivers that will get metadata and updated settings.
	private void registerAndLoadStatus() {
		if (mContext == null)
			return;
		
		mContext.registerReceiver(mSettingsUpdatedReceiver, new IntentFilter(Common.SETTINGS_UPDATED_INTENT));
		
		mAAIntent = mContext.registerReceiver(mAAReceiver, new IntentFilter(PowerAMPiAPI.ACTION_AA_CHANGED));
		mTrackIntent = mContext.registerReceiver(mTrackReceiver, new IntentFilter(PowerAMPiAPI.ACTION_TRACK_CHANGED));
		mStatusIntent = mContext.registerReceiver(mStatusReceiver, new IntentFilter(PowerAMPiAPI.ACTION_STATUS_CHANGED));

		IntentFilter iF = new IntentFilter();
		iF.addAction("com.android.music.metachanged");
		iF.addAction("com.htc.music.metachanged");
		iF.addAction("fm.last.android.metachanged");
		iF.addAction("com.sec.android.app.music.metachanged");
		iF.addAction("com.nullsoft.winamp.metachanged");
		iF.addAction("com.amazon.mp3.metachanged");     
		iF.addAction("com.miui.player.metachanged");        
		iF.addAction("com.real.IMP.metachanged");
		iF.addAction("com.sonyericsson.music.metachanged");
		iF.addAction("com.rdio.android.metachanged");
		iF.addAction("com.samsung.sec.android.MusicPlayer.metachanged");
		iF.addAction("com.andrew.apollo.metachanged");
		iF.addAction("com.android.music.playstatechanged");
		
		mContext.registerReceiver(metadataChangedReceiver, iF);
	}
	
	// Although Poweramp uses one of the intents registered to this receiver,
	// we simply ignore anything sent by it so as not to confuse the user.
	private BroadcastReceiver metadataChangedReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent == null)
				return;
			
			if (mCurrentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER)
					|| intent.hasExtra("com.maxmpz.audioplayer.source")
					|| mCurrentMediaPlayer.equals(Common.SAMSUNG_MEDIAPLAYER))
				return;
			
			if (intent.getAction().equals("com.android.music.playstatechanged")) {
				mIsPlaying = intent.getBooleanExtra("playstate", false);
			} else {
				String title = "Unknown title";
				String artist = "Unknown artist";
				
				if (intent.hasExtra("track"))
					title = intent.getStringExtra("track");
				
				if (intent.hasExtra("artist"))
					artist = intent.getStringExtra("artist");
				
				setTrackMetadata(title, artist);
				// Attempt to load album art from either Google Play Music or MediaStore.
				Bitmap bitmap = getAlbumart(context, intent.getLongExtra("albumId", -1), Common.GOOGLE_PLAY_CONTENT_PROVIDER_URI);
				if (bitmap == null)
					bitmap = getAlbumart(context, intent.getLongExtra("albumId", -1), Common.MEDIA_STORE_CONTENT_PROVIDER_URI);
				
				if (bitmap == null)
					bitmap = BitmapFactory.decodeResource(modRes, R.drawable.appce_ic_music);
				
				setAlbumArt(bitmap);
			}

			updateRemoteFieldsFromLocalFields();
		}
	};
	
	// Reloads settings, the Intent is sent by SettingsActivity.
	private BroadcastReceiver mSettingsUpdatedReceiver = new BroadcastReceiver() {		
		@Override
		public void onReceive(Context context, Intent intent) {
			XposedBridge.log(Common.PACKAGE_NAME + ": " + "Settings changed, reloading...");
			prefs.reload();
			
			loadSettings();
		}
	};
	
	// PowerAMP broadcast receivers, code adapted from PowerAMP API samples.
	private BroadcastReceiver mTrackReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mTrackIntent = intent;
			mCurrentTrack = null;
			if (mTrackIntent != null) {
				mCurrentTrack = mTrackIntent.getBundleExtra(PowerAMPiAPI.TRACK);
				if (!mCurrentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER))
					return;
				
				String title, artist;
				title = artist = null;
				if (mTrackIntent != null) {
					title = mCurrentTrack.getString(PowerAMPiAPI.Track.TITLE);
					artist = mCurrentTrack.getString(PowerAMPiAPI.Track.ARTIST);
				}
				
				setTrackMetadata(title, artist);
			}
		}
	};
	
	private BroadcastReceiver mAAReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mAAIntent = intent;
			if (!mCurrentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER))
				return;
			
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
				setAlbumArt(BitmapFactory.decodeResource(modRes, R.drawable.appce_ic_music));
			}
			 
			 updateRemoteFieldsFromLocalFields();
		}
	};
	
	private BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mStatusIntent = intent;
			if (!mCurrentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER))
				return;
			
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
				
				if (paused) {
					setVisibilityOfMusicWidgets(View.GONE);
					
				} else {
					setVisibilityOfMusicWidgets(View.VISIBLE);
				}
				
				mIsPlaying = !paused;
			}
			
			updateRemoteFieldsFromLocalFields();	
		}
	};
	
	// We use this to scroll the text, aka marquee effect.
	Runnable scrollTextRunnable = new Runnable() {
		@Override
		public void run() {
			if (mTrackTitle != null)
				mTrackTitle.setSelected(true);
		}
	};
	
	private void scrollText() {
		if (mHandler != null)
			mHandler.postDelayed(scrollTextRunnable, 20);
		else
			mTrackTitle.setSelected(true);
	}
	
	// Go over the two widgets and set their visibility, helper method.
	private void setVisibilityOfMusicWidgets(int visibility) {
		if (mTrackTitle != null) {
			mTrackTitle.setVisibility(visibility);
			scrollText();
		}
		
		if (mAlbumArtWithImage != null)
			mAlbumArtWithImage.setVisibility(visibility);
	}

	// Sets and saves metadata used to populate view and restore it when
	// it reinflates.
	private void setTrackMetadata(String title, String artist) {
		mTrackTitleString = title;
		mArtistNameString = artist;
		
		setTrackTitleText(getTextToSet(mTrackTitleString, mArtistNameString));
		updateRemoteFieldsFromLocalFields();
	}
	
	// Helper method to advance playing track.
	private void nextTrack() {
		if (mContext == null)
			return;
		
		if (mCurrentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER)) {
			Intent powerampActionIntent = new Intent(PowerAMPiAPI.ACTION_API_COMMAND);
			powerampActionIntent.putExtra(PowerAMPiAPI.COMMAND, PowerAMPiAPI.Commands.NEXT);
			startServiceAsUser(powerampActionIntent, mCurrentUserHandle);
		} else if (mCurrentMediaPlayer.equals(Common.GOOGLEPLAY_MEDIAPLAYER)) {
			sendMusicServiceCommand(MusicServiceCommands.NEXT);
		} else if (mCurrentMediaPlayer.equals(Common.EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
			sendMediaButton(KeyEvent.KEYCODE_MEDIA_NEXT);
		} else if (mCurrentMediaPlayer.equals(Common.SAMSUNG_MEDIAPLAYER)) {
			sendMusicServiceCommand(Common.SAMSUNG_MEDIAPLAYER, MusicServiceCommands.NEXT);
		}
	}
	
	private void previousTrack() {
		if (mContext == null)
			return;
		
		if (mCurrentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER)) {
			Intent powerampActionIntent = new Intent(PowerAMPiAPI.ACTION_API_COMMAND);
			powerampActionIntent.putExtra(PowerAMPiAPI.COMMAND, PowerAMPiAPI.Commands.PREVIOUS);
			startServiceAsUser(powerampActionIntent, mCurrentUserHandle);
		} else if (mCurrentMediaPlayer.equals(Common.GOOGLEPLAY_MEDIAPLAYER)) {
			sendMusicServiceCommand(MusicServiceCommands.PREVIOUS);
		} else if (mCurrentMediaPlayer.equals(Common.EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
			sendMediaButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
		} else if (mCurrentMediaPlayer.equals(Common.SAMSUNG_MEDIAPLAYER)) {
			sendMusicServiceCommand(Common.SAMSUNG_MEDIAPLAYER, MusicServiceCommands.PREVIOUS);
		}
	}
	
	private void togglePlayback() {
		if (mContext == null || !mEnableLongPressToToggle)
			return;
		
		if (mCurrentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER)) {
			Intent powerampActionIntent = new Intent(PowerAMPiAPI.ACTION_API_COMMAND);
			powerampActionIntent.putExtra(PowerAMPiAPI.COMMAND, PowerAMPiAPI.Commands.TOGGLE_PLAY_PAUSE);
			startServiceAsUser(powerampActionIntent, mCurrentUserHandle);
		} else if (mCurrentMediaPlayer.equals(Common.GOOGLEPLAY_MEDIAPLAYER)) {
			sendMusicServiceCommand(MusicServiceCommands.PLAY_PAUSE);
		} else if (mCurrentMediaPlayer.equals(Common.EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
			sendMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
		} else if (mCurrentMediaPlayer.equals(Common.SAMSUNG_MEDIAPLAYER)) {
			sendMusicServiceCommand(Common.SAMSUNG_MEDIAPLAYER, MusicServiceCommands.PLAY_PAUSE);
		}
	}
	
	// TODO: Deprecate this.
	// Helper method to send metadata for Google Play Music.
	private void sendMusicServiceCommand(MusicServiceCommands command) {
		sendMusicServiceCommand(Common.GOOGLEPLAY_MEDIAPLAYER, command);
	}
	
	// Send musicservicecommand for a specific package name.
	private void sendMusicServiceCommand(String packageName, MusicServiceCommands command) {
		Intent musicIntent = new Intent();
		String action = packageName + ".musicservicecommand";

		switch (command) {
		case PLAY_PAUSE:
			action = action + ".togglepause";
			break;
		case NEXT:
			musicIntent.putExtra("command", "next");
			break;
		case PREVIOUS:
			musicIntent.putExtra("command", "previous");
			break;
		default:
			break;
		}
		
		// Typical Samsung not adhering to standards.
		if (packageName.equals(Common.SAMSUNG_MEDIAPLAYER)) {
			if (musicIntent.hasExtra("command")) {
				action += "." + musicIntent.getStringExtra("command");
			}
		}		
		
		musicIntent.setAction(action);
		sendBroadcastAsUser(musicIntent, mCurrentUserHandle);
	}
	
	// Send generic simulated media button press.
	private void sendMediaButton(int keyCode) {
		KeyEvent keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
		Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
		intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
		
		if (mClientIntent != null) {
			try {
			    mClientIntent.send(mContext, 0, intent);
			} catch (CanceledException e) {
				XposedBridge.log("Error sending intent for media button down");
			    e.printStackTrace();
			}
		} else {
			sendOrderedBroadcastAsUser(intent, mCurrentUserHandle);
		}
		
		keyEvent = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
		intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
		intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
		
		if (mClientIntent != null) {
			try {
			    mClientIntent.send(mContext, 0, intent);
			} catch (CanceledException e) {
				XposedBridge.log("Error sending intent for media button up");
				e.printStackTrace();
			}
		} else {
			sendOrderedBroadcastAsUser(intent, mCurrentUserHandle);
		}
	}	
	
	// Helpers methods to use Android private API.
	private ComponentName startServiceAsUser(Intent intent, UserHandle user) {
		if (mContext == null)
			return null;
		
		if (user == null) {
			// Oh well, we tried
			return mContext.startService(intent);
		}
		
		return (ComponentName) XposedHelpers.callMethod(mContext, "startServiceAsUser", intent, user);
	}
	
	private void sendOrderedBroadcastAsUser(Intent intent, UserHandle user) {
		if (mContext == null)
			return;
		
		if (user == null) {
			// Oh well, we tried again
			mContext.sendOrderedBroadcast(intent, null);
			return;
		}
		
		mContext.sendOrderedBroadcastAsUser(intent, user, null, null, null, 0, null, null);
	}
	
	private void sendBroadcastAsUser(Intent intent, UserHandle user) {
		if (mContext == null)
			return;
		
		if (user == null) {
			// Oh well, we tried again and again
			mContext.sendOrderedBroadcast(intent, null);
			return;
		}
		
		mContext.sendBroadcastAsUser(intent, user);
	}

	// Get album art from a content provider
	public Bitmap getAlbumart(Context context, Long album_id, String contentProviderUri) {
		Bitmap bm = null;
		if (album_id == -1)
			return bm;
        
		try {
			final Uri sArtworkUri = Uri.parse(contentProviderUri);
			Uri uri = ContentUris.withAppendedId(sArtworkUri, album_id);
			ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");

			if (pfd != null) {
				FileDescriptor fd = pfd.getFileDescriptor();
				bm = BitmapFactory.decodeFileDescriptor(fd);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return bm;
	}
}

