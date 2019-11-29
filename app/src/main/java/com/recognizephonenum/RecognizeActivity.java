package com.recognizephonenum;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewStub;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.Result;
import com.recognizephonenum.camera.CameraManager;
import com.recognizephonenum.decode.CaptureActivityHandler;
import com.recognizephonenum.decode.DecodeManager;
import com.recognizephonenum.tess.TesseractCallback;
import com.recognizephonenum.tess.TesseractThread;
import com.recognizephonenum.utils.Tools;
import com.recognizephonenum.view.ScannerFinderView;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;


public class RecognizeActivity extends AppCompatActivity implements Callback, Camera.PictureCallback, Camera.ShutterCallback {
    private static final String TAG = "ScannerActivity";
    private CaptureActivityHandler mCaptureActivityHandler;
    private boolean mHasSurface;
    private ScannerFinderView mQrCodeFinderView;
    private SurfaceView mSurfaceView;
    private ViewStub mSurfaceViewStub;
    private DecodeManager mDecodeManager = new DecodeManager();
    private ListView mListView;
    private List<String> listPhones = new ArrayList<>();
    private EditText mEditText;
    private Button mBtnDone;
    private Button mBtnNext;

    private ArrayAdapter<String> arrayAdapter;
    private ProgressDialog progressDialog;
    private Bitmap bmp;
    private Button mBtnLight;
    //是否使用特殊的标题栏背景颜色，android5.0以上可以设置状态栏背景色，如果不使用则使用透明色值
    protected boolean useThemestatusBarColor = true;
    //是否使用状态栏文字和图标为暗色，如果状态栏采用了白色系，则需要使状态栏和图标为暗色，android6.0以上可以设置
    protected boolean useStatusBarColor = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recognize);
        setStatusBar();
        if (ContextCompat.checkSelfPermission(this, CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{CAMERA, READ_EXTERNAL_STORAGE}, 100);
        } else {
            initView();
        }
    }


    protected void setStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {//5.0及以上
            View decorView = getWindow().getDecorView();
            int option = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            decorView.setSystemUiVisibility(option);
            //根据上面设置是否对状态栏单独设置颜色
            if (useThemestatusBarColor) {
                getWindow().setStatusBarColor(getResources().getColor(R.color.finder_laser));//设置状态栏背景色
            } else {
                getWindow().setStatusBarColor(Color.TRANSPARENT);//透明
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {//4.4到5.0
            WindowManager.LayoutParams localLayoutParams = getWindow().getAttributes();
            localLayoutParams.flags = (WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | localLayoutParams.flags);
        } else {
            Toast.makeText(this, "低于4.4的android系统版本不存在沉浸式状态栏", Toast.LENGTH_SHORT).show();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && useStatusBarColor) {//android6.0以后可以对状态栏文字颜色和图标进行修改
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            boolean permissionGranted = true;
            for (int i : grantResults) {
                if (i != PackageManager.PERMISSION_GRANTED) {
                    permissionGranted = false;
                }
            }
            if (permissionGranted) {
                initView();
            } else {
                // 无权限退出
                finish();
            }
        }
    }

    private void initView() {
        mQrCodeFinderView = (ScannerFinderView) findViewById(R.id.qr_code_view_finder);
        mSurfaceViewStub = (ViewStub) findViewById(R.id.qr_code_view_stub);
        mListView = (ListView) findViewById(R.id.lv);
        mEditText = (EditText) findViewById(R.id.et_tel);
        //tag=-1表示输入框为添加新的元素，如果不为-1表示输入框的元素是listview中的修改元素
        mEditText.setTag(-1);
        mBtnDone = (Button) findViewById(R.id.btn_done);
        mBtnNext = (Button) findViewById(R.id.btn_next);
        mBtnLight = (Button) findViewById(R.id.btn_light);
        mHasSurface = false;

        arrayAdapter = new ArrayAdapter<>(RecognizeActivity.this, R.layout.list_view_item, listPhones);
        mListView.setAdapter(arrayAdapter);
        //下一个
        mBtnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String tel = mEditText.getText().toString();
                if (TextUtils.isEmpty(tel)) {
                    Tools.dialogNotice(RecognizeActivity.this, "请输入手机号码！！！");
                    restartPreview();
                    return;
                }

                if (!Tools.isTelPhone(tel)) {
                    Tools.dialogNotice(RecognizeActivity.this, "请输入格式正确的手机号码！！！");
                    restartPreview();
                    return;
                }

                int position = Integer.parseInt(mEditText.getTag().toString());
                if (position != -1) {
                    arrayAdapter.remove(arrayAdapter.getItem(position));
                    mEditText.setTag(-1);
                }
                addItemToListView(tel.trim());
                mEditText.setText("");
                restartPreview();
            }
        });


        //完成
        mBtnDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                Log.d(TAG, "onClick: " + listPhones.size());
            }
        });


        //闪光灯
        mBtnLight.setSelected(false);
        mBtnLight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mBtnLight.isSelected()) {
                    CameraManager.get().setFlashLight(true);
                    mBtnLight.setSelected(true);
                    mBtnLight.setTextColor(getResources().getColor(R.color.finder_laser));
                } else {
                    CameraManager.get().setFlashLight(false);
                    mBtnLight.setSelected(false);
                    mBtnLight.setTextColor(getResources().getColor(R.color.white));
                }
            }
        });

        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String item = arrayAdapter.getItem(position);
                mEditText.setText(item);
                mEditText.setTag(position);
            }
        });


        mListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {

                final String item = arrayAdapter.getItem(position);
                Log.d(TAG, "onItemLongClick: " + item);

                new AlertDialog.Builder(RecognizeActivity.this).setTitle(R.string.notification)
                        .setMessage(R.string.confirm_del_item_phone_num)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                arrayAdapter.remove(item);
                            }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                            }
                        })
                        .show();

                return true;
            }
        });

    }

    public Rect getCropRect() {
        return mQrCodeFinderView.getRect();
    }


    @Override
    protected void onResume() {
        super.onResume();
        CameraManager.init();
        initCamera();
    }

    private void initCamera() {
        if (null == mSurfaceView) {
            mSurfaceViewStub.setLayoutResource(R.layout.layout_surface_view);
            mSurfaceView = (SurfaceView) mSurfaceViewStub.inflate();
        }
        SurfaceHolder surfaceHolder = mSurfaceView.getHolder();
        if (mHasSurface) {
            initCamera(surfaceHolder);
        } else {
            surfaceHolder.addCallback(this);
            // 防止sdk8的设备初始化预览异常(可去除，本项目最小16)
            surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }
    }

    @Override
    protected void onPause() {
        if (mCaptureActivityHandler != null) {
            try {
                mCaptureActivityHandler.quitSynchronously();
                mCaptureActivityHandler = null;
                if (null != mSurfaceView && !mHasSurface) {
                    mSurfaceView.getHolder().removeCallback(this);
                }
                CameraManager.get().closeDriver();
            } catch (Exception e) {
                // 关闭摄像头失败的情况下,最好退出该Activity,否则下次初始化的时候会显示摄像头已占用.
                finish();
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    /**
     * @param result
     */
    public void handleDecode(Result result) {
        if (null == result) {
            mDecodeManager.showCouldNotReadQrCodeFromScanner(this, new DecodeManager.OnRefreshCameraListener() {
                @Override
                public void refresh() {
                    restartPreview();
                }
            });
        } else {
            handleResult(result);
        }
    }

    private void initCamera(SurfaceHolder surfaceHolder) {
        try {
            if (!CameraManager.get().openDriver(surfaceHolder)) {
                return;
            }
        } catch (IOException e) {
            // 基本不会出现相机不存在的情况
            Toast.makeText(this, getString(R.string.camera_not_found), Toast.LENGTH_SHORT).show();
            finish();
            return;
        } catch (RuntimeException re) {
            re.printStackTrace();
            return;
        }
        mQrCodeFinderView.setVisibility(View.VISIBLE);
        if (mCaptureActivityHandler == null) {
            mCaptureActivityHandler = new CaptureActivityHandler(this);
        }
    }

    public void restartPreview() {
        if (null != mCaptureActivityHandler) {
            try {
                mCaptureActivityHandler.restartPreviewAndDecode();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!mHasSurface) {
            mHasSurface = true;
            initCamera(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mHasSurface = false;
    }

    public Handler getCaptureActivityHandler() {
        return mCaptureActivityHandler;
    }

    private void handleResult(Result result) {
        if (TextUtils.isEmpty(result.getText())) {
            mDecodeManager.showCouldNotReadQrCodeFromScanner(this, new DecodeManager.OnRefreshCameraListener() {
                @Override
                public void refresh() {
                    restartPreview();
                }
            });
        } else {
            phoneSucceed(result.getText(), result.getBitmap());
        }
    }

    @Override
    public void onPictureTaken(byte[] data, Camera camera) {
        if (data == null) {
            return;
        }
        mCaptureActivityHandler.onPause();
        bmp = null;
        bmp = Tools.getFocusedBitmap(this, camera, data, getCropRect());

        TesseractThread mTesseractThread = new TesseractThread(bmp, new TesseractCallback() {

            @Override
            public void succeed(String result) {
                Message message = Message.obtain();
                message.what = 0;
                message.obj = result;
                mHandler.sendMessage(message);
            }

            @Override
            public void fail() {
                Message message = Message.obtain();
                message.what = 1;
                mHandler.sendMessage(message);
            }
        });

        Thread thread = new Thread(mTesseractThread);
        thread.start();
    }

    @Override
    public void onShutter() {
    }

    private String lastResult = "";

    private void phoneSucceed(String result, Bitmap bitmap) {
        Log.d(TAG, "phoneSucceed: " + result + "   lastResult:" + lastResult + "  cmp:" + result.equals(lastResult));
        result = result.trim();
        if (!lastResult.equals(result)) {
            lastResult = result;
        } else {
            Vibrator vibrator = (Vibrator) this.getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(50L);
            addItemToListView(result);
        }
        restartPreview();
    }

    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case 0:
                    phoneSucceed((String) msg.obj, bmp);
                    break;
                case 1:
                    Toast.makeText(RecognizeActivity.this, "无法识别", Toast.LENGTH_SHORT).show();
                    break;
                default:
                    break;
            }
        }
    };


    /**
     * @param item
     */
    private void addItemToListView(String item) {
        if (arrayAdapter != null) {
            int position = arrayAdapter.getPosition(item);
            if (position == -1) {
                arrayAdapter.insert(item, 0);
            }
        }
    }
}