package com.twofours.surespot.activities;

import java.io.File;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.view.ViewPager;

import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.google.android.gcm.GCMRegistrar;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.twofours.surespot.GCMIntentService;
import com.twofours.surespot.IdentityController;
import com.twofours.surespot.R;
import com.twofours.surespot.SurespotApplication;
import com.twofours.surespot.chat.ChatController;
import com.twofours.surespot.chat.ChatUtils;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.network.IAsyncCallback;
import com.twofours.surespot.network.NetworkController;
import com.twofours.surespot.services.CredentialCachingService;
import com.twofours.surespot.services.CredentialCachingService.CredentialCachingBinder;
import com.viewpagerindicator.TitlePageIndicator;

public class MainActivity extends SherlockFragmentActivity {
	public static final String TAG = "MainActivity";

	private ChatController mChatController;
	private CredentialCachingService mCredentialCachingService;

	private NotificationManager mNotificationManager;


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		setContentView(R.layout.activity_main);

		Intent intent = new Intent(this, CredentialCachingService.class);
		bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

		SurespotApplication.setStartupIntent(getIntent());
		Utils.logIntent(TAG, getIntent());

	}

	private ServiceConnection mConnection = new ServiceConnection() {
		public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) {
			SurespotLog.v(TAG, "caching service bound");
			CredentialCachingBinder binder = (CredentialCachingBinder) service;
			mCredentialCachingService = binder.getService();

			// make sure these are there so startup code can execute
			SurespotApplication.setCachingService(mCredentialCachingService);
			SurespotApplication.setNetworkController(new NetworkController(MainActivity.this));
			startup();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {

		}
	};

	private void startup() {
		try {
			// device without GCM throws exception
			GCMRegistrar.checkDevice(this);
			GCMRegistrar.checkManifest(this);

			final String regId = GCMRegistrar.getRegistrationId(this);
			// boolean registered = GCMRegistrar.isRegistered(this);
			// boolean registeredOnServer = GCMRegistrar.isRegisteredOnServer(this);
			if (regId.equals("")) {
				SurespotLog.v(TAG, "Registering for GCM.");
				GCMRegistrar.register(this, GCMIntentService.SENDER_ID);
			}
			else {
				SurespotLog.v(TAG, "GCM already registered.");
			}
		}
		catch (Exception e) {
			SurespotLog.w(TAG, "onCreate", e);
		}

		// NetworkController.unregister(this, regId);
		Intent intent = getIntent();
		// if we have any users or we don't need to create a user, figure out if we need to login
		if (IdentityController.hasIdentity() && !intent.getBooleanExtra("create", false)) {

			// if we have a current user we're logged in
			String user = IdentityController.getLoggedInUser();

			String notificationType = intent.getStringExtra(SurespotConstants.ExtraNames.NOTIFICATION_TYPE);
			String messageTo = intent.getStringExtra(SurespotConstants.ExtraNames.MESSAGE_TO);

			SurespotLog.v(TAG, "user: " + user);
			SurespotLog.v(TAG, "type: " + notificationType);
			SurespotLog.v(TAG, "messageTo: " + messageTo);

			// if we have a message to the currently logged in user, set the from and start the chat activity
			if ((user == null)
					|| (intent.getBooleanExtra("401", false))
					|| ((SurespotConstants.IntentFilters.MESSAGE_RECEIVED.equals(notificationType)
							|| SurespotConstants.IntentFilters.INVITE_REQUEST.equals(notificationType) || SurespotConstants.IntentFilters.INVITE_RESPONSE
								.equals(notificationType)) && (!messageTo.equals(user)))) {

				SurespotLog.v(TAG, "need a (different) user, showing login");
				Intent newIntent = new Intent(this, LoginActivity.class);
				newIntent.putExtra(SurespotConstants.ExtraNames.MESSAGE_TO, messageTo);
				startActivityForResult(newIntent, SurespotConstants.IntentRequestCodes.LOGIN);
			}
			else {
				launch(getIntent());
			}

		}
		// otherwise show the signup activity
		else {
			SurespotLog.v(TAG, "starting signup activity");
			Intent newIntent = new Intent(this, SignupActivity.class);
			// intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

			startActivity(newIntent);
			finish();
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		SurespotLog.v(TAG, "onActivityResult, requestCode: " + requestCode);

		Uri selectedImageUri = null;
		if (resultCode == RESULT_OK) {
			switch (requestCode) {
			case SurespotConstants.IntentRequestCodes.LOGIN:
				if (resultCode == RESULT_OK) {
					launch(SurespotApplication.getStartupIntent());
				}
				else {
					finish();
				}
				break;

			case SurespotConstants.IntentRequestCodes.REQUEST_EXISTING_IMAGE:
				Intent intent = new Intent(this, ImageSelectActivity.class);
				intent.putExtra("source", ImageSelectActivity.SOURCE_EXISTING_IMAGE);
				intent.putExtra("to", mChatController.getCurrentChat());
				intent.setData(data.getData());
				startActivityForResult(intent, SurespotConstants.IntentRequestCodes.REQUEST_SELECT_IMAGE);

				break;

			case SurespotConstants.IntentRequestCodes.REQUEST_SELECT_IMAGE:
				selectedImageUri = data.getData();
				String to = data.getStringExtra("to");
				final String filename = data.getStringExtra("filename");
				if (selectedImageUri != null) {

					Utils.makeToast(this, getString(R.string.uploading_image));
					ChatUtils.uploadPictureMessageAsync(this, selectedImageUri, to, false, filename, new IAsyncCallback<Boolean>() {
						@Override
						public void handleResponse(Boolean result) {
							if (result) {
								Utils.makeToast(MainActivity.this, getString(R.string.image_successfully_uploaded));

							}
							else {
								Utils.makeToast(MainActivity.this, getString(R.string.could_not_upload_image));
							}

							new File(filename).delete();
						}
					});
					break;
				}
			}
		}

	}

	private void launch(Intent intent) {
		SurespotLog.v(TAG, "launch");

		NetworkController networkController = SurespotApplication.getNetworkController();
		if (networkController != null) {
			// make sure the gcm is set

			// use case:
			// user signs-up without google account (unlikely)
			// user creates google account
			// user opens app again, we have session so neither login or add user is called (which would set the gcm)

			// so we need to upload the gcm here if we haven't already

			networkController.registerGcmId(new AsyncHttpResponseHandler() {

				@Override
				public void onSuccess(int arg0, String arg1) {
					SurespotLog.v(TAG, "GCM registered in surespot server");
				}

				@Override
				public void onFailure(Throwable arg0, String arg1) {
					SurespotLog.e(TAG, arg0.toString(), arg0);
				}
			});
		}

		String action = intent.getAction();
		String type = intent.getType();

		String messageTo = intent.getStringExtra(SurespotConstants.ExtraNames.MESSAGE_TO);
		String messageFrom = intent.getStringExtra(SurespotConstants.ExtraNames.MESSAGE_FROM);
		String notificationType = intent.getStringExtra(SurespotConstants.ExtraNames.NOTIFICATION_TYPE);

		String lastName = Utils.getSharedPrefsString(getApplicationContext(), SurespotConstants.PrefNames.LAST_CHAT);

		boolean mSet = false;
		String name = null;

		// if we're coming from an invite notification, or we need to send to someone
		// then display friends
		if (SurespotConstants.IntentFilters.INVITE_REQUEST.equals(notificationType)
				|| SurespotConstants.IntentFilters.INVITE_RESPONSE.equals(notificationType)
				|| (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null)) {
			Utils.configureActionBar(this, "send", "select recipient", false);
			mSet = true;
		}

		// message received show chat activity for user
		if (SurespotConstants.IntentFilters.MESSAGE_RECEIVED.equals(notificationType)) {

			SurespotLog.v(TAG, "found chat name, starting chat activity, to: " + messageTo + ", from: " + messageFrom);
			name = messageFrom;
			Utils.configureActionBar(this, "surespot", IdentityController.getLoggedInUser(), false);
			mSet = true;
		}

		if (!mSet) {
			Utils.configureActionBar(this, "surespot", IdentityController.getLoggedInUser(), false);
			if (lastName == null) {
				name = lastName;
			}
		}

		SurespotApplication.setChatController(new ChatController(MainActivity.this, (ViewPager) findViewById(R.id.pager),
				(TitlePageIndicator) findViewById(R.id.indicator), getSupportFragmentManager(), name));
		mChatController = SurespotApplication.getChatController();
		mChatController.onResume();
	}

	@Override
	protected void onResume() {
		// TODO Auto-generated method stub
		super.onResume();
		SurespotLog.v(TAG, "onResume");
		if (mChatController != null) {
			mChatController.onResume();
		}
	}

	@Override
	protected void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
		if (mChatController != null) {
			mChatController.onPause();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(com.actionbarsherlock.view.Menu menu) {
		super.onCreateOptionsMenu(menu);
		MenuInflater inflater = getSupportMenuInflater();
		inflater.inflate(R.menu.activity_main, menu);

		if (Camera.getNumberOfCameras() == 0) {
			SurespotLog.v(TAG, "hiding capture image menu option");
			menu.findItem(R.id.menu_capture_image_bar).setVisible(false);
		}

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Intent intent = null;
		switch (item.getItemId()) {
		case android.R.id.home:
			// This is called when the Home (Up) button is pressed
			// in the Action Bar.
			// showUi(!mChatsShowing);
			mChatController.setCurrentChat(null);
			return true;
		case R.id.menu_close_bar:
		case R.id.menu_close:
			mChatController.closeTab();
			return true;

		case R.id.menu_send_image_bar:
		case R.id.menu_send_image:
			intent = new Intent();
			// TODO paid version allows any file
			intent.setType("image/*");
			intent.setAction(Intent.ACTION_GET_CONTENT);
		//	Utils.configureActionBar(this, getString(R.string.select_image), mChatController.getCurrentChat(), false);
			startActivityForResult(Intent.createChooser(intent, getString(R.string.select_image)),
					SurespotConstants.IntentRequestCodes.REQUEST_EXISTING_IMAGE);
			return true;
		case R.id.menu_capture_image_bar:
		case R.id.menu_capture_image:
			// case R.id.menu_capture_image_menu:
			intent = new Intent(this, ImageSelectActivity.class);
			intent.putExtra("source", ImageSelectActivity.SOURCE_CAPTURE_IMAGE);
			intent.putExtra("to", mChatController.getCurrentChat());
			startActivityForResult(intent, SurespotConstants.IntentRequestCodes.REQUEST_SELECT_IMAGE);

			return true;
		case R.id.menu_settings_bar:
		case R.id.menu_settings:
			intent = new Intent(this, SettingsActivity.class);
			startActivityForResult(intent, SurespotConstants.IntentRequestCodes.REQUEST_SETTINGS);
			return true;
		case R.id.menu_logout:
		case R.id.menu_logout_bar:
			IdentityController.logout();
			intent = new Intent(MainActivity.this, MainActivity.class);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
			MainActivity.this.startActivity(intent);
			finish();

			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void onDestroy() {

		super.onDestroy();
		unbindService(mConnection);
	}
}