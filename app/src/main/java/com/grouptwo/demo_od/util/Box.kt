package com.grouptwo.demo_od.util

// Define a class named Box that will show bounding box around the detected object

import android.graphics.Color
import android.graphics.RectF
import kotlin.random.Random

/**
 * Represents a bounding box for object detection.
 *
 * @property x0 The x-coordinate of the top-left corner.
 * @property y0 The y-coordinate of the top-left corner.
 * @property x1 The x-coordinate of the bottom-right corner.
 * @property y1 The y-coordinate of the bottom-right corner.
 * @property label The index of the detected object's label.
 * @property score The confidence score of the detection.
 */
class Box(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    private val label: Int,
    private val score: Float
) {


    companion object {
        // List of object labels for detection
        private val labels = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }

    /**
     * Get the bounding box as a RectF object.
     *
     * @return RectF representing the bounding box.
     */
    fun getRect(): RectF = RectF(x0, y0, x1, y1)

    /**
     * Get the label of the detected object.
     *
     * @return String representation of the label.
     */
    fun getLabel(): String = labels[label]

    /**
     * Get the confidence score of the detection.
     *
     * @return Float value representing the confidence score.
     */
    fun getScore(): Float = score

    /**
     * Generate a random color based on the label index.
     *
     * @return Int representation of the color in ARGB format.
     */
    fun getColor(): Int {
        val random = Random(label)
        return Color.argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256))
    }
}