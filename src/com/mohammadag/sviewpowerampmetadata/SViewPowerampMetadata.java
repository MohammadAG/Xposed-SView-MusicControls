package com.mohammadag.sviewpowerampmetadata;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;

import static de.robv.android.xposed.XposedBridge.hookAllConstructors;

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
	private OnLongClickListener mLongClickListener = null;
	
	// Needed for swipe gestures
	private PointerCoords mDownPos = new PointerCoords();
	private static PointerCoords mUpPos = new PointerCoords();
	
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
	
	private static XSharedPreferences prefs;
	private XModuleResources modRes = null;
	
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
		
		mCurrentUserHandle = (UserHandle) getStaticObjectField(UserHandle.class, "CURRENT");
		
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
						if (Math.abs(dx) > Common.MIN_DISTANCE) {
						    if (dx > 0)
						        onSwipeLeft();
						    else
						        onSwipeRight();
						    return true;
						}
						
						float dy = mDownPos.y - mUpPos.y;
						
						// Check for vertical wipe
						if (Math.abs(dy) > Common.MIN_DISTANCE) {
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
		
		mLongClickListener = new OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				extendTimeout();
				togglePlayback();
				return true;
			}
		};
		
		findAndHookMethod(MusicWidget, "onFinishInflate", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				mMusicWidgetObject = param.thisObject;
				mTrackTitle = (TextView) getObjectField(param.thisObject, "mTrackTitle");
				mTrackTitle.setOnTouchListener(gestureListener);
				mAlbumArtWithImage = (ImageView) getObjectField(param.thisObject, "mAlbumArtWithImage");
				
				updateRemoteFieldsFromLocalFields();
				
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
		});
		
		findAndHookMethod(ClockWidget, "onFinishInflate", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				mClockView = (LinearLayout) getObjectField(param.thisObject, "mClockView");
				mClockView.setOnTouchListener(gestureListener);
			}
		});
		
		findAndHookMethod(MusicWidget, "handleMediaUpdate", int.class, int.class, Uri.class, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) {
				if (prefs.getBoolean(Common.SETTINGS_DISABLE_SAMSUNG_METADATA_UPDATES, false))
					param.setResult(false);
			}
		});
		loadSettings();
	}

	private void loadSettings() {
		boolean enableLongPressToToggle = prefs.getBoolean(Common.SETTINGS_LONGPRESS_KEY, false);
		
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
			XposedHelpers.setBooleanField(mMusicWidgetObject, "mIsPlaying", mIsPlaying);
		}
	}
	
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
	
	private BroadcastReceiver metadataChangedReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent == null)
				return;
			
			String currentMediaPlayer = prefs.getString(Common.SETTINGS_MEDIAPLAYER_KEY, Common.POWERAMP_MEDIAPLAYER);
			if (currentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER) || intent.hasExtra("com.maxmpz.audioplayer.source"))
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
	
	private BroadcastReceiver mSettingsUpdatedReceiver = new BroadcastReceiver() {		
		@Override
		public void onReceive(Context context, Intent intent) {
			XposedBridge.log(Common.PACKAGE_NAME + ": " + "Settings changed, reloading...");
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
				String currentMediaPlayer = prefs.getString(Common.SETTINGS_MEDIAPLAYER_KEY, Common.POWERAMP_MEDIAPLAYER);
				if (!currentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER))
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
			String currentMediaPlayer = prefs.getString(Common.SETTINGS_MEDIAPLAYER_KEY, Common.POWERAMP_MEDIAPLAYER);
			if (!currentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER))
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
			String currentMediaPlayer = prefs.getString(Common.SETTINGS_MEDIAPLAYER_KEY, Common.POWERAMP_MEDIAPLAYER);
			if (!currentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER))
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
				
				mIsPlaying = !paused;
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
		
		String currentMediaPlayer = prefs.getString(Common.SETTINGS_MEDIAPLAYER_KEY, Common.POWERAMP_MEDIAPLAYER);
		
		if (currentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER)) {
			Intent powerampActionIntent = new Intent(PowerAMPiAPI.ACTION_API_COMMAND);
			powerampActionIntent.putExtra(PowerAMPiAPI.COMMAND, PowerAMPiAPI.Commands.NEXT);
			startServiceAsUser(powerampActionIntent, mCurrentUserHandle);
		} else if (currentMediaPlayer.equals(Common.GOOGLEPLAY_MEDIAPLAYER)) {
			sendMusicServiceCommand(MusicServiceCommands.NEXT);
		} else if (currentMediaPlayer.equals(Common.EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
			sendMediaButton(KeyEvent.KEYCODE_MEDIA_NEXT);
		}
	}
	
	private void previousTrack() {
		if (mContext == null)
			return;

		String currentMediaPlayer = prefs.getString(Common.SETTINGS_MEDIAPLAYER_KEY, Common.POWERAMP_MEDIAPLAYER);
		
		if (currentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER)) {
			Intent powerampActionIntent = new Intent(PowerAMPiAPI.ACTION_API_COMMAND);
			powerampActionIntent.putExtra(PowerAMPiAPI.COMMAND, PowerAMPiAPI.Commands.PREVIOUS);
			startServiceAsUser(powerampActionIntent, mCurrentUserHandle);
		} else if (currentMediaPlayer.equals(Common.GOOGLEPLAY_MEDIAPLAYER)) {
			sendMusicServiceCommand(MusicServiceCommands.PREVIOUS);
		} else if (currentMediaPlayer.equals(Common.EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
			sendMediaButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
		}
	}
	
	private void togglePlayback() {
		if (mContext == null)
			return;
		
		if (!prefs.getBoolean(Common.SETTINGS_LONGPRESS_KEY, false))
			return;
		
		String currentMediaPlayer = prefs.getString(Common.SETTINGS_MEDIAPLAYER_KEY, Common.POWERAMP_MEDIAPLAYER);
		
		if (currentMediaPlayer.equals(Common.POWERAMP_MEDIAPLAYER)) {
			Intent powerampActionIntent = new Intent(PowerAMPiAPI.ACTION_API_COMMAND);
			powerampActionIntent.putExtra(PowerAMPiAPI.COMMAND, PowerAMPiAPI.Commands.TOGGLE_PLAY_PAUSE);
			startServiceAsUser(powerampActionIntent, mCurrentUserHandle);
		} else if (currentMediaPlayer.equals(Common.GOOGLEPLAY_MEDIAPLAYER)) {
			sendMusicServiceCommand(MusicServiceCommands.PLAY_PAUSE);
		} else if (currentMediaPlayer.equals(Common.EMULATE_MEDIA_KEYS_MEDIAPLAYER)) {
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
	
	// Helpers methods
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
