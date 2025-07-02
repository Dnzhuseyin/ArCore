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
import java.nio.ShortBuffer
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
    
    // Background rendering
    private var backgroundTextureId = 0
    private var backgroundProgram = 0
    private var backgroundVertexBuffer: FloatBuffer? = null
    
    // Cube rendering  
    private var cubeProgram = 0
    private var cubeVertexBuffer: FloatBuffer? = null
    private var cubeIndexBuffer: ShortBuffer? = null
    
    // Screen dimensions
    private var viewportWidth = 0
    private var viewportHeight = 0

    companion object {
        private const val CAMERA_PERMISSION_CODE = 0
        private const val TAG = "ArCoreActivity"
        
        // Simplified background quad vertices
        private val BACKGROUND_COORDS = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        
        // Background texture coords for correct orientation
        private val BACKGROUND_TEX_COORDS = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )
        
        // Simple cube vertices (position only)
        private val CUBE_COORDS = floatArrayOf(
            // Front face
            -0.05f, -0.05f,  0.05f,
             0.05f, -0.05f,  0.05f,
             0.05f,  0.05f,  0.05f,
            -0.05f,  0.05f,  0.05f,
            // Back face
            -0.05f, -0.05f, -0.05f,
             0.05f, -0.05f, -0.05f,
             0.05f,  0.05f, -0.05f,
            -0.05f,  0.05f, -0.05f
        )
        
        // Cube face indices
        private val CUBE_INDICES = shortArrayOf(
            0, 1, 2, 0, 2, 3,    // Front
            4, 5, 6, 4, 6, 7,    // Back
            0, 1, 5, 0, 5, 4,    // Bottom
            2, 3, 7, 2, 7, 6,    // Top
            0, 3, 7, 0, 7, 4,    // Left
            1, 2, 6, 1, 6, 5     // Right
        )
        
        // Background shaders
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
        
        // Cube shaders  
        private const val CUBE_VERTEX_SHADER = """
            uniform mat4 u_MVP;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MVP * a_Position;
            }
        """
        
        private const val CUBE_FRAGMENT_SHADER = """
            precision mediump float;
            void main() {
                gl_FragColor = vec4(0.0, 0.7, 1.0, 1.0);  // Light blue
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
                onTap(event)
            }
            true
        }
        
        setContentView(surfaceView)
        
        // Check camera permission
        if (!checkCameraPermission()) {
            requestCameraPermission()
        }
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !shouldConfigureSession)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        shouldConfigureSession = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }

                if (checkCameraPermission()) {
                    session = Session(this)
                    configureSession()
                } else {
                    Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_LONG).show()
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR session", e)
                Toast.makeText(this, "AR oturum başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            Toast.makeText(this, "Kamera kullanılamıyor", Toast.LENGTH_LONG).show()
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

    private fun onTap(event: MotionEvent) {
        surfaceView.queueEvent {
            val session = session ?: return@queueEvent
            
            try {
                val frame = session.update()
                if (frame.camera.trackingState != TrackingState.TRACKING) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Kamera henüz hazır değil, biraz bekleyin", Toast.LENGTH_SHORT).show()
                    }
                    return@queueEvent
                }

                val hits = frame.hitTest(event.x, event.y)
                for (hit in hits) {
                    val trackable = hit.trackable
                    if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                        // Remove existing anchor
                        modelAnchor?.detach()
                        
                        // Create new anchor
                        modelAnchor = hit.createAnchor()
                        
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "✓ Mavi küp yerleştirildi!", Toast.LENGTH_SHORT).show()
                        }
                        return@queueEvent
                    }
                }
                
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Yüzey bulunamadı, biraz daha tarayın", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in hit test", e)
            }
        }
    }

    // GLSurfaceView.Renderer methods
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        
        try {
            initializeBackgroundRendering()
            initializeCubeRendering()
            
            runOnUiThread {
                Toast.makeText(this@MainActivity, "ArCore hazır! Yüzeyleri tarayın", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize rendering", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.i(TAG, "Surface changed: ${width}x${height}")
        
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "Invalid surface size")
            return
        }
        
        viewportWidth = width
        viewportHeight = height
        
        GLES20.glViewport(0, 0, width, height)
        
        // Set display geometry
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
            session.setCameraTextureName(backgroundTextureId)
            val frame = session.update()
            val camera = frame.camera

            // Draw camera background
            drawBackground()

            // Draw AR content if tracking
            if (camera.trackingState == TrackingState.TRACKING) {
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                camera.getViewMatrix(viewMatrix, 0)

                // Draw cube if anchor exists
                modelAnchor?.let { anchor ->
                    if (anchor.trackingState == TrackingState.TRACKING) {
                        drawCube(anchor)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDrawFrame", e)
        }
    }

    private fun initializeBackgroundRendering() {
        // Create background texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        backgroundTextureId = textures[0]
        
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backgroundTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // Create background shader program
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, BACKGROUND_VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, BACKGROUND_FRAGMENT_SHADER)
        
        backgroundProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(backgroundProgram, vertexShader)
        GLES20.glAttachShader(backgroundProgram, fragmentShader)
        GLES20.glLinkProgram(backgroundProgram)

        // Create background vertex buffer
        val coords = FloatArray(BACKGROUND_COORDS.size + BACKGROUND_TEX_COORDS.size)
        for (i in BACKGROUND_COORDS.indices step 2) {
            coords[i * 2] = BACKGROUND_COORDS[i]
            coords[i * 2 + 1] = BACKGROUND_COORDS[i + 1]
            coords[i * 2 + 2] = BACKGROUND_TEX_COORDS[i]
            coords[i * 2 + 3] = BACKGROUND_TEX_COORDS[i + 1]
        }
        
        val bb = ByteBuffer.allocateDirect(coords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        backgroundVertexBuffer = bb.asFloatBuffer()
        backgroundVertexBuffer?.put(coords)
        backgroundVertexBuffer?.position(0)
    }

    private fun initializeCubeRendering() {
        // Create cube shader program
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, CUBE_VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, CUBE_FRAGMENT_SHADER)
        
        cubeProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(cubeProgram, vertexShader)
        GLES20.glAttachShader(cubeProgram, fragmentShader)
        GLES20.glLinkProgram(cubeProgram)

        // Create cube vertex buffer
        val bb = ByteBuffer.allocateDirect(CUBE_COORDS.size * 4)
        bb.order(ByteOrder.nativeOrder())
        cubeVertexBuffer = bb.asFloatBuffer()
        cubeVertexBuffer?.put(CUBE_COORDS)
        cubeVertexBuffer?.position(0)

        // Create cube index buffer
        val ib = ByteBuffer.allocateDirect(CUBE_INDICES.size * 2)
        ib.order(ByteOrder.nativeOrder())
        cubeIndexBuffer = ib.asShortBuffer()
        cubeIndexBuffer?.put(CUBE_INDICES)
        cubeIndexBuffer?.position(0)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            throw RuntimeException("Shader compilation failed")
        }
        
        return shader
    }

    private fun drawBackground() {
        if (backgroundProgram == 0 || backgroundVertexBuffer == null) return

        GLES20.glUseProgram(backgroundProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val positionHandle = GLES20.glGetAttribLocation(backgroundProgram, "a_Position")
        val texCoordHandle = GLES20.glGetAttribLocation(backgroundProgram, "a_TexCoord")
        val textureHandle = GLES20.glGetUniformLocation(backgroundProgram, "u_Texture")

        // Enable vertex arrays
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        // Set vertex data
        backgroundVertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, backgroundVertexBuffer)
        
        backgroundVertexBuffer?.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, backgroundVertexBuffer)

        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backgroundTextureId)
        GLES20.glUniform1i(textureHandle, 0)

        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawCube(anchor: Anchor) {
        if (cubeProgram == 0 || cubeVertexBuffer == null || cubeIndexBuffer == null) return

        // Calculate model matrix
        anchor.pose.toMatrix(modelMatrix, 0)
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES20.glUseProgram(cubeProgram)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val positionHandle = GLES20.glGetAttribLocation(cubeProgram, "a_Position")
        val mvpHandle = GLES20.glGetUniformLocation(cubeProgram, "u_MVP")

        // Set MVP matrix
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, modelViewProjectionMatrix, 0)

        // Set vertex data
        GLES20.glEnableVertexAttribArray(positionHandle)
        cubeVertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, cubeVertexBuffer)

        // Draw cube
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, CUBE_INDICES.size, GLES20.GL_UNSIGNED_SHORT, cubeIndexBuffer)

        // Disable vertex array
        GLES20.glDisableVertexAttribArray(positionHandle)
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
                Toast.makeText(this, "✓ Kamera izni verildi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "❌ Kamera izni gerekli", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}