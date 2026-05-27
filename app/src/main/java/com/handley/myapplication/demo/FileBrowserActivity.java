package com.handley.myapplication.demo;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.handley.myapplication.R;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FileBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_SELECTED_FILE = "selected_file";
    private static final String DEFAULT_DIR = "/storage/emulated/0/Android/data/com.handley.TestP/files/dump";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private TextView tvCurrentPath;
    private RecyclerView recyclerView;
    private File currentDir;
    private FileBrowserAdapter adapter;
    private android.widget.Button btnParent;
    private android.widget.Button btnTmp;
    private android.widget.Button btnStorage;
    private android.widget.Button btnDump;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        tvCurrentPath = findViewById(R.id.tv_current_path);
        recyclerView = findViewById(R.id.recycler_view);
        btnParent = findViewById(R.id.btn_parent);
        btnTmp = findViewById(R.id.btn_tmp);
        btnStorage = findViewById(R.id.btn_storage);
        btnDump = findViewById(R.id.btn_dump);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        btnParent.setOnClickListener(v -> goToParent());
        btnTmp.setOnClickListener(v -> goToTmp());
        btnStorage.setOnClickListener(v -> goToStorage());
        btnDump.setOnClickListener(v -> goToDump());

        // 检查权限
        if (hasStoragePermission()) {
            navigateToInitialDir();
        } else {
            requestStoragePermission();
        }
    }

    private void goToParent() {
        if (currentDir != null && currentDir.getParentFile() != null) {
            navigateTo(currentDir.getParentFile());
        } else {
            Toast.makeText(this, "已经是根目录", Toast.LENGTH_SHORT).show();
        }
    }

    private void goToTmp() {
        File tmpDir = new File("/data/local/tmp");
        if (tmpDir.exists() && tmpDir.isDirectory()) {
            navigateTo(tmpDir);
        } else {
            Toast.makeText(this, "目录不存在: " + tmpDir.getAbsolutePath(), Toast.LENGTH_SHORT).show();
        }
    }

    private void goToStorage() {
        File storageDir = new File("/storage/emulated/0");
        if (storageDir.exists() && storageDir.isDirectory()) {
            navigateTo(storageDir);
        } else {
            Toast.makeText(this, "目录不存在: " + storageDir.getAbsolutePath(), Toast.LENGTH_SHORT).show();
        }
    }

    private void goToDump() {
        File dumpDir = new File("/storage/emulated/0/Android/data/com.handley.TestP/files/dump");
        if (dumpDir.exists() && dumpDir.isDirectory()) {
            navigateTo(dumpDir);
        } else {
            Toast.makeText(this, "目录不存在: " + dumpDir.getAbsolutePath(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, "android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{"android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"},
                PERMISSION_REQUEST_CODE);
    }

    private void navigateToInitialDir() {
        // 初始目录
        File initialDir = new File(DEFAULT_DIR);
        if (!initialDir.exists() || !initialDir.isDirectory()) {
            initialDir = Environment.getExternalStorageDirectory();
        }
        navigateTo(initialDir);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                navigateToInitialDir();
            } else {
                Toast.makeText(this, "需要存储权限才能浏览文件", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void navigateTo(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }

        // 检查是否可以访问该目录
        if (!dir.canRead()) {
            Toast.makeText(this, "无法访问该目录: " + dir.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            return;
        }

        currentDir = dir;
        tvCurrentPath.setText(dir.getAbsolutePath());

        File[] files = dir.listFiles();
        List<File> fileList = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() || f.getName().endsWith(".h264")) {
                    fileList.add(f);
                }
            }
            fileList.sort((a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
        } else {
            Toast.makeText(this, "无法读取目录内容", Toast.LENGTH_SHORT).show();
        }

        adapter = new FileBrowserAdapter(fileList, new FileBrowserAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(File file) {
                if (file.isDirectory()) {
                    navigateTo(file);
                } else {
                    returnResult(file);
                }
            }
        });
        recyclerView.setAdapter(adapter);
    }

    private void returnResult(File file) {
        Intent intent = new Intent();
        intent.putExtra(EXTRA_SELECTED_FILE, file.getAbsolutePath());
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (currentDir != null && currentDir.getParentFile() != null) {
            navigateTo(currentDir.getParentFile());
        } else {
            super.onBackPressed();
        }
    }
}
