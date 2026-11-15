/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 WebGAL 移动工作站作者
 * 本文件为原创代码，许可证见随附 LICENSE-MIT.txt
 */
package com.zhixitai.vn;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 工程库：用 SharedPreferences 存一份 JSON 清单，够轻量，也不需要数据库。 */
public class Store {

    public static class Project {
        public String id = "";
        public String name = "";
        /** 工程根目录（绝对路径） */
        public String dir = "";
        /** 真正给引擎用的 game 目录（绝对路径），可能是 dir 本身，也可能是 dir/game */
        public String gameDir = "";
        public String gameName = "";
        public String source = "import";
        public long bytes = 0;
        public int files = 0;
        public int scenes = 0;
        public long createdAt = 0;
        /** 上次打包用掉的 versionCode，下一次自动 +1，免得同号覆盖装不上 */
        public int lastVersionCode = 0;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("dir", dir);
                o.put("gameDir", gameDir);
                o.put("gameName", gameName);
                o.put("source", source);
                o.put("bytes", bytes);
                o.put("files", files);
                o.put("scenes", scenes);
                o.put("createdAt", createdAt);
                o.put("lastVersionCode", lastVersionCode);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Project fromJson(JSONObject o) {
            Project p = new Project();
            p.id = o.optString("id", "");
            p.name = o.optString("name", "");
            p.dir = o.optString("dir", "");
            p.gameDir = o.optString("gameDir", "");
            p.gameName = o.optString("gameName", "");
            p.source = o.optString("source", "import");
            p.bytes = o.optLong("bytes", 0);
            p.files = o.optInt("files", 0);
            p.scenes = o.optInt("scenes", 0);
            p.createdAt = o.optLong("createdAt", 0);
            p.lastVersionCode = o.optInt("lastVersionCode", 0);
            return p;
        }

        public JSONObject toUiJson() {
            JSONObject o = toJson();
            try {
                o.put("bytesText", Util.human(bytes));
                o.put("exists", new File(gameDir).exists());
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    private static final String PREF = "webgal_station";
    private static final String KEY_PROJECTS = "projects";
    private static final String KEY_BACKUP = "projects_backup";
    private static final String KEY_SCHEMA = "projects_schema";
    private static final String KEY_DISCLAIMER = "disclaimer_ack";
    /** 清单结构版本号：加字段时改这个，fromJson 里就能分辨老数据。 */
    private static final int SCHEMA = 2;

    private final SharedPreferences sp;
    private final List<Project> projects = new ArrayList<Project>();
    /** 工程目录根（由 Activity 在拿得到 getBuildRoot() 之后塞进来），清单坏了靠它重建。 */
    private File projectsDir;
    /** 上次启动时清单读坏了（坏数据已备份）。界面据此提示「已从磁盘重建」。 */
    private boolean recovered = false;

    public Store(Context ctx) {
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        load();
    }

    public void setProjectsDir(File dir) {
        this.projectsDir = dir;
        // 构造时还不知道工程目录（得先拿 getBuildRoot），清单已经坏了的就在这里补一次重建
        if (recovered && projects.isEmpty()) {
            rebuildFromDisk();
        }
    }

    private synchronized void load() {
        projects.clear();
        recovered = false;
        String raw = sp.getString(KEY_PROJECTS, null);
        if (raw == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                projects.add(Project.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            // 清单坏了以前是「清空内存 + 什么都不说」，用户打开就以为工程全没了。
            // 现在把坏串原样备份，再从 projects/ 目录扫一遍重建。
            Log.w(Util.TAG, "load projects failed, backup + rebuild", e);
            sp.edit().putString(KEY_BACKUP, raw).apply();
            recovered = true;
            rebuildFromDisk();
        }
    }

    /** 从 projects/ 目录把工程重新入库（只认带 config.txt 的目录）。 */
    private void rebuildFromDisk() {
        File[] kids = projectsDir == null ? null : projectsDir.listFiles();
        if (kids == null) {
            return;
        }
        for (int i = 0; i < kids.length; i++) {
            File dir = kids[i];
            File game = new File(dir, "game");
            if (!game.isDirectory()) {
                game = dir;
            }
            File cfg = new File(game, "config.txt");
            if (!cfg.isFile()) {
                continue;
            }
            Project p = new Project();
            p.id = dir.getName();
            String gameName = Util.readGameName(game);
            p.name = gameName == null || gameName.isEmpty() ? dir.getName() : gameName;
            p.dir = dir.getAbsolutePath();
            p.gameDir = game.getAbsolutePath();
            p.gameName = p.name;
            p.source = "recovered";
            p.createdAt = dir.lastModified();
            projects.add(p);
        }
        persist();
        Log.i(Util.TAG, "rebuild from disk: " + projects.size() + " 个工程");
    }

    /** 是否发生过「清单损坏 → 备份 + 重建」，前端据此提示用户。 */
    public boolean wasRecovered() {
        return recovered;
    }

    /** 备份键里那份坏数据（供用户/作者排查），没有就返回 null。 */
    public String rawBackup() {
        return sp.getString(KEY_BACKUP, null);
    }

    private synchronized void persist() {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < projects.size(); i++) {
            arr.put(projects.get(i).toJson());
        }
        sp.edit().putString(KEY_PROJECTS, arr.toString()).putInt(KEY_SCHEMA, SCHEMA).apply();
    }

    public synchronized List<Project> list() {
        return new ArrayList<Project>(projects);
    }

    public synchronized Project find(String id) {
        for (int i = 0; i < projects.size(); i++) {
            if (projects.get(i).id.equals(id)) {
                return projects.get(i);
            }
        }
        return null;
    }

    public synchronized void add(Project p) {
        for (int i = projects.size() - 1; i >= 0; i--) {
            if (projects.get(i).id.equals(p.id)) {
                projects.remove(i);
            }
        }
        projects.add(0, p);
        persist();
    }

    public synchronized void remove(String id) {
        for (int i = projects.size() - 1; i >= 0; i--) {
            if (projects.get(i).id.equals(id)) {
                projects.remove(i);
            }
        }
        persist();
    }

    public synchronized void rename(String id, String newName) {
        Project p = find(id);
        if (p != null) {
            p.name = newName;
            persist();
        }
    }

    /** 记住这次打包用掉的 versionCode，下次自动往上加。 */
    public synchronized void setLastVersionCode(String id, int code) {
        Project p = find(id);
        if (p != null && code > p.lastVersionCode) {
            p.lastVersionCode = code;
            persist();
        }
    }

    public boolean disclaimerAccepted() {
        return sp.getBoolean(KEY_DISCLAIMER, false);
    }

    public void acceptDisclaimer() {
        sp.edit().putBoolean(KEY_DISCLAIMER, true).apply();
    }

    public String getString(String key, String def) {
        return sp.getString(key, def);
    }

    public void putString(String key, String value) {
        sp.edit().putString(key, value).apply();
    }
}
