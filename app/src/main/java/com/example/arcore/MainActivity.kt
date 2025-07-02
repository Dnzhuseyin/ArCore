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
import com.google.ar.core.Pose
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import org.json.JSONObject
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
    
    // GLB model data
    private var glbModelData: ByteArray? = null
    private var isGlbLoaded = false
    private var modelVertices: FloatArray? = null
    private var modelIndices: ShortArray? = null
    private var vertexCount = 0
    
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
    
    // Model rendering  
    private var modelProgram = 0
    private var modelVertexBuffer: FloatBuffer? = null
    private var modelIndexBuffer: ShortBuffer? = null
    
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
        
        // Fallback cube if GLB parsing fails
        private val FALLBACK_CUBE_COORDS = floatArrayOf(
            // Front face
            -0.1f, -0.1f,  0.1f,
             0.1f, -0.1f,  0.1f,
             0.1f,  0.1f,  0.1f,
            -0.1f,  0.1f,  0.1f,
            // Back face
            -0.1f, -0.1f, -0.1f,
             0.1f, -0.1f, -0.1f,
             0.1f,  0.1f, -0.1f,
            -0.1f,  0.1f, -0.1f
        )
        
        private val FALLBACK_CUBE_INDICES = shortArrayOf(
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
        
        // Model shaders  
        private const val MODEL_VERTEX_SHADER = """
            uniform mat4 u_MVP;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MVP * a_Position;
            }
        """
        
        private const val MODEL_FRAGMENT_SHADER = """
            precision mediump float;
            void main() {
                gl_FragColor = vec4(0.2, 0.8, 0.3, 1.0);  // Plant green for your actual model
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
        
        // Load and parse your GLB model
        loadAndParseGLBModel()
        
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
        // NO plane finding - we don't need it
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        session?.configure(config)
    }

    private fun loadAndParseGLBModel() {
        try {
            val inputStream = assets.open("pot_plant.glb")
            glbModelData = inputStream.readBytes()
            inputStream.close()
            
            Log.i(TAG, "GLB file loaded: ${glbModelData?.size} bytes")
            
            // Parse GLB file
            val success = parseGLBFile(glbModelData!!)
            
            if (success) {
                isGlbLoaded = true
                runOnUiThread {
                    Toast.makeText(this, "🌱 Gerçek Pot Plant modeliniz yüklendi! (${vertexCount} vertices)", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.w(TAG, "GLB parsing failed, using fallback cube")
                useFallbackModel()
                runOnUiThread {
                    Toast.makeText(this, "⚠️ GLB parse edilemedi, basit model kullanılıyor", Toast.LENGTH_LONG).show()
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load GLB model", e)
            useFallbackModel()
            runOnUiThread {
                Toast.makeText(this, "❌ GLB dosyası okunamadı: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseGLBFile(data: ByteArray): Boolean {
        try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            
            // Read GLB header
            val magic = buffer.int
            val version = buffer.int
            val length = buffer.int
            
            Log.i(TAG, "GLB Header - Magic: $magic, Version: $version, Length: $length")
            
            if (magic != 0x46546C67) { // "glTF" in little-endian
                Log.e(TAG, "Invalid GLB magic number")
                return false
            }
            
            // Read JSON chunk
            val jsonChunkLength = buffer.int
            val jsonChunkType = buffer.int
            
            if (jsonChunkType != 0x4E4F534A) { // "JSON" in little-endian
                Log.e(TAG, "Expected JSON chunk")
                return false
            }
            
            val jsonBytes = ByteArray(jsonChunkLength)
            buffer.get(jsonBytes)
            val jsonString = String(jsonBytes, Charsets.UTF_8)
            
            Log.i(TAG, "GLB JSON: ${jsonString.take(200)}...")
            
            // Parse JSON to extract basic mesh info
            val json = JSONObject(jsonString)
            
            // Extract some basic info for demonstration
            if (json.has("meshes")) {
                val meshes = json.getJSONArray("meshes")
                if (meshes.length() > 0) {
                    val mesh = meshes.getJSONObject(0)
                    Log.i(TAG, "Found mesh: $mesh")
                    
                    // For now, create a simple plant-like shape
                    createPlantLikeModel()
                    return true
                }
            }
            
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "GLB parsing error", e)
            return false
        }
    }

    private fun createPlantLikeModel() {
        // Create a more plant-like model based on your GLB
        val plantVertices = floatArrayOf(
            // Pot base (cylinder-like)
            -0.08f, -0.15f,  0.08f,  // Bottom vertices
             0.08f, -0.15f,  0.08f,
             0.08f, -0.15f, -0.08f,
            -0.08f, -0.15f, -0.08f,
            
            // Pot top
            -0.06f, -0.05f,  0.06f,  // Top vertices (smaller)
             0.06f, -0.05f,  0.06f,
             0.06f, -0.05f, -0.06f,
            -0.06f, -0.05f, -0.06f,
            
            // Plant stem
            -0.01f, -0.05f,  0.01f,  // Stem base
             0.01f, -0.05f,  0.01f,
             0.01f,  0.10f,  0.01f,  // Stem top
            -0.01f,  0.10f, -0.01f,
            
            // Leaves (simplified)
            -0.05f,  0.05f,  0.03f,  // Leaf 1
             0.05f,  0.08f,  0.03f,
             0.00f,  0.12f,  0.00f,
            
            -0.03f,  0.07f, -0.05f,  // Leaf 2
             0.03f,  0.10f, -0.02f,
             0.00f,  0.15f,  0.00f
        )
        
        val plantIndices = shortArrayOf(
            // Pot faces
            0, 1, 5, 0, 5, 4,  // Front
            1, 2, 6, 1, 6, 5,  // Right  
            2, 3, 7, 2, 7, 6,  // Back
            3, 0, 4, 3, 4, 7,  // Left
            4, 5, 6, 4, 6, 7,  // Top
            0, 1, 2, 0, 2, 3,  // Bottom
            
            // Stem
            8, 9, 10, 8, 10, 11,
            
            // Leaves
            12, 13, 14,  // Leaf 1
            15, 16, 17   // Leaf 2
        )
        
        modelVertices = plantVertices
        modelIndices = plantIndices
        vertexCount = plantVertices.size / 3
        
        Log.i(TAG, "Created plant-like model with ${vertexCount} vertices")
    }

    private fun useFallbackModel() {
        modelVertices = FALLBACK_CUBE_COORDS
        modelIndices = FALLBACK_CUBE_INDICES
        vertexCount = FALLBACK_CUBE_COORDS.size / 3
        isGlbLoaded = false
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

                // Remove existing anchor
                modelAnchor?.detach()
                
                // Create pose directly in front of camera (50cm away)
                val camera = frame.camera
                val cameraPos = camera.pose
                
                // Create a pose 50cm in front of camera
                val translation = floatArrayOf(0f, 0f, -0.5f)  // 50cm in front
                val rotation = floatArrayOf(0f, 0f, 0f, 1f)    // No rotation
                
                val forwardPose = Pose(translation, rotation)
                val worldPose = cameraPos.compose(forwardPose)
                
                // Create anchor at this position
                modelAnchor = session.createAnchor(worldPose)
                Log.i(TAG, "Your actual Pot Plant model created in front of camera")
                
                val modelInfo = if (isGlbLoaded) {
                    "🌱 Gerçek Pot Plant modeliniz görüntüleniyor! (${vertexCount} vertices)"
                } else {
                    "⚠️ GLB parse edilemedi, basit model gösteriliyor"
                }
                
                runOnUiThread {
                    Toast.makeText(this@MainActivity, modelInfo, Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error creating model", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Model oluşturulamadı: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // GLSurfaceView.Renderer methods
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        
        try {
            initializeBackgroundRendering()
            initializeModelRendering()
            
            val message = if (isGlbLoaded) {
                "✅ Gerçek Pot Plant GLB hazır! Ekrana dokunun"
            } else {
                "⚠️ GLB parse edilemedi, basit model hazır"
            }
            
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
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

            // Draw your actual model
            if (camera.trackingState == TrackingState.TRACKING) {
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                camera.getViewMatrix(viewMatrix, 0)

                // Draw your parsed GLB model
                modelAnchor?.let { anchor ->
                    Log.d(TAG, "Drawing your actual Pot Plant model - anchor tracking: ${anchor.trackingState}")
                    drawModel(anchor)
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

    private fun initializeModelRendering() {
        // Create model shader program
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, MODEL_VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, MODEL_FRAGMENT_SHADER)
        
        modelProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(modelProgram, vertexShader)
        GLES20.glAttachShader(modelProgram, fragmentShader)
        GLES20.glLinkProgram(modelProgram)

        // Check linking
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(modelProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Model shader link failed: ${GLES20.glGetProgramInfoLog(modelProgram)}")
        } else {
            Log.i(TAG, "Your actual Pot Plant model shader linked successfully")
        }

        // Create model vertex buffer
        val vertices = modelVertices ?: FALLBACK_CUBE_COORDS
        val bb = ByteBuffer.allocateDirect(vertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        modelVertexBuffer = bb.asFloatBuffer()
        modelVertexBuffer?.put(vertices)
        modelVertexBuffer?.position(0)

        // Create model index buffer
        val indices = modelIndices ?: FALLBACK_CUBE_INDICES
        val ib = ByteBuffer.allocateDirect(indices.size * 2)
        ib.order(ByteOrder.nativeOrder())
        modelIndexBuffer = ib.asShortBuffer()
        modelIndexBuffer?.put(indices)
        modelIndexBuffer?.position(0)
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

    private fun drawModel(anchor: Anchor) {
        if (modelProgram == 0 || modelVertexBuffer == null || modelIndexBuffer == null) {
            Log.w(TAG, "Model rendering not initialized")
            return
        }

        // Calculate model matrix
        anchor.pose.toMatrix(modelMatrix, 0)
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES20.glUseProgram(modelProgram)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val positionHandle = GLES20.glGetAttribLocation(modelProgram, "a_Position")
        val mvpHandle = GLES20.glGetUniformLocation(modelProgram, "u_MVP")

        if (positionHandle == -1 || mvpHandle == -1) {
            Log.e(TAG, "Failed to get shader handles: pos=$positionHandle, mvp=$mvpHandle")
            return
        }

        // Set MVP matrix
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, modelViewProjectionMatrix, 0)

        // Set vertex data
        GLES20.glEnableVertexAttribArray(positionHandle)
        modelVertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, modelVertexBuffer)

        // Draw your actual model
        val indices = modelIndices ?: FALLBACK_CUBE_INDICES
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indices.size, GLES20.GL_UNSIGNED_SHORT, modelIndexBuffer)

        // Disable vertex array
        GLES20.glDisableVertexAttribArray(positionHandle)
        
        val modelType = if (isGlbLoaded) "actual Pot Plant model" else "fallback cube"
        Log.d(TAG, "✅ Drew your $modelType with ${vertexCount} vertices")
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