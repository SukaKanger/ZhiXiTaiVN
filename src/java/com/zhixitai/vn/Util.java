/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 WebGAL 移动工作站作者
 * 本文件为原创代码，许可证见随附 LICENSE-MIT.txt
 */
package com.zhixitai.vn;

import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Util {

    public static final String TAG = "WGStation";

    private Util() {
    }

    public static String readText(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return readText(in);
        } catch (Exception e) {
            return null;
        } finally {
            close(in);
        }
    }

    public static String readText(InputStream in) {
        if (in == null) {
            return null;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        try {
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
        } catch (IOException e) {
            return null;
        }
        try {
            return new String(buf.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 APK assets 读文本。 */
    public static String readAsset(AssetManager am, String path) {
        InputStream in = null;
        try {
            in = am.open(path);
            return readText(in);
        } catch (Exception e) {
            return null;
        } finally {
            close(in);
        }
    }

    public static boolean writeText(File f, String text) {
        FileOutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "writeText failed: " + f, e);
            return false;
        } finally {
            close(out);
        }
    }

    public static boolean copyAsset(AssetManager am, String assetPath, File destFile) {
        InputStream in = null;
        OutputStream out = null;
        try {
            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            in = am.open(assetPath);
            out = new FileOutputStream(destFile);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "copyAsset failed: " + assetPath, e);
            return false;
        } finally {
            close(in);
            close(out);
        }
    }

    public interface FileVisitor {
        void onFile(File file) throws IOException;
    }

    /** 递归复制 assets 里的目录。返回复制的文件数。 */
    public static int copyAssetTree(AssetManager am, String assetDir, File destDir, FileVisitor visitor) throws IOException {
        int count = 0;
        String[] children = am.list(assetDir);
        if (children == null || children.length == 0) {
            // 目录为空或本身是文件
            if (!destDir.exists()) {
                destDir.mkdirs();
            }
            return 0;
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("cannot create dir: " + destDir);
        }
        for (int i = 0; i < children.length; i++) {
            String name = children[i];
            String childAsset = assetDir.isEmpty() ? name : assetDir + "/" + name;
            File childDest = new File(destDir, name);
            String[] grand = am.list(childAsset);
            if (grand != null && grand.length > 0) {
                count += copyAssetTree(am, childAsset, childDest, visitor);
            } else {
                if (!copyAsset(am, childAsset, childDest)) {
                    throw new IOException("copy asset failed: " + childAsset);
                }
                count++;
                if (visitor != null) {
                    visitor.onFile(childDest);
                }
            }
        }
        return count;
    }

    /** 递归复制普通目录。返回复制的文件数。 */
    public static int copyTree(File src, File dest, FileVisitor visitor) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs()) {
                throw new IOException("cannot create dir: " + dest);
            }
            File[] kids = src.listFiles();
            int count = 0;
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) {
                    count += copyTree(kids[i], new File(dest, kids[i].getName()), visitor);
                }
            }
            return count;
        } else {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("cannot create dir: " + parent);
            }
            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                close(in);
                close(out);
            }
            if (visitor != null) {
                visitor.onFile(dest);
            }
            return 1;
        }
    }

    public static long dirSize(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        if (dir.isFile()) {
            return dir.length();
        }
        long total = 0;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                total += dirSize(kids[i]);
            }
        }
        return total;
    }

    public static int countFiles(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        if (dir.isFile()) {
            return 1;
        }
        int total = 0;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                total += countFiles(kids[i]);
            }
        }
        return total;
    }

    public static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) {
                    deleteTree(kids[i]);
                }
            }
        }
        if (!f.delete()) {
            Log.w(TAG, "delete failed: " + f);
        }
    }

    public static List<File> listScenes(File gameDir) {
        List<File> out = new ArrayList<File>();
        if (gameDir == null) {
            return out;
        }
        File sceneDir = new File(gameDir, "scene");
        File[] files = sceneDir.listFiles();
        if (files == null) {
            return out;
        }
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile() && files[i].getName().toLowerCase(Locale.US).endsWith(".txt")) {
                out.add(files[i]);
            }
        }
        return out;
    }

    /** 从 game/config.txt 里读 Game_name。 */
    public static String readGameName(File gameDir) {
        if (gameDir == null) {
            return null;
        }
        String text = readText(new File(gameDir, "config.txt"));
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("Game_name:")) {
                String value = line.substring("Game_name:".length()).trim();
                if (value.endsWith(";")) {
                    value = value.substring(0, value.length() - 1);
                }
                return value.trim();
            }
        }
        return null;
    }

    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    public static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 简易包名校验：至少两段，段内字母开头，只允许字母数字下划线。 */
    public static boolean validPackage(String pkg) {
        if (pkg == null) {
            return false;
        }
        String[] parts = pkg.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                return false;
            }
            char c0 = p.charAt(0);
            if (!((c0 >= 'a' && c0 <= 'z') || (c0 >= 'A' && c0 <= 'Z'))) {
                return false;
            }
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
                if (!ok) {
                    return false;
                }
            }
        }
        for (int i = 0; i < RESERVED_PACKAGES.length; i++) {
            if (RESERVED_PACKAGES[i].equals(pkg)) {
                return false;
            }
        }
        return true;
    }

    private static final String[] RESERVED_PACKAGES = {
            "android", "com.android", "java", "javax", "com.termux"
    };

    public static String slugify(String name) {
        if (name == null || name.isEmpty()) {
            return "game";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 24; i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
            } else if (c >= 0x4e00 && c <= 0x9fff) {
                sb.append(c);
            } else if (c == ' ' || c == '_' || c == '-') {
                sb.append('_');
            }
        }
        String s = sb.toString();
        if (s.isEmpty()) {
            s = "game";
        }
        return s.toLowerCase(Locale.US).replaceAll("^_+|_+$", "");
    }

    public static String jsonString(JSONObject o, String key, String fallback) {
        if (o == null || !o.has(key)) {
            return fallback;
        }
        String v = o.optString(key, fallback);
        return v == null ? fallback : v;
    }

    public static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
