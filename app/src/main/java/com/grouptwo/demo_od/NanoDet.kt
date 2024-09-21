package com.grouptwo.demo_od

import android.content.res.AssetManager
import android.graphics.Bitmap
import com.grouptwo.demo_od.util.Box

/**
 * NanoDet class for object detection using a native library.
 *
 * This class provides an interface to a native object detection implementation,
 * likely using the NanoDet model.
 */
object NanoDet {

    /**
     * Load the native library on class initialization.
     */
    init {
        System.loadLibrary("yolov5")
    }

    /**
     * Initialize the NanoDet model.
     *
     * @param manager The AssetManager to access model files.
     * @param useGPU Whether to use GPU acceleration.
     */
    @JvmStatic
    external fun init(manager: AssetManager, useGPU: Boolean)

    /**
     * Perform object detection on a bitmap image.
     *
     * @param bitmap The input image as a Bitmap.
     * @param threshold Confidence threshold for detections.
     * @param nmsThreshold Non-maximum suppression threshold.
     * @return An array of detected [Box] objects.
     */
    @JvmStatic
    external fun detect(bitmap: Bitmap, threshold: Double, nmsThreshold: Double): Array<Box>
}