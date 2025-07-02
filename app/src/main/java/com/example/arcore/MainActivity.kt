package com.example.arcore

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLES11Ext
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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
    
    // Background texture renderer
    private var backgroundTextureId = 0
    private var backgroundVertexBuffer: FloatBuffer? = null
    private var backgroundProgram = 0
    
    // Cube rendering
    private var cubeVertexBuffer: FloatBuffer? = null
    private var cubeProgram = 0
    
    // Display size
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var displayRotation = 0

    companion object {
        private const val CAMERA_PERMISSION_CODE = 0
        private const val TAG = "ArCoreActivity"
        
        // Background quad vertices (portrait corrected)
        private val BACKGROUND_VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f, 1.0f,  // Bottom left
            1.0f, -1.0f, 0.0f, 1.0f, 1.0f,   // Bottom right  
            -1.0f, 1.0f, 0.0f, 0.0f, 0.0f,   // Top left
            1.0f, 1.0f, 0.0f, 1.0f, 0.0f     // Top right
        )
        
        // Cube vertices
        private val CUBE_VERTICES = floatArrayOf(
            // Front face
            -0.1f, -0.1f,  0.1f,  0.0f, 0.0f, 1.0f,
             0.1f, -0.1f,  0.1f,  0.0f, 0.0f, 1.0f,
             0.1f,  0.1f,  0.1f,  0.0f, 0.0f, 1.0f,
            -0.1f,  0.1f,  0.1f,  0.0f, 0.0f, 1.0f,
            // Back face
            -0.1f, -0.1f, -0.1f,  0.0f, 0.0f, -1.0f,
             0.1f, -0.1f, -0.1f,  0.0f, 0.0f, -1.0f,
             0.1f,  0.1f, -0.1f,  0.0f, 0.0f, -1.0f,
            -0.1f,  0.1f, -0.1f,  0.0f, 0.0f, -1.0f
        )
        
        private val CUBE_INDICES = shortArrayOf(
            // Front
            0, 1, 2, 2, 3, 0,
            // Back
            4, 5, 6, 6, 7, 4,
            // Left
            0, 3, 7, 7, 4, 0,
            // Right
            1, 2, 6, 6, 5, 1,
            // Top
            3, 2, 6, 6, 7, 3,
            // Bottom
            0, 1, 5, 5, 4, 0
        )
        
        private const val BACKGROUND_VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """
        
        private const val BACKGROUND_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
        
        private const val CUBE_VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            attribute vec3 a_Color;
            varying vec3 v_Color;
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
                v_Color = a_Color;
            }
        """
        
        private const val CUBE_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 v_Color;
            void main() {
                gl_FragColor = vec4(v_Color, 1.0);
            }
        """
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
                Log.d(TAG, "Camera not tracking yet")
                return
            }

            val hits = frame.hitTest(x, y)
            Log.d(TAG, "Hit test found ${hits.size} hits")
            
            for (hit in hits) {
                val trackable = hit.trackable
                if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                    // Clear existing anchor
                    modelAnchor?.detach()
                    
                    // Create new anchor
                    modelAnchor = hit.createAnchor()
                    Log.i(TAG, "Model anchor created at pose: ${hit.hitPose}")
                    
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
        
        // Create background texture
        createBackgroundTexture()
        
        // Create background shader program
        createBackgroundShader()
        
        // Create background vertex buffer
        createBackgroundVertexBuffer()
        
        // Create cube shader program
        createCubeShader()
        
        // Create cube vertex buffer
        createCubeVertexBuffer()
        
        // Load GLB model here in the future
        loadGLBModel()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.i(TAG, "onSurfaceChanged: width=$width, height=$height")
        
        // Validate dimensions
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "Invalid surface dimensions: ${width}x${height}")
            return
        }
        
        viewportWidth = width
        viewportHeight = height
        
        GLES20.glViewport(0, 0, width, height)
        
        // Get display rotation
        displayRotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        
        // Set display geometry with validated dimensions
        session?.setDisplayGeometry(displayRotation, width, height)
        Log.i(TAG, "Display geometry set: rotation=$displayRotation, ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = session ?: return

        try {
            // Set the camera texture
            session.setCameraTextureName(backgroundTextureId)
            
            val frame = session.update()
            val camera = frame.camera
            
            // Draw camera background
            drawBackground()
            
            // Only draw AR content if tracking
            if (camera.trackingState == TrackingState.TRACKING) {
                // Get projection matrix
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

                // Get camera matrix and draw
                camera.getViewMatrix(viewMatrix, 0)

                // Draw planes (debug visualization)
                drawPlanes(frame)

                // Draw model if anchor exists
                modelAnchor?.let { anchor ->
                    if (anchor.trackingState == TrackingState.TRACKING) {
                        Log.d(TAG, "Drawing model at anchor")
                        drawModel(anchor)
                    }
                }
            } else {
                Log.d(TAG, "Camera tracking state: ${camera.trackingState}")
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Exception on the OpenGL thread", e)
        }
    }

    private fun createBackgroundTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        backgroundTextureId = textures[0]
        
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backgroundTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    private fun createBackgroundShader() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, BACKGROUND_VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, BACKGROUND_FRAGMENT_SHADER)
        
        backgroundProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(backgroundProgram, vertexShader)
        GLES20.glAttachShader(backgroundProgram, fragmentShader)
        GLES20.glLinkProgram(backgroundProgram)
    }

    private fun createCubeShader() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, CUBE_VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, CUBE_FRAGMENT_SHADER)
        
        cubeProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(cubeProgram, vertexShader)
        GLES20.glAttachShader(cubeProgram, fragmentShader)
        GLES20.glLinkProgram(cubeProgram)
        
        // Check linking status
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(cubeProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Error linking cube program: ${GLES20.glGetProgramInfoLog(cubeProgram)}")
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        
        // Check compilation status
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Error compiling shader: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        
        return shader
    }

    private fun createBackgroundVertexBuffer() {
        val bb = ByteBuffer.allocateDirect(BACKGROUND_VERTICES.size * 4)
        bb.order(ByteOrder.nativeOrder())
        backgroundVertexBuffer = bb.asFloatBuffer()
        backgroundVertexBuffer?.put(BACKGROUND_VERTICES)
        backgroundVertexBuffer?.position(0)
    }

    private fun createCubeVertexBuffer() {
        val bb = ByteBuffer.allocateDirect(CUBE_VERTICES.size * 4)
        bb.order(ByteOrder.nativeOrder())
        cubeVertexBuffer = bb.asFloatBuffer()
        cubeVertexBuffer?.put(CUBE_VERTICES)
        cubeVertexBuffer?.position(0)
    }

    private fun drawBackground() {
        if (backgroundProgram == 0 || backgroundVertexBuffer == null) return
        
        GLES20.glUseProgram(backgroundProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        
        // Get attribute locations
        val positionHandle = GLES20.glGetAttribLocation(backgroundProgram, "a_Position")
        val texCoordHandle = GLES20.glGetAttribLocation(backgroundProgram, "a_TexCoord")
        val textureHandle = GLES20.glGetUniformLocation(backgroundProgram, "u_Texture")
        
        // Set vertex data
        backgroundVertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 20, backgroundVertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        
        backgroundVertexBuffer?.position(3)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 20, backgroundVertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        
        // Set texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backgroundTextureId)
        GLES20.glUniform1i(textureHandle, 0)
        
        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // Clean up
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawPlanes(frame: Frame) {
        // Log detected planes for debugging
        val planes = frame.getUpdatedTrackables(Plane::class.java)
        if (planes.isNotEmpty()) {
            Log.d(TAG, "Detected ${planes.size} planes")
        }
        
        for (plane in planes) {
            if (plane.trackingState == TrackingState.TRACKING) {
                Log.d(TAG, "Plane tracking: ${plane.type}")
            }
        }
    }

    private fun drawModel(anchor: Anchor) {
        if (cubeProgram == 0 || cubeVertexBuffer == null) return
        
        // Get the current pose of the anchor
        anchor.pose.toMatrix(modelMatrix, 0)
        
        // Combine transformations
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
        
        // Draw the cube
        drawCube()
    }

    private fun drawCube() {
        if (cubeProgram == 0 || cubeVertexBuffer == null) return
        
        GLES20.glUseProgram(cubeProgram)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        
        // Get attribute and uniform locations
        val positionHandle = GLES20.glGetAttribLocation(cubeProgram, "a_Position")
        val colorHandle = GLES20.glGetAttribLocation(cubeProgram, "a_Color")
        val mvpHandle = GLES20.glGetUniformLocation(cubeProgram, "u_ModelViewProjection")
        
        // Set MVP matrix
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, modelViewProjectionMatrix, 0)
        
        // Set vertex data
        cubeVertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, cubeVertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        
        cubeVertexBuffer?.position(3)
        GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, 24, cubeVertexBuffer)
        GLES20.glEnableVertexAttribArray(colorHandle)
        
        // Draw the cube faces
        for (i in 0 until 6) {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, i * 6, 6)
        }
        
        // Clean up
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
        
        Log.d(TAG, "Cube drawn successfully")
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