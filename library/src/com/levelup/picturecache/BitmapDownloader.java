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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.FloatMath;

import com.levelup.OOMHandler;
import com.levelup.Utils;

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
		abstract void onJobFinishedWithNewBitmaps(BitmapDownloader job, HashMap<CacheVariant,Bitmap> newBitmaps);
	}

	private final String mURL;
	private final PictureCache mCache;
	private final CopyOnWriteArrayList<DownloadTarget> mTargets = new CopyOnWriteArrayList<DownloadTarget>();

	private JobMonitor mMonitor;

	// locked by mTargets
	/** see {@link CacheType} values */
	private Integer mType;
	private long mItemDate;

	private boolean mCanDownload;
	private boolean mIsThreadStarted;
	private boolean mAborting;

	private static final int CONNECT_TIMEOUT_DL = 10000; // 10s

	BitmapDownloader(String URL, PictureCache cache) {
		if (URL==null) throw new NullPointerException("How are we supposed to download a null URL?");
		mURL = URL;
		mCache = cache;
		setName("PictureDL-"+mURL.hashCode());
	}

	void setMonitor(JobMonitor monitor) {
		mMonitor = monitor;
	}

	String getURL() {
		return mURL;
	}
	int getType() {
		return mType;
	}
	long getItemDate() {
		return mItemDate;
	}

	@Override
	public String toString() {
		return "BitmapLoader:"+mURL+"@"+super.hashCode();
	}

	public void run() {
		//mCache.getLogger().v( "start image load in cache: " + mURL);
		final HashMap<CacheKey,Bitmap> targetBitmaps = new HashMap<CacheKey, Bitmap>();
		final HashMap<CacheVariant,Bitmap> targetNewBitmaps = new HashMap<CacheVariant, Bitmap>();
		File downloadToFile = null;
		boolean downloaded = false;
		try {
			setPriority(Thread.MIN_PRIORITY);
			BitmapFactory.Options tmpFileOptions = new BitmapFactory.Options();
			tmpFileOptions.inJustDecodeBounds = false;

			for (int i=0;i<mTargets.size();++i) {
				DownloadTarget target = mTargets.get(i);
				checkAbort();

				target.fileInCache = mCache.getCachedFile(target.mKey, mURL, mItemDate);
				final boolean bitmapWasInCache = target.fileInCache!=null && target.fileInCache.exists() && target.fileInCache.canRead();
				if (!bitmapWasInCache) {
					// we can't use the older version, download the file and create the stored file again
					if (target.fileInCache!=null)
						target.fileInCache.delete();
					target.fileInCache = mCache.getCachedFilepath(target.mKey);
					if (downloadToFile==null && mCanDownload)
						downloadToFile = new File(mCache.getAvailaibleTempDir(), "tmp_"+target.mKey.getFilename());
				}

				if (target.fileInCache!=null) {
					Bitmap bitmap = targetBitmaps.get(target.mKey);
					if (bitmap==null && bitmapWasInCache)
						bitmap = BitmapFactory.decodeFile(target.fileInCache.getAbsolutePath());

					if (bitmap==null) {
						// we don't have that final file yet, use the download file to generate it
						if (downloadToFile!=null && !downloaded) {
							try {
								downloaded = downloadInTempFile(downloadToFile);
								if (downloaded) {
									// we need the dimensions of the downloaded file
									tmpFileOptions.inJustDecodeBounds = true;
									BitmapFactory.decodeFile(downloadToFile.getAbsolutePath(), tmpFileOptions);
								}
							} finally {
								if (!downloaded)
									downloadToFile.delete();
							}
							checkAbort();
						}

						if (downloaded) {
							bitmap = BitmapFactory.decodeFile(downloadToFile.getAbsolutePath(), getOutputOptions(tmpFileOptions.outWidth, tmpFileOptions.outHeight, target.mKey));
							if (bitmap!=null) {
								int finalHeight = target.mKey.getBitmapHeight(bitmap.getWidth(), bitmap.getHeight());
								if (finalHeight!=0 && finalHeight != bitmap.getHeight()) {
									//mCache.getLogger().v(" source size:"+bmp.getWidth()+"x"+bmp.getHeight());
									Bitmap newBmp = Bitmap.createScaledBitmap(bitmap, (bitmap.getWidth() * finalHeight) / bitmap.getHeight(), finalHeight, true);
									/*if (bitmap!=newBmp)
										bitmap.recycle();*/
									bitmap = newBmp;
								}

								if (target.loadHandler.getStorageTransform()!=null)
									bitmap = target.loadHandler.getStorageTransform().transformBitmapForStorage(bitmap);
							}
						}
					}

					if (bitmap!=null) {
						targetBitmaps.put(target.mKey, bitmap);
						if (!bitmapWasInCache) {
							CacheVariant variant = new CacheVariant(target.fileInCache, target.mKey);
							targetNewBitmaps.put(variant, bitmap);
						}
					} else
						targetBitmaps.remove(target.mKey);
				}

				if (DEBUG_BITMAP_DOWNLOADER) mCache.getLogger().i(this+" target:"+target+" fileInCache:"+target.fileInCache+" bitmap:"+targetBitmaps.get(target.mKey));
			}
		} catch (OutOfMemoryError e) {
			mCache.getLogger().e("Failed to load " + mURL, e);
			OOMHandler.handleOutOfMemory(mCache.getContext(), mCache.postHandler, e);
			/*} catch (InterruptedException e) {
			mCache.getLogger().e("Interrupted while loading " + mURL, e);*/
		} catch (AbortDownload e) {
			// do nothing
		} catch (Throwable e) {
			mCache.getLogger().e("exception on "+mURL, e);
		} finally {
			try {
				// tell the monitor we are done
				//mCache.getLogger().i("finished download thread for " + mURL + " bmp:"+bmp + " rbmp:"+rbmp);
				//mCache.getLogger().i("send display bitmap "+mURL+" aborted:"+abortRequested.get()+" size:"+reqTargets.size());
				//mCache.getLogger().i("ViewUpdate loop "+mURL+" aborted:"+abortRequested.get()+" size:"+reqTargets.size()+" bmp:"+bmp+" rbmp:"+rbmp);
				synchronized (mTargets) {
					if (DEBUG_BITMAP_DOWNLOADER) mCache.getLogger().e(this+" finished loading targets:"+mTargets+" bitmaps:"+targetBitmaps);

					mAborting = true; // after this point new targets are not OK for this job
					for (DownloadTarget target : mTargets) {
						//mCache.getLogger().i(false, "ViewUpdate "+mURL);
						PictureLoaderHandler j = target.loadHandler;
						Bitmap bitmap = null;
						if (target.fileInCache!=null)
							bitmap = targetBitmaps.get(target.mKey);

						if (DEBUG_BITMAP_DOWNLOADER) mCache.getLogger().i(this+" display "+bitmap+" in "+target.loadHandler);
						//mCache.getLogger().i("display "+mURL+" in "+j+" abort:"+abortRequested);
						if (bitmap!=null) {
							if (j.getDisplayTransform()!=null)
								bitmap = j.getDisplayTransform().transformBitmap(bitmap);
							j.drawBitmap(bitmap, mURL, mCache.postHandler, mCache.getLogger());
						} else
							j.drawDefaultPicture(mURL, mCache.postHandler, mCache.getLogger());
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
	 * @param type
	 * @return
	 */
	boolean addTarget(PictureLoaderHandler loadHandler, CacheKey key, long itemDate, int type)
	{
		DownloadTarget newTarget = new DownloadTarget(loadHandler, key);
		//mCache.getLogger().i("add recipient view "+view+" for " + mURL);
		if (DEBUG_BITMAP_DOWNLOADER) mCache.getLogger().e(this+" addTarget "+loadHandler+" key:"+key);
		synchronized (mTargets) {
			if (mAborting) {
				if (DEBUG_BITMAP_DOWNLOADER) mCache.getLogger().e(this+ "is aborting");
				return false;
			}

			if (mTargets.contains(newTarget)) {
				// TODO: update the rounded/rotation status
				if (DEBUG_BITMAP_DOWNLOADER) mCache.getLogger().d(this+" target "+newTarget+" already pending");
				return true;
			}
			mTargets.add(newTarget);

			mCanDownload |= loadHandler.isDownloadAllowed();

			if (mItemDate < itemDate)
				mItemDate = itemDate;
			if (mType==null || CacheType.isStrictlyLowerThan(mType, type))
				mType = Integer.valueOf(type);
		}

		if (!mIsThreadStarted) {
			mIsThreadStarted = true;
			if (DEBUG_BITMAP_DOWNLOADER) mCache.getLogger().d(this+" start thread");
			//mCache.getLogger().i("start download job for " + mURL);
			start();
		}
		return true;
	}

	boolean removeTarget(PictureLoaderHandler target)
	{
		synchronized (mTargets) {

			boolean deleted = false;
			if (DEBUG_BITMAP_DOWNLOADER) mCache.getLogger().e(this+" removeTarget "+target);
			for (int i=0;i<mTargets.size();++i) {
				if (mTargets.get(i).loadHandler.equals(target)) {
					deleted = mTargets.remove(i)!=null;
					break;
				}
			}

			if (DEBUG_BITMAP_DOWNLOADER) mCache.getLogger().e(this+" removeTarget "+target+" = "+deleted+" remains:"+mTargets.size());
			if (deleted) {
				//mCache.getLogger().v(" deleted job view:"+target+" for "+mURL);
				//target.setLoadingURL(mCache, mURL);
				target.drawDefaultPicture(mURL, mCache.postHandler, mCache.getLogger());
			}
			//else mCache.getLogger().i(" keep downloading URL:" + mURL + " remaining views:" + reqViews.size() + " like view:"+reqViews.get(0));
			return deleted;
		}
	}

	private BitmapFactory.Options getOutputOptions(int srcWidth, int srcHeight, CacheKey key) {
		BitmapFactory.Options opts = new BitmapFactory.Options();
		if (srcHeight <= 0) {
			mCache.getLogger().i("could not get the dimension for " + mURL+" use raw decoding");
		} else {
			int finalHeight = key.getBitmapHeight(srcWidth, srcHeight);

			if (srcHeight > finalHeight*2) {
				//mCache.getLogger().e(" Picture scaling by: " + scale +" from Height:" + opts.outHeight + " to "+finalHeight+" for "+mURL);
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
				mAborting = true;
				throw new AbortDownload();
			}
		}
	}

	private boolean downloadInTempFile(File tmpFile) {
		//mCache.getLogger().i("loading "+mURL);
		InputStream is = null;
		try {
			try {
				Uri dataUri = Uri.parse(mURL);
				is = mCache.getContext().getContentResolver().openInputStream(dataUri);
				//mCache.getLogger().v("using the content resolver for "+mURL);
			} catch (FileNotFoundException e) {
				//mCache.getLogger().d(false, "cache error trying ContentResolver on "+mURL);
				URL url = new URL(mURL);
				URLConnection conn = Utils.getUrlConnection(mCache.getContext(), url);
				conn.setConnectTimeout(CONNECT_TIMEOUT_DL);
				conn.setUseCaches(false);
				conn.setRequestProperty("Accept-Encoding", "identity");
				//mCache.getLogger().e(conn.getContentEncoding()+" encoding for "+mURL);
				checkAbort();
				try {
					is = conn.getInputStream();
				} catch (FileNotFoundException fe) {
					mCache.getLogger().i("cache URL not found "+mURL);
					return false;
				} catch (Exception ee) {
					mCache.getLogger().w("cache error opening "+mURL, ee);
					return false;
				}
			}

			if (is==null) {
				mCache.getLogger().w("impossible to get a stream for "+mURL);
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

			//mCache.getLogger().v(" got direct:"+bmp);
		} catch (MalformedURLException e) {
			mCache.getLogger().w("bad URL " + mURL, e);
		} catch (UnknownHostException e) {
			mCache.getLogger().w("host not found in "+mURL, e);
		} catch (OutOfMemoryError e) {
			mCache.getLogger().w("Could not decode image " + mURL, e);
			OOMHandler.handleOutOfMemory(mCache.getContext(), mCache.postHandler, e);
		} catch (IOException e) {
			mCache.getLogger().e("Could not read " + mURL, e);
		} finally {
			try {
				if (is!=null)
					is.close();
			} catch (IOException e) {
				mCache.getLogger().e("Could not close " + is, e);
			}
		}
		return false;
	}
}