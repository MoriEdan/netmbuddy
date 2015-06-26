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

package free.yhc.netmbuddy.model;

import static free.yhc.netmbuddy.utils.Utils.eAssert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import free.yhc.netmbuddy.utils.ImageUtils;
import free.yhc.netmbuddy.utils.Utils;

public class YTSearchHelper {
    private static final boolean DBG = false;
    private static final Utils.Logger P = new Utils.Logger(YTSearchHelper.class);

    private static final int MSG_WHAT_OPEN                  = 0;
    private static final int MSG_WHAT_CLOSE                 = 1;
    private static final int MSG_WHAT_SEARCH                = 2;
    private static final int MSG_WHAT_LOAD_THUMBNAIL        = 3;

    private BGHandler                   mBgHandler      = null;
    private SearchDoneReceiver          mSearchRcvr     = null;
    private LoadThumbnailDoneReceiver   mThumbnailRcvr  = null;

    public interface SearchDoneReceiver {
        void searchDone(YTSearchHelper helper, SearchArg arg,
                        YTFeed.Result result, Err err);
    }

    public interface LoadThumbnailDoneReceiver {
        void loadThumbnailDone(YTSearchHelper helper, LoadThumbnailArg arg,
                               Bitmap bm, Err err);
    }

    public static enum Err {
        NO_ERR,
        IO_NET,
        INTERRUPTED,
        NETWORK_UNAVAILABLE,
        PARAMETER,
        FEED_FORMAT,
        BAD_REQUEST,
        UNKNOWN
    }

    public static enum SearchType {
        VID_KEYWORD,
        VID_AUTHOR,
        VID_PLAYLIST,
        PL_USER
    }

    public static class SearchArg {
        public Object       tag;   // user data tag
        public SearchType   type;
        public String       text;  //
        public String       title; // title of this search.
        public int          starti;// start index
        public int          max;   // max size to search
        public SearchArg(Object aTag,
                         SearchType aType, String aText, String aTitle,
                         int aStarti, int aMax) {
            tag = aTag;
            type = aType;
            text = aText;
            title = aTitle;
            starti = aStarti;
            max = aMax;
        }
    }

    public static class LoadThumbnailArg {
        public Object   tag;
        public String   url;
        public int      width;
        public int      height;
        public LoadThumbnailArg(Object aTag, String aUrl,
                                int aWidth, int aHeight) {
            tag = aTag;
            url = aUrl;
            width = aWidth;
            height = aHeight;
        }
    }

    public static class SearchReturn {
        public YTFeed.Result   r;
        public Err             err;
        SearchReturn(YTFeed.Result aR, Err aErr) {
            r = aR;
            err = aErr;
        }
    }

    public static class LoadThumbnailReturn {
        public Bitmap  bm;
        public Err     err;
        LoadThumbnailReturn(Bitmap aBm, Err aErr) {
            bm = aBm;
            err = aErr;
        }
    }

    private static enum FeedType {
        VIDEO,
        PLAYLIST
    }

    private static class BGThread extends HandlerThread {
        private BGHandler mHandler = null;
        BGThread() {
            super("YTSearchHelper.BGThread",Process.THREAD_PRIORITY_BACKGROUND);
        }

        void
        setBgHandler(BGHandler handler) {
            mHandler = handler;
        }

        @Override
        protected void
        onLooperPrepared() {
            super.onLooperPrepared();
        }

        @Override
        public void
        run() {
            try {
                super.run();
            } catch (Exception ignored) {
            } finally {
                if (null != mHandler)
                    mHandler.cleanupPostClose();
            }
        }
    }

    private static class BGHandler extends Handler {
        private final YTSearchHelper        _mHelper;

        private MultiThreadRunner           _mMtrunner      = null;
        private SearchDoneReceiver          _mSearchRcvr    = null;
        private LoadThumbnailDoneReceiver   _mThumbnailRcvr = null;
        private boolean                     _mClosed        = false;

        BGHandler(Looper looper, YTSearchHelper helper) {
            super(looper);
            _mHelper = helper;
        }

        private void
        sendFeedDone(final SearchArg arg, final YTFeed.Result res, final Err err) {
            Utils.getUiHandler().post(new Runnable() {
                @Override
                public void
                run() {
                    if (!_mClosed && null != _mSearchRcvr)
                        _mSearchRcvr.searchDone(_mHelper, arg, res, err);
                }
            });
            return;
        }

        private void
        sendLoadThumbnailDone(final LoadThumbnailArg arg, final Bitmap bm, final Err err) {
            Utils.getUiHandler().post(new Runnable() {
                @Override
                public void
                run() {
                    if (!_mClosed && null != _mThumbnailRcvr)
                        _mThumbnailRcvr.loadThumbnailDone(_mHelper, arg, bm, err);
                }
            });
            return;
        }

        /**
         * This function will be called end of 'run()' function in HandlerThread.
         */
        void
        cleanupPostClose() {
            if (null != _mMtrunner) {
                _mMtrunner.cancel();
                _mMtrunner = null;
            }
        }

        void
        close(boolean interrupt) {
            if (_mClosed)
                return; // already closed.

            removeMessages(MSG_WHAT_OPEN);
            removeMessages(MSG_WHAT_SEARCH);
            removeMessages(MSG_WHAT_LOAD_THUMBNAIL);
            _mSearchRcvr = null;
            _mThumbnailRcvr = null;
            if (interrupt) {
                _mClosed = true;
                getLooper().getThread().interrupt();
                ((HandlerThread)getLooper().getThread()).quit();
                return;
            } else
                sendEmptyMessage(MSG_WHAT_CLOSE);
        }

        void
        setSearchDoneRecevier(SearchDoneReceiver receiver) {
            _mSearchRcvr = receiver;
        }

        void
        setLoadThumbnailDoneRecevier(LoadThumbnailDoneReceiver receiver) {
            _mThumbnailRcvr = receiver;
        }

        @Override
        public void
        handleMessage(final Message msg) {
            if (_mClosed)
                return;

            switch (msg.what) {
            case MSG_WHAT_OPEN: {
                eAssert(null == _mMtrunner);
                _mMtrunner = new MultiThreadRunner(this,
                                                   Policy.YTSEARCH_MAX_LOAD_THUMBNAIL_THREAD);
            } break;

            case MSG_WHAT_CLOSE: {
                _mClosed = true;
                ((HandlerThread)getLooper().getThread()).quit();
            } break;

            case MSG_WHAT_SEARCH: {
                SearchArg arg = (SearchArg)msg.obj;
                SearchReturn r = doSearch(arg);
                sendFeedDone(arg, r.r, r.err);
            } break;

            case MSG_WHAT_LOAD_THUMBNAIL: {
                final LoadThumbnailArg arg = (LoadThumbnailArg)msg.obj;
                MultiThreadRunner.Job<Integer> job
                    = new MultiThreadRunner.Job<Integer>(true, 0) {
                    @Override
                    public Integer
                    doJob() {
                        LoadThumbnailReturn r = null;
                        try {
                            r = doLoadThumbnail(arg);
                        } finally {
                            if (null == r)
                                sendLoadThumbnailDone(arg, null, YTSearchHelper.Err.UNKNOWN);
                            else
                                sendLoadThumbnailDone(arg, r.bm, r.err);
                        }
                        return 0;
                    }
                };
                _mMtrunner.appendJob(job);
            } break;
            }
        }
    }

    private static Err
    map(NetLoader.Err err, Object extra) {
        switch (err) {
        case IO_NET:
            return Err.IO_NET;
        case HTTPGET:
            if (!(extra instanceof Integer))
                return Err.IO_NET;

            int stcode = (Integer)extra;
            switch (stcode) {
            case HttpUtils.SC_BAD_REQUEST:
                return Err.BAD_REQUEST;

            case HttpUtils.SC_NOT_FOUND:
                return Err.PARAMETER;

            default:
                return Err.IO_NET;
            }

        case INTERRUPTED:
            return Err.INTERRUPTED;

        case NO_ERR:
            eAssert(false);

        default:
            return Err.UNKNOWN;
        }
    }

    private static LoadThumbnailReturn
    doLoadThumbnail(LoadThumbnailArg arg) {
        Bitmap bm;
        try {
            bm = ImageUtils.decodeImage(loadUrl(arg.url), arg.width, arg.height);
        } catch (NetLoader.LocalException e) {
            eAssert(NetLoader.Err.NO_ERR != e.error());
            return new LoadThumbnailReturn(null, map(e.error(), e.extra()));
        } catch (IOException e) {
            return new LoadThumbnailReturn(null, Err.UNKNOWN);
        }
        return new LoadThumbnailReturn(bm, Err.NO_ERR);
    }

    private static byte[]
    loadUrl(String urlStr) throws NetLoader.LocalException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Uri uri = Uri.parse(urlStr);
        NetLoader loader = new NetLoader().open(null);
        loader.readHttpData(baos, uri);
        loader.close();

        byte[] data = baos.toByteArray();
        baos.close();
        return data;
    }

    private static YTFeed.Result
    parse(byte[] xmlData, FeedType type) throws
        IOException, SAXException, ParserConfigurationException {
        Document dom = DocumentBuilderFactory.newInstance()
                                             .newDocumentBuilder()
                                             .parse(new ByteArrayInputStream(xmlData));

        switch (type) {
        case VIDEO:
            return YTVideoFeed.parseFeed(dom);

        case PLAYLIST:
            return YTPlaylistFeed.parseFeed(dom);

        default:
            eAssert(false);
        }
        return null;
    }

    private static SearchReturn
    doSearch(SearchArg arg) {
        YTFeed.Result r = null;
        try {
            switch (arg.type) {
            case VID_KEYWORD:
                r = parse(loadUrl(YTVideoFeed.getFeedUrlByKeyword(arg.text, arg.starti, arg.max)),
                          FeedType.VIDEO);
                break;

            case VID_AUTHOR:
                r = parse(loadUrl(YTVideoFeed.getFeedUrlByAuthor(arg.text, arg.starti, arg.max)),
                          FeedType.VIDEO);
                break;

            case VID_PLAYLIST:
                r = parse(loadUrl(YTVideoFeed.getFeedUrlByPlaylist(arg.text, arg.starti, arg.max)),
                          FeedType.VIDEO);
                break;

            case PL_USER:
                r = parse(loadUrl(YTPlaylistFeed.getFeedUrlByUser(arg.text, arg.starti, arg.max)),
                          FeedType.PLAYLIST);
                break;

            default:
                eAssert(false);
            }
        } catch (IOException e) {
            if (DBG) P.w("DOM IO error!");
            return new SearchReturn(null, Err.IO_NET);
        } catch (SAXException e) {
            if (DBG) P.w("Parse unexpected format!");
            return new SearchReturn(null, Err.FEED_FORMAT);
        } catch (ParserConfigurationException pe) {
            if (DBG) P.w("Parse cofiguration exception!");
            return new SearchReturn(null, Err.UNKNOWN);
        } catch (NetLoader.LocalException e) {
            eAssert(NetLoader.Err.NO_ERR != e.error());
            Object extra = e.extra();
            Err reterr = map(e.error(), extra);


            return new SearchReturn(null, reterr);
        }
        return new SearchReturn(r, Err.NO_ERR);
    }

    /**
     * Thread safe
     * @param arg
     * @return
     */
    public static SearchReturn
    search(SearchArg arg) {
        if (!Utils.isNetworkAvailable())
            return new SearchReturn(null, Err.NETWORK_UNAVAILABLE);
        return doSearch(arg);
    }

    /**
     * Thread safe
     * @param arg
     * @return
     */
    public static LoadThumbnailReturn
    loadThumbnail(LoadThumbnailArg arg) {
        if (!Utils.isNetworkAvailable())
            return new LoadThumbnailReturn(null, Err.NETWORK_UNAVAILABLE);
        return doLoadThumbnail(arg);
    }

    public YTSearchHelper() {
    }

    public void
    setSearchDoneRecevier(SearchDoneReceiver receiver) {
        mSearchRcvr = receiver;
        if (null != mBgHandler)
            mBgHandler.setSearchDoneRecevier(receiver);
    }

    public void
    setLoadThumbnailDoneRecevier(LoadThumbnailDoneReceiver receiver) {
        mThumbnailRcvr = receiver;
        if (null != mBgHandler)
            mBgHandler.setLoadThumbnailDoneRecevier(receiver);
    }

    public void
    loadThumbnailAsync(LoadThumbnailArg arg) {
        if (null == mBgHandler)
            return;

        Message msg = mBgHandler.obtainMessage(MSG_WHAT_LOAD_THUMBNAIL, arg);
        mBgHandler.sendMessage(msg);
    }

    public Err
    searchAsync(SearchArg arg) {
        eAssert(0 < arg.starti && 0 < arg.max && arg.max <= Policy.YTSEARCH_MAX_RESULTS);
        if (Utils.isNetworkAvailable()) {
            Message msg = mBgHandler.obtainMessage(MSG_WHAT_SEARCH, arg);
            mBgHandler.sendMessage(msg);
            return Err.NO_ERR;
        }
        return Err.NETWORK_UNAVAILABLE;
    }

    public void
    open() {
        BGThread hThread = new BGThread();
        hThread.start();
        mBgHandler = new BGHandler(hThread.getLooper(), this);
        hThread.setBgHandler(mBgHandler);
        mBgHandler.sendEmptyMessage(MSG_WHAT_OPEN);
        mBgHandler.setSearchDoneRecevier(mSearchRcvr);
        mBgHandler.setLoadThumbnailDoneRecevier(mThumbnailRcvr);
    }

    public void
    close(boolean interrupt) {
        // TODO
        // Stop running thread!
        // Need to check that below code works as expected perfectly.
        // "interrupting thread" is quite annoying and unpredictable job!
        if (null != mBgHandler) {
            mBgHandler.close(interrupt);
            mBgHandler = null;
        }
    }
}
