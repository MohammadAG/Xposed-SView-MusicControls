package com.mohammadag.sviewpowerampmetadata;

import static de.robv.android.xposed.XposedHelpers.getObjectField;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.IRemoteControlDisplay;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
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
	
	// Settings keys
	private static final String SETTINGS_LONGPRESS_KEY = "longpress_to_toggle_playback";
	private static final String SETTINGS_MEDIAPLAYER_KEY = "current_media_player";
	
	// Mediaplayer values
	private static final String POWERAMP_MEDIAPLAYER = "poweramp";
	private static final String EMULATE_MEDIA_KEYS_MEDIAPLAYER = "emulate_media_buttons";
	
	// Lockscreen specific fields
	private int mClientGeneration;
	private PendingIntent mClientIntent;
	public static final int MSG_SET_ARTWORK = 104;
	public static final int MSG_SET_GENERATION_ID = 103;
	public static final int MSG_SET_METADATA = 101;
	public static final int MSG_SET_TRANSPORT_CONTROLS = 102;
	public static final int MSG_UPDATE_STATE = 100;
	
	private static XSharedPreferences prefs;

	public void initZygote(StartupParam startupParam) {
		prefs = new XSharedPreferences(PACKAGE_NAME);
	}
	
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("android"))
			return;
		
		if (prefs != null)
			prefs.reload();
		
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
					initializeRemoteControlDisplay(mHandler.getLooper());
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
		
		final OnTouchListener gestureListener = new OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
		        switch(event.getAction()) {
	            case MotionEvent.ACTION_DOWN: {
	                event.getPointerCoords(0, mDownPos);
	                return false;
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
	            }
	        }
	        return false;
			}

			private void onSwipeRight() {
				extendTimeout();
				previousTrack();
			}

			private void onSwipeLeft() {
				extendTimeout();
				nextTrack();
			}
		};
		
		XposedHelpers.findAndHookMethod(MusicWidget, "onFinishInflate", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				mMusicWidgetObject = param.thisObject;
				
				mTrackTitle = (TextView) getObjectField(param.thisObject, "mTrackTitle");
				mTrackTitle.setOnTouchListener(gestureListener);
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
		
		XposedHelpers.findAndHookMethod(ClockWidget, "onFinishInflate", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				mClockView = (LinearLayout) getObjectField(param.thisObject, "mClockView");
				mClockView.setOnTouchListener(gestureListener);
			}
		});
		
		loadSettings();
	}
	
	private void loadSettings() {
		boolean enableLongPressToToggle = prefs.getBoolean(SETTINGS_LONGPRESS_KEY,	 false);
		
		if (mClockView != null)
			mClockView.setHapticFeedbackEnabled(enableLongPressToToggle);
		
		if (mTrackTitle != null)
			mTrackTitle.setHapticFeedbackEnabled(enableLongPressToToggle);
		
		if (enableLongPressToToggle) {
			if (mTrackTitle != null)
				mTrackTitle.setOnLongClickListener(mLongClickListener);
			if (mClockView != null)
				mClockView.setOnLongClickListener(mLongClickListener);
		} else {
			if (mTrackTitle != null)
				mTrackTitle.setOnLongClickListener(null);
			if (mClockView != null)
				mClockView.setOnLongClickListener(null);
		}
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
	}
	
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
				updateTrackUI();
			}
		}
	};
	
	private BroadcastReceiver mAAReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mAAIntent = intent;
			updateAlbumArt();
		}
	};
	
	private BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mStatusIntent = intent;
			updateStatusUI();		
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

	protected void updateStatusUI() {
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

	private void setTrackMetadata(String title, String artist) {
		mTrackTitleString = title;
		mArtistNameString = artist;
		
		setTrackTitleText(getTextToSet(mTrackTitleString, mArtistNameString));
		updateRemoteFieldsFromLocalFields();
	}
	
	private void updateTrackUI() {
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
	
	private void nextTrack() {
		if (mContext == null)
			return;
		
		String currentMediaPlayer = prefs.getString(SETTINGS_MEDIAPLAYER_KEY, POWERAMP_MEDIAPLAYER);
		
		if (currentMediaPlayer.equals(POWERAMP_MEDIAPLAYER)) {
			Intent powerampActionIntent = new Intent(PowerAMPiAPI.ACTION_API_COMMAND);
			powerampActionIntent.putExtra(PowerAMPiAPI.COMMAND, PowerAMPiAPI.Commands.NEXT);
			mContext.startService(powerampActionIntent);
		}
		
		if (currentMediaPlayer.equals(EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
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
			mContext.startService(powerampActionIntent);
		}
		
		if (currentMediaPlayer.equals(EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
			sendMediaButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
		}
	}
	
	private void togglePlayback() {
		if (mContext == null)
			return;
		
		prefs.reload();
		
		if (!prefs.getBoolean(SETTINGS_LONGPRESS_KEY, false))
			return;
		
		String currentMediaPlayer = prefs.getString(SETTINGS_MEDIAPLAYER_KEY, POWERAMP_MEDIAPLAYER);
		
		if (currentMediaPlayer.equals(POWERAMP_MEDIAPLAYER)) {
			Intent powerampActionIntent = new Intent(PowerAMPiAPI.ACTION_API_COMMAND);
			powerampActionIntent.putExtra(PowerAMPiAPI.COMMAND, PowerAMPiAPI.Commands.TOGGLE_PLAY_PAUSE);
			mContext.startService(powerampActionIntent);
		}
		
		if (currentMediaPlayer.equals(EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
			sendMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
		}
	}
	
	private void sendMediaButton(int keyCode) {
		if (mClientIntent == null)
			return;
		
		XposedBridge.log("Sending media button");
		
		KeyEvent keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
		Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
		intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
		try {
		    mClientIntent.send(mContext, 0, intent);
		} catch (CanceledException e) {
			XposedBridge.log("Error sending intent for media button down");
		    e.printStackTrace();
		}

		keyEvent = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
		intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
		intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
		try {
		    mClientIntent.send(mContext, 0, intent);
		} catch (CanceledException e) {
		    XposedBridge.log("Error sending intent for media button up");
		    e.printStackTrace();
		}
	}
	
	private void initializeRemoteControlDisplay(Looper looper) {
		myHandler = new Handler(looper, new Handler.Callback() {
	        @Override
	        public boolean handleMessage(Message msg) {
				switch (msg.what) {
				case MSG_UPDATE_STATE:
					if (mClientGeneration == msg.arg1)
						updatePlayPauseState(msg.arg2);
					break;
				case MSG_SET_METADATA:
					if (mClientGeneration == msg.arg1)
						updateMetadata((Bundle) msg.obj);
					break;
				case MSG_SET_TRANSPORT_CONTROLS:
					break;
				case MSG_SET_GENERATION_ID:
					mClientGeneration = msg.arg1;
					mClientIntent = (PendingIntent) msg.obj;
					break;
				case MSG_SET_ARTWORK:
					if (mClientGeneration == msg.arg1) {
						if (mAlbumArt != null) {
							mAlbumArt.recycle();
						}
						mAlbumArt = (Bitmap)msg.obj;
						setAlbumArt(mAlbumArt);
					}
					break;
				}
				return true;
	        }
		});
		
		RemoteControlDisplay remoteDisplay = new RemoteControlDisplay(myHandler);
		
		AudioManager manager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
		manager.registerRemoteControlDisplay(remoteDisplay);
	}
	
	public class RemoteControlDisplay extends IRemoteControlDisplay.Stub {
		static final int MSG_SET_ARTWORK = 104;
		static final int MSG_SET_GENERATION_ID = 103;
		static final int MSG_SET_METADATA = 101;
		static final int MSG_SET_TRANSPORT_CONTROLS = 102;
		static final int MSG_UPDATE_STATE = 100;

		private WeakReference<Handler> mLocalHandler;

		RemoteControlDisplay(Handler handler) {
			mLocalHandler = new WeakReference<Handler>(handler);
		}

		public void setAllMetadata(int generationId, Bundle metadata, Bitmap bitmap) {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_SET_METADATA, generationId, 0, metadata).sendToTarget();
				handler.obtainMessage(MSG_SET_ARTWORK, generationId, 0, bitmap).sendToTarget();
			}
		}

		public void setArtwork(int generationId, Bitmap bitmap) {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_SET_ARTWORK, generationId, 0, bitmap).sendToTarget();
			}
		}

		public void setCurrentClientId(int clientGeneration, PendingIntent mediaIntent,
				boolean clearing) throws RemoteException {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_SET_GENERATION_ID, clientGeneration, (clearing ? 1 : 0), mediaIntent).sendToTarget();
			}
		}

		public void setMetadata(int generationId, Bundle metadata) {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_SET_METADATA, generationId, 0, metadata).sendToTarget();
			}
		}

		public void setPlaybackState(int generationId, int state, long stateChangeTimeMs) {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_UPDATE_STATE, generationId, state).sendToTarget();
			}
		}
				
		public void setTransportControlFlags(int generationId, int flags) {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_SET_TRANSPORT_CONTROLS, generationId, flags).sendToTarget();
			}
		}
	}
	
	protected void updatePlayPauseState(int playstate) {
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
}
