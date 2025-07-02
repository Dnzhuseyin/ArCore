package com.example.arcore

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.Anchor
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private var session: Session? = null
    private var shouldConfigureSession = false
    
    // Model anchor
    private var modelAnchor: Anchor? = null
    
    // Display matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)
    
    // Background texture renderer ID
    private var backgroundTextureId = 0

    companion object {
        private const val CAMERA_PERMISSION_CODE = 0
        private const val TAG = "ArCoreActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        surfaceView = GLSurfaceView(this)
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        
        surfaceView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleTap(event)
            }
            true
        }
        
        setContentView(surfaceView)
        
        // Check for camera permission
        if (!checkCameraPermission()) {
            requestCameraPermission()
        }
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !shouldConfigureSession)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        shouldConfigureSession = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        // ARCore is installed, we can create the session
                    }
                }

                // Create a session
                if (checkCameraPermission()) {
                    session = Session(this)
                } else {
                    message = "Kamera izni gerekli"
                    exception = SecurityException(message)
                }
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "ARCore yüklenmemiş"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "ARCore kurulumu reddedildi"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "ARCore güncellemesi gerekli"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "SDK güncellemesi gerekli"
                exception = e
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "Cihaz ARCore desteklemiyor"
                exception = e
            } catch (e: Exception) {
                message = "ArCore oturumu oluşturulamadı"
                exception = e
            }

            if (message != null) {
                Log.e(TAG, "Exception creating session", exception)
                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                return
            }
        }

        // Configure session
        if (shouldConfigureSession) {
            configureSession()
            shouldConfigureSession = false
        }

        // Resume the session
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            session = null
            return
        }

        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        if (session != null) {
            session?.close()
            session = null
        }
        super.onDestroy()
    }

    private fun configureSession() {
        val config = Config(session)
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        session?.configure(config)
    }

    private fun handleTap(event: MotionEvent) {
        // Schedule this for the next frame update on the main thread
        surfaceView.queueEvent {
            performHitTest(event.x, event.y)
        }
    }

    private fun performHitTest(x: Float, y: Float) {
        val session = session ?: return
        
        try {
            val frame = session.update()
            
            if (frame.camera.trackingState != TrackingState.TRACKING) {
                return
            }

            val hits = frame.hitTest(x, y)
            for (hit in hits) {
                val trackable = hit.trackable
                if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                    // Clear existing anchor
                    modelAnchor?.detach()
                    
                    // Create new anchor
                    modelAnchor = hit.createAnchor()
                    
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Model yerleştirildi!", Toast.LENGTH_SHORT).show()
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during hit test", e)
        }
    }

    // GLSurfaceView.Renderer implementation
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        
        // Generate background texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        backgroundTextureId = textures[0]
        
        // Load GLB model here in the future
        loadGLBModel()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val displayRotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        session?.setDisplayGeometry(displayRotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = session ?: return

        try {
            // Set the camera texture
            session.setCameraTextureName(backgroundTextureId)
            
            val frame = session.update()
            val camera = frame.camera
            
            // Only draw if tracking
            if (camera.trackingState == TrackingState.TRACKING) {
                // Get projection matrix
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

                // Get camera matrix and draw
                camera.getViewMatrix(viewMatrix, 0)

                // Draw planes
                drawPlanes(frame)

                // Draw model if anchor exists
                modelAnchor?.let { anchor ->
                    if (anchor.trackingState == TrackingState.TRACKING) {
                        drawModel(anchor)
                    }
                }
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Exception on the OpenGL thread", e)
        }
    }

    private fun drawPlanes(frame: Frame) {
        // Simple plane visualization - just for indication
        for (plane in frame.getUpdatedTrackables(Plane::class.java)) {
            if (plane.trackingState == TrackingState.TRACKING) {
                // Draw a simple representation of detected planes
                // This is a placeholder - in a real app you'd render plane geometry
            }
        }
    }

    private fun drawModel(anchor: Anchor) {
        // Get the current pose of the anchor
        anchor.pose.toMatrix(modelMatrix, 0)
        
        // Combine transformations
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
        
        // Draw a simple cube as placeholder for the GLB model
        drawCube()
    }

    private fun drawCube() {
        // This is a simple cube rendering as placeholder
        // In a complete implementation, this would render the GLB model
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        
        // Simple colored cube vertices and rendering would go here
        // For now, just a placeholder that indicates where the model would be
        Log.d(TAG, "Drawing placeholder cube")
    }

    private fun loadGLBModel() {
        // Simplified - just render a simple cube for now
        // GLB loading will be added in a future iteration
        Log.i(TAG, "Basit geometrik şekil hazırlandı - GLB loading gelecek güncellemede eklenecek")
        
        // Move Toast to main thread
        runOnUiThread {
            Toast.makeText(this@MainActivity, "Yüzeyi dokunarak küp yerleştirin", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == 
               PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this, 
            arrayOf(Manifest.permission.CAMERA), 
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Kamera izni verildi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}