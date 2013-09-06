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
import android.speech.tts.TextToSpeech;
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
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedHelpers.ClassNotFoundError;
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
	private UserHandle mCurrentUserHandle = (UserHandle) getStaticObjectField(UserHandle.class, "CURRENT");
	
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
	private boolean mUserDebugMode = false;
	private int mLongPressTimeout = Common.DEFAULT_LONG_PRESS_TIME_MS;
	
	// One time or internal use
	private boolean mShouldShowLongPressInstructions = false;
	private boolean mBlockOtherTextChanges = false;
	
	private Object mSviewCoverManagerInstance = null;
	
	// Debug stuff
	private TextToSpeech mTTS = null;
	
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
		// Spotify has no API, and the source code is obfuscated, the only thing
		// we can hook onto reliably is the widget.
		if (lpparam.packageName.equals(Common.SPOTIFY_PACKAGE_NAME)) {
			try {
				Class<?> SpotifyWidget = XposedHelpers.findClass(Common.SPOTIFY_PACKAGE_NAME + ".widget.SpotifyWidget", lpparam.classLoader);
				findAndHookMethod(SpotifyWidget, "onReceive", Context.class, Intent.class, new XC_MethodHook() {
					@Override
					protected void beforeHookedMethod(MethodHookParam param)
							throws Throwable {
						
						final Context context = (Context) param.args[0];
						
						Intent intent = (Intent) param.args[1];
						if (intent != null) {
							if ("android.appwidget.action.APPWIDGET_UPDATE".equals(intent.getAction())) {
								Bundle extras = intent.getExtras();
								if (extras != null) {
									if (!intent.hasExtra("track_uri"))
										return;
									
									String trackName = "";
									String artistName = "";
									
									if (extras.containsKey("track_name"))
										trackName = extras.getString("track_name", "");
									
									if (extras.containsKey("artist_name"))
										artistName = extras.getString("artist_name");
									
									boolean paused = intent.getBooleanExtra("paused", true);
									Bitmap cover = ((Bitmap) intent.getParcelableExtra("cover"));
									
									if (cover == null)
										cover = BitmapFactory.decodeResource(modRes, R.drawable.appce_ic_music);
									
									if (mUserDebugMode) {
										XposedBridge.log("Spotify metadata update start");
										XposedBridge.log("Track title: " + trackName);
										XposedBridge.log("Artist name: " + artistName);
										XposedBridge.log("Playing?: " + String.valueOf(!paused));
										XposedBridge.log("Spotify metadata update end");
									}
										
									Intent broadcastIntent = new Intent(Common.SPOTIFY_METACHANGED_INTENT);
									broadcastIntent.putExtra("track", trackName);
									broadcastIntent.putExtra("artist", artistName);
									broadcastIntent.putExtra("albumArtBitmap", cover);
									broadcastIntent.putExtra("isPlaying", !paused);
									context.sendBroadcast(broadcastIntent);
								}
							}
						}
					}
				});
			} catch (ClassNotFoundError e) {
				XposedBridge.log("Not hoooking Spotify, some update probably broke us.");
			}
		}
		
		if (lpparam.packageName.equals(Common.PACKAGE_NAME)) {
			findAndHookMethod(SettingsActivity.class.getName(), lpparam.classLoader,
					"isModuleActivated", XC_MethodReplacement.returnConstant(true));
		}
		
		if (!lpparam.packageName.equals("android"))
			return;
		
		if (prefs != null)
			prefs.reload();
		
		modRes = XModuleResources.createInstance(MODULE_PATH, null);
		
		// We need this to use send*AsUser
		if (mCurrentUserHandle == null)
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
							speakTextIfNeeded("Long pressed, toggling playback");
							
							extendTimeout();
							togglePlayback();
						}

						return true;
				 	}
				 }
				 return false;
			}

			private void onSwipeRight() {
				if (mSwipeTracksOnlyWhilePlaying && !isPlaying()) {
					speakTextIfNeeded("Swiped ignored because no music is playing");
					return;
				}
				
				speakTextIfNeeded("Swiped right, previous track");
				
				previousTrack();
				extendTimeout();
			}
			
			private void onSwipeLeft() {
				if (mSwipeTracksOnlyWhilePlaying && !isPlaying()) {
					speakTextIfNeeded("Swiped ignored because no music is playing");
					return;
				}
				
				speakTextIfNeeded("Swiped left, next track");
				
				nextTrack();
				extendTimeout();
			}
			private void onSwipeUp() {
				speakTextIfNeeded("Swipe up not yet implemented");
			}
			private void onSwipeDown() {
				speakTextIfNeeded("Swipe down not yet implemented");
			}
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
				
				mSviewCoverManagerInstance = param.thisObject;
				
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
		
		if (!Common.PUBLIC_RELEASE) {
			findAndHookMethod(SViewCoverManager, "updateCoverState", boolean.class, ComponentName.class, new XC_MethodReplacement() {
				
				@Override
				protected Object replaceHookedMethod(MethodHookParam param)
						throws Throwable {
					XposedBridge.log("updateCoverState: " + String.valueOf((Boolean) param.args[0]));
					XposedHelpers.setBooleanField(param.thisObject, "mIsCoverOpen", (Boolean) param.args[0]);
					boolean mIsCoverOpen = XposedHelpers.getBooleanField(param.thisObject, "mIsCoverOpen");
					boolean mSuppressCoverUI = XposedHelpers.getBooleanField(param.thisObject, "mSuppressCoverUI");
					ComponentName paramComponentName = (ComponentName) param.args[1];
					//Slog.d("SViewCoverManager", "updateCoverState( mIsCoverOpen is " + this.mIsCoverOpen + ")");
					if (mIsCoverOpen) {
						if (mHandler != null)
							mHandler.sendEmptyMessage(0);
						return null;
					}
					
					if ((mSuppressCoverUI) && (paramComponentName != null)) {
						String str = paramComponentName.getPackageName();
						if (str != null)
							XposedBridge.log(str);
						if ((str != null)
								&& (!"com.sec.android.app.clockpackage".equals(str))
								&& (!"com.android.phone".equals(str))
								&& (!"com.sec.imsphone".equals(str))
								&& (!"com.android.calendar".equals(str))
								&& (!"com.mohammadag.azlyricsviewer".equals(str))) {
							XposedHelpers.setBooleanField(param.thisObject, "mSuppressCoverUI", false);
					  }
					}
					if (mHandler != null)
						mHandler.sendEmptyMessage(2);
					
					return null;
				}
			});
		}
	}
	
	protected boolean isPlaying() {
		if (Common.SAMSUNG_MEDIAPLAYER.equals(mCurrentMediaPlayer)) {
			boolean isPlaying = false;
			// The default media player hides the music widget when it's paused,
			// fall back to that
			if (mTrackTitle != null) {
				int visibility = mTrackTitle.getVisibility();
				isPlaying = (visibility == View.VISIBLE);
			}
			
			return isPlaying;
		}
		return mIsPlaying;
	}

	private void initializeTTS(Context context) {
		if (context == null)
			return;
		
		mTTS = new TextToSpeech(context, null);
	}
	
	private void speakTextIfNeeded(String text) {
		if (mUserDebugMode)
			speakText(text);
	}
	
	private void speakText(String text) {
		if (mTTS == null)
			initializeTTS(mContext);
		
		if (mTTS == null)
			return;
		
		mTTS.speak(text, TextToSpeech.QUEUE_FLUSH, null);
	}
	
	private void showSViewScreen(boolean show) {
		if (Common.PUBLIC_RELEASE)
			return;
		
		XposedBridge.log("showSViewScreen");
		if (mHandler == null)
			return;
		
		if (show) {
			XposedHelpers.callMethod(mSviewCoverManagerInstance, "updateSupressCover", false);
			mHandler.sendEmptyMessage(2);
		} else {
			XposedHelpers.callMethod(mSviewCoverManagerInstance, "updateSupressCover", true);
			mHandler.sendEmptyMessage(0);
		}
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
		mEnableLongPressToToggle = prefs.getBoolean(Common.SETTINGS_LONGPRESS_KEY, true);
		mLongPressTimeout = Integer.parseInt(prefs.getString(Common.SETTINGS_LONGPRESS_TIMEOUT_KEY,
				String.valueOf(Common.DEFAULT_LONG_PRESS_TIME_MS)));
		
		mCurrentMediaPlayer = prefs.getString(Common.SETTINGS_MEDIAPLAYER_KEY, Common.DEFAULT_MEDIAPLAYER);
		mSwipeTracksOnlyWhilePlaying = prefs.getBoolean(Common.SETTINGS_SWIPE_ONLY_WHEN_PLAYING_KEY, false);
		mUserDebugMode = prefs.getBoolean(Common.SETTINGS_USERDEBUG_KEY, false);
		
		if (mUserDebugMode)
			initializeTTS(mContext);
		
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
				postDelayedMethod.invoke(mHandler, mGoToSleepRunnable, Common.DEFAULT_SVIEW_SCREEN_TIMEOUT);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private String getTextToSet(String title, String artist) {
		String localTitle = title;
		String localArtist = artist;
		if (title == null || title.isEmpty())
			localTitle = "Unknown title";
		if (artist == null || artist.isEmpty())
			localArtist = "Unknown artist";
		
		if ((title == null || title.isEmpty()) && (artist == null || artist.isEmpty()))
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
		if (Common.SAMSUNG_MEDIAPLAYER.equals(mCurrentMediaPlayer))
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
		iF.addAction(Common.SPOTIFY_METACHANGED_INTENT);
		
		mContext.registerReceiver(metadataChangedReceiver, iF);
		
		if (mUserDebugMode)
			initializeTTS(mContext);
		
		if (Common.PUBLIC_RELEASE)
			return;
		IntentFilter iF2 = new IntentFilter();
		iF.addAction("com.mohammadag.sviewpowerampmetadata.SHOW_SVIEW_SCREEN");
		iF.addAction("com.mohammadag.sviewpowerampmetadata.HIDE_SVIEW_SCREEN");
		
		mContext.registerReceiver(new BroadcastReceiver() {

			@Override
			public void onReceive(Context arg0, Intent intent) {
				if ("com.mohammadag.sviewpowerampmetadata.SHOW_SVIEW_SCREEN".equals(intent.getAction())) {
					showSViewScreen(true);
				} else if ("com.mohammadag.sviewpowerampmetadata.HIDE_SVIEW_SCREEN".equals(intent.getAction())) {
					showSViewScreen(false);
				}
			}
			
		}, iF2);
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
				if (Common.SPOTIFY_METACHANGED_INTENT.equals(intent.getAction()) && mCurrentMediaPlayer.equals(Common.SPOTIFY_MEDIAPLAYER)) {
					mIsPlaying = intent.getBooleanExtra("isPlaying", false);
					Bitmap cover = intent.getParcelableExtra("albumArtBitmap");
					setAlbumArt(cover);
					updateRemoteFieldsFromLocalFields();
					return;
				}
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
		} else if (mCurrentMediaPlayer.equals(Common.SPOTIFY_MEDIAPLAYER)) {
			sendBroadcastAsUser(new Intent("com.spotify.mobile.android.ui.widget.NEXT"), mCurrentUserHandle);
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
		} else if (mCurrentMediaPlayer.equals(Common.SPOTIFY_MEDIAPLAYER)) {
			sendBroadcastAsUser(new Intent("com.spotify.mobile.android.ui.widget.PREVIOUS"), mCurrentUserHandle);
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
		} else if (mCurrentMediaPlayer.equals(Common.SPOTIFY_MEDIAPLAYER)) {
			sendBroadcastAsUser(new Intent("com.spotify.mobile.android.ui.widget.PLAY"), mCurrentUserHandle);
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

