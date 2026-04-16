package com.propdf.editor.ui.scanner

import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import com.propdf.editor.R

class DocumentScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private var camera: androidx.camera.core.Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure you have a PreviewView in your layout or created programmatically
        previewView = PreviewView(this) 
        
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point).build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }
            true
        }
    }
}
