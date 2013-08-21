package com.mohammadag.sviewpowerampmetadata;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.IRemoteControlDisplay;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.View.OnTouchListener;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.maxmpz.audioplayer.player.PowerAMPiAPI;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class SViewPowerampMetadata implements IXposedHookLoadPackage, IXposedHookZygoteInit {
	
	static final int MIN_DISTANCE = 100;
	private static final String PACKAGE_NAME = "com.mohammadag.sviewpowerampmetadata";
	
	// This intent is sent by our settings activity, this way we don't use prefs.reload()
	// in every method.
	public static final String SETTINGS_UPDATED_INTENT = "com.mohammadag.sviewpowerampmetadata.SETTINGS_UPDATED";
	
	// Gesture related fields
	private PointerCoords mDownPos = new PointerCoords();
	private PointerCoords mUpPos = new PointerCoords();
	private OnLongClickListener mLongClickListener = null;
	
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
	private boolean isPlaying = false;
	private String mTrackTitleString = "";
	private String mArtistNameString = "";
	private Bitmap mAlbumArt = null;
	
	// Handler from S View classes
	private Handler mHandler = null;
	
	// Our own handler to handle metadata changes
	public Handler myHandler = null;
	
	// Avoid system warnings
	private UserHandle mCurrentUserHandle = null;
	
	// Settings keys
	private static final String SETTINGS_LONGPRESS_KEY = "longpress_to_toggle_playback";
	private static final String SETTINGS_MEDIAPLAYER_KEY = "current_media_player";
	
	// Mediaplayer values
	private static final String POWERAMP_MEDIAPLAYER = "com.maxmpz.audioplayer";
	private static final String GOOGLEPLAY_MEDIAPLAYER = "com.android.music";
	private static final String EMULATE_MEDIA_KEYS_MEDIAPLAYER = "emulate_media_buttons";
	
	// Lockscreen specific fields
	private PendingIntent mClientIntent;
	
	private static XSharedPreferences prefs;
	
	private enum MusicServiceCommands {
		PLAY_PAUSE,
		NEXT,
		PREVIOUS
	}

	public void initZygote(StartupParam startupParam) {
		prefs = new XSharedPreferences(PACKAGE_NAME);
	}
	
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("android"))
			return;
		
		doRemoteControlHooks(lpparam);
		
		if (prefs != null)
			prefs.reload();
		
		mCurrentUserHandle = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "CURRENT");
		
		final OnTouchListener gestureListener = new OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
		        switch(event.getAction()) {
	            case MotionEvent.ACTION_DOWN: {
	                event.getPointerCoords(0, mDownPos);
	                return true;
	            }
	            
	            case MotionEvent.ACTION_UP: {
	                event.getPointerCoords(0, mUpPos);
	 
	                float dx = mDownPos.x - mUpPos.x;
	                if (Math.abs(dx) > MIN_DISTANCE) {
	                    if (dx > 0)
	                        onSwipeLeft();
	                    else
	                        onSwipeRight();
	                    return true;
	                }
	                
	            	float dy = mDownPos.y - mUpPos.y;

	            	// Check for vertical wipe
	            	if (Math.abs(dy) > MIN_DISTANCE) {
	            		if (dy > 0)
	            			onSwipeUp();
	            		else
	            			onSwipeDown();
	            		return true;
	            	}
	            }
	        }
	        return false;
			}

			private void onSwipeRight() {
				previousTrack();
				extendTimeout();
			}

			private void onSwipeLeft() {
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
		
		XposedBridge.hookAllConstructors(keyguardViewMediator, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				mKeyguardViewMediator = param.thisObject;
			}
		});
		
		XposedBridge.hookAllConstructors(SViewCoverManager, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				if (mContext == null)
					mContext = (Context) getObjectField(param.thisObject, "mContext");
				if (mHandler == null) {
					mHandler = (Handler) getObjectField(param.thisObject, "mHandler");
					//initializeRemoteControlDisplay(mHandler.getLooper());
				}
				if (mGoToSleepRunnable == null)
					mGoToSleepRunnable = (Runnable) getObjectField(param.thisObject, "mGoToSleepRunnable");
				
				registerAndLoadStatus();
			}
		});
		
		Class<?> MusicWidget = XposedHelpers.findClass("com.android.internal.policy.impl.sviewcover.SViewCoverWidget$MusicWidet",
				lpparam.classLoader);
		Class<?> ClockWidget = XposedHelpers.findClass("com.android.internal.policy.impl.sviewcover.SViewCoverWidget$Clock",
				lpparam.classLoader);
		
		mLongClickListener = new OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				extendTimeout();
				togglePlayback();
				return true;
			}
		};
		
		XposedHelpers.findAndHookMethod(MusicWidget, "onFinishInflate", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				XposedBridge.log("MusicWidget: onFinishInflate");
				mMusicWidgetObject = param.thisObject;
				mTrackTitle = (TextView) getObjectField(param.thisObject, "mTrackTitle");
				mTrackTitle.setOnTouchListener(gestureListener);
				mAlbumArtWithImage = (ImageView) getObjectField(param.thisObject, "mAlbumArtWithImage");
				
				updateRemoteFieldsFromLocalFields();
				
				setTrackTitleText(getTextToSet(mTrackTitleString, mArtistNameString));
				if (mAlbumArt != null) {
					mAlbumArtWithImage.setImageBitmap(mAlbumArt);
				}
				
				if (isPlaying) {
					setVisibilityOfMusicWidgets(View.VISIBLE);
				} else {
					setVisibilityOfMusicWidgets(View.GONE);
				}
			}
		});
		
		XposedHelpers.findAndHookMethod(ClockWidget, "onFinishInflate", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				XposedBridge.log("ClockWidget: onFinishInflate");
				mClockView = (LinearLayout) getObjectField(param.thisObject, "mClockView");
				mClockView.setOnTouchListener(gestureListener);
				//initializeRemoteControlDisplay(mHandler.getLooper());
			}
		});
		
		loadSettings();
	}
	
	private void doRemoteControlHooks(LoadPackageParam lpparam) {
		XC_MethodHook setPlaybackStateHook = new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) {
				int state = (Integer) param.args[0];
				updatePlayPauseState(state);
			}
		};
		
		for (Method method: RemoteControlClient.class.getMethods()) {
			if (method.getName().equals("setPlaybackState")) {
				XposedBridge.hookMethod(method, setPlaybackStateHook);
			}
		}
		
		findAndHookMethod(RemoteControlClient.MetadataEditor.class, "apply", new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) {
				Bundle mEditorMetadata = (Bundle) getObjectField(param.thisObject, "mEditorMetadata");
				boolean isArtworkExist = XposedHelpers.getBooleanField(param.thisObject, "mArtworkChanged");
				if (isArtworkExist) {
					Bitmap artwork = (Bitmap) getObjectField(param.thisObject, "mEditorArtwork");
					setAlbumArt(artwork);
				}
				
				updateMetadata(mEditorMetadata);
			}
		});
	}

	private void loadSettings() {
		boolean enableLongPressToToggle = prefs.getBoolean(SETTINGS_LONGPRESS_KEY,	 false);
		
		if (enableLongPressToToggle) {
			if (mTrackTitle != null)
				mTrackTitle.setOnLongClickListener(mLongClickListener);
			if (mClockView != null)
				mClockView.setOnLongClickListener(mLongClickListener);
		}
		
		if (mTrackTitle != null)
			mTrackTitle.setHapticFeedbackEnabled(enableLongPressToToggle);
		if (mClockView != null)
			mClockView.setHapticFeedbackEnabled(enableLongPressToToggle);
	}

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
	
	private void setTrackTitleText(String text) {
		if (mTrackTitle != null) {
			mTrackTitle.setText(text);
			mTrackTitle.setSelected(true);
			if (!text.isEmpty()) {
				setVisibilityOfMusicWidgets(View.VISIBLE);
			} else {
				setVisibilityOfMusicWidgets(View.GONE);
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
		if (mContext == null)
			return;
		
		mContext.registerReceiver(mSettingsUpdatedReceiver, new IntentFilter(SViewPowerampMetadata.SETTINGS_UPDATED_INTENT));
		
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

	    mContext.registerReceiver(metadataChangedReceiver, iF);
	}
	
	private BroadcastReceiver metadataChangedReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent == null)
				return;
			
			String currentMediaPlayer = prefs.getString(SETTINGS_MEDIAPLAYER_KEY, POWERAMP_MEDIAPLAYER);
			if (currentMediaPlayer.equals(POWERAMP_MEDIAPLAYER) || intent.hasExtra("com.maxmpz.audioplayer.source"))
				return;
			
			String title = "Unknown title";
			String artist = "Unknown artist";
			
			if (intent.hasExtra("track"))
				title = intent.getStringExtra("track");
			
			if (intent.hasExtra("artist"))
				artist = intent.getStringExtra("artist");
			
			setTrackMetadata(title, artist);
			
			/*
			if (intent.hasExtra("albumId")) {
				long albumId = intent.getLongExtra("albumId", -1);
				Bitmap albumArt = null;
				albumArt = getAlbumartForAlbumId(context, albumId);
				if (albumArt == null)
					XposedBridge.log("Failed to get album art");
				setAlbumArt(albumArt);
			}*/
			
			updateRemoteFieldsFromLocalFields();
		}
	};
	
	private BroadcastReceiver mSettingsUpdatedReceiver = new BroadcastReceiver() {		
		@Override
		public void onReceive(Context context, Intent intent) {
			XposedBridge.log(PACKAGE_NAME + ": " + "Settings changed, reloading...");
			prefs.reload();
			
			loadSettings();
		}
	};
	
	// Poweramp broadcast receivers
	private BroadcastReceiver mTrackReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mTrackIntent = intent;
			mCurrentTrack = null;
			if (mTrackIntent != null) {
				mCurrentTrack = mTrackIntent.getBundleExtra(PowerAMPiAPI.TRACK);
				String currentMediaPlayer = prefs.getString(SETTINGS_MEDIAPLAYER_KEY, POWERAMP_MEDIAPLAYER);
				if (!currentMediaPlayer.equals(POWERAMP_MEDIAPLAYER))
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
			String currentMediaPlayer = prefs.getString(SETTINGS_MEDIAPLAYER_KEY, POWERAMP_MEDIAPLAYER);
			if (!currentMediaPlayer.equals(POWERAMP_MEDIAPLAYER))
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
				setAlbumArt(null);
			}
			 
			 updateRemoteFieldsFromLocalFields();
		}
	};
	
	private BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mStatusIntent = intent;
			String currentMediaPlayer = prefs.getString(SETTINGS_MEDIAPLAYER_KEY, POWERAMP_MEDIAPLAYER);
			if (!currentMediaPlayer.equals(POWERAMP_MEDIAPLAYER))
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
	};
	
	private void setVisibilityOfMusicWidgets(int visibility) {
		if (mTrackTitle != null) {
			mTrackTitle.setVisibility(visibility);
			mTrackTitle.setSelected(true);
		}
		
		if (mAlbumArtWithImage != null)
			mAlbumArtWithImage.setVisibility(visibility);
	}

	private void setTrackMetadata(String title, String artist) {
		mTrackTitleString = title;
		mArtistNameString = artist;
		
		setTrackTitleText(getTextToSet(mTrackTitleString, mArtistNameString));
		updateRemoteFieldsFromLocalFields();
	}
	
	private void nextTrack() {
		if (mContext == null)
			return;
		
		String currentMediaPlayer = prefs.getString(SETTINGS_MEDIAPLAYER_KEY, POWERAMP_MEDIAPLAYER);
		
		if (currentMediaPlayer.equals(POWERAMP_MEDIAPLAYER)) {
			Intent powerampActionIntent = new Intent(PowerAMPiAPI.ACTION_API_COMMAND);
			powerampActionIntent.putExtra(PowerAMPiAPI.COMMAND, PowerAMPiAPI.Commands.NEXT);
			startServiceAsUser(powerampActionIntent, mCurrentUserHandle);
		} else if (currentMediaPlayer.equals(GOOGLEPLAY_MEDIAPLAYER)) {
			sendMusicServiceCommand(MusicServiceCommands.NEXT);
		} else if (currentMediaPlayer.equals(EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
			sendMediaButton(KeyEvent.KEYCODE_MEDIA_NEXT);
		}
	}
	
	private void previousTrack() {
		if (mContext == null)
			return;

		String currentMediaPlayer = prefs.getString(SETTINGS_MEDIAPLAYER_KEY, POWERAMP_MEDIAPLAYER);
		
		if (currentMediaPlayer.equals(POWERAMP_MEDIAPLAYER)) {
			Intent powerampActionIntent = new Intent(PowerAMPiAPI.ACTION_API_COMMAND);
			powerampActionIntent.putExtra(PowerAMPiAPI.COMMAND, PowerAMPiAPI.Commands.PREVIOUS);
			startServiceAsUser(powerampActionIntent, mCurrentUserHandle);
		} else if (currentMediaPlayer.equals(GOOGLEPLAY_MEDIAPLAYER)) {
			sendMusicServiceCommand(MusicServiceCommands.PREVIOUS);
		} else if (currentMediaPlayer.equals(EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
			sendMediaButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
		}
	}
	
	private void togglePlayback() {
		if (mContext == null)
			return;
		
		if (!prefs.getBoolean(SETTINGS_LONGPRESS_KEY, false))
			return;
		
		String currentMediaPlayer = prefs.getString(SETTINGS_MEDIAPLAYER_KEY, POWERAMP_MEDIAPLAYER);
		
		if (currentMediaPlayer.equals(POWERAMP_MEDIAPLAYER)) {
			Intent powerampActionIntent = new Intent(PowerAMPiAPI.ACTION_API_COMMAND);
			powerampActionIntent.putExtra(PowerAMPiAPI.COMMAND, PowerAMPiAPI.Commands.TOGGLE_PLAY_PAUSE);
			startServiceAsUser(powerampActionIntent, mCurrentUserHandle);
		} else if (currentMediaPlayer.equals(GOOGLEPLAY_MEDIAPLAYER)) {
			sendMusicServiceCommand(MusicServiceCommands.PLAY_PAUSE);
		} else if (currentMediaPlayer.equals(EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
			sendMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
		}
	}
	
	private void sendMusicServiceCommand(MusicServiceCommands command) {
		sendMusicServiceCommand("com.android.music", command);
	}
	
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
		
		musicIntent.setAction(action);
		sendBroadcastAsUser(musicIntent, mCurrentUserHandle);
	}
	
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
	
	public void updatePlayPauseState(int playstate) {
		XposedBridge.log("updatePlayPauseState" + " " + playstate);
		boolean playing = false;
		if (playstate == RemoteControlClient.PLAYSTATE_PLAYING) {
			playing = true;
		} else if (playstate == RemoteControlClient.PLAYSTATE_PAUSED || playstate == RemoteControlClient.PLAYSTATE_NONE
				|| playstate == RemoteControlClient.PLAYSTATE_NONE) {
			playing = false;
		}
		
		isPlaying = playing;
		if (playing)
			setVisibilityOfMusicWidgets(View.VISIBLE);
		else
			setVisibilityOfMusicWidgets(View.GONE);
		
		updateRemoteFieldsFromLocalFields();
	}

	protected void updateMetadata(Bundle data) {
		String artist = getMdString(data, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
		
		if (artist == null)
			artist = "";
		
		String title = getMdString(data, MediaMetadataRetriever.METADATA_KEY_TITLE);
		if (title == null) {
			title = "";
		}
		
		XposedBridge.log("updateMetadata" + " " + title + " " + artist);
		
		setTrackMetadata(title, artist);
		updateRemoteFieldsFromLocalFields();
	}

	private String getMdString(Bundle data, int id) {
		return data.getString(Integer.toString(id));
	}
	
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
	
	public static Bitmap getAlbumartForAlbumId(Context context, Long album_id) {

		if (context == null)
			return null;
		
		return getArtworkQuick(context, album_id, 64, 64);
		/*
		Bitmap bm = null;
		try {
			final Uri sArtworkUri = Uri.parse("content://media/external/audio/albumart");

			Uri uri = ContentUris.withAppendedId(sArtworkUri, album_id);

			ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");

			if (pfd != null) {
				FileDescriptor fd = pfd.getFileDescriptor();
				bm = BitmapFactory.decodeFileDescriptor(fd);
			}
		} catch (Exception e) {}
	    return bm;
	    */
	}
	
	private static final BitmapFactory.Options sBitmapOptionsCache = new BitmapFactory.Options();
	private static final Uri sArtworkUri = Uri.parse("content://media/external/audio/albumart");
	
    // Get album art for specified album. This method will not try to
    // fall back to getting artwork directly from the file, nor will
    // it attempt to repair the database.
    private static Bitmap getArtworkQuick(Context context, Long album_id, int w, int h) {
        // NOTE: There is in fact a 1 pixel frame in the ImageView used to
        // display this drawable. Take it into account now, so we don't have to
        // scale later.
        w -= 2;
        h -= 2;
        ContentResolver res = context.getContentResolver();
        Uri uri = ContentUris.withAppendedId(sArtworkUri, album_id);
        if (uri != null) {
            ParcelFileDescriptor fd = null;
            try {
                fd = res.openFileDescriptor(uri, "r");
                int sampleSize = 1;
                
                // Compute the closest power-of-two scale factor 
                // and pass that to sBitmapOptionsCache.inSampleSize, which will
                // result in faster decoding and better quality
                sBitmapOptionsCache.inJustDecodeBounds = true;
                BitmapFactory.decodeFileDescriptor(
                        fd.getFileDescriptor(), null, sBitmapOptionsCache);
                int nextWidth = sBitmapOptionsCache.outWidth >> 1;
                int nextHeight = sBitmapOptionsCache.outHeight >> 1;
                while (nextWidth>w && nextHeight>h) {
                    sampleSize <<= 1;
                    nextWidth >>= 1;
                    nextHeight >>= 1;
                }

                sBitmapOptionsCache.inSampleSize = sampleSize;
                sBitmapOptionsCache.inJustDecodeBounds = false;
                Bitmap b = BitmapFactory.decodeFileDescriptor(
                        fd.getFileDescriptor(), null, sBitmapOptionsCache);

                if (b != null) {
                    // finally rescale to exactly the size we need
                    if (sBitmapOptionsCache.outWidth != w || sBitmapOptionsCache.outHeight != h) {
                        Bitmap tmp = Bitmap.createScaledBitmap(b, w, h, true);
                        b.recycle();
                        b = tmp;
                    }
                }
                
                return b;
            } catch (FileNotFoundException e) {
            } finally {
                try {
                    if (fd != null)
                        fd.close();
                } catch (IOException e) {
                }
            }
        }
        return null;
    }
 }
