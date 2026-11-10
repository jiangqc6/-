(function () {
    // 顶层页面和同源 iframe 各装一份：触摸事件不会跨 document 冒泡，
    // 很多站点的播放器是 iframe 嵌进来的，只装顶层会“划了没反应”。
    // 每个 window 一套独立状态。

    function install(win) {
        try {
            if (!win) return;
            var doc = null;
            try { doc = win.document; } catch (e) { return; }
            if (!doc || !doc.addEventListener || !doc.querySelectorAll) return;
            // iframe 导航后 window 对象可能还在，但 document 已经换了，
            // 监听器留在旧 document 上等于没装，所以按 document 判断。
            if (win.__jqcGesturesInstalled && win.__jqcGesturesDoc === doc) return;
            win.__jqcGesturesInstalled = true;
            win.__jqcGesturesDoc = doc;

            var appFullscreen = false;
            var overlay = null;
            var state = null;
            var clickGuard = null;

            // 新装进来的 iframe 要继承当前“App 内全屏”状态（同源才读得到）。
            try {
                var topWin = win.top;
                if (topWin && topWin !== win && topWin.__jqcAppFullscreen === true) {
                    appFullscreen = true;
                }
            } catch (e) {}

            function format(sec) {
                sec = Math.max(0, Math.floor(sec || 0));
                var h = Math.floor(sec / 3600);
                var m = Math.floor((sec % 3600) / 60);
                var s = sec % 60;
                function p(n) { return (n < 10 ? '0' : '') + n; }
                return h > 0 ? (h + ':' + p(m) + ':' + p(s)) : (p(m) + ':' + p(s));
            }

            function largestVideo() {
                var best = null;
                var bestArea = 0;
                var vids = doc.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                    var v = vids[i];
                    var d = v.duration;
                    if (!d || !isFinite(d)) continue;
                    var r = v.getBoundingClientRect();
                    var area = r.width * r.height;
                    if (area > bestArea) {
                        best = v;
                        bestArea = area;
                    }
                }
                return best;
            }

            // App 内进入全屏，或网页把播放器“最大化”到接近满屏时，才接管手势，避免干扰普通浏览。
            function shouldHandle(v) {
                if (!v) return false;
                if (appFullscreen) return true;
                try {
                    if (doc.fullscreenElement) return true;
                } catch (e) {}
                var r = v.getBoundingClientRect();
                var vw = win.innerWidth || 1;
                var vh = win.innerHeight || 1;
                return r.width >= vw * 0.7 && r.height >= vh * 0.4;
            }

            // 网页元素全屏（requestFullscreen）时，body 上的提示层不会渲染，
            // 必须把提示挂进当前全屏元素内部。
            function hintParent() {
                try {
                    if (doc.fullscreenElement) return doc.fullscreenElement;
                    if (doc.webkitFullscreenElement) return doc.webkitFullscreenElement;
                } catch (e) {}
                return doc.body || doc.documentElement;
            }

            function showHint(text) {
                var parent = hintParent();
                if (!overlay) {
                    overlay = doc.createElement('div');
                    overlay.style.cssText =
                        'position:fixed;left:50%;top:10%;transform:translateX(-50%);z-index:2147483647;' +
                        'background:rgba(0,0,0,0.75);color:#fff;font-family:sans-serif;font-size:15px;' +
                        'line-height:1.4;padding:8px 14px;border-radius:10px;pointer-events:none;' +
                        'display:none;white-space:nowrap;';
                }
                if (overlay.parentNode !== parent) parent.appendChild(overlay);
                overlay.textContent = text;
                overlay.style.display = 'block';
            }

            function hideHint() {
                if (overlay) overlay.style.display = 'none';
            }

            function stopTimer() {
                if (state && state.timer) {
                    clearTimeout(state.timer);
                    state.timer = null;
                }
            }

            function restoreBoost() {
                if (!state || !state.video) return;
                if (state.mode === 'boost') {
                    try { state.video.playbackRate = state.rate; } catch (e) {}
                    hideHint();
                }
            }

            function finishState() {
                if (!state) return;
                stopTimer();
                restoreBoost();
                state = null;
            }

            function suppressNextClick() {
                if (clickGuard) {
                    doc.removeEventListener('click', clickGuard, true);
                }
                clickGuard = function (ev) {
                    ev.preventDefault();
                    ev.stopPropagation();
                    doc.removeEventListener('click', clickGuard, true);
                    clickGuard = null;
                };
                doc.addEventListener('click', clickGuard, true);
                setTimeout(function () {
                    if (clickGuard) {
                        doc.removeEventListener('click', clickGuard, true);
                        clickGuard = null;
                    }
                }, 400);
            }

            function onStart(e) {
                if (!e.touches || e.touches.length !== 1 || state) return;
                var v = largestVideo();
                if (!shouldHandle(v)) return;
                var t = e.touches[0];
                // 屏幕左右各 24px 让给系统返回手势：从这里起手横滑，
                // 系统会先接管，抢过来只会让用户“想返回却快进”。
                var vw = win.innerWidth || 0;
                if (t.clientX < 24 || (vw > 0 && t.clientX > vw - 24)) return;
                var st = {
                    video: v,
                    x: t.clientX,
                    y: t.clientY,
                    lastX: t.clientX,
                    lastY: t.clientY,
                    mode: 'pending',
                    rate: v.playbackRate || 1,
                    startTime: v.currentTime || 0,
                    duration: isFinite(v.duration) ? v.duration : 0,
                    target: null,
                    lastSeek: 0,
                    // 避开网站自己的底部控制条/进度条，只在中部区域接管。
                    bottom: t.clientY > (win.innerHeight || 0) - 90,
                    timer: null,
                };
                state = st;
                st.timer = setTimeout(function () {
                    if (!state || state !== st || state.mode !== 'pending') return;
                    if (Math.abs(st.lastX - st.x) > 12 || Math.abs(st.lastY - st.y) > 12) return;
                    if (st.video.paused) return;
                    st.mode = 'boost';
                    try { st.video.playbackRate = 2; } catch (err) {}
                    try { if (win.navigator && win.navigator.vibrate) win.navigator.vibrate(25); } catch (err) {}
                    showHint('2.0x 加速中，松手恢复');
                }, 500);
            }

            // 目标时间是否已经缓冲好；只有已缓冲的跳转才是瞬时的。
            function isBuffered(v, t) {
                try {
                    var b = v.buffered;
                    for (var i = 0; i < b.length; i++) {
                        if (t >= b.start(i) && t <= b.end(i) - 0.05) return true;
                    }
                } catch (e) {}
                return false;
            }

            function onMove(e) {
                if (!state || !e.touches) return;
                if (e.touches.length > 1) {
                    finishState();
                    hideHint();
                    return;
                }
                if (e.touches.length === 0) return;
                var t = e.touches[0];
                state.lastX = t.clientX;
                state.lastY = t.clientY;
                var dx = t.clientX - state.x;
                var dy = t.clientY - state.y;

                if (state.mode === 'pending') {
                    if (Math.abs(dx) > 16 && Math.abs(dx) > Math.abs(dy) &&
                        !state.bottom && state.duration > 0) {
                        state.mode = 'seek';
                        stopTimer();
                    } else if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                        stopTimer();
                    }
                }

                if (state.mode === 'seek') {
                    e.preventDefault();
                    e.stopPropagation();
                    var delta = dx / (win.innerWidth || 1) * state.duration;
                    var target = Math.max(0, Math.min(state.duration - 0.2, state.startTime + delta));
                    state.target = target;
                    var now = Date.now();
                    // 只在目标已缓冲时实时跟手。远距离快进若边拖边跳，会让播放器反复丢弃
                    // 缓冲、重新拉流，松手后卡顿更明显；未缓冲的跳转统一留到松手时做一次。
                    if (now - state.lastSeek > 150 && isBuffered(state.video, target)) {
                        state.lastSeek = now;
                        try { state.video.currentTime = target; } catch (err) {}
                    }
                    showHint((delta >= 0 ? '快进 ' : '快退 ') + format(target) + ' / ' + format(state.duration));
                } else if (state.mode === 'boost') {
                    e.preventDefault();
                    e.stopPropagation();
                }
            }

            function watchBuffer(v) {
                showHint('缓冲中…');
                var done = false;
                var onReady = function () {
                    if (done) return;
                    done = true;
                    v.removeEventListener('playing', onReady);
                    v.removeEventListener('canplay', onReady);
                    if (!state) hideHint();
                };
                v.addEventListener('playing', onReady);
                v.addEventListener('canplay', onReady);
                setTimeout(function () {
                    done = true;
                    v.removeEventListener('playing', onReady);
                    v.removeEventListener('canplay', onReady);
                    if (!state) hideHint();
                }, 10000);
            }

            function onEnd(e) {
                if (!state) return;
                stopTimer();
                if (state.mode === 'seek') {
                    var v = state.video;
                    if (state.target != null) {
                        try { v.currentTime = state.target; } catch (err) {}
                        try { if (win.navigator && win.navigator.vibrate) win.navigator.vibrate(20); } catch (err) {}
                        // 远距离跳转后要重新拉流：若 300ms 内还没就绪，提示“缓冲中”，
                        // 等画面可播再消掉，避免误以为卡死。
                        setTimeout(function () {
                            try {
                                if (state) return;
                                if (v && !v.ended && v.readyState < 3) {
                                    watchBuffer(v);
                                } else {
                                    hideHint();
                                }
                            } catch (err) {}
                        }, 300);
                    } else {
                        hideHint();
                    }
                    if (e && e.cancelable) e.preventDefault();
                    if (e) e.stopPropagation();
                    suppressNextClick();
                } else if (state.mode === 'boost') {
                    restoreBoost();
                }
                state = null;
            }

            doc.addEventListener('touchstart', onStart, { capture: true, passive: true });
            doc.addEventListener('touchmove', onMove, { capture: true, passive: false });
            doc.addEventListener('touchend', onEnd, { capture: true, passive: false });
            doc.addEventListener('touchcancel', onEnd, { capture: true, passive: false });
            doc.addEventListener('fullscreenchange', function () { hideHint(); }, true);
            doc.addEventListener('webkitfullscreenchange', function () { hideHint(); }, true);

            win.__jqcHint = showHint;
            win.__jqcHideHint = hideHint;
            win.__jqcReport = function (v, force) {
                try {
                    var api = null;
                    try { api = win.top.__jqcProgressApi; } catch (e) { api = win.__jqcProgressApi; }
                    if (api && api.report) api.report(win, v, force);
                } catch (e) {}
            };
            function notifyPlayState() {
                try {
                    var api = null;
                    try { api = win.top.__jqcProgressApi; } catch (e) { api = win.__jqcProgressApi; }
                    if (api && api.stateChanged) api.stateChanged();
                } catch (e) {}
            }
            // 播放/暂停时通知原生层，用于浮现或收起“影院模式”入口。
            doc.addEventListener('play', function (ev) {
                var t = ev.target;
                if (t && t.tagName === 'VIDEO') notifyPlayState();
            }, true);
            // 暂停/播完时立刻上报一次位置，不等 10 秒轮询，避免刚暂停就切走丢进度。
            doc.addEventListener('pause', function (ev) {
                var t = ev.target;
                if (t && t.tagName === 'VIDEO') { win.__jqcReport(t, true); notifyPlayState(); }
            }, true);
            doc.addEventListener('ended', function (ev) {
                var t = ev.target;
                if (t && t.tagName === 'VIDEO') { win.__jqcReport(t, true); notifyPlayState(); }
            }, true);
            // 视频加载失败时给原生层一个兜底提示的机会。
            doc.addEventListener('error', function (ev) {
                var t = ev.target;
                if (!t || t.tagName !== 'VIDEO') return;
                var key = '';
                var src = '';
                try { src = String(t.currentSrc || t.src || ''); } catch (e) {}
                try { key = src.slice(0, 1000); } catch (e) {}
                try {
                    if (window.JqcBridge && window.JqcBridge.notifyVideoError) {
                        window.JqcBridge.notifyVideoError(key, src.slice(0, 4096));
                    }
                } catch (e) {}
            }, true);

            win.__jqcGesturesSetLocal = function (on) {
                appFullscreen = !!on;
                if (!appFullscreen) {
                    finishState();
                    hideHint();
                }
            };
            win.__jqcGestures = { setAppFullscreen: win.__jqcGesturesSetLocal };
        } catch (e) {}
    }

    // iframe 里再导航时是新的 window/document，需要在每次 load 后重新安装。
    function hookFrames(win) {
        var doc = null;
        try { doc = win.document; } catch (e) { return; }
        var frames = null;
        try { frames = doc.querySelectorAll('iframe'); } catch (e) { return; }
        for (var i = 0; i < frames.length; i++) {
            var frame = frames[i];
            if (frame.__jqcGestureHooked) continue;
            frame.__jqcGestureHooked = true;
            frame.addEventListener('load', function (ev) {
                var target = ev.currentTarget;
                try { install(target.contentWindow); } catch (e) {}
                try { installTree(target.contentWindow); } catch (e) {}
            });
        }
    }

    function installTree(win) {
        try { install(win); } catch (e) {}
        try { hookFrames(win); } catch (e) {}
        var frames = null;
        try { frames = win.frames; } catch (e) { return; }
        if (!frames) return;
        var count = 0;
        try { count = frames.length; } catch (e) { return; }
        for (var i = 0; i < count; i++) {
            var child = null;
            try { child = frames[i]; } catch (e) { continue; }
            try { installTree(child); } catch (e) {}
        }
    }

    // 跨域 iframe 访问会抛异常，直接跳过，不影响顶层手势。
    function propagate(win, on) {
        var frames = null;
        try { frames = win.frames; } catch (e) { return; }
        if (!frames) return;
        var count = 0;
        try { count = frames.length; } catch (e) { return; }
        for (var i = 0; i < count; i++) {
            var child = null;
            try { child = frames[i]; } catch (e) { continue; }
            try {
                if (child.__jqcGesturesSetLocal) child.__jqcGesturesSetLocal(on);
                else if (child.__jqcGestures) child.__jqcGestures.setAppFullscreen(on);
            } catch (e) {}
            try { propagate(child, on); } catch (e) {}
        }
    }

    try { window.__jqcAppFullscreen = window.__jqcAppFullscreen === true; } catch (e) {}
    installTree(window);

    // 顶层注入入口：同步“App 内全屏”状态到所有同源子页。
    window.__jqcGestures = {
        setAppFullscreen: function (on) {
            try { window.__jqcAppFullscreen = !!on; } catch (e) {}
            try {
                if (window.__jqcGesturesSetLocal) window.__jqcGesturesSetLocal(on);
            } catch (e) {}
            try { propagate(window, !!on); } catch (e) {}
        }
    };

    // 新插入的 iframe（有些站点点击播放后才创建）也要接上手势。
    try {
        var pending = false;
        new MutationObserver(function () {
            if (pending) return;
            pending = true;
            setTimeout(function () {
                pending = false;
                installTree(window);
            }, 300);
        }).observe(document.documentElement || document, { childList: true, subtree: true });
    } catch (e) {}

    // ---- 网页播放进度记忆 ----
    // 每页只装一套：注入脚本会在 onPageStarted / onPageFinished 各执行一次。
    if (window.__jqcProgressInstalled) return;
    window.__jqcProgressInstalled = true;

    function frameId(win) {
        try { return win.location.origin + win.location.pathname; } catch (e) { return ''; }
    }

    function collectVideos(win, out, depth) {
        if (depth > 4) return out;
        try {
            var vids = win.document.querySelectorAll('video');
            for (var i = 0; i < vids.length; i++) out.push({ win: win, video: vids[i] });
        } catch (e) {}
        var frames = null;
        try { frames = win.frames; } catch (e) { return out; }
        if (!frames) return out;
        var count = 0;
        try { count = frames.length; } catch (e) { return out; }
        for (var j = 0; j < count; j++) {
            var child = null;
            try { child = frames[j]; } catch (e) { continue; }
            collectVideos(child, out, depth + 1);
        }
        return out;
    }

    // 地址里的直链最稳定；blob:/MediaSource 每次加载都会变，
    // 退回“同源页面 + 元素序号”，刷新后同一位置仍是同一路视频。
    function keyFor(item) {
        var src = '';
        try { src = item.video.currentSrc || item.video.src || ''; } catch (e) {}
        src = String(src || '');
        if (/^https?:/i.test(src)) {
            return src.split('#')[0].split('?')[0].slice(0, 1000);
        }
        var idx = -1;
        try {
            var vids = item.win.document.querySelectorAll('video');
            for (var i = 0; i < vids.length; i++) {
                if (vids[i] === item.video) { idx = i; break; }
            }
        } catch (e) {}
        return (frameId(item.win) + '|v' + idx).slice(0, 1000);
    }

    var lastReportAt = {};

    function reportItem(item, force) {
        var v = item.video;
        var d = 0;
        try { d = v.duration; } catch (e) {}
        if (!d || !isFinite(d) || d < 1) return;
        var t = 0;
        try { t = v.currentTime || 0; } catch (e) {}
        if (!force && t < 1) return;
        var key = keyFor(item);
        if (!key) return;
        var now = Date.now();
        if (!force && lastReportAt[key] && now - lastReportAt[key] < 5000) return;
        lastReportAt[key] = now;
        try {
            if (window.JqcBridge && window.JqcBridge.reportPlayback) {
                window.JqcBridge.reportPlayback(key, t, d);
            }
        } catch (e) {}
    }

    function anyPlaying() {
        var items = collectVideos(window, [], 0);
        for (var i = 0; i < items.length; i++) {
            try {
                if (!items[i].video.paused && !items[i].video.ended) return true;
            } catch (e) {}
        }
        return false;
    }

    var lastPlayingState = false;

    function notifyPlaybackState() {
        var playing = anyPlaying();
        if (playing === lastPlayingState) return;
        lastPlayingState = playing;
        try {
            if (window.JqcBridge && window.JqcBridge.notifyPlaybackState) {
                window.JqcBridge.notifyPlaybackState(playing);
            }
        } catch (e) {}
    }

    // 卡顿兜底：正在播放但 readyState 连续 10 秒上不来，就通知一次原生层。
    var stallTicks = {};
    var stallNotifiedAt = {};

    function monitorStall() {
        var items = collectVideos(window, [], 0);
        for (var i = 0; i < items.length; i++) {
            var v = items[i].video;
            var key = keyFor(items[i]);
            if (!key) continue;
            var bad = false;
            try { bad = !v.paused && !v.ended && v.readyState < 3; } catch (e) {}
            if (!bad) {
                stallTicks[key] = 0;
                continue;
            }
            stallTicks[key] = (stallTicks[key] || 0) + 1;
            if (stallTicks[key] < 5) continue;
            var now = Date.now();
            if (stallNotifiedAt[key] && now - stallNotifiedAt[key] < 60000) continue;
            stallNotifiedAt[key] = now;
            var src = '';
            try { src = String(v.currentSrc || v.src || ''); } catch (e) {}
            try {
                if (window.JqcBridge && window.JqcBridge.notifyVideoBuffering) {
                    window.JqcBridge.notifyVideoBuffering(key, src.slice(0, 4096));
                }
            } catch (e) {}
        }
    }

    function reportPlaying() {
        var items = collectVideos(window, [], 0);
        for (var i = 0; i < items.length; i++) {
            var v = items[i].video;
            try {
                if (!v.paused && !v.ended) reportItem(items[i], false);
            } catch (e) {}
        }
        notifyPlaybackState();
    }

    function flushProgress() {
        var items = collectVideos(window, [], 0);
        for (var i = 0; i < items.length; i++) {
            var t = 0;
            try { t = items[i].video.currentTime || 0; } catch (e) {}
            if (t > 0) reportItem(items[i], true);
        }
    }

    function findItemByKey(key) {
        var items = collectVideos(window, [], 0);
        for (var i = 0; i < items.length; i++) {
            if (keyFor(items[i]) === key) return items[i];
        }
        return null;
    }

    function fmt(sec) {
        sec = Math.max(0, Math.floor(sec || 0));
        var h = Math.floor(sec / 3600);
        var m = Math.floor((sec % 3600) / 60);
        var s = sec % 60;
        function p(n) { return (n < 10 ? '0' : '') + n; }
        return h > 0 ? (h + ':' + p(m) + ':' + p(s)) : (p(m) + ':' + p(s));
    }

    function hintIn(win, text) {
        try {
            if (win.__jqcHint) win.__jqcHint(text);
        } catch (e) {}
    }

    // key -> { applied, timer, attempts }
    var resumeStates = {};

    /**
     * 请求续播。视频可能还在创建中（站点先渲染再插 <video>），
     * 轮询最多 30 秒；每个 key 每次页面加载只尝试一次，避免反复回跳。
     * 返回 false 表示不需要/已处理过。
     */
    function applyResume(key, position, duration) {
        if (!key || typeof position !== 'number' || !isFinite(position) || position < 2) return false;
        var st = resumeStates[key];
        if (st && (st.applied || st.timer)) return false;
        st = { applied: false, timer: null, attempts: 0 };
        resumeStates[key] = st;
        st.timer = setInterval(function () {
            st.attempts++;
            if (st.attempts > 60) {
                clearInterval(st.timer);
                st.timer = null;
                return;
            }
            var item = findItemByKey(key);
            if (!item) return;
            var v = item.video;
            var d = 0;
            try { d = v.duration; } catch (e) {}
            if (!d || !isFinite(d) || d < 1) return;
            // 时长对不上说明内容换了，不硬跳。
            if (typeof duration === 'number' && isFinite(duration) && duration > 0 &&
                Math.abs(d - duration) > Math.max(30, duration * 0.2)) {
                clearInterval(st.timer);
                st.timer = null;
                return;
            }
            var t = 0;
            try { t = v.currentTime || 0; } catch (e) {}
            // 用户已经自己播了一会儿，尊重他的选择，不再拉回进度。
            if (!v.paused && t > 10) {
                clearInterval(st.timer);
                st.timer = null;
                return;
            }
            var target = Math.max(0, Math.min(position, d - 3));
            if (target < 2) {
                clearInterval(st.timer);
                st.timer = null;
                return;
            }
            try {
                v.currentTime = target;
            } catch (e) {
                return;
            }
            st.applied = true;
            clearInterval(st.timer);
            st.timer = null;
            hintIn(item.win, '已续播到 ' + fmt(target));
            try {
                if (window.JqcBridge && window.JqcBridge.notifyResumeApplied) {
                    window.JqcBridge.notifyResumeApplied(key, target, d);
                }
            } catch (e) {}
        }, 500);
        return true;
    }

    function seekToStart(key) {
        var st = resumeStates[key];
        if (st) {
            if (st.timer) clearInterval(st.timer);
            st.applied = true;
            st.timer = null;
        }
        var item = findItemByKey(key);
        if (!item) return false;
        try { item.video.currentTime = 0; } catch (e) { return false; }
        hintIn(item.win, '已从头播放');
        return true;
    }

    /**
     * 影院模式：优先把正在播放的视频、否则把显示面积最大的视频滚到视口中央。
     * 只做滚动，不改页面结构，退出后页面保持原样。
     */
    function theater() {
        var items = collectVideos(window, [], 0);
        var chosen = null;
        var bestArea = 0;
        for (var i = 0; i < items.length; i++) {
            var v = items[i].video;
            var area = 0;
            var playing = false;
            try { area = (v.clientWidth || 0) * (v.clientHeight || 0); } catch (e) {}
            try { playing = !v.paused && !v.ended; } catch (e) {}
            if (playing) { chosen = items[i]; break; }
            if (!chosen && area > bestArea) { chosen = items[i]; bestArea = area; }
        }
        if (!chosen) return false;
        try {
            chosen.video.scrollIntoView({ block: 'center', inline: 'center' });
        } catch (e) {
            try { chosen.video.scrollIntoView(); } catch (e2) { return false; }
        }
        return true;
    }

    window.__jqcProgressApi = {
        report: function (win, video, force) {
            reportItem({ win: win, video: video }, !!force);
        },
        stateChanged: notifyPlaybackState
    };
    window.__jqcApplyResume = applyResume;
    window.__jqcSeekStart = seekToStart;
    window.__jqcFlushProgress = flushProgress;
    window.__jqcTheater = theater;

    setInterval(reportPlaying, 10000);
    setInterval(monitorStall, 2000);
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'hidden') flushProgress();
    }, true);
    window.addEventListener('pagehide', flushProgress);
})();
