(function () {
    if (window.__jqcSnifferInstalled) return;
    window.__jqcSnifferInstalled = true;

    function report(u, hint) {
        try {
            if (!u || typeof u !== 'string') return;
            if (!/^https?:\/\//i.test(u)) return;
            if (u.length > 4096) return;
            if (window.JqcBridge && window.JqcBridge.reportMedia) {
                window.JqcBridge.reportMedia(u, hint || '');
            }
        } catch (e) {}
    }

    function absolute(u) {
        try { return new URL(u, location.href).href; } catch (e) { return null; }
    }

    var originalFetch = window.fetch;
    if (originalFetch) {
        window.fetch = function (input) {
            try {
                var raw = typeof input === 'string' ? input : (input && input.url);
                var abs = raw ? absolute(raw) : null;
                if (abs) report(abs, 'fetch');
            } catch (e) {}
            var promise = originalFetch.apply(this, arguments);
            if (promise && promise.then) {
                promise.then(function (response) {
                    try { if (response && response.url) report(response.url, 'fetch'); } catch (e) {}
                });
            }
            return promise;
        };
    }

    var originalOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
        try {
            var abs = absolute(url);
            if (abs) report(abs, 'xhr');
        } catch (e) {}
        return originalOpen.apply(this, arguments);
    };

    var originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function () {
        var xhr = this;
        xhr.addEventListener('load', function () {
            try { if (xhr.responseURL) report(xhr.responseURL, 'xhr'); } catch (e) {}
        });
        return originalSend.apply(this, arguments);
    };

    function scan() {
        try {
            var nodes = document.querySelectorAll('video, source');
            for (var i = 0; i < nodes.length; i++) {
                var el = nodes[i];
                if (el.currentSrc) report(el.currentSrc, 'video');
                if (el.src) report(el.src, 'video');
            }
            var html = document.documentElement ? document.documentElement.innerHTML : '';
            if (html) {
                var re = /https?:(?:\\?\/){2}[^"'\s<>]+?\.m3u8[^"'\s<>]*/g;
                var match;
                var guard = 0;
                while ((match = re.exec(html)) && guard++ < 20) {
                    report(match[0].replace(/\\\//g, '/'), 'html');
                }
            }
        } catch (e) {}
    }

    var scheduled = false;
    function scheduleScan() {
        if (scheduled) return;
        scheduled = true;
        setTimeout(function () {
            scheduled = false;
            scan();
        }, 300);
    }

    document.addEventListener('DOMContentLoaded', scan);
    try {
        new MutationObserver(scheduleScan).observe(document.documentElement || document, {
            childList: true,
            subtree: true,
        });
    } catch (e) {}
    scan();
})();
