package com.levelup.picturecache.loaders;

import android.graphics.drawable.Drawable;
import android.view.View;

public abstract class ViewReference<V extends View> {

	protected abstract void displayDrawable(Drawable drawable);

	private final V view;

	ViewReference(V view) {
		this.view = view;
	}

	protected boolean canDisplay() {
		return true;
	}

	void setImageDrawable(Drawable drawable) {
		if (canDisplay()) {
			displayDrawable(drawable);
		}
	}

	V getImageView() {
		return view;
	}

	ViewLoadingTag getTag() {
		return (ViewLoadingTag) view.getTag();
	}

	void setTag(ViewLoadingTag tag) {
		view.setTag(tag);
	}

	@Override
	public String toString() {
		return "ViewRef:"+view.toString();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ViewReference<?>)) return false;
		return view.equals(((ViewReference<?>) o).view);
	}

	@Override
	public int hashCode() {
		return view.hashCode();
	}
}
