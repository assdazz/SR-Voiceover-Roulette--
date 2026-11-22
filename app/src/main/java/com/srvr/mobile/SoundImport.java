package com.srvr.mobile;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SoundImport {
    static final int REQUEST = 22;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SoundImport() {}

    static void open(Activity activity) {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            activity.startActivityForResult(picker, REQUEST);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "此设备没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    static void copy(Context context, SoundLibrary library, Intent data, String setId) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        Context app = context.getApplicationContext();
        Toast.makeText(app, "正在导入音频…", Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            List<SoundLibrary.Sound> copies = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    copies.add(library.copy(uri));
                } catch (IOException | RuntimeException e) {
                    android.util.Log.w("SRVR", "Could not import selected audio", e);
                }
            }
            MAIN.post(() -> {
                if (!copies.isEmpty()) library.register(copies, setId);
                int failed = uris.size() - copies.size();
                String message = "已导入 " + copies.size() + " 个音效";
                if (failed > 0) message += "，" + failed + " 个文件读取失败";
                Toast.makeText(app, message, Toast.LENGTH_SHORT).show();
            });
        });
    }
}
