/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho.widget;

import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.OnScrollListener;
import android.support.v7.widget.RecyclerView.ViewHolder;
import com.facebook.litho.widget.ViewportInfo.ViewportChanged;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.concurrent.ThreadSafe;

/**
 * This class will handle all viewport changes due to both scrolling and
 * {@link ViewHolder} removal that is not related to scrolling.
 *
 * Classes that are interested to have its viewport changes handled by {@link ViewportManager}
 * should set the {@link OnScrollListener} returned from {@link ViewportManager#getScrollListener()}
 * in the {@link RecyclerView}
 */
@ThreadSafe
final class ViewportManager {

  private int mCurrentFirstVisiblePosition;
  private int mCurrentLastVisiblePosition;
  private int mCurrentFirstFullyVisiblePosition;
  private int mCurrentLastFullyVisiblePosition;
  private int mTotalItemCount;
  private boolean mIsDataChangedVisible;

  @Nullable private List<ViewportChanged> mViewportChangedListeners;

  private final LayoutInfo mLayoutInfo;
  private final Handler mMainThreadHandler;
  private final ViewportScrollListener mViewportScrollListener = new ViewportScrollListener();
  private final Runnable mViewportChangedRunnable =
      new Runnable() {
        @Override
        public void run() {
          onViewportChanged(ViewportInfo.State.DATA_CHANGES);
        }
      };

  ViewportManager(
      int currentFirstVisiblePosition,
      int currentLastVisiblePosition,
      LayoutInfo layoutInfo,
      Handler mainThreadHandler) {
    mCurrentFirstVisiblePosition = currentFirstVisiblePosition;
    mCurrentLastVisiblePosition = currentLastVisiblePosition;
    mCurrentFirstFullyVisiblePosition = layoutInfo.findFirstFullyVisibleItemPosition();
    mCurrentLastFullyVisiblePosition = layoutInfo.findLastFullyVisibleItemPosition();
    mTotalItemCount = layoutInfo.getItemCount();
    mLayoutInfo = layoutInfo;
    mMainThreadHandler = mainThreadHandler;
  }

  /**
   * Handles a change in viewport. This method should not be called outside of the method {@link
   * OnScrollListener#onScrolled(RecyclerView, int, int)}
   */
  @UiThread
  void onViewportChanged(@ViewportInfo.State int state) {
    final int firstVisiblePosition = mLayoutInfo.findFirstVisibleItemPosition();
    final int lastVisiblePosition = mLayoutInfo.findLastVisibleItemPosition();
    final int firstFullyVisibleItemPosition = mLayoutInfo.findFirstFullyVisibleItemPosition();
    final int lastFullyVisibleItemPosition = mLayoutInfo.findLastFullyVisibleItemPosition();
    final int totalItemCount = mLayoutInfo.getItemCount();

    if (firstVisiblePosition < 0 || lastVisiblePosition < 0) {
      return;
    }

    if (firstVisiblePosition == mCurrentFirstVisiblePosition
        && lastVisiblePosition == mCurrentLastVisiblePosition
        && firstFullyVisibleItemPosition == mCurrentFirstFullyVisiblePosition
        && lastFullyVisibleItemPosition == mCurrentLastFullyVisiblePosition
        && totalItemCount == mTotalItemCount
        && state != ViewportInfo.State.DATA_CHANGES) {
      return;
    }

    mCurrentFirstVisiblePosition = firstVisiblePosition;
    mCurrentLastVisiblePosition = lastVisiblePosition;
    mCurrentFirstFullyVisiblePosition = firstFullyVisibleItemPosition;
    mCurrentLastFullyVisiblePosition = lastFullyVisibleItemPosition;
    mTotalItemCount = totalItemCount;

    if (mViewportChangedListeners == null || mViewportChangedListeners.isEmpty()) {
      return;
    }


    for (ViewportChanged viewportChangedListener : mViewportChangedListeners) {
      viewportChangedListener.viewportChanged(
          firstVisiblePosition,
          lastVisiblePosition,
          firstFullyVisibleItemPosition,
          lastFullyVisibleItemPosition,
          state);
    }

    resetDataChangedIsVisible();
  }

  @UiThread
  void setDataChangedIsVisible(boolean isUpdated) {
    mIsDataChangedVisible = mIsDataChangedVisible || isUpdated;
    if (mIsDataChangedVisible) {
      putViewportChangedRunnableToEndOfUIThreadQueue();
    }
  }

  @UiThread
  void resetDataChangedIsVisible() {
    mIsDataChangedVisible = false;
  }

  @UiThread
  boolean isInsertInVisibleRange(int position, int size, int viewportCount) {
    if (shouldForceDataChangedIsVisibleToTrue() || viewportCount == -1) {
      return true;
    }

    final int lastPosition =
        (mCurrentFirstVisiblePosition + viewportCount - 1 > mCurrentLastVisiblePosition)
            ? mCurrentFirstVisiblePosition + viewportCount - 1
            : mCurrentLastVisiblePosition;

    for (int index = position; index < position + size; ++index) {
      if (mCurrentFirstVisiblePosition <= index && index <= lastPosition) {
        return true;
      }
    }

    return false;
  }

  @UiThread
  boolean isUpdateInVisibleRange(int position, int size) {
    if (shouldForceDataChangedIsVisibleToTrue()) {
      return true;
    }

    for (int index = position; index < position + size; ++index) {
      if (mCurrentFirstVisiblePosition <= index && index <= mCurrentLastVisiblePosition) {
        return true;
      }
    }

    return false;
  }

  @UiThread
  boolean isMoveInVisibleRange(int fromPosition, int toPosition, int viewportCount) {
    if (shouldForceDataChangedIsVisibleToTrue() || viewportCount == -1) {
      return true;
    }

    final boolean isNewPositionInVisibleRange =
        toPosition >= mCurrentFirstVisiblePosition
            && toPosition <= mCurrentFirstVisiblePosition + viewportCount - 1;

    final boolean isOldPositionInVisibleRange =
        fromPosition >= mCurrentFirstVisiblePosition
            && fromPosition <= mCurrentFirstVisiblePosition + viewportCount - 1;

    return isNewPositionInVisibleRange || isOldPositionInVisibleRange;
  }

  @UiThread
  boolean isRemoveInVisibleRange(int position, int size) {
    if (shouldForceDataChangedIsVisibleToTrue()) {
      return true;
    }

    for (int index = position; index < position + size; ++index) {
      if (mCurrentFirstVisiblePosition <= index && index <= mCurrentLastVisiblePosition) {
        return true;
      }
    }

    return false;
  }

  private boolean shouldForceDataChangedIsVisibleToTrue() {
    return mCurrentFirstVisiblePosition < 0
        || mCurrentLastVisiblePosition < 0
        || mIsDataChangedVisible;
  }

  private void putViewportChangedRunnableToEndOfUIThreadQueue() {
    mMainThreadHandler.removeCallbacks(mViewportChangedRunnable);
    mMainThreadHandler.post(mViewportChangedRunnable);
  }

  @UiThread
  void addViewportChangedListener(@Nullable ViewportChanged viewportChangedListener) {
    if (viewportChangedListener == null) {
      return;
    }

    if (mViewportChangedListeners == null) {
      mViewportChangedListeners = new ArrayList<>(2);
    }

    mViewportChangedListeners.add(viewportChangedListener);
  }

  @UiThread
  void removeViewportChangedListener(@Nullable ViewportChanged viewportChangedListener) {
    if (viewportChangedListener == null || mViewportChangedListeners == null) {
      return;
    }

    mViewportChangedListeners.remove(viewportChangedListener);
  }

  @UiThread
  ViewportScrollListener getScrollListener() {
    return mViewportScrollListener;
  }

  private class ViewportScrollListener extends RecyclerView.OnScrollListener {

    @Override
    public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
      onViewportChanged(ViewportInfo.State.SCROLLING);
    }
  }
}
