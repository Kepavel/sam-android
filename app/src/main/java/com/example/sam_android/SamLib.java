package com.example.sam_android;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SamLib {

    private static final String TAG = "SamLib_java";
    // Load the 'samandroid' library on class loading.
    static {
        System.loadLibrary("ggml");
        System.loadLibrary("sam");
        System.loadLibrary("samandroid");
    }

    // Private constructor to prevent instantiation
    private SamLib() {
    }


    public static boolean computeEmbImg(long statePtr, Bitmap bitmap) {
        // Convert the Bitmap to a byte array
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        // Allocate a DirectByteBuffer to hold the image data
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 3);
        buffer.order(ByteOrder.nativeOrder()); // Use the native byte order

        // Convert pixels to byte values and put them into the buffer
        for (int i = 0; i < pixels.length; ++i) {
            buffer.put((byte) ((pixels[i] >> 16) & 0xFF)); // Red
            buffer.put((byte) ((pixels[i] >> 8) & 0xFF));  // Green
            buffer.put((byte) (pixels[i] & 0xFF));         // Blue
        }
        buffer.flip(); // Reset the position to the beginning of the buffer

        // Copy the buffer into a byte array
        byte[] imageData = new byte[width * height * 3];
        buffer.get(imageData);

        // Call the native function with the byte array
        return samComputeEmbImg(statePtr, imageData, width, height);
    }

    public static Bitmap computeMasks(long statePtr, Bitmap bitmap, float x, float y) {
        Log.d(TAG, "computeMasks called with statePtr: " + statePtr + ", x: " + x + ", y: " + y);
        // Convert the Bitmap to a byte array
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        // Convert pixels to byte values
        byte[] imageData = new byte[width * height * 3];
        for (int i = 0; i < pixels.length; ++i) {
            imageData[3 * i] = (byte) ((pixels[i] >> 16) & 0xFF); // Red
            imageData[3 * i + 1] = (byte) ((pixels[i] >> 8) & 0xFF);  // Green
            imageData[3 * i + 2] = (byte) (pixels[i] & 0xFF);         // Blue
        }
        Log.d(TAG, "Image data prepared for native call.");
        // Call the native function with the byte array
        byte[] maskData = samComputeMasks(statePtr, imageData, width, height, x, y);

        if (maskData == null) {
            Log.e(TAG, "Native method samComputeMasks returned null."); // Log if native call returns null
            return null;
        }

        Log.d(TAG, "Native method samComputeMasks returned mask data.");
        // Create a Bitmap to hold the mask data
        Bitmap maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        // Assuming maskData is a 1D array with the mask data for the entire image
        int[] maskPixels = new int[width * height];
        for (int i = 0; i < maskPixels.length; ++i) {
            // Convert the mask byte value to an ARGB pixel value
            int alpha = maskData[i] & 0xFF;
            // Assuming the mask should be white (you can change the color if needed)
            maskPixels[i] = Color.argb(alpha, 255, 255, 255);
        }

        // Set the pixels of the mask bitmap
        maskBitmap.setPixels(maskPixels, 0, width, 0, 0, width, height);
        Log.d(TAG, "Mask bitmap created and pixels set.");

        // Log the color of more pixels to check for transparency or color
        for (int i = 0; i < width; i += width / 10) { // Sample 10 pixels across the width
            int pixelColor = maskBitmap.getPixel(i, height / 2); // Sample from the middle row
            Log.d(TAG, "Pixel color at (" + i + ", " + (height / 2) + "): ARGB(" +
                    Color.alpha(pixelColor) + ", " +
                    Color.red(pixelColor) + ", " +
                    Color.green(pixelColor) + ", " +
                    Color.blue(pixelColor) + ")");
        }
        return maskBitmap;
    }

    // Native method declarations
    public static native String stringFromJNI();
    public static native long samLoadModel(String modelPath);
    public static native boolean samComputeEmbImg(long stateId, byte[] imageData, int width, int height);
    public static native byte[] samComputeMasks(long stateId, byte[] imageData, int width, int height, float x, float y);
    public static native void samDeinit(long stateId);
}
