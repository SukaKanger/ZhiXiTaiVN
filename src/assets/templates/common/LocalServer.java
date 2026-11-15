/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 WebGAL 移动工作站作者
 * 本文件为原创代码，许可证见随附 LICENSE-MIT.txt
 */
/*
 * LocalServer —— 移动工作站内置的极简 HTTP 服务。
 *
 * 为什么要它：WebView 里的 WebGAL 引擎用 fetch/XHR 读剧本和素材，
 * file:// 源会被 Chromium 拦掉。所以把引擎与工程目录挂到 127.0.0.1 上，
 * 引擎按原来的相对路径 ./game/xxx 取文件，一切照旧。
 *
 * 依赖：只用 android framework + java.io/net，不引第三方库，
 * 这样同一份代码既能在工作站里跑，也能被塞进打出来的游戏 APK。
 */
package com.zhixitai.vn;

import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LocalServer {

    /** 一个可以按相对路径取文件的来源。 */
    public interface Source {
        boolean exists(String relativePath);

        /** 文件字节数，取不到返回 -1。 */
        long length(String relativePath);

        InputStream open(String relativePath) throws IOException;
    }

    /** APK 内置 assets 里的目录。 */
    public static class AssetSource implements Source {
        private final AssetManager am;
        private final String prefix;
        /** assets 里的流不支持随机 seek，长度算一次就记下来，别每次请求都把字体读一遍。 */
        private final Map<String, Long> lenCache = new HashMap<String, Long>();

        public AssetSource(AssetManager am, String prefix) {
            this.am = am;
            this.prefix = (prefix == null || prefix.isEmpty()) ? "" : prefix;
        }

        private String full(String relativePath) {
            if (prefix.isEmpty()) {
                return relativePath;
            }
            if (relativePath.isEmpty()) {
                return prefix;
            }
            return prefix + "/" + relativePath;
        }

        @Override
        public boolean exists(String relativePath) {
            try {
                InputStream in = am.open(full(relativePath));
                in.close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public long length(String relativePath) {
            Long cached = lenCache.get(relativePath);
            if (cached != null) {
                return cached;
            }
            long len = -1;
            try {
                android.content.res.AssetFileDescriptor fd = am.openFd(full(relativePath));
                len = fd.getLength();
                fd.close();
            } catch (Exception ignored) {
            }
            if (len < 0) {
                InputStream in = null;
                try {
                    in = am.open(full(relativePath));
                    long total = 0;
                    byte[] head = new byte[16];
                    int n;
                    while ((n = in.read(head)) > 0) {
                        total += n;
                        if (total > 256L * 1024L * 1024L) {
                            break;
                        }
                    }
                    len = total;
                } catch (Exception e) {
                    len = -1;
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
            if (len >= 0) {
                lenCache.put(relativePath, len);
            }
            return len;
        }

        @Override
        public InputStream open(String relativePath) throws IOException {
            return am.open(full(relativePath));
        }
    }

    /** 手机存储里的真实目录（导入的工程）。 */
    public static class FileSource implements Source {
        private final File root;

        public FileSource(File root) {
            this.root = root;
        }

        private File safe(String relativePath) {
            File f = new File(root, relativePath);
            try {
                String base = root.getCanonicalPath();
                String target = f.getCanonicalPath();
                if (!target.startsWith(base)) {
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
            return f;
        }

        @Override
        public boolean exists(String relativePath) {
            File f = safe(relativePath);
            return f != null && f.isFile();
        }

        @Override
        public long length(String relativePath) {
            File f = safe(relativePath);
            return (f != null && f.isFile()) ? f.length() : -1;
        }

        @Override
        public InputStream open(String relativePath) throws IOException {
            File f = safe(relativePath);
            if (f == null || !f.isFile()) {
                throw new IOException("not a file: " + relativePath);
            }
            return new FileInputStream(f);
        }
    }

    private static final class Mount {
        final String prefix;
        final Source source;

        Mount(String prefix, Source source) {
            this.prefix = prefix;
            this.source = source;
        }
    }

    private final List<Mount> mounts = new ArrayList<Mount>();
    private ServerSocket serverSocket;
    private Thread acceptor;
    private volatile boolean running;
    private int port = -1;
    private final List<Thread> workers = Collections.synchronizedList(new ArrayList<Thread>());

    public synchronized void mount(String prefix, Source source) {
        String p = prefix;
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (!p.endsWith("/")) {
            p = p + "/";
        }
        for (int i = mounts.size() - 1; i >= 0; i--) {
            if (mounts.get(i).prefix.equals(p)) {
                mounts.remove(i);
            }
        }
        mounts.add(new Mount(p, source));
        Collections.sort(mounts, new Comparator<Mount>() {
            @Override
            public int compare(Mount a, Mount b) {
                return b.prefix.length() - a.prefix.length();
            }
        });
    }

    public synchronized int start() throws IOException {
        if (running && serverSocket != null && !serverSocket.isClosed()) {
            return port;
        }
        final ServerSocket ss = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        ss.setReuseAddress(true);
        serverSocket = ss;
        port = ss.getLocalPort();
        running = true;
        acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(ss);
            }
        }, "local-server-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        android.util.Log.i("LocalServer", "start port=" + port + " 线程=" + acceptor.getName());
        return port;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void stop() {
        running = false;
        ServerSocket ss = serverSocket;
        serverSocket = null;
        acceptor = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        // 换端口重启后旧 worker 还攥着旧 socket / 旧 FileSource，
        // 短时间内容易读到上一个工程的挂载，一起中断掉
        for (int i = 0; i < workers.size(); i++) {
            Thread w = workers.get(i);
            if (w != null) {
                w.interrupt();
            }
        }
        workers.clear();
    }

    /** 换端口重启：每次预览用新 origin，避免 WebView / ServiceWorker 跨工程读到旧缓存。 */
    public synchronized int restart() throws IOException {
        stop();
        port = -1;
        return start();
    }

    /** 只认自己那一代 socket，别在多线程里空指针把 accept 线程搞死。 */
    private void acceptLoop(ServerSocket ss) {
        android.util.Log.i("LocalServer", "accept 线程启动 port=" + ss.getLocalPort());
        while (running && ss == serverSocket && !ss.isClosed()) {
            Socket socket;
            try {
                socket = ss.accept();
            } catch (IOException e) {
                if (!running || ss.isClosed() || ss != serverSocket) {
                    break;
                }
                continue;
            }
            final Socket s = socket;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        handle(s);
                    } catch (Exception e) {
                        android.util.Log.w("LocalServer", "处理请求出错", e);
                    } finally {
                        try {
                            s.close();
                        } catch (IOException ignored) {
                        }
                        workers.remove(Thread.currentThread());
                    }
                }
            }, "local-server-worker");
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        android.util.Log.i("LocalServer", "accept 线程退出 port=" + port);
    }

    private void handle(Socket socket) throws IOException {
        socket.setSoTimeout(20000);
        InputStream rawIn = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        String requestLine = readLine(rawIn);
        if (requestLine == null || requestLine.isEmpty()) {
            return;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return;
        }
        String method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];
        android.util.Log.d("LocalServer", "收到 " + method + " " + target);

        Map<String, String> headers = new HashMap<String, String>();
        String line;
        int guard = 0;
        while ((line = readLine(rawIn)) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim().toLowerCase(Locale.US),
                        line.substring(idx + 1).trim());
            }
            if (++guard > 64) {
                break;
            }
        }

        if ("OPTIONS".equals(method)) {
            writeSimple(out, 204, "No Content", null, 0);
            return;
        }
        // 同机别的网页想读工程文件：Origin/Referer 不是本站一律拒掉
        if (!originAllowed(headers.get("origin"), headers.get("referer"))) {
            android.util.Log.w("LocalServer", "拒绝跨源请求 origin=" + headers.get("origin")
                    + " referer=" + headers.get("referer"));
            writeSimple(out, 403, "Forbidden", "text/plain; charset=utf-8", 0);
            return;
        }
        boolean headOnly = "HEAD".equals(method);
        if (!"GET".equals(method) && !headOnly) {
            writeSimple(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", 0);
            return;
        }

        String path = normalize(target);
        if (path == null) {
            writeSimple(out, 400, "Bad Request", "text/plain; charset=utf-8", 0);
            return;
        }

        Mount mount = null;
        String relative = null;
        for (int i = 0; i < mounts.size(); i++) {
            Mount m = mounts.get(i);
            if (path.equals(m.prefix) || path.startsWith(m.prefix)) {
                mount = m;
                relative = path.substring(m.prefix.length());
                break;
            }
        }
        if (mount == null && path.equals("/")) {
            for (int i = 0; i < mounts.size(); i++) {
                if (mounts.get(i).prefix.equals("/")) {
                    mount = mounts.get(i);
                    relative = "";
                    break;
                }
            }
        }

        if (mount == null) {
            android.util.Log.w("LocalServer", "404 无挂载 " + path);
            writeText(out, 404, "Not Found: " + path);
            return;
        }

        if (relative.isEmpty() || relative.endsWith("/")) {
            relative = relative + "index.html";
        }

        long total = mount.source.length(relative);
        android.util.Log.d("LocalServer", method + " " + path + " → " + mount.prefix + relative
                + " len=" + total);
        if (total < 0) {
            writeText(out, 404, "Not Found: " + path);
            return;
        }

        String mime = mimeOf(relative);
        String rangeHeader = headers.get("range");
        long startByte = 0;
        long endByte = total - 1;
        boolean partial = false;

        if (rangeHeader != null && total > 0 && rangeHeader.toLowerCase(Locale.US).startsWith("bytes=")) {
            String spec = rangeHeader.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String a = spec.substring(0, dash).trim();
                String b = spec.substring(dash + 1).trim();
                try {
                    if (a.isEmpty() && !b.isEmpty()) {
                        long suffix = Long.parseLong(b);
                        startByte = Math.max(0, total - suffix);
                        endByte = total - 1;
                        partial = true;
                    } else if (!a.isEmpty()) {
                        startByte = Long.parseLong(a);
                        endByte = b.isEmpty() ? total - 1 : Math.min(Long.parseLong(b), total - 1);
                        partial = startByte <= endByte && startByte < total;
                    }
                } catch (NumberFormatException ignored) {
                    partial = false;
                }
            }
        }

        if (partial) {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 206 Partial Content\r\n");
            sb.append("Content-Type: ").append(mime).append("\r\n");
            sb.append("Content-Length: ").append(endByte - startByte + 1).append("\r\n");
            sb.append("Content-Range: bytes ").append(startByte).append("-").append(endByte)
                    .append("/").append(total).append("\r\n");
            appendCommonHeaders(sb);
            sb.append("\r\n");
            out.write(sb.toString().getBytes("UTF-8"));
        } else {
            startByte = 0;
            endByte = total - 1;
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 200 OK\r\n");
            sb.append("Content-Type: ").append(mime).append("\r\n");
            sb.append("Content-Length: ").append(total).append("\r\n");
            appendCommonHeaders(sb);
            sb.append("\r\n");
            out.write(sb.toString().getBytes("UTF-8"));
        }

        if (headOnly || total == 0) {
            out.flush();
            return;
        }

        InputStream in = null;
        try {
            in = mount.source.open(relative);
            long skip = startByte;
            while (skip > 0) {
                long skipped = in.skip(skip);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        break;
                    }
                    skipped = 1;
                }
                skip -= skipped;
            }
            long remaining = endByte - startByte + 1;
            byte[] buf = new byte[16384];
            while (remaining > 0) {
                int want = (int) Math.min((long) buf.length, remaining);
                int n = in.read(buf, 0, want);
                if (n <= 0) {
                    break;
                }
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.flush();
        } catch (IOException ignored) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void appendCommonHeaders(StringBuilder sb) {
        sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Cache-Control: no-store, no-cache, must-revalidate\r\n");
        sb.append("Pragma: no-cache\r\n");
        // 只回自己的 origin：* 等于对同机任何浏览器页面敞开读工程文件
        sb.append("Access-Control-Allow-Origin: ").append(ownOrigin()).append("\r\n");
        sb.append("Vary: Origin\r\n");
        sb.append("Connection: close\r\n");
    }

    private String ownOrigin() {
        int p = port > 0 ? port : this.port;
        return p > 0 ? "http://127.0.0.1:" + p : "http://127.0.0.1";
    }

    /** 请求带了 Origin/Referer 就必须是本站；不带的（同源导航、curl）放行。 */
    private boolean originAllowed(String origin, String referer) {
        String ref = origin != null && origin.length() > 0 ? origin : referer;
        if (ref == null || ref.length() == 0) {
            return true;
        }
        String low = ref.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("http://127.0.0.1:") || low.startsWith("http://localhost:")) {
            return true;
        }
        return false;
    }

    private void writeSimple(OutputStream out, int code, String reason, String mime, long len) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" ").append(reason).append("\r\n");
        if (mime != null) {
            sb.append("Content-Type: ").append(mime).append("\r\n");
        }
        sb.append("Content-Length: ").append(len).append("\r\n");
        appendCommonHeaders(sb);
        sb.append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        out.flush();
    }

    private void writeText(OutputStream out, int code, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" ").append(code == 404 ? "Not Found" : "OK").append("\r\n");
        sb.append("Content-Type: text/plain; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(bytes.length).append("\r\n");
        appendCommonHeaders(sb);
        sb.append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            if (buf.size() > 8192) {
                break;
            }
        }
        if (c == -1 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), "UTF-8");
    }

    /** 取路径部分、百分号解码、消掉 .. 与重复斜杠。 */
    private static String normalize(String target) {
        String p = target;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int f = p.indexOf('#');
        if (f >= 0) {
            p = p.substring(0, f);
        }
        if (!p.startsWith("/")) {
            int scheme = p.indexOf("://");
            if (scheme > 0) {
                int slash = p.indexOf('/', scheme + 3);
                p = slash >= 0 ? p.substring(slash) : "/";
            } else {
                p = "/" + p;
            }
        }
        p = percentDecode(p);
        if (p == null) {
            return null;
        }
        ArrayList<String> segs = new ArrayList<String>();
        String[] raw = p.split("/");
        for (int i = 0; i < raw.length; i++) {
            String s = raw[i];
            if (s.isEmpty() || ".".equals(s)) {
                continue;
            }
            if ("..".equals(s)) {
                if (segs.isEmpty()) {
                    return null;
                }
                segs.remove(segs.size() - 1);
                continue;
            }
            segs.add(s);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            sb.append('/').append(segs.get(i));
        }
        String result = sb.toString();
        if (result.isEmpty()) {
            result = "/";
        }
        if (p.endsWith("/") && !result.endsWith("/")) {
            result = result + "/";
        }
        return result;
    }

    private static String percentDecode(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                String hex = s.substring(i + 1, i + 3);
                try {
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static final Map<String, String> MIME = new HashMap<String, String>();

    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("htm", "text/html; charset=utf-8");
        MIME.put("js", "application/javascript; charset=utf-8");
        MIME.put("mjs", "application/javascript; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("txt", "text/plain; charset=utf-8");
        MIME.put("md", "text/plain; charset=utf-8");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("webp", "image/webp");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("gif", "image/gif");
        MIME.put("ico", "image/x-icon");
        MIME.put("avif", "image/avif");
        MIME.put("ttf", "font/ttf");
        MIME.put("otf", "font/otf");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("wav", "audio/wav");
        MIME.put("ogg", "audio/ogg");
        MIME.put("oga", "audio/ogg");
        MIME.put("m4a", "audio/mp4");
        MIME.put("mp4", "video/mp4");
        MIME.put("webm", "video/webm");
        MIME.put("wasm", "application/wasm");
        MIME.put("zip", "application/zip");
        MIME.put("apk", "application/vnd.android.package-archive");
        MIME.put("live2d", "application/octet-stream");
    }

    private static String mimeOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.US);
        String mime = MIME.get(ext);
        return mime == null ? "application/octet-stream" : mime;
    }
}
