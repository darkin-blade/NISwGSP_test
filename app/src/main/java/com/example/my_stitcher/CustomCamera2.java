package com.example.my_stitcher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Queue;

import Jama.Matrix;

import static com.example.my_stitcher.MainActivity.PERMISSION_CAMERA_REQUEST_CODE;
import static com.example.my_stitcher.MainActivity.appPath;

public class CustomCamera2 extends DialogFragment {
    Button btnCapture;
    Button btnStitch;
    Button btnBack;
    Button btnDebug;
    TextureView cameraPreview;
    TextView text1_1, text1_2, text1_3, text1_4;
    TextView text2_1, text2_2, text2_3, text2_4;
    TextView photoNum;

    // 实时镜头
    ImageView myImageView;
    Bitmap myBitmap;
    Canvas myCanvas;
    Paint myPaint;
    double myRadius;// 屏幕尺寸
    int halfW, halfH;// 屏幕的一半宽/高
    int switchGuide = 1;// 开启辅助功能

    CameraDevice mCameraDevice;// 摄像头设备,(参数:预览尺寸,拍照尺寸等)
    CameraCaptureSession mCameraCaptureSession;// 相机捕获会话,用于处理拍照和预览的工作
    CaptureRequest.Builder captureRequestBuilder;// 捕获请求,定义输出缓冲区及显示界面(TextureView或SurfaceView)

    Size previewSize;// 在textureView预览的尺寸
    Size captureSize;// 拍摄的尺寸

    ImageReader mImageReader;
    Handler backgroundHandler;

    static public ArrayList<String> photo_name = new ArrayList<>();// 图片地址list
    static public ArrayList<ArrayList<Double> > photo_rotation = new ArrayList<>();// 图片角度list
    static public int photo_num;// 照片总数
    static public int photo_index;// 用于照片命名
    // 照片配对
    static public ArrayList<Integer> pairFirst = new ArrayList<>();
    static public ArrayList<Integer> pairSecond = new ArrayList<>();

    double[] photo_center = new double[2];// 所有照片的中心
    boolean is_taking_picture;

    // 解决多线程问题
    Queue<File> file_queue = new LinkedList<>();// 文件名

    // 传感器
    SensorManager mSensorManager;
    Sensor mAccelerator;// 加速度传感器
    Sensor mGravity;// 重力传感器
    Sensor mMagnet;// 地磁场传感器
    Sensor mRotation;// 旋转传感器

    // 当前图片
    double gravity_theta;// 手机在球面切面上的旋转角度
    double this_longitude;// 经度
    double this_latitude;// 纬度
    float acceleratorValue[] = new float[3];
    float magnetValue[] = new float[3];
    float rotationMatrix[] = new float[9];// 旋转矩阵
    float orientationValue[] = new float[3];// 手机方向
    long last_time_1, last_time_2, last_time_3, last_time_4;// 每个传感器的UI刷新时间

    static boolean enable_repeat = true;// 允许重复拍摄同一个地方
    static public int dismiss_result = 0;// 0: 返回, 1: 拍照

    static final SparseArray<Integer> ORIENTATIONS = new SparseArray<>();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER
            || sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                // TODO 没用
                if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                    // 加速度
                    System.arraycopy(sensorEvent.values, 0, acceleratorValue, 0, acceleratorValue.length);
                } else if (sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                    // 地磁场
                    System.arraycopy(sensorEvent.values, 0, magnetValue, 0, magnetValue.length);
                }
                // 更新手机方位角
                SensorManager.getRotationMatrix(rotationMatrix, null, acceleratorValue, magnetValue);
                SensorManager.getOrientation(rotationMatrix, orientationValue);

                long cur_time = System.currentTimeMillis();
                long time_interval = cur_time - last_time_1;
                if (time_interval > 500) {
                    last_time_1 = cur_time;
                    text1_2.setText("" + (int) Math.toDegrees(orientationValue[0]));
                    text1_3.setText("" + (int) Math.toDegrees(orientationValue[1]));
                    text1_4.setText("" + (int) Math.toDegrees(orientationValue[2]));
                }
            } else if (sensorEvent.sensor.getType() == Sensor.TYPE_GRAVITY) {
                // 重力
                double gravity = Math.sqrt(sensorEvent.values[0] * sensorEvent.values[0]
                        + sensorEvent.values[1] * sensorEvent.values[1]
                        + sensorEvent.values[2] * sensorEvent.values[2]);
                gravity_theta = Math.atan(sensorEvent.values[0] / sensorEvent.values[1]);// tan = x / y
                if (gravity_theta > 0 && sensorEvent.values[0] < 0) {
                    gravity_theta -= Math.PI;
                } else if (gravity_theta < 0 && sensorEvent.values[0] > 0) {
                    gravity_theta += Math.PI;
                }
                this_latitude = Math.acos(sensorEvent.values[2] / gravity) - Math.PI / 2;// cos = z / g

                long cur_time = System.currentTimeMillis();
                long time_interval = cur_time - last_time_2;
                if (time_interval > 500) {
                    last_time_2 = cur_time;
//                    text2_1.setText("" + sensorEvent.values[0]);
                    text2_2.setText("水平: " + (int) Math.toDegrees(this_longitude));
                    text2_3.setText("仰角: " + (int) Math.toDegrees(this_latitude));
                    text2_4.setText("切面: " + (int) Math.toDegrees(gravity_theta));
                }
            } else if (sensorEvent.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                // 旋转
                // 水平角度调整
                this_longitude = Math.atan(sensorEvent.values[1] / sensorEvent.values[0]) * 2 + gravity_theta;// tan = y / x
                if (this_longitude < - Math.PI) {
                    this_longitude += 2 * Math.PI;
                } else if (this_longitude > Math.PI) {
                    this_longitude -= 2 * Math.PI;
                }

//                double theta = Math.acos(sensorEvent.values[3]);
//                double x = sensorEvent.values[0] / Math.sin(theta);
//                double y = sensorEvent.values[1] / Math.sin(theta);
//                double z = sensorEvent.values[2] / Math.sin(theta);
            }

            // 拍照, 球面距离计算
            long cur_time = System.currentTimeMillis();
            long time_interval = cur_time - last_time_4;
            if (time_interval > 300) {
                last_time_4 = cur_time;
                double last_longitude = 0;
                double last_latitude = - Math.PI / 2;
                if (photo_num != 0) {
                    last_longitude = photo_rotation.get(photo_num - 1).get(0);
                    last_latitude = photo_rotation.get(photo_num - 1).get(1);
                }
                double sphere_dis = 1000 * Math.acos(Math.cos(last_latitude) * Math.cos(this_latitude) * Math.cos(this_longitude - last_longitude)
                + Math.sin(last_latitude) * Math.sin(this_latitude));
                text2_1.setText("" + (int) sphere_dis);

                if (is_taking_picture) {
                    // 按下快门
                    if (photo_num == 0 || sphere_dis > 100) {
                        // 拍摄照片
                        takePictures();
                        // 照片去重
                        removeRepeat();
                    }
                }
            }

            panoramaGuide();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    };

    CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        // 打开相机后调用
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;// 获取camera对象
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int i) {
            camera.close();
            mCameraDevice = null;
        }
    };

    @Override
    public void show(FragmentManager fragmentManager, String tag) {
        super.show(fragmentManager, tag);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setCancelable(false);// 返回键不能后退
        setStyle(STYLE_NO_FRAME, android.R.style.Theme);// 关闭背景(点击外部不能取消)
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.custom_camera, container);
        getDialog().getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));// 背景透明

        initCamera();// 初始化变量
        initSensor();// 初始化传感器
        initUI(view);// 初始化按钮

        return view;
    }

    @Override
    public void onDismiss(final DialogInterface dialogInterface) {
        super.onDismiss(dialogInterface);

        destroySensor();// 取消注册传感器

        Activity activity = getActivity();
        if (activity instanceof DialogInterface.OnDismissListener) {
            ((DialogInterface.OnDismissListener) activity).onDismiss(dialogInterface);
        }
    }

    void initCamera() {
        dismiss_result = 0;

        photo_name.clear();
        photo_rotation.clear();
        pairFirst.clear();
        pairSecond.clear();

        photo_num = 0;
        photo_index = 0;
        is_taking_picture = false;
        photo_center[0] = 0;
        photo_center[1] = 0;
    }

    void initSensor() {
        mSensorManager = (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);
        mAccelerator = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);// 加速度
        mGravity = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);// 重力
        mMagnet = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);// 地磁场
        mRotation = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);// 旋转
        // 注册监听
//        mSensorManager.registerListener(mSensorEventListener, mAccelerator, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(mSensorEventListener, mRotation, SensorManager.SENSOR_DELAY_UI);
//        mSensorManager.registerListener(mSensorEventListener, mMagnet, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(mSensorEventListener, mGravity, SensorManager.SENSOR_DELAY_UI);
    }

    void destroySensor() {
        mSensorManager.unregisterListener(mSensorEventListener);
    }

    void initUI(View view) {
        text1_1 = view.findViewById(R.id.text1_1);
        text1_2 = view.findViewById(R.id.text1_2);
        text1_3 = view.findViewById(R.id.text1_3);
        text1_4 = view.findViewById(R.id.text1_4);
        text2_1 = view.findViewById(R.id.text2_1);
        text2_2 = view.findViewById(R.id.text2_2);
        text2_3 = view.findViewById(R.id.text2_3);
        text2_4 = view.findViewById(R.id.text2_4);
        photoNum = view.findViewById(R.id.text2_1);
        photoNum.setText("photos: " + photo_num);

        myImageView = view.findViewById(R.id.my_canvas);
        if (myBitmap != null) {
            // 清空画布
            myBitmap.eraseColor(Color.TRANSPARENT);
            myImageView.setImageBitmap(myBitmap);
        }

        btnCapture = view.findViewById(R.id.capture);
        btnCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                 if (is_taking_picture) {
                     // 正在拍照 => 停止拍照
                     is_taking_picture = false;
                     btnCapture.setText("capture");
                     setNewCenter();// 计算中心位置
                 } else {
                     // 没有拍照 => 开始拍照
                     initCamera();
                     is_taking_picture = true;
                     btnCapture.setText("finish");
                 }
            }
        });

        btnStitch = view.findViewById(R.id.stitch);
        btnStitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (is_taking_picture == false && file_queue.size() == 0) {// 等待其他线程把照片存完
                    dismiss_result = 1;
                    computePairs();// 计算照片的配对关系
                    dismiss();
                }
            }
        });

        btnBack = view.findViewById(R.id.back);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (is_taking_picture == false) {
                    dismiss_result = 0;
                    dismiss();
                }
            }
        });

        btnDebug = view.findViewById(R.id.debug);
        btnDebug.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
//                Matrix A = new Matrix(new double[][]{
//                        {21, 29, 77},
//                        {6, 30, 85},
//                        {-55, 19, -62}
//                });
//                Matrix b = new Matrix(new double[][] {
//                        {-1},
//                        {-7},
//                        {-54}
//                });
//                Matrix x = A.solve(b);
//                for (int i = 0; i < x.getRowDimension(); i ++) {
//                    infoLog(i + ": " + x.get(i, 0));
//                }
                double[] sphereP = new double[]{Math.PI, Math.PI/4};
                double[] pointP = new double[3];
                sphere2Coordinate(sphereP, pointP);
                double[] sphereQ = new double[]{0, -Math.PI/4 + 0.01, 0};
                infoLog("new theta: " + sphereThetaConvert(pointP, sphereQ));
                switchGuide = 1 - switchGuide;
            }
        });

        cameraPreview = view.findViewById(R.id.camera_preview);
        cameraPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

            }
        });
    }

    static public void infoLog(String log) {
        Log.i("fuck", log);
    }

    void openCamera() {
        CameraManager cameraManager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        try {
            String camera_id = cameraManager.getCameraIdList()[CameraCharacteristics.LENS_FACING_FRONT];// 后置摄像头
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(camera_id);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);// 管理camera的输出格式和尺寸
//            previewSize = map.getOutputSizes(SurfaceTexture.class)[0];
            captureSize = new Size(cameraPreview.getHeight(), cameraPreview.getWidth());
            previewSize = new Size(cameraPreview.getHeight() * 2, cameraPreview.getWidth() * 2);
            infoLog("preview: " + previewSize.getWidth() + ", " + previewSize.getHeight());
            infoLog("capture: " + captureSize.getWidth() + ", " + captureSize.getHeight());

            Size[] imgFormatSizes = map.getOutputSizes(ImageFormat.JPEG);
            // 如果jpegSize通过map.getOutputSizes已被赋值,则captureSize按照赋值结果,否则按照自定义
            if (imgFormatSizes != null && imgFormatSizes.length > 0) {
                captureSize = imgFormatSizes[0];
            }
            setupImageReader();

            // 只适用 SDK > 23
            int hasCameraPermission = ContextCompat.checkSelfPermission(getActivity().getApplication(), Manifest.permission.CAMERA);
            if (hasCameraPermission == PackageManager.PERMISSION_GRANTED) {
                // 有调用相机权限
                infoLog("has permission of camera");
            } else {
                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA_REQUEST_CODE);
            }

            cameraManager.openCamera(camera_id, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    void createCameraPreview() {
        SurfaceTexture surfaceTexture = cameraPreview.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        try {
            captureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);
            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, mImageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        mCameraCaptureSession = session;
                        mCameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    ;
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    void setupImageReader() {
        mImageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);
        // 对内容进行监听
        mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                // 有新的照片
                Image image = reader.acquireLatestImage();
                try {
                    // 将帧数据转成字节数组,类似回调的预览数据
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    final byte[] bytes = new byte[buffer.capacity()];
                    buffer.get(bytes);

                    // 新建线程保存图片
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                File tmp_file = file_queue.poll();// 获取并从队列删除第一个元素
                                OutputStream outputStream = new FileOutputStream(tmp_file);
                                outputStream.write(bytes);

                                infoLog("save photo " + tmp_file);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }).start();
                } finally {
                    if (image != null) {
                        image.close();// 画面会卡住
                    }
                }
            }
        }, backgroundHandler);
    }

    void takePictures() {
        photo_num ++;
        photo_index ++;
        photoNum.setText("photos: " + photo_num);
        // 保存到图片list
        String timeStamp = photo_index + ".jpg";
        File tmp_file = new File(appPath, timeStamp);
        file_queue.offer(tmp_file);
        photo_name.add(tmp_file.getAbsolutePath());// 此时file_queue中的文件不一定处理完了
        // 记录照片的角度
        ArrayList<Double> tmp_rotation = new ArrayList<>();
        tmp_rotation.add(this_longitude);
        tmp_rotation.add(this_latitude);
        tmp_rotation.add(gravity_theta);
        photo_rotation.add(tmp_rotation);

        // 进行拍摄
        try {
            final CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mImageReader.getSurface());// 将captureRequest输出到imageReader
            int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
            builder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            mCameraCaptureSession.stopRepeating();
            mCameraCaptureSession.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    try {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                        mCameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    void setNewCenter() {
        // 计算照片的平均经度和纬度
        // 把所有点转换成球面向量并求解向量和
        double[] tmp_sphere = new double[3];
        double[] tmp_coordinate = new double[3];
        double sumX = 0;
        double sumY = 0;
        double sumZ = 0;
        for (int i = 0; i < photo_num; i ++) {
            ArrayList<Double> tmp_rotation = photo_rotation.get(i);
            tmp_sphere[0] = tmp_rotation.get(0);// 经度
            tmp_sphere[1] = tmp_rotation.get(1);
            sphere2Coordinate(tmp_sphere, tmp_coordinate);
            sumX += tmp_coordinate[0];
            sumY += tmp_coordinate[1];
            sumZ += tmp_coordinate[2];
        }
        // 计算中心位置
        tmp_coordinate[0] = sumX / photo_num;
        tmp_coordinate[1] = sumY / photo_num;
        tmp_coordinate[2] = sumZ / photo_num;
        // 归一化
        double rate = Math.sqrt(1 / (tmp_coordinate[0]*tmp_coordinate[0] + tmp_coordinate[1]*tmp_coordinate[1] + tmp_coordinate[2]*tmp_coordinate[2]));
        tmp_coordinate[0] *= rate;
        tmp_coordinate[1] *= rate;
        tmp_coordinate[2] *= rate;
        coordinate2sphere(tmp_coordinate, tmp_sphere);
        // 照片中心
        photo_center[0] = tmp_sphere[0];
        photo_center[1] = tmp_sphere[1];
        // 计算新的北极点(只可能在北半球)
        double[] sphereN_ = new double[2];
        sphereN_[1] = photo_center[1] + Math.PI / 2;// 纬度
        if (sphereN_[1] > Math.PI / 2) {
            // 越过了北极点
            sphereN_[1] = Math.PI - sphereN_[1];
            // 计算经度
            if (photo_center[0] > 0) {
                sphereN_[0] = photo_center[0] - Math.PI;
            } else {
                sphereN_[0] = photo_center[0] + Math.PI;
            }
        } else {
            sphereN_[0] = photo_center[0];// 经度
        }
        // 换算成直角坐标系坐标
        double[] pointN_ = new double[3];
        sphere2Coordinate(sphereN_, pointN_);
        // 重新计算所有点的屏幕旋转角度
        for (int i = 0; i < photo_num; i ++) {
            // 将B点转换成直角坐标系坐标
            ArrayList<Double> tmp_rotation = photo_rotation.get(i);
            tmp_sphere[0] = tmp_rotation.get(0);// 经度
            tmp_sphere[1] = tmp_rotation.get(1);// 纬度
            tmp_sphere[2] = tmp_rotation.get(2);// 屏幕旋转角度
            double new_rotation = sphereThetaConvert(pointN_, tmp_sphere);
            tmp_rotation.set(2, new_rotation);
            photo_rotation.set(i, tmp_rotation);
        }
    }

    void computePairs() {
        // 计算图片的配对信息
        double[] sphereA = new double[2];
        double[] sphereB = new double[2];
        double[] pointA = new double[3];
        double[] pointB = new double[3];
        Comparator<Edge> edgeComparator = new Comparator<Edge>() {
            @Override
            public int compare(Edge edge1, Edge edge2) {
                if (edge1.w < edge2.w) {
                    return -1;
                } else {
                    return 1;
                }
            }
        };
        PriorityQueue<Edge> edges = new PriorityQueue<>(edgeComparator);

        for (int i = 0; i < photo_num; i ++) {
            sphereA[0] = photo_rotation.get(i).get(0);
            sphereA[1] = photo_rotation.get(i).get(1);
            sphere2Coordinate(sphereA, pointA);
            for (int j = i + 1; j < photo_num; j ++) {
                sphereB[0] = photo_rotation.get(j).get(0);
                sphereB[1] = photo_rotation.get(j).get(1);
                sphere2Coordinate(sphereB, pointB);
                double AB = sphereDistance(pointA, pointB);
                edges.add(new Edge(i, j, AB));
            }
        }
        // 将所有照片连通
        UnionFind unionFind = new UnionFind(photo_num);
        boolean[][] pairMatrix = new boolean[photo_num][photo_num];
        for (int i = 0; !edges.isEmpty(); i ++) {
            Edge tmp = edges.poll();
            int u = tmp.u;
            int v = tmp.v;
            pairMatrix[u][v] = true;
            int unionSize = unionFind.union(u, v);
            if (unionSize == photo_num) {
                break;
            }
        }
        // 将矩阵转换为数组
        for (int i = 0; i < photo_num; i ++) {
            for (int j = 1; j < photo_num; j ++) {
                if (pairMatrix[i][j]) {
                    pairFirst.add(i);
                    pairSecond.add(j);
                    infoLog(i + " match " + j);
                }
            }
        }
    }

    private void removeRepeat() {
        // 删除重复度较高的照片
        if (enable_repeat) {
            double[][] point = new double[3][3];// A(x, y, z), B(x, y, z), C(x, y, z)
            double distance_1;// AB
            double distance_2;// AC
            double distance_3;// BC
            double distance_4;// CC' / 2

            // 计算当前位置
            double[] tmp_c = new double[2];
            // C
            tmp_c[0] = photo_rotation.get(photo_num - 1).get(0);
            tmp_c[1] = photo_rotation.get(photo_num - 1).get(1);
            sphere2Coordinate(tmp_c, point[2]);
            ArrayList<Integer> adjacentIndex = new ArrayList<>();
            // 找出所有相邻的点
            for (int i = 0; i < photo_num - 1; i ++) {
                // 当前拍摄的是最后一张, 不进行考虑
                double[] tmp = new double[2];
                // A
                tmp[0] = photo_rotation.get(i).get(0);
                tmp[1] = photo_rotation.get(i).get(1);
                sphere2Coordinate(tmp, point[0]);
                // AC
                distance_2 = 1000 * sphereDistance(point[0], point[2]);
                if (distance_2 < 400) {
                    adjacentIndex.add(i);// 保存照片的索引
                }
            }
            int adjacentCount = adjacentIndex.size();
            if (adjacentCount >= 2) {
                // 暴力搜索共线的点
                int[] removeIndex = new int[photo_num];
                for (int i = 0; i < adjacentCount; i ++) {
                    for (int j = i + 1; j < adjacentCount; j ++) {
                        double[] tmp = new double[2];
                        int indexA = adjacentIndex.get(i);
                        int indexB = adjacentIndex.get(j);
                        // A
                        tmp[0] = photo_rotation.get(indexA).get(0);
                        tmp[1] = photo_rotation.get(indexA).get(1);
                        sphere2Coordinate(tmp, point[0]);
                        // B
                        tmp[0] = photo_rotation.get(indexB).get(0);
                        tmp[1] = photo_rotation.get(indexB).get(1);
                        sphere2Coordinate(tmp, point[1]);
                        // 计算C到AB距离
                        distance_4 = 1000 * point2Line(point[0], point[1], point[2]);
                        if (distance_4 <= 80) {
                            // 在保证C不在AB之间的前提下, 删掉离C更近的那个点
                            // AB
                            distance_1 = sphereDistance(point[0], point[1]);
                            // AC
                            distance_2 = sphereDistance(point[0], point[2]);
                            // BC
                            distance_3 = sphereDistance(point[1], point[2]);
                            if (distance_3 > distance_2 && distance_3 > distance_1) {// B - A - C
                                // 删除A点
                                removeIndex[indexA] = 1;
                            } else if (distance_2 > distance_3 && distance_2 > distance_1) {// A - B - C
                                // 删除B点
                                removeIndex[indexB] = 1;
                            }
                        }
                    }
                }
                for (int i = photo_num - 1; i >= 0; i --) {
                    // 从后往前删除
                    if (removeIndex[i] == 1) {
                        // 删掉第i张照片
                        infoLog("remove " + photo_name.get(i));
                        photo_name.remove(i);
                        photo_rotation.remove(i);
                        photo_num --;
                    }
                }
            }
        } else {
            double[][] point = new double[3][3];// A(x, y, z), B(x, y, z), C(x, y, z)
            double distance_1;// AB
            double distance_2;// CC' / 2
            if (photo_num >= 3) {
                // 计算3点的空间直角坐标(x与经度0的方向平行)(右手系)
                double[] tmp = new double[2];
                // A
                tmp[0] = photo_rotation.get(photo_num - 3).get(0);
                tmp[1] = photo_rotation.get(photo_num - 3).get(1);
                sphere2Coordinate(tmp, point[0]);
                // B
                tmp[0] = photo_rotation.get(photo_num - 2).get(0);
                tmp[1] = photo_rotation.get(photo_num - 2).get(1);
                sphere2Coordinate(tmp, point[1]);
                // C
                tmp[0] = photo_rotation.get(photo_num - 1).get(0);
                tmp[1] = photo_rotation.get(photo_num - 1).get(1);
                sphere2Coordinate(tmp, point[2]);

                // 计算弧AB长度
                distance_1 = 1000 * sphereDistance(point[0], point[1]);// AB
                if (distance_1 >= 300) {
                    // 保留B点
                    return;
                }

                // 计算C到AB距离
                distance_2 = 1000 * point2Line(point[0], point[1], point[2]);
                if (distance_2 <= 80) {// TODO 阈值
                    // 删除B点
                    infoLog("remove " + photo_name.get(photo_num - 2));
                    photo_name.remove(photo_num - 2);
                    photo_rotation.remove(photo_num - 2);
                    photo_num --;
                }
            }
        }
    }

    private void panoramaGuide() {
        // 辅助拍摄
        if (switchGuide == 0) {
            return;
        }
        if (myImageView == null || myImageView.getWidth() <= 0) {
            return;
        }

        // 获取canvas
        if (myBitmap == null) {
            myBitmap = Bitmap.createBitmap(myImageView.getWidth(), myImageView.getHeight(), Bitmap.Config.ARGB_8888);
            myCanvas = new Canvas(myBitmap);
            myPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            myPaint.setStrokeWidth(5);
            // 屏幕尺寸信息
            halfW = myImageView.getWidth() / 2;
            halfH = myImageView.getHeight() / 2;
            myRadius = Math.sqrt(halfW*halfW + halfH*halfH);// 半径为屏幕对角线长度 / 2
        }
        myBitmap.eraseColor(Color.TRANSPARENT);

        double[] positionP = new double[2];// P
        double[] positionQ = new double[2];// Q
        double[] positionQ_ = new double[2];// Q变换后的坐标
        positionP[0] = this_longitude;
        positionP[1] = this_latitude;

        for (int i = 0; i < photo_num; i ++) {
            // 计算所有拍照点的坐标变换
            positionQ[0] = photo_rotation.get(i).get(0);// 经度
            positionQ[1] = photo_rotation.get(i).get(1);// 纬度
            sphereConvert(positionP, positionQ, positionQ_);
            if (positionQ_[1] < Math.PI / 3) {
                // TODO 纬度与北极相差60以内
                // 球面坐标->极坐标, 0经度线显示为竖直向上, 并且以顺时针为正方向(TODO 即球面坐标系中的正西方向)
                positionQ_[0] += Math.PI;

                // TODO 处理手机角度偏移, 极坐标角度必须在[0, 360]内
                positionQ_[0] += gravity_theta;
                if (positionQ_[0] > 2 * Math.PI) {
                    positionQ_[0] -= 2 * Math.PI;
                } else if (positionQ_[0] < 0) {
                    positionQ_[0] += 2 * Math.PI;
                }

                // 极坐标->直角坐标
                int tmpX = (int) (  Math.cos(positionQ_[0]) * myRadius * (positionQ_[1] / (Math.PI / 5))  );
                int tmpY = (int) (  Math.sin(positionQ_[0]) * myRadius * (positionQ_[1] / (Math.PI / 5))  );
                int coordinateX = halfW + tmpY;
                int coordinateY = halfH - tmpX;

                // 绘制拍照点
                if (i != 0 && i == photo_num - 1) {
                    myPaint.setColor(0x99ffffff);
                } else {
                    myPaint.setColor(0xffffffff);
                }
                myCanvas.drawCircle(coordinateX, coordinateY, 30, myPaint);
            }
        }

        if (!is_taking_picture) {
            // 显示照片中心的位置
            sphereConvert(positionP, photo_center, positionQ_);
            if (positionQ_[1] < Math.PI / 3) {
                // TODO 纬度与北极相差60以内
                // 球面坐标->极坐标, 0经度线显示为竖直向上, 并且以顺时针为正方向(TODO 即球面坐标系中的正西方向)
                positionQ_[0] += Math.PI;

                // TODO 处理手机角度偏移, 极坐标角度必须在[0, 360]内
                positionQ_[0] += gravity_theta;
                if (positionQ_[0] > 2 * Math.PI) {
                    positionQ_[0] -= 2 * Math.PI;
                } else if (positionQ_[0] < 0) {
                    positionQ_[0] += 2 * Math.PI;
                }

                // 极坐标->直角坐标
                int tmpX = (int) (  Math.cos(positionQ_[0]) * myRadius * (positionQ_[1] / (Math.PI / 5))  );
                int tmpY = (int) (  Math.sin(positionQ_[0]) * myRadius * (positionQ_[1] / (Math.PI / 5))  );
                int coordinateX = halfW + tmpY;
                int coordinateY = halfH - tmpX;

                // 绘制拍照点
                myPaint.setColor(0xff00ffff);
                myCanvas.drawCircle(coordinateX, coordinateY, 30, myPaint);
            }
        }

        myImageView.setImageBitmap(myBitmap);
    }

    private double sphereDistance(final double[] pointA, final double[] pointB) {
        // 计算球面上AB的距离
        double longitude_1, latitude_1;
        double longitude_2, latitude_2;
        longitude_1 = Math.atan(pointA[1] / pointA[0]);// tan = y / x
        latitude_1 = Math.asin(pointA[2]);// sin = z
        longitude_2 = Math.atan(pointB[1] / pointB[0]);// tan = y / x
        latitude_2 = Math.asin(pointB[2]);// sin = z

        // 经度调整
        if (pointA[1] > 0 && longitude_1 < 0) {
            longitude_1 += Math.PI;
        } else if (pointA[1] < 0 && longitude_1 > 0) {
            longitude_1 -= Math.PI;
        }
        if (pointB[1] > 0 && longitude_2 < 0) {
            longitude_2 += Math.PI;
        } else if (pointB[1] < 0 && longitude_2 > 0) {
            longitude_2 -= Math.PI;
        }

        double sphere_dis = Math.acos(Math.cos(latitude_1) * Math.cos(latitude_2) * Math.cos(longitude_2 - longitude_1)
                + Math.sin(latitude_1) * Math.sin(latitude_2));
        return sphere_dis;
    }

    private void sphere2Coordinate(final double[] sphere, double[] coordinate) {
        // 球面坐标系转空间直角坐标系, OX方向为0经度方向
        coordinate[2] = Math.sin(sphere[1]);// 纬度计算z
        double xy = Math.sqrt(1 - coordinate[2] * coordinate[2]);// sqrt(x^2 + y^2)
        coordinate[0] = xy * Math.cos(sphere[0]);// 经度计算x
        coordinate[1] = xy * Math.sin(sphere[0]);// 经度计算y
    }

    private void coordinate2sphere(final double[] coordinate, double[] sphere) {
        // 空间直角坐标系转球面坐标系
        sphere[0] = Math.atan(coordinate[1] / coordinate[0]);// TODO tan = y / x
        sphere[1] = Math.asin(coordinate[2]);
        if (coordinate[1] > 0 && sphere[0] < 0) {
            sphere[0] += Math.PI;
        } else if (coordinate[1] < 0 && sphere[0] > 0) {
            sphere[0] -= Math.PI;
        }
    }

    private double point2Line(final double[] pointA, final double[] pointB, final double[] pointC) {
        // 点到球面直线距离, 输入为: 弧AB, 点C
        double[] pointC_ = new double[3];

        // 计算C在OAB上的垂足D
        Matrix A = new Matrix(new double[][]{
                {pointA[0]*pointA[0] + pointA[1]*pointA[1] + pointA[2]*pointA[2],
                        pointA[0]*pointB[0] + pointA[1]*pointB[1] + pointA[2]*pointB[2]},
                {pointA[0]*pointB[0] + pointA[1]*pointB[1] + pointA[2]*pointB[2],
                        pointB[0]*pointB[0] + pointB[1]*pointB[1] + pointB[2]*pointB[2]}
        });
        Matrix b = new Matrix(new double[][]{
                {pointA[0]*pointC[0] + pointA[1]*pointC[1] + pointA[2]*pointC[2]},
                {pointB[0]*pointC[0] + pointB[1]*pointC[1] + pointB[2]*pointC[2]},
        });
        Matrix x = A.solve(b);

        // 计算C到关于D的对称点C'的弧长
        double delta_x = x.get(0, 0) * pointA[0] + x.get(1, 0) * pointB[0] - pointC[0];// dx - cx
        double delta_y = x.get(0, 0) * pointA[1] + x.get(1, 0) * pointB[1] - pointC[1];// dy - cy
        double delta_z = x.get(0, 0) * pointA[2] + x.get(1, 0) * pointB[2] - pointC[2];// dz - cz
        pointC_[0] = pointC[0] + 2 * delta_x;
        pointC_[1] = pointC[1] + 2 * delta_y;
        pointC_[2] = pointC[2] + 2 * delta_z;

        // 计算C到AB的距离
        return sphereDistance(pointC, pointC_)/2;// CC' / 2
    }

    private void rotateByVector(final double[] pointP, final double[] vectorAxis, final double _theta, double[] pointP_) {
        // 计算P点绕axis旋转theta之后的坐标P'
        // 右手大拇指与axis同向, 四指的方向为正方向
        double xP = pointP[0];
        double yP = pointP[1];
        double zP = pointP[2];
        // 向量的坐标
        double xV = vectorAxis[0];
        double yV = vectorAxis[1];
        double zV = vectorAxis[2];
        double c = Math.cos(_theta);
        double s = Math.sin(_theta);
        double xP_ = (xV*xV*(1 - c) + c)*xP + (xV*yV*(1 - c) - zV*s)*yP + (xV*zV*(1 - c) + yV*s)*zP;
        double yP_ = (yV*xV*(1 - c) + zV*s)*xP + (yV*yV*(1- c) + c)*yP + (yV*zV*(1 - c) - xV*s)*zP;
        double zP_ = (zV*xV*(1 - c) - yV*s)*xP + (zV*yV*(1 - c) + xV*s)*yP + (zV*zV*(1 - c) + c)*zP;
        pointP_[0] = xP_;
        pointP_[1] = yP_;
        pointP_[2] = zP_;
    }

    private double planeAngle(final double[] pointA, final double[] pointB, final double[] pointC) {
        // 计算2个平面之间的夹角(绝对值), 输入为(平面1某一向量, 公共边, 平面2某一向量), 设为(OA, OB, OC)
        // 计算OA在OB上的垂足D, OD = a(OB)
        double a = (pointA[0]*pointB[0] + pointA[1]*pointB[1] + pointA[2]*pointB[2])/(pointB[0]*pointB[0] + pointB[1]*pointB[1] + pointB[2]*pointB[2]);
        // 计算OC在OB上的垂足E, OE = b(OB)
        double b = (pointC[0]*pointB[0] + pointC[1]*pointB[1] + pointC[2]*pointB[2])/(pointB[0]*pointB[0] + pointB[1]*pointB[1] + pointB[2]*pointB[2]);

        // 计算DA, EB夹角的绝对值
        double[] vectorDA = new double[3];// DA
        double[] vectorEB = new double[3];// EB
        vectorDA[0] = pointA[0] - a * pointB[0];
        vectorDA[1] = pointA[1] - a * pointB[1];
        vectorDA[2] = pointA[2] - a * pointB[2];
        vectorEB[0] = pointC[0] - b * pointB[0];
        vectorEB[1] = pointC[1] - b * pointB[1];
        vectorEB[2] = pointC[2] - b * pointB[2];
        double cos_theta = (vectorDA[0]*vectorEB[0] + vectorDA[1]*vectorEB[1] + vectorDA[2]*vectorEB[2]) /
                (Math.sqrt(vectorDA[0]*vectorDA[0] + vectorDA[1]*vectorDA[1] + vectorDA[2]*vectorDA[2]) * Math.sqrt(vectorEB[0]*vectorEB[0] + vectorEB[1]*vectorEB[1] + vectorEB[2]*vectorEB[2]));
        return Math.acos(cos_theta);// 这里不是求的法向量夹角, 所以不需要计算补角
    }

    private void sphereConvert(final double[] positionP, final double[] positionQ, double[] positionR) {
        // 球面坐标系的坐标变换, 输入为(新的北极点, 待变换的点), 设为(P, Q), 坐标为地理坐标(经度, 纬度)
        // 第3个参数用于返回(经度, 纬度)

        // 计算3个参考点:
        // U: 新旧坐标系中的(-90, 0), W往西90
        // V: 新旧坐标系中的(90, 0), W往东90
        // W: 新坐标系中P沿着0经度往南90纬度, TODO 在新的坐标系中, 0经度方向为PW的方向
        double[] positionU = new double[2];// U
        double[] positionV = new double[2];// V
        double[] positionW = new double[2];// W
        positionU[0] = positionP[0] - Math.PI / 2;// U的经度
        positionU[1] = 0;
        if (positionU[0] < -Math.PI) {
            // 超过了180经度线
            positionU[0] += 2 * Math.PI;
        }
        positionV[0] = positionP[0] + Math.PI / 2;// U的经度
        positionV[1] = 0;
        if (positionV[0] > Math.PI) {
            // 超过了180经度线
            positionV[0] -= 2 * Math.PI;
        }
        positionW[0] = positionP[0];// U的经度
        positionW[1] = positionP[1] - Math.PI / 2;// U的纬度
        if (positionW[1] < -Math.PI / 2) {
            // 越过了南极, 取相反的经度
            if (positionW[0] < 0) {
                // 原坐标系西半球
                positionW[0] += Math.PI;
            } else {
                // 原坐标系东半球
                positionW[0] -= Math.PI;
            }
        }

        // 计算Q在以P为北极的坐标系中的坐标
        double[] pointP = new double[3];// P
        double[] pointQ = new double[3];// Q
        double[] pointU = new double[3];// U
        double[] pointV = new double[3];// V
        double[] pointW = new double[3];// W
        sphere2Coordinate(positionP, pointP);// P
        sphere2Coordinate(positionQ, pointQ);// Q
        sphere2Coordinate(positionU, pointU);// U
        sphere2Coordinate(positionV, pointV);// V
        sphere2Coordinate(positionW, pointW);// W
        double new_longitude = planeAngle(pointQ, pointP, pointW);// 使用OQ, OP, OW计算新的经度(绝对值)
        double new_latitude = sphereDistance(pointP, pointQ);// PQ的长度即为弧度, 结果为纬度 + 90

        // 判断Q在W的东侧还是西侧, TODO W的东边是V, W的西边是U
        double qu = sphereDistance(pointQ, pointU);
        double qv = sphereDistance(pointQ, pointV);
        if (qu < qv) {
            // Q在W西侧
            new_longitude = -new_longitude;
        }

        // 返回结果
        positionR[0] = new_longitude;// Q的经度
        positionR[1] = new_latitude;// Q的纬度
    }

    private double sphereThetaConvert(final double[] pointM, final double[] sphereP) {
        double a, b, product, cos;// 临时变量
        // 计算求坐标系变换后, 新的切面旋转角度
        // 输入为: 新北极点(原空间直角坐标系下的坐标), 待变换的点(必须是3维, 包含切面旋转角度)
        final double[] pointN = new double[]{0, 0, 1};// 原北极点
        // 计算在P处的正北方向PR
        double[] pointP = new double[3];
        double[] vectorPR = new double[3];
        sphere2Coordinate(sphereP, pointP);
        // 计算OP与ON的夹角
        cos = (pointN[0]*pointP[0] + pointN[1]*pointP[1] + pointN[2]*pointP[2])/
                (Math.sqrt(pointN[0]*pointN[0] + pointN[1]*pointN[1] + pointN[2]*pointN[2])
                *Math.sqrt(pointP[0]*pointP[0] + pointP[1]*pointP[1] + pointP[2]*pointP[2]));
        if (cos > 1) {
            cos = 1;
        } else if (cos < -1) {
            cos = -1;
        }
        double thetaNOP = Math.acos(cos);
        a = 1/Math.abs(Math.sin(thetaNOP));
        b = -a * Math.cos(thetaNOP);
        vectorPR[0] = a * pointN[0] + b * pointP[0];
        vectorPR[1] = a * pointN[1] + b * pointP[1];
        vectorPR[2] = a * pointN[2] + b * pointP[2];
        if (vectorPR[2] < 0) {
            // 求得方向为正南
            vectorPR[0] = -vectorPR[0];
            vectorPR[1] = -vectorPR[1];
            vectorPR[2] = -vectorPR[2];
        }
        // TODO 计算PQ(手机的朝向): 在坐标系中角度旋转的正方向与函数的相反
        double thetaRPQ = sphereP[2];
        double[] vectorPQ = new double[3];
        rotateByVector(vectorPR, pointP, -thetaRPQ, vectorPQ);
        // 求新直角坐标系下的正北方向PS
        double[] vectorPS = new double[3];
        // 计算OP与OM的夹角
        cos = (pointM[0]*pointP[0] + pointM[1]*pointP[1] + pointM[2]*pointP[2])/
                (Math.sqrt(pointM[0]*pointM[0] + pointM[1]*pointM[1] + pointM[2]*pointM[2])
                *Math.sqrt(pointP[0]*pointP[0] + pointP[1]*pointP[1] + pointP[2]*pointP[2]));
        if (cos > 1) {
            cos = 1;
        } else if (cos < -1) {
            cos = -1;
        }
        double thetaMOP = Math.acos(cos);
        a = 1/Math.abs(Math.sin(thetaMOP));
        b = -a * Math.cos(thetaMOP);
        vectorPS[0] = a * pointM[0] + b * pointP[0];
        vectorPS[1] = a * pointM[1] + b * pointP[1];
        vectorPS[2] = a * pointM[2] + b * pointP[2];
        product = vectorPS[0]*pointM[0] + vectorPS[1]*pointM[1] + vectorPS[2]*pointM[2];
        if (product < 0) {
            // 方向为正南
            vectorPS[0] = -vectorPS[0];
            vectorPS[1] = -vectorPS[1];
            vectorPS[2] = -vectorPS[2];
        }
        // 用OM与OP的叉乘(右手定则)计算P处的正东方向PE
        double[] vectorPE = new double[3];
        vectorPE[0] = pointM[1]*pointP[2] - pointP[1]*pointM[2];
        vectorPE[1] = pointP[0]*pointM[2] - pointM[0]*pointP[2];
        vectorPE[2] = pointM[0]*pointP[1] - pointP[0]*pointM[1];
        // TODO 计算PS与PQ的夹角(0 - PI)
        cos = (vectorPS[0]*vectorPQ[0] + vectorPS[1]*vectorPQ[1] + vectorPS[2]*vectorPQ[2])/
                (Math.sqrt(vectorPS[0]*vectorPS[0] + vectorPS[1]*vectorPS[1] + vectorPS[2]*vectorPS[2])
                *Math.sqrt(vectorPQ[0]*vectorPQ[0] + vectorPQ[1]*vectorPQ[1] + vectorPQ[2]*vectorPQ[2]));
        if (cos > 1) {
            cos = 1;
        } else if (cos < -1) {
            cos = -1;
        }
        double thetaSPQ = Math.acos(cos);
        // 判断PQ是向东(PE)还是向西
        product = vectorPQ[0]*vectorPE[0] + vectorPQ[1]*vectorPE[1] + vectorPQ[2]*vectorPE[2];
        if (product < 0) {
            // PQ朝向西方
            thetaSPQ = -thetaSPQ;
        }
        return thetaSPQ;
    }

    class UnionFind {
        public int[] father;
        public int[] size;

        public UnionFind(int num) {
            father = new int[num];
            size = new int[num];
            for (int i = 0; i < num; i ++) {
                father[i] = i;
                size[i] = 1;
            }
        }

        public int find(int x) {
            if (father[x] == x) {
                return x;
            }
            father[x] = find(father[x]);
            size[x] = size[father[x]];
            return father[x];
        }

        public int union(int x, int y) {
            int fatherX = find(x);
            int fatherY = find(y);
            if (fatherX != fatherY) {
                father[fatherX] = fatherY;
                size[fatherY] += size[fatherX];
            }
            return size[fatherY];
        }
    }

    class Edge {
        public int u, v;
        public double w;

        public Edge(int u, int v, double w) {
            if (u > v) {
               int tmp = u;
               u = v;
               v = tmp;
            }
            // u <= v
            this.u = u;
            this.v = v;
            this.w = w;
        }
    }
}