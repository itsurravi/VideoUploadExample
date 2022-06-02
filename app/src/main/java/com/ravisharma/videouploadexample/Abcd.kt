package com.ravisharma.videouploadexample

import android.Manifest
import android.app.Activity
import android.app.ProgressDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import com.ravisharma.videouploadexample.UploadRequestBody.UploadCallback
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

class Abcd : AppCompatActivity(), UploadCallback {

    private val REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124

    var mediaPath: String? = null
    var mediaColumns = arrayOf(MediaStore.Video.Media._ID)
    lateinit var progressDialog: ProgressDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initView()
        setListeners()
        askForPermissions()
    }

    private fun initView() {
        progressDialog = ProgressDialog(this)
        progressDialog.setMessage("Uploading...")
        progressDialog.setCancelable(false)
    }

    private fun setListeners() {
        btnUpload.setOnClickListener { uploadFile() }

        // Video must be low in Memory or need to be compressed before uploading...
        btnPickVideo.setOnClickListener {
            val galleryIntent = Intent(Intent.ACTION_PICK,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(galleryIntent, 1)
        }

        btnCompressVideo.setOnClickListener {
            compressVideo()
        }
    }

    private fun compressVideo() {
        if(mediaPath!=null) {
            GlobalScope.launch {
                val desFile = saveVideoFile(mediaPath)

                desFile?.let {
                    var time = 0L
                    VideoCompressor.start(mediaPath!!, desFile.path, object : CompressionListener {
                        override fun onCancelled() {
                            Log.e("VideoCompress", "compression has been cancelled")
                        }

                        override fun onFailure(failureMessage: String) {
                            Log.e("VideoCompress", "Failure: $failureMessage")
                        }

                        override fun onProgress(percent: Float) {
                            if (percent <= 100 && percent.toInt() % 5 == 0) {
                                Log.e("VideoCompress", "Progress: ${percent.toLong()}%")
                                runOnUiThread {
                                    fileNameAfterCompress.text = "Compressed ${percent.toLong()}%"
                                }
                            }
                        }

                        override fun onStart() {
                            time = System.currentTimeMillis()
                            Log.e("VideoCompress", "Started $time")
                            fileNameBeforeCompress.append(" Size: ${getFileSize(File(mediaPath).length())}")
                        }

                        override fun onSuccess() {
                            val newSizeValue = desFile.length()

                            fileNameAfterCompress.text = "${desFile.absolutePath} Size:${getFileSize(newSizeValue)}"

                            Log.e("VideoCompress", "Completed: ${desFile.absolutePath} Size:${getFileSize(newSizeValue)}")
                        }
                    }, VideoQuality.MEDIUM, isMinBitRateEnabled = false, keepOriginalResolution = true)
                }
            }
        }
        else{
            Toast.makeText(this, "Please select a video first", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            if (requestCode == 1 && resultCode == Activity.RESULT_OK && null != data) {

                // Get the Video from data
                val selectedVideo = data.data
                val filePathColumn = arrayOf(MediaStore.Video.Media.DATA)
                val cursor = contentResolver.query(selectedVideo!!, filePathColumn, null, null, null)!!
                cursor.moveToFirst()
                val columnIndex = cursor.getColumnIndex(filePathColumn[0])
                mediaPath = cursor.getString(columnIndex)
                fileNameBeforeCompress.text = mediaPath
                // Set the Video Thumb in ImageView Previewing the Media
                imgView!!.setImageBitmap(getThumbnailPathForLocalFile(this, selectedVideo))
                cursor.close()


            } else {
                Toast.makeText(this, "You haven't picked Video", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Something went wrong", Toast.LENGTH_LONG).show()
        }
    }

    // Providing Thumbnail For Selected Image
    private fun getThumbnailPathForLocalFile(context: Activity, fileUri: Uri?): Bitmap {
        val fileId = getFileId(context, fileUri)
        return MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver,
                fileId, MediaStore.Video.Thumbnails.MINI_KIND, null)
    }

    // Getting Selected File ID
    private fun getFileId(context: Activity, fileUri: Uri?): Long {
        val cursor = context.managedQuery(fileUri, mediaColumns, null, null, null)
        if (cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            return cursor.getInt(columnIndex).toLong()
        }
        return 0
    }

    private fun saveVideoFile(filePath: String?): File? {
        filePath?.let {
            val videoFile = File(filePath)
            val videoFileName = "${System.currentTimeMillis()}_${videoFile.name}"
            val folderName = Environment.DIRECTORY_MOVIES
            if (Build.VERSION.SDK_INT >= 30) {

                val values = ContentValues().apply {

                    put(
                            MediaStore.Images.Media.DISPLAY_NAME,
                            videoFileName
                    )
                    put(MediaStore.Images.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Images.Media.RELATIVE_PATH, folderName)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val collection =
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                val fileUri = applicationContext.contentResolver.insert(collection, values)

                fileUri?.let {
                    application.contentResolver.openFileDescriptor(fileUri, "rw")
                            .use { descriptor ->
                                descriptor?.let {
                                    FileOutputStream(descriptor.fileDescriptor).use { out ->
                                        FileInputStream(videoFile).use { inputStream ->
                                            val buf = ByteArray(4096)
                                            while (true) {
                                                val sz = inputStream.read(buf)
                                                if (sz <= 0) break
                                                out.write(buf, 0, sz)
                                            }
                                        }
                                    }
                                }
                            }

                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    applicationContext.contentResolver.update(fileUri, values, null, null)

                    return File(getMediaPath(applicationContext, fileUri))
                }
            } else {
                val downloadsPath =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val desFile = File(downloadsPath, videoFileName)

                if (desFile.exists())
                    desFile.delete()

                try {
                    desFile.createNewFile()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

                return desFile
            }
        }
        return null
    }

    // Uploading Image/Video
    private fun uploadFile() {
        progressDialog.show()

        // Map is used to multipart the file using okhttp3.RequestBody
        val file = File(mediaPath)

        // Parsing any Media type file
        val requestBody = UploadRequestBody(file, "video", this)
        val fileToUpload = MultipartBody.Part.createFormData("video", file.name, requestBody)
        val filename = RequestBody.create(MediaType.parse("text/plain"), file.name)
        val getResponse = AppConfig.getRetrofit().create(ApiConfig::class.java)
        val call = getResponse.uploadFile(fileToUpload, filename)
        call.enqueue(object : Callback<ServerResponse?> {
            override fun onResponse(call: Call<ServerResponse?>, response: Response<ServerResponse?>) {
                val serverResponse = response.body()
                if (serverResponse != null) {
                    Log.v("Response", "" + serverResponse.toString())
                    Toast.makeText(applicationContext, serverResponse.getMessage(), Toast.LENGTH_SHORT).show()
                } else {
                    Log.v("Response", "" + serverResponse)
                }
                progressDialog.dismiss()
            }

            override fun onFailure(call: Call<ServerResponse?>, t: Throwable) {}
        })
    }

    private fun askForPermissions() {
        val permissionsNeeded: MutableList<String> = ArrayList()
        val permissionsList: MutableList<String> = ArrayList()
        if (!addPermission(permissionsList, Manifest.permission.ACCESS_NETWORK_STATE)) permissionsNeeded.add("ACCESS_NETWORK_STATE")
        if (!addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE)) permissionsNeeded.add("READ_EXTERNAL_STORAGE")
        if (!addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE)) permissionsNeeded.add("WRITE_EXTERNAL_STORAGE")
        if (!addPermission(permissionsList, Manifest.permission.INTERNET)) permissionsNeeded.add("INTERNET")
        if (permissionsList.size > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(permissionsList.toTypedArray(),
                        REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS)
            }
            return
        }
    }

    private fun addPermission(permissionsList: MutableList<String>, permission: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(permission)
                // Check for Rationale Option
                if (!shouldShowRequestPermissionRationale(permission)) return false
            }
        }
        return true
    }

    override fun onProgressUpdate(percentage: Int) {
        Log.e("ProgressUpdate", "" + percentage)
        progressDialog.setMessage("Uploading... $percentage%")
    }
}