/**
 * 
 */
package com.levelup.picturecache;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.levelup.picturecache.loaders.ViewLoader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.FloatMath;

class BitmapDownloader extends Thread {

	private static final boolean DEBUG_BITMAP_DOWNLOADER = false;

	private static class DownloadTarget {
		final PictureLoaderHandler loadHandler;
		final CacheKey mKey;
		File fileInCache;
		DownloadTarget(PictureLoaderHandler handler, CacheKey key) {
			this.loadHandler = handler;
			this.mKey = key;
		}

		@Override
		public boolean equals(Object o) {
			if (this==o) return true;
			if (!(o instanceof DownloadTarget)) return false;
			DownloadTarget d = (DownloadTarget) o;
			return mKey.equals(d.mKey) && loadHandler.equals(d.loadHandler);
		}

		@Override
		public int hashCode() {
			return mKey.hashCode() * 31 + loadHandler.hashCode();
		}

		@Override
		public String toString() {
			return "DownloadTarget:"+loadHandler;
		}
	}

	abstract interface JobMonitor {
		abstract void onJobFinishedWithNewBitmaps(BitmapDownloader job, HashMap<CacheVariant,Drawable> newBitmaps);
	}

	private final String mURL;
	private final Object mCookie;
	private final PictureCache mCache;
	private final CopyOnWriteArrayList<DownloadTarget> mTargets = new CopyOnWriteArrayList<DownloadTarget>();

	private JobMonitor mMonitor;

	// locked by mTargets
	/** see {@link LifeSpan} values */
	private LifeSpan mLifeSpan;
	private long mItemDate;

	private boolean mCanDownload;
	private boolean mIsThreadStarted;
	private boolean mAborting;

	private static final int CONNECT_TIMEOUT_DL = 10000; // 10s

	BitmapDownloader(String URL, Object cookie, PictureCache cache) {
		if (URL==null) throw new NullPointerException("How are we supposed to download a null URL?");
		mURL = URL;
		mCookie = cookie;
		mCache = cache;
		setName("PictureDL-"+mURL.hashCode());
	}

	void setMonitor(JobMonitor monitor) {
		mMonitor = monitor;
	}

	String getURL() {
		return mURL;
	}
	LifeSpan getLifeSpan() {
		return mLifeSpan;
	}
	long getItemDate() {
		return mItemDate;
	}

	@Override
	public String toString() {
		return "BitmapLoader:"+mURL+"@"+super.hashCode();
	}

	public void run() {
		//LogManager.logger.v( "start image load in cache: " + mURL);
		final HashMap<CacheKey,Drawable> targetBitmaps = new HashMap<CacheKey, Drawable>();
		final HashMap<CacheVariant,Drawable> targetNewBitmaps = new HashMap<CacheVariant, Drawable>();
		File downloadToFile = null;
		boolean downloaded = false;
		try {
			setPriority(Thread.MIN_PRIORITY);
			BitmapFactory.Options tmpFileOptions = new BitmapFactory.Options();
			tmpFileOptions.inJustDecodeBounds = false;

			for (int i=0;i<mTargets.size();++i) {
				DownloadTarget target = mTargets.get(i);
				checkAbort();

				target.fileInCache = mCache.getCachedFile(target.mKey);
				boolean bitmapWasInCache = target.fileInCache!=null;
				if (!bitmapWasInCache) {
					// we can't use the older version, download the file and create the stored file again
					if (target.fileInCache!=null)
						target.fileInCache.delete();
					target.fileInCache = mCache.getCachedFilepath(target.mKey);
					if (downloadToFile==null && mCanDownload)
						downloadToFile = new File(mCache.getAvailaibleTempDir(), "tmp_"+target.mKey.getFilename());
				}

				if (target.fileInCache!=null) {
					Drawable displayDrawable;
					if (bitmapWasInCache)
						displayDrawable = new BitmapDrawable(mCache.getContext().getResources(), target.fileInCache.getAbsolutePath());
					else
						displayDrawable = null;

					if (displayDrawable==null) {
						// we don't have that final file yet, use the download file to generate it
						displayDrawable = targetBitmaps.get(target.mKey);
						if (displayDrawable==null) {
							displayDrawable = mCache.loadResourceDrawable(mURL);

							if (displayDrawable!=null) {
								if (target.loadHandler.getStorageTransform()!=null)
									displayDrawable = new BitmapDrawable(target.loadHandler.getStorageTransform().transformBitmapForStorage(ViewLoader.drawableToBitmap(displayDrawable)));
								else
									bitmapWasInCache = true; // do not store the drawable as a bitmap as it is equal to the source
							}
						}

						if (displayDrawable==null && downloadToFile!=null && !downloaded) {
							try {
								downloaded = downloadInTempFile(downloadToFile);
								if (downloaded) {
									// we need the dimensions of the downloaded file
									tmpFileOptions.inJustDecodeBounds = true;
									BitmapFactory.decodeFile(downloadToFile.getAbsolutePath(), tmpFileOptions);
									if (DEBUG_BITMAP_DOWNLOADER && tmpFileOptions.outHeight <= 0) LogManager.logger.i(PictureCache.LOG_TAG, this+" failed to get dimensions from "+downloadToFile);
								}
							} finally {
								if (!downloaded)
									downloadToFile.delete();
							}
							checkAbort();
						}

						if (downloaded) {
							Bitmap bitmap = BitmapFactory.decodeFile(downloadToFile.getAbsolutePath(), getOutputOptions(tmpFileOptions.outWidth, tmpFileOptions.outHeight, target.mKey));
							if (bitmap!=null) {
								int finalHeight = target.mKey.getBitmapHeight(bitmap.getWidth(), bitmap.getHeight());
								if (finalHeight!=0 && finalHeight != bitmap.getHeight()) {
									//LogManager.logger.v(" source size:"+bmp.getWidth()+"x"+bmp.getHeight());
									Bitmap newBmp = Bitmap.createScaledBitmap(bitmap, (bitmap.getWidth() * finalHeight) / bitmap.getHeight(), finalHeight, true);
									/*if (bitmap!=newBmp)
										bitmap.recycle();*/
									bitmap = newBmp;
								}

								if (target.loadHandler.getStorageTransform()!=null)
									bitmap = target.loadHandler.getStorageTransform().transformBitmapForStorage(bitmap);
								
								displayDrawable = new BitmapDrawable(mCache.getContext().getResources(), bitmap);
							}
						}
					}

					if (displayDrawable!=null) {
						targetBitmaps.put(target.mKey, displayDrawable);
						if (!bitmapWasInCache) {
							CacheVariant variant = new CacheVariant(target.fileInCache, target.mKey);
							targetNewBitmaps.put(variant, displayDrawable);
						}
					} else {
						if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.d(PictureCache.LOG_TAG, this+" failed to get a bitmap for:"+target);
						targetBitmaps.remove(target.mKey);
					}
				}

				if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.i(PictureCache.LOG_TAG, this+" target:"+target+" fileInCache:"+target.fileInCache+" bitmap:"+targetBitmaps.get(target.mKey));
			}
		} catch (OutOfMemoryError e) {
			LogManager.logger.e(PictureCache.LOG_TAG, "Failed to load " + mURL, e);
			mCache.ooHandler.onOutOfMemoryError(e);
			/*} catch (InterruptedException e) {
			LogManager.logger.e(PictureCache.TAG, "Interrupted while loading " + mURL, e);*/
		} catch (AbortDownload e) {
			// do nothing
		} catch (Throwable e) {
			LogManager.logger.e(PictureCache.LOG_TAG, "exception on "+mURL, e);
		} finally {
			try {
				// tell the monitor we are done
				//LogManager.logger.i(PictureCache.TAG, "finished download thread for " + mURL + " bmp:"+bmp + " rbmp:"+rbmp);
				//LogManager.logger.i(PictureCache.TAG, "send display bitmap "+mURL+" aborted:"+abortRequested.get()+" size:"+reqTargets.size());
				//LogManager.logger.i(PictureCache.TAG, "ViewUpdate loop "+mURL+" aborted:"+abortRequested.get()+" size:"+reqTargets.size()+" bmp:"+bmp+" rbmp:"+rbmp);
				synchronized (mTargets) {
					if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.e(PictureCache.LOG_TAG, this+" finished loading targets:"+mTargets+" bitmaps:"+targetBitmaps);

					mAborting = true; // after this point new targets are not OK for this job
					for (DownloadTarget target : mTargets) {
						//LogManager.logger.i(PictureCache.TAG, false, "ViewUpdate "+mURL);
						PictureLoaderHandler j = target.loadHandler;
						Drawable drawable = targetBitmaps.get(target.mKey);

						if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.i(PictureCache.LOG_TAG, this+" display "+drawable+" in "+target.loadHandler+" file:"+target.fileInCache+" key:"+target.mKey);
						if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.v(PictureCache.LOG_TAG, this+"  targets:"+mTargets+" bitmaps:"+targetBitmaps);
						//LogManager.logger.i(PictureCache.TAG, "display "+mURL+" in "+j+" abort:"+abortRequested);
						if (drawable!=null) {
							Bitmap bitmap = ViewLoader.drawableToBitmap(drawable);
							if (j.getDisplayTransform()!=null)
								bitmap = j.getDisplayTransform().transformBitmap(bitmap);

							Drawable cacheableBmp = null;
							if (mCache.mBitmapCache != null && target.loadHandler.canKeepBitmapInMemory(bitmap))
								cacheableBmp = mCache.mBitmapCache.put(PictureCache.keyToBitmapCacheKey(target.mKey, mURL, j), bitmap);

							if (cacheableBmp == null) {
								if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap()==bitmap)
									cacheableBmp = drawable;
								else
									cacheableBmp = new BitmapDrawable(mCache.getContext().getResources(), bitmap);
							}
							j.drawBitmap(cacheableBmp, mURL, mCookie, mCache.postHandler, mCache.mBitmapCache);
						} else
							j.drawDefaultPicture(mURL, mCache.postHandler, mCache.mBitmapCache);
					}
					mTargets.clear();
				}

				if (mMonitor!=null)
					mMonitor.onJobFinishedWithNewBitmaps(this, targetNewBitmaps);
			} finally {
				if (downloadToFile!=null)
					downloadToFile.delete();
			}
		}
	}

	/**
	 * add a handler for when the URL is downloaded and start the download+processing if it wasn't started
	 * @param loadHandler
	 * @param key
	 * @param itemDate
	 * @param lifeSpan
	 * @return
	 */
	boolean addTarget(PictureLoaderHandler loadHandler, CacheKey key, long itemDate, LifeSpan lifeSpan)
	{
		DownloadTarget newTarget = new DownloadTarget(loadHandler, key);
		//LogManager.logger.i(PictureCache.TAG, "add recipient view "+view+" for " + mURL);
		if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.e(PictureCache.LOG_TAG, this+" addTarget "+loadHandler+" key:"+key);
		synchronized (mTargets) {
			if (mAborting) {
				if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.w(PictureCache.LOG_TAG, this+ " is aborting");
				return false;
			}

			if (mTargets.contains(newTarget)) {
				// TODO: update the rounded/rotation status
				if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.d(PictureCache.LOG_TAG, this+" target "+newTarget+" already pending");
				return true;
			}
			mTargets.add(newTarget);

			mCanDownload |= loadHandler.isDownloadAllowed();

			if (mItemDate < itemDate)
				mItemDate = itemDate;

			if (mLifeSpan==null)
				mLifeSpan = lifeSpan;
			else if (mLifeSpan.compare(lifeSpan)<0)
				mLifeSpan = lifeSpan;
		}

		if (!mIsThreadStarted) {
			mIsThreadStarted = true;
			if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.d(PictureCache.LOG_TAG, this+" start loading thread");
			//LogManager.logger.i(PictureCache.TAG, "start download job for " + mURL);
			start();
		}
		return true;
	}

	boolean removeTarget(PictureLoaderHandler target) {
		synchronized (mTargets) {

			boolean deleted = false;
			if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.e(PictureCache.LOG_TAG, this+" removeTarget "+target);
			for (int i=0;i<mTargets.size();++i) {
				if (mTargets.get(i).loadHandler.equals(target)) {
					deleted = mTargets.remove(i)!=null;
					break;
				}
			}

			if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.e(PictureCache.LOG_TAG, this+" removeTarget "+target+" = "+deleted+" remains:"+mTargets.size());
			if (deleted) {
				//LogManager.logger.v(" deleted job view:"+target+" for "+mURL);
				//target.setLoadingURL(mCache, mURL);
				target.drawDefaultPicture(mURL, mCache.postHandler, mCache.mBitmapCache);
			}
			//else LogManager.logger.i(PictureCache.TAG, " keep downloading URL:" + mURL + " remaining views:" + reqViews.size() + " like view:"+reqViews.get(0));
			return deleted;
		}
	}

	private BitmapFactory.Options getOutputOptions(int srcWidth, int srcHeight, CacheKey key) {
		BitmapFactory.Options opts = new BitmapFactory.Options();
		if (srcHeight <= 0) {
			LogManager.logger.i(PictureCache.LOG_TAG, "could not get the dimension for " + mURL+" use raw decoding");
		} else {
			int finalHeight = key.getBitmapHeight(srcWidth, srcHeight);

			if (finalHeight>0 && srcHeight > finalHeight*2) {
				//LogManager.logger.e(PictureCache.TAG, " Picture scaling by: " + scale +" from Height:" + opts.outHeight + " to "+finalHeight+" for "+mURL);
				opts.inSampleSize = (int) FloatMath.floor((float)srcHeight / finalHeight);
			}
			else opts.inSampleSize = 1;
		}
		opts.inInputShareable = true;
		opts.inPurgeable = true;

		return opts;
	}

	private static class AbortDownload extends RuntimeException {
		private static final long serialVersionUID = 5568245153235248681L;
	}

	private void checkAbort() {
		synchronized (mTargets) {
			if (mTargets.isEmpty()) {
				if (DEBUG_BITMAP_DOWNLOADER) LogManager.logger.i(PictureCache.LOG_TAG, this+ " no more targets, aborting");
				mAborting = true;
				throw new AbortDownload();
			}
		}
	}

	private boolean downloadInTempFile(File tmpFile) {
		//LogManager.logger.i(PictureCache.TAG, "loading "+mURL);
		InputStream is = null;
		try {
			try {
				Uri dataUri = Uri.parse(mURL);
				is = mCache.getContext().getContentResolver().openInputStream(dataUri);
				//LogManager.logger.v("using the content resolver for "+mURL);
			} catch (FileNotFoundException e) {
				//LogManager.logger.d(PictureCache.TAG, false, "cache error trying ContentResolver on "+mURL);
				URL url = new URL(mURL);
				URLConnection conn = url.openConnection();
				conn.setConnectTimeout(CONNECT_TIMEOUT_DL);
				conn.setUseCaches(false);
				conn.setRequestProperty("Accept-Encoding", "identity");
				//LogManager.logger.e(PictureCache.TAG, conn.getContentEncoding()+" encoding for "+mURL);
				checkAbort();
				try {
					is = conn.getInputStream();
				} catch (FileNotFoundException fe) {
					LogManager.logger.i(PictureCache.LOG_TAG, "cache URL not found "+mURL);
				} catch (Exception ee) {
					LogManager.logger.w(PictureCache.LOG_TAG, "cache error opening "+mURL, ee);
				}
			}

			if (is==null) {
				LogManager.logger.d(PictureCache.LOG_TAG, "impossible to get a stream for "+mURL);
				return false;
			}

			checkAbort();
			// store the stream in a temp file
			BufferedInputStream bis = new BufferedInputStream(is);
			FileOutputStream out = new FileOutputStream(tmpFile);
			try {
				byte[] data = new byte[1422];
				int readAmount = is.read(data);
				while (readAmount >= 0) {
					out.write(data, 0, readAmount);
					checkAbort();
					readAmount = is.read(data);
				}
				return true;
			} finally {
				bis.close();
				out.flush();
				out.close();
			}

			//LogManager.logger.v(" got direct:"+bmp);
		} catch (MalformedURLException e) {
			LogManager.logger.w(PictureCache.LOG_TAG, "bad URL " + mURL, e);
		} catch (UnknownHostException e) {
			LogManager.logger.w(PictureCache.LOG_TAG, "host not found in "+mURL, e);
		} catch (OutOfMemoryError e) {
			LogManager.logger.w(PictureCache.LOG_TAG, "Could not decode image " + mURL, e);
			mCache.ooHandler.onOutOfMemoryError(e);
		} catch (IOException e) {
			LogManager.logger.e(PictureCache.LOG_TAG, "Could not read " + mURL, e);
		} finally {
			try {
				if (is!=null)
					is.close();
			} catch (IOException e) {
				LogManager.logger.e(PictureCache.LOG_TAG, "Could not close " + is, e);
			}
		}
		return false;
	}
}