package com.example.sam_android;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;

public class SegPicActivity extends AppCompatActivity {

    private static final int MODEL_LOADING_TIMEOUT_MS = 60000; // 60 seconds
    private static final int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 100;
    private static final String TAG = "SegPicActivity";
    private static final String PIC_ASSET_PATH = "samples/img.png";
    private static final String MODEL_ASSET_PATH = "models/ggml-model-f16.bin";

    private Spinner mSipnnerModel;
    private Button mStartOrStopButton;
    private ImageView mImageView;
    private TextView mTextViewTiming; // Add this field
    private LinkedList<String> mTimingMessages = new LinkedList<>();
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable computeRunnable;
    private long mModelPointer = 0;
    private boolean isComputing = false;
    private int current_model = 0;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_seg_pic);
        checkIfNeedPermission();
        mTextViewTiming = findViewById(R.id.textViewTiming);
        mImageView = findViewById(R.id.imageView);
        mImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        //adjust the size to load and show full image
        mImageView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                mImageView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                AssetManager assetManager = getAssets();
                InputStream is = null;
                try {
                    is = assetManager.open(PIC_ASSET_PATH);
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    mImageView.setImageBitmap(bitmap);
                } catch (IOException e) {
                    // Handle the exception
                    e.printStackTrace();
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
        Bitmap bitmap = SamUtils.loadImageFromAssets(this,PIC_ASSET_PATH);
        // Set a touch listener to compute masks when the user touches the image
        mImageView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    try {
                        float x = event.getX();
                        float y = event.getY();
                        PointF pointF = transformTouchPoint(x, y, mImageView, bitmap);
                        if (pointF != null) {
                            long maskComputationStartTime = System.currentTimeMillis(); // Record start time before computation
                            Bitmap maskBitmap = SamLib.computeMasks(mModelPointer, bitmap, pointF.x, pointF.y);
                            long maskComputationEndTime = System.currentTimeMillis(); // Record end time after computation
                            updateTimingTextView("clicking( "+ pointF.x + ", " + pointF.y +"). use: " + (maskComputationEndTime - maskComputationStartTime) + " ms\n");
                            //Bitmap maskBitmap = SamLib.computeMasks(mModelPointer, bitmap, pointF.x, pointF.y);
                            Bitmap finalBitmap = combineBitmaps(bitmap, maskBitmap);
                            if (finalBitmap != null) {
                                // Save the mask bitmap for inspection
                                saveBitmapForInspection(finalBitmap);

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        mImageView.setImageBitmap(finalBitmap);
                                        mImageView.invalidate(); // Force a redraw
                                    }
                                });
                            } else {
                                Log.e(TAG, "Failed to compute masks");
                            }
                        } else {
                            Log.e(TAG, "Failed to transform touch point");
                        }
                    } catch (Exception e) {
                        // Handle the exception
                        Log.e(TAG, "Exception occurred during onTouch", e);
                        // Optionally, show an error dialog or toast
                        //showErrorDialogOrToast("An error occurred while processing the image.");
                    }
                }
                return true;
            }
        });
        //reload model;
        mSipnnerModel = (Spinner) findViewById(R.id.spinnerModel);
        mSipnnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int position, long id)
            {
                if (position != current_model)
                {
                    current_model = position;
                    initializeModel();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0)
            {
            }
        });

        mStartOrStopButton = findViewById(R.id.buttonStartAndStop);
        mStartOrStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isComputing) {
                    startComputing();
                } else {
                    stopComputing();
                }
            }
        });

        //first time to load model
        initializeModel();
    }

    private void showErrorDialogOrToast(String message) {
        // You can choose to show a dialog or a toast. Here's how to show a dialog:
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Error");
        builder.setMessage(message);
        builder.setPositiveButton("OK", null);
        AlertDialog dialog = builder.create();
        dialog.show();
        // Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
    public Bitmap combineBitmaps(Bitmap background, Bitmap mask) {
        Bitmap result = Bitmap.createBitmap(background.getWidth(), background.getHeight(), background.getConfig());
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Draw the background image
        canvas.drawBitmap(background, 0, 0, null);

        // Draw the mask image on top of the background
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        canvas.drawBitmap(mask, 0, 0, paint);

        return result;
    }
    // Utility method to save a bitmap to the device storage for inspection
    private void saveBitmapForInspection(Bitmap bitmap) {
        File outputDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File outputFile = new File(outputDir, "maskBitmap.png");

        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            Log.d(TAG, "Mask bitmap saved for inspection: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to save mask bitmap", e);
        }
    }
    private void checkIfNeedPermission(){
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE);
        }
    }

    private void initializeModel() {
        AssetManager assetManager = getAssets();
        String modelFilePath = getFilesDir() + "/ggml-model-f16.bin"; // Adjust the file name as needed
        Log.d(TAG, "copy model into = "+ modelFilePath);

        // Copy the model file from assets to internal storage
        try (InputStream in = assetManager.open(MODEL_ASSET_PATH);
             OutputStream out = new FileOutputStream(modelFilePath)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy model file", e);
            return;
        }
        loadModelAsync(modelFilePath);
    }

    private void showModelLoadingResultDialog(boolean success) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(success ? "Success" : "Timeout");
        builder.setMessage(success ? "Model loaded successfully!" : "Loading model timed out.");
        builder.setPositiveButton("OK", null);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void loadModelAsync(String modelPath) {
        new AsyncTask<String, Void, Boolean>() {
            private ProgressDialog progressDialog;
            private long startTime; // Add this field to track start time

            @Override
            protected void onPreExecute() {
                progressDialog = ProgressDialog.show(SegPicActivity.this,
                        "Loading Model", "Please wait...", true);
                startTime = System.currentTimeMillis(); // Record start time
            }

            @Override
            protected Boolean doInBackground(String... params) {
                mModelPointer = SamLib.samLoadModel(params[0]);
                Log.d(TAG, "model pointer =" + mModelPointer);
                long duration = System.currentTimeMillis() - startTime;
                return mModelPointer != 0 && duration < MODEL_LOADING_TIMEOUT_MS;
            }

            @Override
            protected void onPostExecute(Boolean success) {
                progressDialog.dismiss();
                showModelLoadingResultDialog(success);
                long duration = System.currentTimeMillis() - startTime; // Calculate duration
                updateTimingTextView("loading ggml-model-f16.bin. use: " + duration + " ms\n"); // Update TextView
            }
        }.execute(modelPath);
    }

    private void updateTimingTextView(final String newTimingInfo) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Add new timing info to the list
                mTimingMessages.addLast(newTimingInfo);

                // Keep only the latest 5 messages
                while (mTimingMessages.size() > 5) {
                    mTimingMessages.removeFirst();
                }

                // Build the string to display
                StringBuilder displayText = new StringBuilder();
                for (String message : mTimingMessages) {
                    displayText.append(message);
                }

                // Set the text to the TextView
                mTextViewTiming.setText(displayText.toString());
            }
        });
    }
    private void startComputing() {
        isComputing = true;
        mStartOrStopButton.setText("Stop");

        // Start the computing task in a new thread
        computeRunnable = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "start computing: model pointer is = " + mModelPointer + " picture path is " + PIC_ASSET_PATH);
                final long startTime = System.currentTimeMillis(); // Start time
                final boolean success = SamUtils.computeEmbeddedImageFromImage(SegPicActivity.this, mModelPointer, PIC_ASSET_PATH);
                final long endTime = System.currentTimeMillis(); // End time
                final long duration = endTime - startTime; // Duration in milliseconds
                updateTimingTextView("computing img (img.png). use: " + duration + " ms\n");

                // Log the duration
                Log.d(TAG, "Computation time: " + duration + " ms");

                // After computation, update the UI on the main thread
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        mStartOrStopButton.setText("Start");
                        isComputing = false;
                        showComputationResultDialog(success, duration); // Show the result dialog with duration
                    }
                });
            }
        };

        // Start the computation with a timeout of 20 seconds
        Thread computeThread = new Thread(computeRunnable);
        computeThread.start();

        // Set a timeout for the computation
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isComputing) {
                    stopComputing();
                    mStartOrStopButton.setText("Start");
                    showComputationResultDialog(false, 0); // Show a failure dialog if timed out
                }
            }
        }, MODEL_LOADING_TIMEOUT_MS); // Timeout after 20 seconds
    }
    private void stopComputing() {
        // Stop the computing task if it's running
        if (computeRunnable != null) {
            handler.removeCallbacks(computeRunnable);
        }
        mStartOrStopButton.setText("Start");
        isComputing = false;
    }


    private void showComputationResultDialog(boolean success, long duration) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(success ? "Computation Success" : "Computation Failure");
        String message = success ?
                "Computing successfully completed in " + duration + " ms!" :
                "Computing failed to complete.";
        builder.setMessage(message);
        builder.setPositiveButton("OK", null);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private PointF transformTouchPoint(float touchX, float touchY, ImageView imageView, Bitmap imageBitmap) {
        if (imageView == null || imageBitmap == null) {
            return null;
        }

        // Define the matrix values array
        float[] matrixValues = new float[9];

        // Get the image matrix and the values
        Matrix matrix = imageView.getImageMatrix();
        matrix.getValues(matrixValues);

        // Extract the scale values using the constants (if aspect ratio maintained, scaleX == scaleY)
        float scaleX = matrixValues[Matrix.MSCALE_X];
        float scaleY = matrixValues[Matrix.MSCALE_Y];

        // Get the offset values (we assume the image is centered in ImageView)
        float offsetX = matrixValues[Matrix.MTRANS_X];
        float offsetY = matrixValues[Matrix.MTRANS_Y];

        // Get the drawable (could also get the bitmap behind the drawable and getWidth/getHeight)
        Drawable drawable = imageView.getDrawable();
        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();

        // Calculate the touch coordinates relative to the drawable
        float drawableX = (touchX - offsetX) / scaleX;
        float drawableY = (touchY - offsetY) / scaleY;

        // Scale these coordinates up to the original image size
        float originalImageX = drawableX * (imageBitmap.getWidth() / (float) drawableWidth);
        float originalImageY = drawableY * (imageBitmap.getHeight() / (float) drawableHeight);

        // Check if the coordinates are in the bounds of the image
        originalImageX = Math.max(0, Math.min(originalImageX, imageBitmap.getWidth()));
        originalImageY = Math.max(0, Math.min(originalImageY, imageBitmap.getHeight()));

        return new PointF(originalImageX, originalImageY);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cancel the computing task to prevent memory leaks
        stopComputing();
        SamLib.samDeinit(mModelPointer);
    }
}
