/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twofours.surespot;

import java.lang.ref.WeakReference;

import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;

import com.twofours.surespot.chat.SurespotMessage;
import com.twofours.surespot.encryption.EncryptionController;
import com.twofours.surespot.network.NetworkController;

/**
 * This helper class download images from the Internet and binds those with the provided ImageView.
 * 
 * <p>
 * It requires the INTERNET permission, which should be added to your application's manifest file.
 * </p>
 * 
 * A local cache of downloaded images is maintained internally to improve performance.
 */
public class ImageDownloader {
	private static final String TAG = "ImageDownloader";
	private static BitmapCache mBitmapCache = new BitmapCache();

	/**
	 * Download the specified image from the Internet and binds it to the provided ImageView. The binding is immediate if the image is found
	 * in the cache and will be done asynchronously otherwise. A null bitmap will be associated to the ImageView if an error occurs.
	 * 
	 * @param url
	 *            The URL of the image to download.
	 * @param imageView
	 *            The ImageView to bind the downloaded image to.
	 */
	public void download(ImageView imageView, SurespotMessage message) {
		Bitmap bitmap = getBitmapFromCache(message.getCipherData());

		if (bitmap == null) {
			forceDownload(imageView, message);
		}
		else {
			cancelPotentialDownload(imageView, message);
			imageView.clearAnimation();
			imageView.setImageBitmap(bitmap);
		}
	}

	/*
	 * Same as download but the image is always downloaded and the cache is not used. Kept private at the moment as its interest is not
	 * clear. private void forceDownload(String url, ImageView view) { forceDownload(url, view, null); }
	 */

	/**
	 * Same as download but the image is always downloaded and the cache is not used. Kept private at the moment as its interest is not
	 * clear.
	 */
	private void forceDownload(ImageView imageView, SurespotMessage message) {
		if (cancelPotentialDownload(imageView, message)) {
			BitmapDownloaderTask task = new BitmapDownloaderTask(imageView, message);
			DownloadedDrawable downloadedDrawable = new DownloadedDrawable(task,
					message.getHeight() == 0 ? SurespotConstants.IMAGE_DISPLAY_HEIGHT : message.getHeight());
			imageView.setImageDrawable(downloadedDrawable);
			task.execute();
		}
	}

	/**
	 * Returns true if the current download has been canceled or if there was no download in progress on this image view. Returns false if
	 * the download in progress deals with the same url. The download is not stopped in that case.
	 */
	private static boolean cancelPotentialDownload(ImageView imageView, SurespotMessage message) {
		BitmapDownloaderTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);

		if (bitmapDownloaderTask != null) {
			SurespotMessage taskMessage = bitmapDownloaderTask.mMessage;
			if ((taskMessage == null) || (!taskMessage.equals(message))) {
				bitmapDownloaderTask.cancel(true);
			}
			else {
				// The same URL is already being downloaded.
				return false;
			}
		}
		return true;
	}

	/**
	 * @param imageView
	 *            Any imageView
	 * @return Retrieve the currently active download task (if any) associated with this imageView. null if there is no such task.
	 */
	private static BitmapDownloaderTask getBitmapDownloaderTask(ImageView imageView) {
		if (imageView != null) {
			Drawable drawable = imageView.getDrawable();
			if (drawable instanceof DownloadedDrawable) {
				DownloadedDrawable downloadedDrawable = (DownloadedDrawable) drawable;
				return downloadedDrawable.getBitmapDownloaderTask();
			}
		}
		return null;
	}

	/**
	 * The actual AsyncTask that will asynchronously download the image.
	 */
	class BitmapDownloaderTask extends AsyncTask<Void, Void, Bitmap> {
		public SurespotMessage mMessage;
		private final WeakReference<ImageView> imageViewReference;

		public BitmapDownloaderTask(ImageView imageView, SurespotMessage message) {
			mMessage = message;
			imageViewReference = new WeakReference<ImageView>(imageView);
		}

		/**
		 * Actual download method.
		 */
		@Override
		protected Bitmap doInBackground(Void... params) {
			String content = NetworkController.getFileSync(mMessage.getCipherData());
			if (!isCancelled()) {
				if (content != null) {
					byte[] decoded = EncryptionController.symmetricBase64DecryptSync(mMessage.getSpot(), mMessage.getIv(), content);
					return Utils.getSampledImage(decoded, SurespotConstants.IMAGE_DISPLAY_HEIGHT);
				}
			}

			return null;
		}

		/**
		 * Once the image is downloaded, associates it to the imageView
		 */
		@Override
		protected void onPostExecute(Bitmap bitmap) {
			if (isCancelled()) {
				bitmap = null;
			}

			if (bitmap != null) {
				addBitmapToCache(mMessage.getCipherData(), bitmap);

				if (imageViewReference != null) {
					ImageView imageView = imageViewReference.get();
					BitmapDownloaderTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);
					// Change bitmap only if this process is still associated with it
					// Or if we don't use any bitmap to task association (NO_DOWNLOADED_DRAWABLE mode)
					if ((this == bitmapDownloaderTask)) {
						imageView.clearAnimation();
						Animation fadeIn = new AlphaAnimation(0, 1);
						fadeIn.setDuration(1000);
						imageView.startAnimation(fadeIn);
						imageView.setImageBitmap(bitmap);
						if (mMessage.getHeight() == 0) {
							bitmapDownloaderTask.mMessage.setHeight(bitmap.getHeight());
							SurespotLog.v(
									TAG,
									"Setting message height from image, id: " + mMessage.getId() + " from: " + mMessage.getFrom()
											+ ", to: " + mMessage.getTo() + ", height: " + bitmap.getHeight() + ", width: "
											+ bitmap.getWidth());
						}
					}
				}
			}
		}
	}

	/**
	 * A fake Drawable that will be attached to the imageView while the download is in progress.
	 * 
	 * <p>
	 * Contains a reference to the actual download task, so that a download task can be stopped if a new binding is required, and makes sure
	 * that only the last started download process can bind its result, independently of the download finish order.
	 * </p>
	 */
	static class DownloadedDrawable extends ColorDrawable {
		private final WeakReference<BitmapDownloaderTask> bitmapDownloaderTaskReference;
		private int mHeight;

		public DownloadedDrawable(BitmapDownloaderTask bitmapDownloaderTask, int height) {
			mHeight = height;
			bitmapDownloaderTaskReference = new WeakReference<BitmapDownloaderTask>(bitmapDownloaderTask);
		}

		public BitmapDownloaderTask getBitmapDownloaderTask() {
			return bitmapDownloaderTaskReference.get();
		}

		/**
		 * Force ImageView to be a certain height
		 */
		@Override
		public int getIntrinsicHeight() {

			return mHeight;
		}
	}

	/**
	 * Adds this bitmap to the cache.
	 * 
	 * @param bitmap
	 *            The newly downloaded bitmap.
	 */
	private void addBitmapToCache(String key, Bitmap bitmap) {
		if (bitmap != null) {
			mBitmapCache.addBitmapToMemoryCache(key, bitmap);

		}
	}

	/**
	 * @param url
	 *            The URL of the image that will be retrieved from the cache.
	 * @return The cached bitmap or null if it was not found.
	 */
	private Bitmap getBitmapFromCache(String key) {

		return mBitmapCache.getBitmapFromMemCache(key);

	}

	public void evictCache() {
		mBitmapCache.evictAll();

	}
}