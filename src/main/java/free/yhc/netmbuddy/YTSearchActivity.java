/******************************************************************************
 * Copyright (C) 2012, 2013, 2014, 2015, 2016
 * Younghyung Cho. <yhcting77@gmail.com>
 * All rights reserved.
 *
 * This file is part of NetMBuddy
 *
 * This program is licensed under the FreeBSD license
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation
 * are those of the authors and should not be interpreted as representing
 * official policies, either expressed or implied, of the FreeBSD Project.
 *****************************************************************************/

package free.yhc.netmbuddy;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.net.ConnectException;

import free.yhc.abaselib.AppEnv;
import free.yhc.baselib.Logger;
import free.yhc.baselib.exception.BadResponseException;
import free.yhc.abaselib.util.AUtil;
import free.yhc.netmbuddy.core.PolicyConstant;
import free.yhc.netmbuddy.core.SearchSuggestionProvider;
import free.yhc.netmbuddy.core.TaskManager;
import free.yhc.netmbuddy.core.YTDataAdapter;
import free.yhc.netmbuddy.core.YTPlayer;
import free.yhc.netmbuddy.task.YTVideoListTask;

public abstract class YTSearchActivity extends Activity {
    private static final boolean DBG = Logger.DBG_DEFAULT;
    private static final Logger P = Logger.create(YTSearchActivity.class, Logger.LOGLV_DEFAULT);

    public static final String KEY_TITLE = "searctitle";
    public static final String KEY_TEXT = "searchtext";
    public static final String KEY_CUR_PAGETOKEN = "cur_pagetoken";
    public static final String KEY_NEXT_PAGETOKEN = "next_pagetoken";
    public static final String KEY_PREV_PAGETOKEN = "prev_pagetoken";

    protected final YTPlayer mMp = YTPlayer.get();
    protected final TaskManager mTm = TaskManager.get();
    protected ListView mListv = null;

    private String mText = null;
    private String mTitle = null;
    private String mCurPageToken = null;
    private String mNextPageToken = null;
    private String mPrevPageToken = null;
    private YTVideoListTask mVLTask = null;

    private final YTVideoListTask.EventListener<YTVideoListTask, YTDataAdapter.VideoListResp> mVLTaskListener
            = new YTVideoListTask.EventListener<YTVideoListTask, YTDataAdapter.VideoListResp>() {
        @Override
        public void
        onPostRun(@NonNull YTVideoListTask task,
                  YTDataAdapter.VideoListResp result,
                  Exception ex) {
            Err err = Err.NO_ERR;
            if (null != ex) {
                /* This may be slower than instanceof... But, it's easy to read
                 * This is literally, exceptional case. So, it doesn't happened frequently.
                 */
                try { throw ex; }
                catch (ConnectException e) {
                    err = Err.NETWORK_UNAVAILABLE;
                } catch (IOException | BadResponseException e) {
                    err = Err.IO_NET;
                } catch (InterruptedException e) {
                    err = Err.CANCELLED;
                } catch (Exception e) {
                    if (DBG) P.w(P.stackTrace(e));
                    err = Err.UNKNOWN;
                }
            }
            handleYtVideoListResponse(task, task.getRequest(), result, err);
        }
    };

    // ========================================================================
    //
    // UI Control
    //
    // ========================================================================
    protected void
    setImageView(ImageView iv,
                int drawable,
                View.OnClickListener onClick) {
        if (drawable <= 0 || null == onClick) {
            iv.setVisibility(View.GONE);
            iv.setOnClickListener(null);
        } else {
            iv.setImageResource(drawable);
            iv.setVisibility(View.VISIBLE);
            iv.setOnClickListener(onClick);
        }
    }

    protected void
    setTitleText(String text) {
        TextView tv = (TextView)findViewById(R.id.title);
        tv.setText(text);
    }

    @SuppressWarnings("unused")
    protected void
    setTitleText(int resid) {
        TextView tv = (TextView)findViewById(R.id.title);
        tv.setText(resid);
    }

    protected ViewGroup
    disableContent() {
        ViewGroup content = (ViewGroup)findViewById(R.id.content);
        TextView tv = (TextView)content.findViewById(R.id.content_txt);
        ImageView iv = (ImageView)content.findViewById(R.id.content_loading);
        ListView lv = (ListView)content.findViewById(R.id.content_list);
        tv.setVisibility(View.INVISIBLE);
        if (null != iv.getAnimation()) {
            iv.getAnimation().cancel();
            iv.setAnimation(null);
        }
        iv.setVisibility(View.INVISIBLE);
        lv.setVisibility(View.INVISIBLE);
        content.setVisibility(View.INVISIBLE);
        return content;
    }

    protected TextView
    enableContentText(int resid) {
        return enableContentText(AUtil.getResString(resid));
    }

    protected TextView
    enableContentText(String text) {
        View content = disableContent();
        content.setVisibility(View.VISIBLE);
        TextView tv = (TextView)content.findViewById(R.id.content_txt);
        tv.setVisibility(View.VISIBLE);
        tv.setText(text);
        return tv;
    }

    protected ImageView
    enableContentLoading() {
        View content = disableContent();
        content.setVisibility(View.VISIBLE);
        ImageView iv = (ImageView)content.findViewById(R.id.content_loading);
        iv.setVisibility(View.VISIBLE);
        iv.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate));
        return iv;
    }

    protected ListView
    enableContentList() {
        View content = disableContent();
        content.setVisibility(View.VISIBLE);
        mListv.setVisibility(View.VISIBLE);
        return mListv;
    }

    private ViewGroup
    disableNaviBar() {
        LinearLayout nb = (LinearLayout)findViewById(R.id.navibar);
        nb.setVisibility(View.INVISIBLE);
        return nb;
    }

    protected ViewGroup
    enableNaviBar(boolean prev, String text, boolean next) {
        LinearLayout nb = (LinearLayout)findViewById(R.id.navibar);
        nb.setVisibility(View.VISIBLE);
        TextView tv = (TextView)nb.findViewById(R.id.navi_current);
        tv.setText(text);
        nb.findViewById(R.id.navi_prev).setVisibility(prev ? View.VISIBLE : View.INVISIBLE);
        nb.findViewById(R.id.navi_next).setVisibility(next ? View.VISIBLE : View.INVISIBLE);
        return nb;
    }

    protected void
    setupToolBtn1(int drawable,
                  View.OnClickListener onClick) {
        setImageView((ImageView) findViewById(R.id.toolbtn1), drawable, onClick);
    }

    protected void
    setupToolBtn2(int drawable,
                  View.OnClickListener onClick) {
        setImageView((ImageView) findViewById(R.id.toolbtn2), drawable, onClick);
    }

    protected void
    setupBottomBar(int tool1Drawable,
                   View.OnClickListener tool1OnClick,
                   int tool2Drawable,
                   View.OnClickListener tool2OnClick) {
        setupToolBtn1(tool1Drawable, tool1OnClick);
        setupToolBtn2(tool2Drawable, tool2OnClick);
    }

    @SuppressWarnings("unused")
    @Nullable
    private YTSearchAdapter
    getAdapter() {
        if (null != mListv)
            return (YTSearchAdapter)mListv.getAdapter();
        return null;
    }

    // ========================================================================
    //
    //
    //
    // ========================================================================
    protected abstract YTDataAdapter.ReqType
    getSearchType();

    protected abstract void
    onListItemClick(View view, int position, long itemId);

    protected abstract void
    onSearchResponse(@NonNull YTVideoListTask ytvl,
                     @NonNull YTDataAdapter.VideoListReq req,
                     @NonNull YTDataAdapter.VideoListResp resp,
                     @NonNull Err err);

    // ========================================================================
    //
    //
    //
    // ========================================================================
    private static String
    searchTypeString(YTDataAdapter.ReqType reqType) {
        switch (reqType) {
        case VID_KEYWORD:
            return AUtil.getResString(R.string.keyword);
        case VID_CHANNEL:
            return AUtil.getResString(R.string.channel);
        }
        P.bug(false);
        return null;
    }


    private void
    handleYtVideoListResponse(
            @NonNull YTVideoListTask ytvl,
            @NonNull YTDataAdapter.VideoListReq req,
            @NonNull YTDataAdapter.VideoListResp resp,
            @NonNull Err err) {
        P.bug(null != mVLTask);
        if (mVLTask != ytvl)
            return; // new Task is already started! Ignore this result.
        if (!handleSearchResult(ytvl, req, resp, err))
            return; // There is an error in search
        onSearchResponse(ytvl, req, resp, err);
    }

    private boolean
    handleSearchResult(@NonNull @SuppressWarnings("unused") YTVideoListTask ytvl,
                       @NonNull @SuppressWarnings("unused") YTDataAdapter.VideoListReq req,
                       @NonNull YTDataAdapter.VideoListResp resp,
                       @NonNull Err err) {
        P.bug(AUtil.isUiThread());
        if (Err.NO_ERR == err
            && resp.vids.length <= 0)
            err = Err.NO_MATCH;

        if (Err.NO_ERR != err) {
            mPrevPageToken = mNextPageToken = null;
            enableContentText(err.getMessage());
            return false;
        }
        mPrevPageToken = resp.page.prevPageToken;
        mNextPageToken = resp.page.nextPageToken;
        enableNaviBar(null != mPrevPageToken, "", null != mNextPageToken);
        return true;
    }

    private void
    loadPage(String title, String text, String pageToken) {
        disableNaviBar();
        // close helper to cancel all existing work.
        if (null != mVLTask) {
            mVLTask.removeEventListener(mVLTaskListener);
            mTm.cancelTask(mVLTask, null);
        }

        mTitle = title;
        mText = text;
        mCurPageToken = pageToken;
        YTDataAdapter.VideoListReq ytreq = new YTDataAdapter.VideoListReq(
                getSearchType(),
                text,
                pageToken,
                PolicyConstant.YTSEARCH_MAX_RESULTS);
        mVLTask  = YTVideoListTask.create(ytreq, null);
        mVLTask.addEventListener(AppEnv.getUiHandlerAdapter(), mVLTaskListener);
        setTitleText(searchTypeString(getSearchType()) + " : " + title);
        if (mTm.addTask(mVLTask,
                        mVLTask, // id
                        this, // type
                        null))
            enableContentLoading();
        else
            enableContentText(Err.UNKNOWN.getMessage());
    }

    protected void
    doNewSearch() {
        onSearchRequested();
    }

    protected void
    startNewSearch(final String title, final String text) {
        loadPage(title, text, "");
    }

    // ========================================================================
    //
    // Overriding activity
    //
    // ========================================================================
    @SuppressWarnings("unused")
    private void
    restoreInstanceState(Bundle b) {
        if (null == b)
            return;
        mText = b.getString(KEY_TEXT);
        mCurPageToken = b.getString(KEY_CUR_PAGETOKEN);
        mPrevPageToken = b.getString(KEY_PREV_PAGETOKEN);
        mNextPageToken = b.getString(KEY_NEXT_PAGETOKEN);
    }

    private static void
    putBundleString(Bundle b, String key, String value) {
        if (null != value)
            b.putString(key, value);
    }

    @Override
    public void
    onSaveInstanceState(@NonNull Bundle b) {
        putBundleString(b, KEY_TEXT, mText);
        putBundleString(b, KEY_CUR_PAGETOKEN, mCurPageToken);
        putBundleString(b, KEY_PREV_PAGETOKEN, mPrevPageToken);
        putBundleString(b, KEY_NEXT_PAGETOKEN, mNextPageToken);
    }

    @Override
    public void
    onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
    }

    @Override
    public void
    onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.ytsearch);

        mListv = (ListView)findViewById(R.id.content_list);
        registerForContextMenu(mListv);
        mListv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void
            onItemClick(AdapterView<?> parent, View view, int position, long itemId) {
                onListItemClick(view, position, itemId);
            }
        });

        findViewById(R.id.navi_prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void
            onClick(View v) {
                if (null == mPrevPageToken) {
                    P.bug(false); // This is UNEXPECTED.
                    return;
                }
                loadPage(mTitle, mText, mPrevPageToken);
            }
        });

        findViewById(R.id.navi_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void
            onClick(View v) {
                if (null == mNextPageToken) {
                    P.bug(false); // This is UNEXPECTED.
                    return;
                }
                loadPage(mTitle, mText, mNextPageToken);
            }
        });


        disableContent();
        disableNaviBar();
    }

    @Override
    protected void
    onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!Intent.ACTION_SEARCH.equals(intent.getAction()))
            return; // ignore unexpected intent

        final String query = intent.getStringExtra(SearchManager.QUERY);
        SearchSuggestionProvider.saveRecentQuery(query);
        disableContent();
        disableNaviBar();
        AppEnv.getUiHandler().post(new Runnable() {
            @Override
            public void
            run() {
                startNewSearch(query, query);
            }
        });
    }

    @Override
    protected void
    onResume() {
        // onResume of each fragments SHOULD be called after 'setController'.
        // So, super.onResume() is located at the bottom of onResume().
        super.onResume();

        ViewGroup playerv = (ViewGroup)findViewById(R.id.player);
        mMp.setController(this,
                          playerv,
                          (ViewGroup)findViewById(R.id.list_drawer),
                          null,
                          mMp.getVideoToolButton());
        if (mMp.hasActiveVideo())
            playerv.setVisibility(View.VISIBLE);
        else
            playerv.setVisibility(View.GONE);
    }

    @Override
    protected void
    onPause() {
        mMp.unsetController(this);
        super.onPause();
    }

    @Override
    protected void
    onStop() {
        super.onStop();
    }

    @Override
    protected void
    onDestroy() {
        super.onDestroy();
    }

    @Override
    public void
    onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Do nothing!
    }

    @Override
    public void
    onBackPressed() {
        super.onBackPressed();
    }
}
