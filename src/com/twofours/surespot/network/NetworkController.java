package com.twofours.surespot.network;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpStatus;
import org.apache.http.client.CookieStore;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.HttpContext;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.gcm.GCMRegistrar;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.PersistentCookieStore;
import com.loopj.android.http.RequestParams;
import com.twofours.surespot.SurespotApplication;
import com.twofours.surespot.SurespotConstants;
import com.twofours.surespot.ui.activities.LoginActivity;

public class NetworkController {
	protected static final String TAG = "NetworkController";
	private static Cookie mConnectCookie;

	private static AsyncHttpClient mClient;
	private static CookieStore mCookieStore;

	public static void get(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
		mClient.get(SurespotConstants.BASE_URL + url, params, responseHandler);
	}

	public static void post(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
		mClient.post(SurespotConstants.BASE_URL + url, params, responseHandler);
	}

	public static Cookie getConnectCookie() {
		return mConnectCookie;
	}

	public static boolean hasSession() {
		return mConnectCookie != null;
	}

	public static CookieStore getCookieStore() {
		return mCookieStore;
	}

	static {
		mCookieStore = new PersistentCookieStore(SurespotApplication.getAppContext());
		if (mCookieStore.getCookies().size() > 0) {
			Log.v(TAG, "mmm cookies in the jar: " + mCookieStore.getCookies().size());
			mConnectCookie = getConnectCookie(mCookieStore);
		}

		mClient = new AsyncHttpClient();
		mClient.setCookieStore(mCookieStore);

		// handle 401s
		((DefaultHttpClient) mClient.getHttpClient()).addResponseInterceptor(new HttpResponseInterceptor() {

			@Override
			public void process(HttpResponse response, HttpContext context) throws HttpException, IOException {

				if (response.getStatusLine().getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
					String origin = context.getAttribute("http.cookie-origin").toString();

					if (origin != null) {
						Log.v(TAG, "response origin: " + origin);
						if (!origin.equals("[" + SurespotConstants.BASE_URL.substring(7) + "/login]")) {
						    mClient.cancelRequests(SurespotApplication.getAppContext(), true);

							Log.v(TAG, "launching login intent");
							Intent intent = new Intent(SurespotApplication.getAppContext(), LoginActivity.class);
							intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							SurespotApplication.getAppContext().startActivity(intent);
							
							

						}
					}
				}
			}
		});

	}

	public static void addUser(String username, String password, String publicKey, String gcmId,
			final AsyncHttpResponseHandler responseHandler) {
		Map<String, String> params = new HashMap<String, String>();
		params.put("username", username);
		params.put("password", password);
		params.put("publickey", publicKey);
		if (gcmId != null) {
			params.put("gcmId", gcmId);
		}

		post("/users", new RequestParams(params), new AsyncHttpResponseHandler() {

			@Override
			public void onSuccess(int responseCode, String result) {
				mConnectCookie = getConnectCookie(mCookieStore);
				if (mConnectCookie == null) {
					Log.e(TAG, "did not get cookie from signup");
					responseHandler.onFailure(new Exception("Did not get cookie."), "Did not get cookie.");
				}
				else {
					responseHandler.onSuccess(responseCode, result);
				}

			}

			@Override
			public void onFailure(Throwable arg0, String content) {
				responseHandler.onFailure(arg0, content);
			}

		});

	}

	private static Cookie getConnectCookie(CookieStore cookieStore) {
		for (Cookie c : cookieStore.getCookies()) {
			// System.out.println("Cookie name: " + c.getName() + " value: " +
			// c.getValue());
			if (c.getName().equals("connect.sid")) { return c; }
		}
		return null;

	}

	public static void login(String username, String password, String gcmId, final AsyncHttpResponseHandler responseHandler) {
		Map<String, String> params = new HashMap<String, String>();
		params.put("username", username);
		params.put("password", password);
		params.put("gcmId", gcmId);

		post("/login", new RequestParams(params), new AsyncHttpResponseHandler() {

			@Override
			public void onSuccess(int responseCode, String result) {
				mConnectCookie = getConnectCookie(mCookieStore);
				if (mConnectCookie == null) {
					Log.e(TAG, "Did not get cookie from login.");
					responseHandler.onFailure(new Exception("Did not get cookie."), null);
				}
				else {
					responseHandler.onSuccess(responseCode, result);
				}

			}

			@Override
			public void onFailure(Throwable arg0, String content) {
				responseHandler.onFailure(arg0, content);
			}
		});

	}

	public static void getFriends(AsyncHttpResponseHandler responseHandler) {
		get("/friends", null, responseHandler);
	}

	public static void getNotifications(AsyncHttpResponseHandler responseHandler) {
		get("/notifications", null, responseHandler);

	}

	public static void getMessages(String room, AsyncHttpResponseHandler responseHandler) {
		get("/conversations/" + room + "/messages", null, responseHandler);
	}

	public static void getPublicKey(String username, AsyncHttpResponseHandler responseHandler) {
		get("/publickey/" + username, null, responseHandler);

	}

	public static void invite(String friendname, AsyncHttpResponseHandler responseHandler) {

		post("/invite/" + friendname, null, responseHandler);

	}

	public static void respondToInvite(String friendname, String action, AsyncHttpResponseHandler responseHandler) {
		post("/invites/" + friendname + "/" + action, null, responseHandler);
	}

	public static void registerGcmId(String id, AsyncHttpResponseHandler responseHandler) {

		Map<String, String> params = new HashMap<String, String>();
		params.put("gcmId", id);

		post("/registergcm", new RequestParams(params), responseHandler);

	}

	public static void userExists(String username, AsyncHttpResponseHandler responseHandler) {
		get("/users/" + username + "/exists", null, responseHandler);
	}

	/**
	 * Unregister this account/device pair within the server.
	 */
	public static void unregister(final Context context, final String regId) {
		Log.i(TAG, "unregistering device (regId = " + regId + ")");
		GCMRegistrar.setRegisteredOnServer(context, false);
	}
}
