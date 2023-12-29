package com.example.sam_android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

public class SamUtils {

    // Function to load an image from assets and use samComputeMasks
    private static final String TAG = "SamUtils";
    public static boolean computeEmbeddedImageFromImage(Context context, long modelPointer, String assetImagePath){
        Log.d(TAG, "[computeEmbeddedImageFromImage] modelPointer = " + modelPointer + " ImagePath = " + assetImagePath);
        Bitmap bitmap = loadImageFromAssets(context, assetImagePath);
        if (bitmap == null) {
            throw new RuntimeException("Failed to load image from assets.");
        }
        return SamLib.computeEmbImg(modelPointer,bitmap);
    }

    // Helper function to load a Bitmap from the assets folder
    public static Bitmap loadImageFromAssets(Context context, String path) {
        InputStream is = null;
        Bitmap bitmap = null;
        try {
            is = context.getAssets().open(path);
            bitmap = BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return bitmap;
    }
}
