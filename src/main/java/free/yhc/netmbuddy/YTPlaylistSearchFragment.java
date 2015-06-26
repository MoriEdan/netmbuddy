/******************************************************************************
 * Copyright (C) 2012, 2013, 2014
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

import static free.yhc.netmbuddy.utils.Utils.eAssert;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import free.yhc.netmbuddy.db.DB;
import free.yhc.netmbuddy.core.MultiThreadRunner;
import free.yhc.netmbuddy.core.MultiThreadRunner.Job;
import free.yhc.netmbuddy.core.Policy;
import free.yhc.netmbuddy.core.UnexpectedExceptionHandler;
import free.yhc.netmbuddy.core.YTConstants;
import free.yhc.netmbuddy.core.YTFeed;
import free.yhc.netmbuddy.core.YTPlaylistFeed;
import free.yhc.netmbuddy.core.YTSearchHelper;
import free.yhc.netmbuddy.core.YTVideoFeed;
import free.yhc.netmbuddy.utils.UiUtils;
import free.yhc.netmbuddy.utils.Utils;
import free.yhc.netmbuddy.utils.YTUtils;

public class YTPlaylistSearchFragment extends YTSearchFragment implements
UnexpectedExceptionHandler.Evidence {
    private static final boolean DBG = false;
    private static final Utils.Logger P = new Utils.Logger(YTPlaylistSearchFragment.class);

    private static class ImportYtPlaylistResult {
        AtomicInteger nrIgnored = new AtomicInteger(0);
        AtomicInteger nrDone    = new AtomicInteger(0);
    }

    private interface ProgressListener {
        void onProgress(int percent);
    }

    private YTPlaylistSearchActivity
    getMyActivity() {
        return (YTPlaylistSearchActivity)getActivity();
    }

    private YTPlaylistSearchAdapter
    getAdapter() {
        return (YTPlaylistSearchAdapter)mListv.getAdapter();
    }

    private void
    checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException();
    }

    private boolean
    insertVideoToPlaylist(long plid, YTVideoFeed.Entry e) {
        int playtm = 0;
        try {
             playtm = Integer.parseInt(e.media.playTime);
        } catch (NumberFormatException ex) {
            return false;
        }

        return YTUtils.insertVideoToPlaylist(plid,
                                             e.media.videoId,
                                             e.media.title,
                                             e.author.name,
                                             playtm,
                                             Policy.DEFAULT_VIDEO_VOLUME);
    }

    private Err
    doBackgroundImportYtPlaylist(final ImportYtPlaylistResult mtpr,
                                 MultiThreadRunner mtrunner,
                                 final long plid,
                                 final String ytplid,
                                 final ProgressListener progl)
        throws InterruptedException {
        DB db = DB.get();
        YTSearchHelper.SearchArg sarg = new YTSearchHelper.SearchArg(
                null,
                YTSearchHelper.SearchType.VID_PLAYLIST,
                ytplid,
                "",
                1,
                YTConstants.MAX_RESULTS_PER_PAGE);
        YTSearchHelper.SearchReturn sr;
        int maxPage = -1;
        int curPage = 1;

        // NOTE
        // Hash map with Youtube video id, should be used because,
        //   youtube playlist may contain more than one video - duplicated video.
        // But, NetMBuddy doesn't allow duplicated video in the playlist.
        // So, use hashmap instead of linked list.
        HashMap<String, YTVideoFeed.Entry> map = new HashMap<String, YTVideoFeed.Entry>();
        int lastPv = -1;
        // PV : progress value
        int pvBase = 0;
        int pvPortion  = 10;
        do {
            sarg.starti = YTConstants.MAX_RESULTS_PER_PAGE * (curPage - 1) + 1;
            sr = YTSearchHelper.search(sarg);
            checkInterrupted();
            if (YTSearchHelper.Err.NO_ERR != sr.err)
                return Err.map(sr.err);

            if (maxPage < 0) {
                int total;
                try {
                    total = Integer.parseInt(sr.r.header.totalResults);
                } catch (NumberFormatException e) {
                    return Err.YTSEARCH;
                }
                // Do only once.
                maxPage = total / YTConstants.MAX_RESULTS_PER_PAGE + 1;
            }

            YTVideoFeed.Entry[] ents = (YTVideoFeed.Entry[])sr.r.entries;
            for (YTVideoFeed.Entry e : ents) {
                // Check that this video is in DB or not.
                // And add video only that is missed at selected local playlist
                if (!db.containsVideo(plid, e.media.videoId))
                    map.put(e.media.videoId, e);
            }

            int curPv = pvBase + (curPage * 100 / maxPage) * pvPortion / 100;
            if (lastPv < curPv && null != progl) {
                lastPv = curPv;
                progl.onProgress(curPv);
            }

        } while (++curPage <= maxPage);

        // Loading video feeds from playlist is done.
        // It's time to load thumbnail and insert to DB.
        // Update progress base and portion.
        pvBase += pvPortion;
        pvPortion = 100 - pvBase; // all remains.

        final int progressBase = pvBase;
        MultiThreadRunner.OnProgressListener progListener = new MultiThreadRunner.OnProgressListener() {
            @Override
            public void
            onProgress(float prog) {
                progl.onProgress((int)(progressBase + prog * 100));
            }
        };
        mtrunner.setOnProgressListener(progListener);

        Err err = Err.NO_ERR;
        Map.Entry<String, YTVideoFeed.Entry>[] mes = map.entrySet().toArray(new Map.Entry[0]);
        for (Map.Entry<String, YTVideoFeed.Entry> me : mes) {
            final YTVideoFeed.Entry e = me.getValue();
            mtrunner.appendJob(new Job<Integer>((float)pvPortion/ (float)100 / mes.length) {
                @Override
                public Integer
                doJob() {
                    boolean r = false;
                    try {
                        r = insertVideoToPlaylist(plid, e);
                        return 0;
                    } finally {
                        if (r)
                            mtpr.nrDone.incrementAndGet();
                        else
                            mtpr.nrIgnored.incrementAndGet();
                    }
                }
            });
        }

        mtrunner.waitAllDone();
        progl.onProgress(100);
        return err;
    }

    private void
    onContextMenuImportYtPlaylist(final long plid, final Object user) {
        final ImportYtPlaylistResult mtpr = new ImportYtPlaylistResult();
        final MultiThreadRunner mtrunner = new MultiThreadRunner(
                Utils.getUiHandler(),
                Policy.YTSEARCH_MAX_LOAD_THUMBNAIL_THREAD);

        DiagAsyncTask.Worker worker = new DiagAsyncTask.Worker() {
            private CharSequence
            getReportText(Err result) {
                CharSequence title = "";
                if (Err.NO_ERR != result)
                    title = Utils.getResString(result.getMessage());

                return title + "\n"
                       + "  " + Utils.getResString(R.string.done) + " : " + mtpr.nrDone.get() + "\n"
                       + "  " + Utils.getResString(R.string.error) + " : " + mtpr.nrIgnored.get();
            }

            private void
            onEnd(Err result) {
                UiUtils.showTextToast(getActivity(), getReportText(result));
            }

            @Override
            public void
            onPostExecute(DiagAsyncTask task, Err result) {
                onEnd(result);
            }

            @Override
            public void
            onCancel(DiagAsyncTask task) {
                mtrunner.cancel();
            }

            @Override
            public void
            onCancelled(DiagAsyncTask task) {
                onEnd(Err.CANCELLED);
            }

            @Override
            public Err
            doBackgroundWork(final DiagAsyncTask task) {
                ProgressListener prog = new ProgressListener() {
                    private int _mLastPercent = -1;
                    @Override
                    public void
                    onProgress(int percent) {
                        if (_mLastPercent < percent) {
                            task.publishProgress(percent);
                            _mLastPercent = percent;
                        }
                    }
                };

                Err err = Err.NO_ERR;
                try {
                    err = doBackgroundImportYtPlaylist(mtpr,
                                                       mtrunner,
                                                       plid,
                                                       getAdapter().getItemPlaylistId((Integer)user),
                                                       prog);
                } catch (InterruptedException e) {
                    err = Err.INTERRUPTED;
                }
                return err;
            }
        };
        new DiagAsyncTask(getActivity(),
                          worker,
                          DiagAsyncTask.Style.PROGRESS,
                          R.string.merging_playlist,
                          true,
                          false)
            .run();
    }

    private void
    onContextMenuImport(int pos) {
        UiUtils.OnPlaylistSelected action = new UiUtils.OnPlaylistSelected() {
            @Override
            public void
            onPlaylist(final long plid, final Object user) {
                onContextMenuImportYtPlaylist(plid, user);
            }

            @Override
            public void
            onUserMenu(int pos, Object user) { }
        };

        UiUtils.buildSelectPlaylistDialog(DB.get(),
                                          getActivity(),
                                          R.string.import_,
                                          null,
                                          action,
                                          DB.INVALID_PLAYLIST_ID,
                                          pos)
               .show();
    }

    @Override
    protected void
    onListItemClick(View view, int position, long itemId) {
        Intent i = new Intent(getActivity(), YTVideoSearchPlaylistActivity.class);
        i.putExtra(YTSearchActivity.MAP_KEY_SEARCH_TEXT,
                   getAdapter().getItemPlaylistId(position));
        i.putExtra(YTSearchActivity.MAP_KEY_SEARCH_TITLE,
                   getAdapter().getItemTitle(position));
        startActivity(i);
    }

    @Override
    public void
    searchDone(YTSearchHelper helper, YTSearchHelper.SearchArg arg,
               YTFeed.Result result, YTSearchHelper.Err err) {
        if (!handleSearchResult(helper, arg, result, err))
            return; // There is an error in search

        stopLoadingLookAndFeel();
        // helper's event receiver is changed to adapter in adapter's constructor.
        YTPlaylistSearchAdapter adapter = new YTPlaylistSearchAdapter(getActivity(),
                                                                      mSearchHelper,
                                                                      (YTPlaylistFeed.Entry[])result.entries);
        YTPlaylistSearchAdapter oldAdapter = getAdapter();
        mListv.setAdapter(adapter);
        // Cleanup before as soon as possible to secure memories.
        if (null != oldAdapter)
            oldAdapter.cleanup();
    }

    @Override
    public boolean
    onContextItemSelected(MenuItem mItem) {
        if (!isPrimary()
            || !getMyActivity().isContextMenuOwner(this))
            return false;

        AdapterContextMenuInfo info = (AdapterContextMenuInfo)mItem.getMenuInfo();
        switch (mItem.getItemId()) {
        case R.id.import_:
            onContextMenuImport(info.position);
            return true;

        }
        eAssert(false);
        return false;
    }

    @Override
    public void
    onCreateContextMenu2(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu2(menu, v, menuInfo);
        MenuInflater inflater = getActivity().getMenuInflater();
        inflater.inflate(R.menu.ytplaylistsearch_context, menu);
        // AdapterContextMenuInfo mInfo = (AdapterContextMenuInfo)menuInfo;
    }

    @Override
    public String
    dump(UnexpectedExceptionHandler.DumpLevel lvl) {
        return this.getClass().getName();
    }

    @Override
    public void
    onAttach(Activity activity) {
        super.onAttach(activity);
    }

    @Override
    public void
    onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UnexpectedExceptionHandler.get().registerModule(this);
    }

    @Override
    public View
    onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = super.onCreateView(inflater, container, savedInstanceState);
        mListv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void
            onItemClick(AdapterView<?> parent, View view, int position, long itemId) {
                onListItemClick(view, position, itemId);
            }
        });
        return v;
    }

    @Override
    public void
    onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void
    onStart() {
        super.onStart();
    }

    @Override
    public void
    onResume() {
        super.onResume();
    }

    @Override
    public void
    onPause() {
        super.onPause();
    }

    @Override
    public void
    onStop() {
        super.onStop();
    }

    @Override
    public void
    onEarlyDestroy() {
        super.onEarlyDestroy();
    }

    @Override
    public void
    onDestroyView() {
        super.onDestroyView();
    }

    @Override
    public void
    onDestroy() {
        UnexpectedExceptionHandler.get().unregisterModule(this);
        super.onDestroy();
    }

    @Override
    public void
    onDetach() {
        super.onDetach();
    }
}
