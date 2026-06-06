// FishTTS - 直接调 fish.audio /v1/tts, 绕过 tiax
// 命令: #f / #fls (弹窗) / #fkey / #fid
// 弹窗里可滚动 / 搜索 / 切换默认 / 试听

import java.io.*;
import java.net.*;
import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import android.graphics.drawable.*;

// ==== 主题色 ====
String BG    = "#0F1115";
String CARD  = "#1A1D24";
String INPUT = "#252934";
String TEXT  = "#E8E9EC";
String HINT  = "#7C828E";
String ACC   = "#5CB6FF";
String DEF   = "#FFD166";

int C(String s) { return Color.parseColor(s); }

GradientDrawable round(String color, int radius) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(C(color));
    g.setCornerRadius(radius);
    return g;
}

GradientDrawable roundBorder(String fill, int radius, String border) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(C(fill));
    g.setCornerRadius(radius);
    g.setStroke(1, C(border));
    return g;
}

// ==== 预置音色 ====
String[][] VOICES = {
    {"御女茉莉",        "6ce7ea8ada884bf3889fa7c7fb206691"},
    {"骚气御姐音",      "f44181a3d6d444beae284ad585a1af37"},
    {"甜美女主播",      "e752df7d20cd4576af9a207520349a33"},
    {"高松灯",          "73b1537fdc11434b965ad01857438ec6"},
    {"小雯魅惑声",      "992fa0a96e454b339376d167137dfea6"},
    {"AI御姐",          "c189c7cff21c400ba67592406202a3a0"},
    {"砂狼白子",        "b51cc0ed29f940a895c35562a1f29e35"},
    {"御姐音塞进去",    "c028181136d145a5b335c3c5c8f6d61f"},
    {"要乐奈",          "42fce205364e4aac9d4c782680e99ac4"},
    {"JOK真喘",         "fd8438ddf6cc41caafc5cd10ece9a4f1"},
    {"软妹喘",          "47423cf562594a7896e852fdec64d2da"},
    {"御姐扫扫153",     "0521d07c553a4b61ba468218aa0ee5fa"},
    {"骚御姐娇喘",      "5c09bfed66ce4a968c3022d6f85c8e07"},
    {"嘉岚",            "fbe02f8306fc4d3d915e9871722a39d5"},
    {"娇喘女声",        "c57d36db93664cf39a4e94b5c1948752"},
    {"娇喘姨妈红",      "8ea681ecd0b44d5e8a93f4821ebbfcdb"},
    {"丁真",            "54a5170264694bfc8e9ad98df7bd89c3"},
    {"AD学姐(女御姐)",  "7f92f8afb8ec43bf81429cc1c9199cb1"},
    {"赛马娘",          "0eb38bc974e1459facca38b359e13511"},
    {"央视配音",        "59cb5986671546eaa6ca8ae6f29f6d22"},
    {"宣传片大气浑厚",  "dd43b30d04d9446a94ebe41f301229b5"},
    {"蒋介石",          "918a8277663d476b95e2c4867da0f6a6"},
    {"丁真锐刻五代",    "332941d1360c48949f1b4e0cabf912cd"},
    {"董宇辉",          "c7cbda1c101c4ce8906c046f01eca1a2"},
    {"樊登极限",        "bc9e47fd83a04010ad6617ed54b92ee3"},
    {"小小班班班",      "06aa54bcb8df4358bdd592eee07ef6cd"},
    {"四川话男(暴躁)",  "a7b370a8f2b149d8898824183c0fba9f"},
    {"四川女",          "68daf00d974545c1a8f783351d558ae6"},
    {"天津卫",          "8f5dee1642944b83beafe0dcfc874738"},
    {"春风吞噬(玄幻)",  "404075f7b5904d139e7535ce08bc26e0"},
    // ---- 男声补充 (v1.1) ----
    {"语文老师(青叔)",  "90408a01eb624c9891f9b336d42381f7"},
    {"王琨(沉稳大叔)",  "4f201abba2574feeae11e5ebf737859e"},
    {"郑翔洲(清朗少年)","ca8fb681ce2040958c15ede5eef86177"},
    {"曼波(活力男)",    "0f08cacd3e354471a4b94dd00b4cc4a3"},
    {"小明剑魔(中二)",  "a9372068ed0740b48326cf9a74d7496a"},
    {"雷军(AI名人)",    "aebaa2305aa2452fbdc8f41eec852a79"},
    // ---- 低音炮 (v1.2, 中年男旁白/广告/ASMR) ----
    {"磁性男声(低音炮)","48cce9c01c9a481e90c397d802ed8375"},
    {"低沉气泡音(ASMR)","966a0dc36e3040c2a4a1c4fec995cb96"},
    {"油腻气泡男5",     "b2acbd1034b744feacc72ff7c76b6fe6"},
    {"气泡带点骚(商务)","c8bafb3edf73427b8df6c1d40dac7582"}
};

String FISH_API = "https://api.fish.audio/v1/tts";
String FISH_MODEL_API = "https://api.fish.audio/model/";

void onLoad() {
    log("FishTTS loaded, " + VOICES.length + " voices ready");
}

void onUnload() {}

// ==== WAuxiliary 主面板插件列表点"设置"时触发 (v1.1) ====
// 直接复用 showListDialog: 列出所有音色, 可搜索/试听/切换默认/设 key
void openSettings() {
    showListDialog();
}

// ---------- 工具 ----------
String esc(String s) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '"') b.append("\\\"");
        else if (c == '\\') b.append("\\\\");
        else if (c == '\n') b.append("\\n");
        else if (c == '\r') b.append("\\r");
        else if (c == '\t') b.append("\\t");
        else if (c < 0x20) b.append(String.format("\\u%04x", (int)c));
        else b.append(c);
    }
    return b.toString();
}

int getDefIdx() {
    try {
        int n = Integer.parseInt(getString("default_idx", "1"));
        if (n >= 1 && n <= VOICES.length) return n;
    } catch (Exception e) {}
    return 1;
}

// ---------- HTTP TTS ----------
boolean fishTTS(String text, String voiceId, String mp3Path) {
    String token = getString("fish_key", "");
    if (token.isEmpty()) { toast("先 #fkey <你的token> 或点弹窗设置"); return false; }
    long t0 = System.currentTimeMillis();
    try {
        log("FishTTS step1: new URL");
        URL url = new URL(FISH_API);
        log("FishTTS step2: openConnection");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(45000);
        conn.setDoOutput(true);

        String body = "{\"text\":\"" + esc(text) + "\",\"reference_id\":\"" + voiceId + "\",\"format\":\"mp3\"}";
        log("FishTTS step3: write body (" + body.length() + " bytes)");
        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();
        log("FishTTS step4: body sent, waiting response (elapsed " + (System.currentTimeMillis()-t0) + "ms)");

        int code = conn.getResponseCode();
        log("FishTTS step5: got HTTP " + code + " (elapsed " + (System.currentTimeMillis()-t0) + "ms)");
        if (code != 200) {
            InputStream es = conn.getErrorStream();
            String err = "";
            if (es != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] eb = new byte[1024];
                int en;
                while ((en = es.read(eb)) > 0) bo.write(eb, 0, en);
                err = new String(bo.toByteArray(), "UTF-8");
            }
            toast("fish HTTP " + code + " " + err);
            log("FishTTS http=" + code + " body=" + err);
            return false;
        }

        InputStream is = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(mp3Path);
        byte[] buf = new byte[8192];
        int n; int total = 0;
        while ((n = is.read(buf)) > 0) { fos.write(buf, 0, n); total += n; }
        fos.flush();
        fos.close();
        is.close();
        log("FishTTS step6: download done " + total + " bytes (total " + (System.currentTimeMillis()-t0) + "ms)");
        return true;
    } catch (Exception e) {
        log("FishTTS err after " + (System.currentTimeMillis()-t0) + "ms: " + e.getClass().getName() + " " + e.getMessage());
        toast("FishTTS 异常 (" + (System.currentTimeMillis()-t0) + "ms): " + e.getClass().getSimpleName() + " " + e.getMessage());
        return false;
    }
}

void doTTS(String talker, String text, String voiceId, String label) {
    toast("生成中 (" + label + ")...");
    final String fTalker = talker;
    final String fText = text;
    final String fId = voiceId;
    final String fLabel = label;
    new Thread(new Runnable() {
        public void run() {
            String mp3 = cacheDir + "/fish_" + System.currentTimeMillis() + ".mp3";
            log("FishTTS doTTS: talker=" + fTalker + " mp3=" + mp3);
            if (!fishTTS(fText, fId, mp3)) { log("FishTTS doTTS: fishTTS failed"); return; }
            final long mp3Size = new File(mp3).length();
            log("FishTTS doTTS: mp3 ready, size=" + mp3Size);
            final String fMp3 = mp3;
            final String fSilk = mp3 + ".silk";
            long t1 = System.currentTimeMillis();
            int r = mp3ToSilk(mp3, fSilk, 24000);
            log("FishTTS doTTS: mp3ToSilk r=" + r + " elapsed=" + (System.currentTimeMillis()-t1) + "ms silkSize=" + (new File(fSilk).exists() ? new File(fSilk).length() : -1));
            if (r != 0) {
                toast("silk 转换失败 errno=" + r);
                new File(fMp3).delete();
                return;
            }
            // sendVoice 第三参数是 int 不是 long, BeanShell 匹配方法签名很严
            final int fDur = (int) getDuration(fSilk);
            log("FishTTS doTTS: silk ready dur=" + fDur + ", scheduling sendVoice on main thread");
            // ★ 关键修复:sendVoice 必须在 Android 主线程调用,否则进队列不发出
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        log("FishTTS doTTS: [main] calling sendVoice");
                        sendVoice(fTalker, fSilk, fDur);
                        log("FishTTS doTTS: [main] sendVoice returned");
                    } catch (Exception e) {
                        log("FishTTS doTTS: [main] sendVoice err " + e);
                        toast("发送失败: " + e.getMessage());
                    }
                    // 延迟删除文件,避免微信还在读
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        public void run() {
                            new File(fSilk).delete();
                            new File(fMp3).delete();
                            log("FishTTS doTTS: cleaned tmp files");
                        }
                    }, 5000);
                }
            });
        }
    }).start();
}

// 试听: 拉 fish.audio /model/{id} 的 samples[0].audio 播放
void previewVoice(String voiceId, String name) {
    toast("拉取 " + name + " 示例...");
    final String fId = voiceId;
    final String fName = name;
    new Thread(new Runnable() {
        public void run() {
            try {
                URL url = new URL(FISH_MODEL_API + fId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                int code = conn.getResponseCode();
                if (code != 200) { toast("HTTP " + code); return; }
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
                r.close();
                String body = sb.toString();
                // 简单解析 "samples":[{"...","audio":"URL"
                int p = body.indexOf("\"samples\"");
                if (p < 0) { toast("无 sample"); return; }
                int ap = body.indexOf("\"audio\"", p);
                if (ap < 0) { toast("无 audio url"); return; }
                int colon = body.indexOf(":", ap);
                int q1 = body.indexOf("\"", colon + 1);
                int q2 = body.indexOf("\"", q1 + 1);
                String audioUrl = body.substring(q1 + 1, q2);

                String mp3 = cacheDir + "/preview_" + System.currentTimeMillis() + ".mp3";
                URL au = new URL(audioUrl);
                HttpURLConnection ac = (HttpURLConnection) au.openConnection();
                InputStream is = ac.getInputStream();
                FileOutputStream fos = new FileOutputStream(mp3);
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                is.close(); fos.close();

                // 用 MediaPlayer 本地播放(不发到聊天)
                android.media.MediaPlayer mp = new android.media.MediaPlayer();
                mp.setDataSource(mp3);
                mp.prepare();
                mp.start();
                mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                    public void onCompletion(android.media.MediaPlayer p) {
                        p.release();
                        new File(mp3).delete();
                    }
                });
            } catch (Exception e) {
                toast("试听失败 " + e.getMessage());
                log("preview err: " + e);
            }
        }
    }).start();
}

// ---------- 主弹窗 ----------
String filterKeyword = "";

void showListDialog() {
    Activity ctx = getTopActivity();
    if (ctx == null) { toast("UI 不可用"); return; }

    final AlertDialog[] dialogHolder = new AlertDialog[1];
    final EditText[] searchHolder = new EditText[1];
    final LinearLayout[] listHolder = new LinearLayout[1];

    LinearLayout root = new LinearLayout(ctx);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(28, 24, 28, 16);
    root.setBackgroundColor(C(BG));

    // 标题 + 当前默认
    TextView title = new TextView(ctx);
    title.setText("FishTTS 音色");
    title.setTextSize(18);
    title.setTextColor(C(TEXT));
    title.getPaint().setFakeBoldText(true);
    title.setGravity(Gravity.CENTER);
    title.setPadding(0, 0, 0, 12);
    root.addView(title);

    int defIdx = getDefIdx();
    TextView curView = new TextView(ctx);
    curView.setText("★ 当前默认: #" + defIdx + " " + VOICES[defIdx - 1][0]);
    curView.setTextSize(13);
    curView.setTextColor(C(DEF));
    curView.setPadding(0, 0, 0, 10);
    curView.setGravity(Gravity.CENTER);
    root.addView(curView);

    // 搜索框
    EditText search = new EditText(ctx);
    search.setHint("搜索名字关键字");
    search.setHintTextColor(C(HINT));
    search.setTextColor(C(TEXT));
    search.setTextSize(14);
    search.setSingleLine(true);
    search.setBackground(round(INPUT, 16));
    search.setPadding(22, 16, 22, 16);
    LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    searchLp.setMargins(0, 0, 0, 12);
    root.addView(search, searchLp);
    searchHolder[0] = search;

    // 滚动列表
    ScrollView scroll = new ScrollView(ctx);
    LinearLayout list = new LinearLayout(ctx);
    list.setOrientation(LinearLayout.VERTICAL);
    listHolder[0] = list;
    scroll.addView(list);
    LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
    root.addView(scroll, scrollLp);

    rebuildList(ctx, list, "", curView, dialogHolder);

    search.addTextChangedListener(new android.text.TextWatcher() {
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
        public void afterTextChanged(android.text.Editable e) {
            filterKeyword = e.toString().trim();
            rebuildList(ctx, listHolder[0], filterKeyword, curView, dialogHolder);
        }
    });

    // 底部按钮: Key / 关闭
    LinearLayout btnRow = new LinearLayout(ctx);
    btnRow.setOrientation(LinearLayout.HORIZONTAL);
    btnRow.setPadding(0, 12, 0, 0);

    TextView keyBtn = new TextView(ctx);
    keyBtn.setText("🔑 Key 配置");
    keyBtn.setTextSize(14);
    keyBtn.setTextColor(C(TEXT));
    keyBtn.setBackground(round(CARD, 24));
    keyBtn.setPadding(28, 16, 28, 16);
    keyBtn.setGravity(Gravity.CENTER);
    keyBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            showKeyDialog();
        }
    });
    LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    keyLp.setMargins(0, 0, 6, 0);
    btnRow.addView(keyBtn, keyLp);

    TextView closeBtn = new TextView(ctx);
    closeBtn.setText("关闭");
    closeBtn.setTextSize(14);
    closeBtn.setTextColor(C(TEXT));
    closeBtn.setBackground(round(CARD, 24));
    closeBtn.setPadding(28, 16, 28, 16);
    closeBtn.setGravity(Gravity.CENTER);
    closeBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        }
    });
    LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    closeLp.setMargins(6, 0, 0, 0);
    btnRow.addView(closeBtn, closeLp);

    root.addView(btnRow);

    AlertDialog dialog = new AlertDialog.Builder(ctx).setView(root).create();
    try { dialog.getWindow().setBackgroundDrawable(new ColorDrawable(C(BG))); } catch (Exception e) {}
    dialogHolder[0] = dialog;
    dialog.show();
}

// ★ BeanShell 闭包陷阱: 内部类引用的循环变量会全部抓最后一次值
// 解法: 把每行的创建抽成独立函数, 参数作为方法局部 final, 才真正隔离
View buildRow(final Activity ctx, final LinearLayout list, final TextView curView, final String kw,
              final int idx, final String name, final String vid, final int defIdx) {
    LinearLayout row = new LinearLayout(ctx);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setBackground(round(idx == defIdx ? INPUT : CARD, 14));
    row.setPadding(20, 14, 14, 14);

    TextView nameView = new TextView(ctx);
    String prefix = idx == defIdx ? "★ #" : "#";
    nameView.setText(prefix + idx + "  " + name);
    nameView.setTextSize(14);
    nameView.setTextColor(C(idx == defIdx ? DEF : TEXT));
    LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    row.addView(nameView, nameLp);

    TextView playBtn = new TextView(ctx);
    playBtn.setText("🔊");
    playBtn.setTextSize(16);
    playBtn.setPadding(14, 6, 14, 6);
    playBtn.setBackground(round(INPUT, 16));
    playBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) { previewVoice(vid, name); }
    });
    LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    playLp.setMargins(6, 0, 6, 0);
    row.addView(playBtn, playLp);

    TextView setBtn = new TextView(ctx);
    setBtn.setText("设为默认");
    setBtn.setTextSize(12);
    setBtn.setTextColor(Color.WHITE);
    setBtn.setBackground(round(ACC, 16));
    setBtn.setPadding(16, 8, 16, 8);
    setBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            putString("default_idx", String.valueOf(idx));
            curView.setText("★ 当前默认: #" + idx + " " + name);
            toast("默认音色 → #" + idx + " " + name);
            rebuildList(ctx, list, kw, curView, null);
        }
    });
    row.addView(setBtn);
    return row;
}

void rebuildList(final Activity ctx, final LinearLayout list, final String kw, final TextView curView, final AlertDialog[] dialogHolder) {
    list.removeAllViews();
    int defIdx = getDefIdx();
    for (int i = 0; i < VOICES.length; i++) {
        int idx = i + 1;
        String name = VOICES[i][0];
        String vid = VOICES[i][1];
        if (!kw.isEmpty() && !name.toLowerCase().contains(kw.toLowerCase()) && !String.valueOf(idx).equals(kw)) continue;
        View row = buildRow(ctx, list, curView, kw, idx, name, vid, defIdx);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 4, 0, 4);
        list.addView(row, rowLp);
    }
}

// ---------- Key 弹窗 ----------
void showKeyDialog() {
    Activity ctx = getTopActivity();
    if (ctx == null) { toast("UI 不可用"); return; }

    LinearLayout root = new LinearLayout(ctx);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(28, 24, 28, 16);
    root.setBackgroundColor(C(BG));

    TextView title = new TextView(ctx);
    title.setText("fish.audio API Key");
    title.setTextSize(18);
    title.setTextColor(C(TEXT));
    title.getPaint().setFakeBoldText(true);
    title.setGravity(Gravity.CENTER);
    title.setPadding(0, 0, 0, 12);
    root.addView(title);

    TextView hint = new TextView(ctx);
    hint.setText("注册 https://fish.audio → 控制台 → API 密钥 → 新建。\n粘贴下方。");
    hint.setTextSize(13);
    hint.setTextColor(C(HINT));
    hint.setPadding(0, 0, 0, 14);
    root.addView(hint);

    final EditText edit = new EditText(ctx);
    edit.setText(getString("fish_key", ""));
    edit.setHint("粘贴你的 fish.audio token");
    edit.setHintTextColor(C(HINT));
    edit.setTextColor(C(TEXT));
    edit.setTextSize(14);
    edit.setSingleLine(true);
    edit.setBackground(round(INPUT, 16));
    edit.setPadding(22, 16, 22, 16);
    root.addView(edit);

    LinearLayout btnRow = new LinearLayout(ctx);
    btnRow.setOrientation(LinearLayout.HORIZONTAL);
    btnRow.setPadding(0, 16, 0, 0);

    final AlertDialog[] dh = new AlertDialog[1];

    TextView saveBtn = new TextView(ctx);
    saveBtn.setText("保存");
    saveBtn.setTextSize(14);
    saveBtn.setTextColor(Color.WHITE);
    saveBtn.setBackground(round(ACC, 24));
    saveBtn.setPadding(28, 16, 28, 16);
    saveBtn.setGravity(Gravity.CENTER);
    saveBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            putString("fish_key", edit.getText().toString().trim());
            toast("已保存");
            if (dh[0] != null) dh[0].dismiss();
        }
    });
    LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    saveLp.setMargins(0, 0, 6, 0);
    btnRow.addView(saveBtn, saveLp);

    TextView cancelBtn = new TextView(ctx);
    cancelBtn.setText("取消");
    cancelBtn.setTextSize(14);
    cancelBtn.setTextColor(C(TEXT));
    cancelBtn.setBackground(round(CARD, 24));
    cancelBtn.setPadding(28, 16, 28, 16);
    cancelBtn.setGravity(Gravity.CENTER);
    cancelBtn.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) { if (dh[0] != null) dh[0].dismiss(); }
    });
    LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    cLp.setMargins(6, 0, 0, 0);
    btnRow.addView(cancelBtn, cLp);

    root.addView(btnRow);

    AlertDialog d = new AlertDialog.Builder(ctx).setView(root).create();
    try { d.getWindow().setBackgroundDrawable(new ColorDrawable(C(BG))); } catch (Exception e) {}
    dh[0] = d;
    d.show();
}

// ---------- 命令分发 ----------
boolean onClickSendBtn(String text) {
    if (text == null) return false;

    // #fls / #f列表 → 弹窗
    if (text.equals("#fls") || text.equals("#flist") || text.equals("#f列表") || text.equals("帮助 fish")) {
        showListDialog();
        return true;
    }

    // #fkey [key] → 弹窗或行内设置
    if (text.equals("#fkey")) {
        showKeyDialog();
        return true;
    }
    if (text.startsWith("#fkey ")) {
        String k = text.substring(6).trim();
        if (!k.isEmpty()) { putString("fish_key", k); toast("key 已保存"); }
        return true;
    }

    // #fset N
    if (text.startsWith("#fset ")) {
        String n = text.substring(6).trim();
        try {
            int idx = Integer.parseInt(n);
            if (idx < 1 || idx > VOICES.length) { toast("范围 1-" + VOICES.length); return true; }
            putString("default_idx", String.valueOf(idx));
            toast("默认: #" + idx + " " + VOICES[idx - 1][0]);
        } catch (Exception e) { toast("序号格式错"); }
        return true;
    }

    // #fid <id> <text>
    if (text.startsWith("#fid ")) {
        String rest = text.substring(5).trim();
        int sp = rest.indexOf(' ');
        if (sp <= 0) { toast("用法: #fid <fish_id> 文本"); return true; }
        String id = rest.substring(0, sp).trim();
        String msg = rest.substring(sp + 1).trim();
        if (msg.isEmpty()) { toast("文本空"); return true; }
        doTTS(getTargetTalker(), msg, id, "临时");
        return true;
    }

    // #f [N] 文本
    if (text.startsWith("#f ")) {
        String rest = text.substring(3).trim();
        if (rest.isEmpty()) { toast("用法: #f 文本 / #f 序号 文本"); return true; }
        int idx = -1;
        String msg = rest;
        int sp = rest.indexOf(' ');
        if (sp > 0) {
            String maybe = rest.substring(0, sp).trim();
            try {
                int n = Integer.parseInt(maybe);
                if (n >= 1 && n <= VOICES.length) {
                    idx = n;
                    msg = rest.substring(sp + 1).trim();
                }
            } catch (Exception ignore) {}
        }
        if (msg.isEmpty()) { toast("文本空"); return true; }
        if (idx < 0) idx = getDefIdx();
        doTTS(getTargetTalker(), msg, VOICES[idx - 1][1], VOICES[idx - 1][0]);
        return true;
    }

    return false;
}
