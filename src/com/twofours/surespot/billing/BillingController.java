package com.twofours.surespot.billing;

import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.content.Context;

import com.loopj.android.http.AsyncHttpResponseHandler;
import com.twofours.surespot.activities.MainActivity;
import com.twofours.surespot.billing.IabHelper.OnConsumeFinishedListener;
import com.twofours.surespot.billing.IabHelper.OnIabPurchaseFinishedListener;
import com.twofours.surespot.billing.IabHelper.OnIabSetupFinishedListener;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.network.IAsyncCallback;
import com.twofours.surespot.network.NetworkController;

public class BillingController {
	protected static final String TAG = "BillingController";

	private IabHelper mIabHelper;
	private boolean mQueried;
	private boolean mQuerying;

	private boolean mHasVoiceMessagingCapability;
	private String mVoiceMessagePurchaseToken;

	public static final int BILLING_QUERYING_INVENTORY = 100;

	public BillingController(Context context) {

		setup(context, true, null);

	}

	public synchronized void setup(Context context, final boolean query, final IAsyncCallback<Integer> callback) {

		if (!mQuerying) {
			if (mIabHelper == null) {
				mIabHelper = new IabHelper(context, SurespotConstants.GOOGLE_APP_LICENSE_KEY);
			}
			try {
				mIabHelper.startSetup(new OnIabSetupFinishedListener() {

					@Override
					public void onIabSetupFinished(IabResult result) {
						if (!result.isSuccess()) {
							// bollocks
							SurespotLog.v(TAG, "Problem setting up In-app Billing: " + result);
							if (callback != null) {
								callback.handleResponse(result.getResponse());
							}
							return;
						}

						if (query) {
							if (!mQueried) {
								SurespotLog.v(TAG, "In-app Billing is a go, querying inventory");
								synchronized (BillingController.this) {
									mQuerying = true;
								}
								mIabHelper.queryInventoryAsync(mGotInventoryListener);

							}
							else {
								SurespotLog.v(TAG, "already queried");
							}
						}

						if (callback != null) {
							callback.handleResponse(result.getResponse());
						}
					}
				});
			}
			// will be thrown if it's already setup
			catch (IllegalStateException ise) {
				if (callback != null) {
					callback.handleResponse(IabHelper.BILLING_RESPONSE_RESULT_OK);
				}
			}
			catch (Exception e) {
				if (callback != null) {
					callback.handleResponse(IabHelper.BILLING_RESPONSE_RESULT_ERROR);
				}
			}
		}
		else {
			SurespotLog.v(TAG, "In-app Billing already setup");
			if (callback != null) {
				callback.handleResponse(IabHelper.BILLING_RESPONSE_RESULT_OK);
			}
		}
	}

	public synchronized IabHelper getIabHelper() {
		return mIabHelper;
	}

	public synchronized boolean hasVoiceMessaging() {
		return mHasVoiceMessagingCapability;
	}

	IabHelper.QueryInventoryFinishedListener mGotInventoryListener = new IabHelper.QueryInventoryFinishedListener() {
		public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
			SurespotLog.d(TAG, "Query inventory finished.");
			synchronized (BillingController.this) {
				mQuerying = false;
				mQueried = true;
			}

			if (result.isFailure()) {
				return;
			}

			SurespotLog.d(TAG, "Query inventory was successful.");

			// consume owned items
			List<Purchase> owned = inventory.getAllPurchases();

			if (owned.size() > 0) {
				SurespotLog.d(TAG, "consuming pwyl purchases");

				List<Purchase> consumables = new ArrayList<Purchase>(owned.size());

				for (Purchase purchase : owned) {
					SurespotLog.v(TAG, "has purchased sku: %s, state: %d, token: %s", purchase.getSku(), purchase.getPurchaseState(), purchase.getToken());

					if (purchase.getSku().equals(SurespotConstants.Products.VOICE_MESSAGING)) {

						if (purchase.getPurchaseState() == 0) {
							setVoiceMessagingToken(purchase.getToken(), null);
						}
					}

					if (isConsumable(purchase.getSku())) {
						consumables.add(purchase);
					}
				}

				if (consumables.size() > 0) {
					mIabHelper.consumeAsync(consumables, new IabHelper.OnConsumeMultiFinishedListener() {

						@Override
						public void onConsumeMultiFinished(List<Purchase> purchases, List<IabResult> results) {
							SurespotLog.d(TAG, "consumed purchases: %s", results);
						}
					});
				}
			}
			else {
				SurespotLog.d(TAG, "no purchases to consume");
			}

		}
	};

	public void purchase(final Activity activity, final String sku, final boolean query, final IAsyncCallback<Integer> callback) {
		if (!mQuerying) {
			setup(activity, query, new IAsyncCallback<Integer>() {

				@Override
				public void handleResponse(Integer response) {
					if (response != IabHelper.BILLING_RESPONSE_RESULT_OK) {
						callback.handleResponse(response);
					}
					else {
						purchaseInternal(activity, sku, callback);
					}

				}
			});
		}
		else {
			callback.handleResponse(BILLING_QUERYING_INVENTORY);
		}

	}

	private void purchaseInternal(Activity activity, final String sku, final IAsyncCallback<Integer> callback) {
		try {
			// showProgress();
			getIabHelper().launchPurchaseFlow(activity, sku, SurespotConstants.IntentRequestCodes.PURCHASE, new OnIabPurchaseFinishedListener() {

				@Override
				public void onIabPurchaseFinished(IabResult result, Purchase info) {
					if (result.isFailure()) {
						callback.handleResponse(result.getResponse());
						return;
					}
					SurespotLog.v(TAG, "purchase successful");

					String returnedSku = info.getSku();

					if (returnedSku.equals(SurespotConstants.Products.VOICE_MESSAGING)) {
						setVoiceMessagingToken(info.getToken(), new IAsyncCallback<Boolean>() {
							@Override
							public void handleResponse(Boolean result) {
								if (result) {
									SurespotLog.v(TAG, " set server purchase token result: %b", result);
								}

							}
						});
					}

					if (isConsumable(returnedSku)) {
						getIabHelper().consumeAsync(info, new OnConsumeFinishedListener() {

							@Override
							public void onConsumeFinished(Purchase purchase, IabResult result) {
								SurespotLog.v(TAG, "consumption result: %b", result.isSuccess());
								callback.handleResponse(result.getResponse());
							}
						});
					}
					else {
						callback.handleResponse(result.getResponse());
					}

				}
			});
		}
		// handle something else going on
		catch (IllegalStateException e) {
			SurespotLog.w(TAG, e, "could not purchase");
			callback.handleResponse(BILLING_QUERYING_INVENTORY);
		}
		catch (Exception e) {
			callback.handleResponse(IabHelper.BILLING_RESPONSE_RESULT_ERROR);
		}
	}

	public void setVoiceMessagingToken(String token, IAsyncCallback<Boolean> updateServerCallback) {
		synchronized (this) {
			mVoiceMessagePurchaseToken = token;
			mHasVoiceMessagingCapability = true;
		}

		if (updateServerCallback != null) {

			// upload to server
			NetworkController networkController = MainActivity.getNetworkController();
			// TODO tell user if we can't update the token on the server tell them to login
			if (networkController != null) {
				networkController.updateVoiceMessagingPurchaseToken(token, new AsyncHttpResponseHandler() {
					@Override
					public void onSuccess(int statusCode, String content) {
						SurespotLog.v(TAG, "successfully updated voice messaging token");
					}
				});
			}

		}
	}

	public synchronized String getVoiceMessagingPurchaseToken() {
		return mVoiceMessagePurchaseToken;
	}

	private boolean isConsumable(String sku) {
		if (sku.equals(SurespotConstants.Products.VOICE_MESSAGING)) {
			return false;
		}
		else {
			if (sku.startsWith(SurespotConstants.Products.PWYL_PREFIX)) {
				return true;
			}
			else {

				return false;
			}
		}
	}

	public synchronized void revokeVoiceMessaging() {
		// Will probably have to kill surespot process to re-query after this but oh well
		mHasVoiceMessagingCapability = false;
		mVoiceMessagePurchaseToken = null;
	}

	public synchronized void dispose() {
		SurespotLog.v(TAG, "dispose");
		if (mIabHelper != null && !mIabHelper.mAsyncInProgress) {
			mIabHelper.dispose();
			mIabHelper = null;
		}

		mQueried = false;
		mQuerying = false;
		mHasVoiceMessagingCapability = false;
		mVoiceMessagePurchaseToken = null;

	}
}
